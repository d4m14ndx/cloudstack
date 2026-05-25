// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.response.ApplicationLoadBalancerInstanceResponse;
import org.apache.cloudstack.api.response.ApplicationLoadBalancerResponse;
import org.apache.cloudstack.api.response.ApplicationLoadBalancerRuleResponse;
import org.apache.cloudstack.api.response.ControlledEntityResponse;
import org.apache.cloudstack.api.response.FirewallResponse;
import org.apache.cloudstack.api.response.FirewallRuleResponse;
import org.apache.cloudstack.api.response.GlobalLoadBalancerResponse;
import org.apache.cloudstack.api.response.IpForwardingRuleResponse;
import org.apache.cloudstack.api.response.LBHealthCheckPolicyResponse;
import org.apache.cloudstack.api.response.LBHealthCheckResponse;
import org.apache.cloudstack.api.response.LBStickinessPolicyResponse;
import org.apache.cloudstack.api.response.LBStickinessResponse;
import org.apache.cloudstack.api.response.LoadBalancerResponse;
import org.apache.cloudstack.api.response.NetworkACLItemResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.network.lb.ApplicationLoadBalancerRule;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.dc.DataCenter;
import com.cloud.domain.Domain;
import com.cloud.network.Network;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.HealthCheckPolicy;
import com.cloud.network.rules.LoadBalancer;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.StaticNatRule;
import com.cloud.network.rules.StickinessPolicy;
import com.cloud.network.vpc.NetworkACL;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.projects.Project;
import com.cloud.region.ha.GlobalLoadBalancerRule;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.net.Ip;

@Component
public class ApiLoadBalancerFirewallResponseServiceImpl implements ApiLoadBalancerFirewallResponseService {

    @Override
    public LoadBalancerResponse createLoadBalancerResponse(LoadBalancer loadBalancer) {
        LoadBalancerResponse lbResponse = new LoadBalancerResponse();
        lbResponse.setId(loadBalancer.getUuid());
        lbResponse.setName(loadBalancer.getName());
        lbResponse.setDescription(loadBalancer.getDescription());
        List<String> cidrs = ApiDBUtils.findFirewallSourceCidrs(loadBalancer.getId());
        lbResponse.setCidrList(StringUtils.join(cidrs, ","));

        IPAddressVO publicIp = ApiDBUtils.findIpAddressById(loadBalancer.getSourceIpAddressId());
        lbResponse.setPublicIpId(publicIp.getUuid());
        lbResponse.setPublicIp(publicIp.getAddress().addr());
        lbResponse.setPublicPort(Integer.toString(loadBalancer.getSourcePortStart()));
        lbResponse.setPrivatePort(Integer.toString(loadBalancer.getDefaultPortStart()));
        lbResponse.setAlgorithm(loadBalancer.getAlgorithm());
        lbResponse.setLbProtocol(loadBalancer.getLbProtocol());
        lbResponse.setForDisplay(loadBalancer.isDisplay());
        lbResponse.setState(getFirewallState(loadBalancer.getState()));
        populateOwner(lbResponse, loadBalancer);
        DataCenter zone = ApiDBUtils.findZoneById(publicIp.getDataCenterId());
        if (zone != null) {
            lbResponse.setZoneId(zone.getUuid());
            lbResponse.setZoneName(zone.getName());
        }

        lbResponse.setTags(createResourceTagResponses(ResourceObjectType.LoadBalancer, loadBalancer.getId()));

        Network ntwk = ApiDBUtils.findNetworkById(loadBalancer.getNetworkId());
        lbResponse.setNetworkId(ntwk.getUuid());

        lbResponse.setCidrList(loadBalancer.getCidrList());

        lbResponse.setObjectName("loadbalancer");
        return lbResponse;
    }

    @Override
    public GlobalLoadBalancerResponse createGlobalLoadBalancerResponse(GlobalLoadBalancerRule globalLoadBalancerRule) {
        GlobalLoadBalancerResponse response = new GlobalLoadBalancerResponse();
        response.setAlgorithm(globalLoadBalancerRule.getAlgorithm());
        response.setStickyMethod(globalLoadBalancerRule.getPersistence());
        response.setServiceType(globalLoadBalancerRule.getServiceType());
        response.setServiceDomainName(globalLoadBalancerRule.getGslbDomain() + "." + ApiDBUtils.getDnsNameConfiguredForGslb());
        response.setName(globalLoadBalancerRule.getName());
        response.setDescription(globalLoadBalancerRule.getDescription());
        response.setRegionIdId(globalLoadBalancerRule.getRegion());
        response.setId(globalLoadBalancerRule.getUuid());
        populateOwner(response, globalLoadBalancerRule);
        response.setObjectName("globalloadbalancer");

        List<LoadBalancerResponse> siteLbResponses = new ArrayList<LoadBalancerResponse>();
        List<? extends LoadBalancer> siteLoadBalancers = ApiDBUtils.listSiteLoadBalancers(globalLoadBalancerRule.getId());
        for (LoadBalancer siteLb : siteLoadBalancers) {
            LoadBalancerResponse siteLbResponse = createLoadBalancerResponse(siteLb);
            siteLbResponses.add(siteLbResponse);
        }
        response.setSiteLoadBalancers(siteLbResponses);
        return response;
    }

    @Override
    public FirewallRuleResponse createPortForwardingRuleResponse(PortForwardingRule fwRule) {
        FirewallRuleResponse response = new FirewallRuleResponse();
        response.setId(fwRule.getUuid());
        response.setPrivateStartPort(Integer.toString(fwRule.getDestinationPortStart()));
        response.setPrivateEndPort(Integer.toString(fwRule.getDestinationPortEnd()));
        response.setProtocol(fwRule.getProtocol());
        response.setPublicStartPort(Integer.toString(fwRule.getSourcePortStart()));
        response.setPublicEndPort(Integer.toString(fwRule.getSourcePortEnd()));
        List<String> cidrs = ApiDBUtils.findFirewallSourceCidrs(fwRule.getId());
        response.setCidrList(StringUtils.join(cidrs, ","));

        Network guestNtwk = ApiDBUtils.findNetworkById(fwRule.getNetworkId());
        response.setNetworkId(guestNtwk.getUuid());
        response.setNetworkName(guestNtwk.getName());

        IPAddressVO ip = ApiDBUtils.findIpAddressById(fwRule.getSourceIpAddressId());

        if (ip != null) {
            response.setPublicIpAddressId(ip.getUuid());
            response.setPublicIpAddress(ip.getAddress().addr());
            if (fwRule.getDestinationIpAddress() != null) {
                response.setDestNatVmIp(fwRule.getDestinationIpAddress().toString());
                UserVm vm = ApiDBUtils.findUserVmById(fwRule.getVirtualMachineId());
                if (vm != null) {
                    response.setVirtualMachineId(vm.getUuid());
                    response.setVirtualMachineName(vm.getHostName());

                    if (vm.getDisplayName() != null) {
                        response.setVirtualMachineDisplayName(vm.getDisplayName());
                    } else {
                        response.setVirtualMachineDisplayName(vm.getHostName());
                    }
                }
            }
        }

        response.setTags(createResourceTagResponses(ResourceObjectType.PortForwardingRule, fwRule.getId()));

        response.setState(getFirewallState(fwRule.getState()));
        response.setForDisplay(fwRule.isDisplay());
        response.setObjectName("portforwardingrule");
        return response;
    }

    @Override
    public IpForwardingRuleResponse createIpForwardingRuleResponse(StaticNatRule fwRule) {
        IpForwardingRuleResponse response = new IpForwardingRuleResponse();
        response.setId(fwRule.getUuid());
        response.setProtocol(fwRule.getProtocol());

        IPAddressVO ip = ApiDBUtils.findIpAddressById(fwRule.getSourceIpAddressId());

        if (ip != null) {
            response.setPublicIpAddressId(ip.getId());
            response.setPublicIpAddress(ip.getAddress().addr());
            if (fwRule.getDestIpAddress() != null) {
                UserVm vm = ApiDBUtils.findUserVmById(ip.getAssociatedWithVmId());
                if (vm != null) {
                    response.setVirtualMachineId(vm.getUuid());
                    response.setVirtualMachineName(vm.getHostName());
                    if (vm.getDisplayName() != null) {
                        response.setVirtualMachineDisplayName(vm.getDisplayName());
                    } else {
                        response.setVirtualMachineDisplayName(vm.getHostName());
                    }
                }
            }
        }

        response.setStartPort(fwRule.getSourcePortStart());
        response.setEndPort(fwRule.getSourcePortEnd());
        response.setProtocol(fwRule.getProtocol());
        response.setState(getFirewallState(fwRule.getState()));
        response.setObjectName("ipforwardingrule");
        return response;
    }

    @Override
    public FirewallResponse createFirewallResponse(FirewallRule fwRule) {
        FirewallResponse response = new FirewallResponse();

        response.setId(fwRule.getUuid());
        response.setProtocol(fwRule.getProtocol());
        if (fwRule.getSourcePortStart() != null) {
            response.setStartPort(fwRule.getSourcePortStart());
        }

        if (fwRule.getSourcePortEnd() != null) {
            response.setEndPort(fwRule.getSourcePortEnd());
        }

        List<String> cidrs = ApiDBUtils.findFirewallSourceCidrs(fwRule.getId());
        response.setCidrList(StringUtils.join(cidrs, ","));

        List<String> destCidrs = ApiDBUtils.findFirewallDestCidrs(fwRule.getId());
        response.setDestCidr(StringUtils.join(destCidrs, ","));

        if (fwRule.getTrafficType() == FirewallRule.TrafficType.Ingress) {
            if (fwRule.getSourceIpAddressId() != null) {
                IPAddressVO ip = ApiDBUtils.findIpAddressById(fwRule.getSourceIpAddressId());
                response.setPublicIpAddressId(ip.getUuid());
                response.setPublicIpAddress(ip.getAddress().addr());
            }
        }

        Network network = ApiDBUtils.findNetworkById(fwRule.getNetworkId());
        response.setNetworkId(network.getUuid());

        response.setIcmpCode(fwRule.getIcmpCode());
        response.setIcmpType(fwRule.getIcmpType());
        response.setForDisplay(fwRule.isDisplay());
        response.setTrafficType(fwRule.getTrafficType().toString());

        response.setTags(createResourceTagResponses(ResourceObjectType.FirewallRule, fwRule.getId()));

        response.setState(getFirewallState(fwRule.getState()));
        response.setObjectName("firewallrule");
        return response;
    }

    @Override
    public NetworkACLItemResponse createNetworkACLItemResponse(NetworkACLItem aclItem) {
        NetworkACLItemResponse response = new NetworkACLItemResponse();

        response.setId(aclItem.getUuid());
        response.setProtocol(aclItem.getProtocol());
        if (aclItem.getSourcePortStart() != null) {
            response.setStartPort(Integer.toString(aclItem.getSourcePortStart()));
        }

        if (aclItem.getSourcePortEnd() != null) {
            response.setEndPort(Integer.toString(aclItem.getSourcePortEnd()));
        }

        response.setCidrList(StringUtils.join(aclItem.getSourceCidrList(), ","));

        response.setTrafficType(aclItem.getTrafficType().toString());
        response.setIcmpCode(aclItem.getIcmpCode());
        response.setIcmpType(aclItem.getIcmpType());
        response.setState(getNetworkAclState(aclItem.getState()));
        response.setNumber(aclItem.getNumber());
        response.setAction(aclItem.getAction().toString());
        response.setForDisplay(aclItem.isDisplay());

        NetworkACL acl = ApiDBUtils.findByNetworkACLId(aclItem.getAclId());
        if (acl != null) {
            response.setAclId(acl.getUuid());
            response.setAclName(acl.getName());
        }

        response.setTags(createResourceTagResponses(ResourceObjectType.NetworkACL, aclItem.getId()));
        response.setReason(aclItem.getReason());
        response.setObjectName("networkacl");
        return response;
    }

    @Override
    public LBStickinessResponse createLBStickinessPolicyResponse(StickinessPolicy stickinessPolicy, LoadBalancer lb) {
        LBStickinessResponse spResponse = new LBStickinessResponse();

        spResponse.setlbRuleId(lb.getUuid());
        addLoadBalancerPolicyOwner(spResponse, lb);

        List<LBStickinessPolicyResponse> responses = new ArrayList<LBStickinessPolicyResponse>();
        LBStickinessPolicyResponse ruleResponse = new LBStickinessPolicyResponse(stickinessPolicy);
        responses.add(ruleResponse);

        spResponse.setRules(responses);

        spResponse.setObjectName("stickinesspolicies");
        return spResponse;
    }

    @Override
    public LBStickinessResponse createLBStickinessPolicyResponse(List<? extends StickinessPolicy> stickinessPolicies, LoadBalancer lb) {
        LBStickinessResponse spResponse = new LBStickinessResponse();

        if (lb == null) {
            return spResponse;
        }
        spResponse.setlbRuleId(lb.getUuid());
        addLoadBalancerPolicyOwner(spResponse, lb);

        List<LBStickinessPolicyResponse> responses = new ArrayList<LBStickinessPolicyResponse>();
        for (StickinessPolicy stickinessPolicy : stickinessPolicies) {
            LBStickinessPolicyResponse ruleResponse = new LBStickinessPolicyResponse(stickinessPolicy);
            responses.add(ruleResponse);
        }
        spResponse.setRules(responses);

        spResponse.setObjectName("stickinesspolicies");
        return spResponse;
    }

    @Override
    public LBHealthCheckResponse createLBHealthCheckPolicyResponse(List<? extends HealthCheckPolicy> healthcheckPolicies, LoadBalancer lb) {
        LBHealthCheckResponse hcResponse = new LBHealthCheckResponse();

        if (lb == null) {
            return hcResponse;
        }
        hcResponse.setlbRuleId(lb.getUuid());
        addLoadBalancerPolicyOwner(hcResponse, lb);

        List<LBHealthCheckPolicyResponse> responses = new ArrayList<LBHealthCheckPolicyResponse>();
        for (HealthCheckPolicy healthcheckPolicy : healthcheckPolicies) {
            LBHealthCheckPolicyResponse ruleResponse = new LBHealthCheckPolicyResponse(healthcheckPolicy);
            responses.add(ruleResponse);
        }
        hcResponse.setRules(responses);

        hcResponse.setObjectName("healthcheckpolicies");
        return hcResponse;
    }

    @Override
    public LBHealthCheckResponse createLBHealthCheckPolicyResponse(HealthCheckPolicy healthcheckPolicy, LoadBalancer lb) {
        LBHealthCheckResponse hcResponse = new LBHealthCheckResponse();

        hcResponse.setlbRuleId(lb.getUuid());
        addLoadBalancerPolicyOwner(hcResponse, lb);

        List<LBHealthCheckPolicyResponse> responses = new ArrayList<LBHealthCheckPolicyResponse>();
        LBHealthCheckPolicyResponse ruleResponse = new LBHealthCheckPolicyResponse(healthcheckPolicy);
        responses.add(ruleResponse);
        hcResponse.setRules(responses);
        hcResponse.setObjectName("healthcheckpolicies");
        return hcResponse;
    }

    @Override
    public ApplicationLoadBalancerResponse createLoadBalancerContainerReponse(ApplicationLoadBalancerRule lb, Map<Ip, UserVm> lbInstances) {
        ApplicationLoadBalancerResponse lbResponse = new ApplicationLoadBalancerResponse();
        lbResponse.setId(lb.getUuid());
        lbResponse.setName(lb.getName());
        lbResponse.setDescription(lb.getDescription());
        lbResponse.setAlgorithm(lb.getAlgorithm());
        lbResponse.setForDisplay(lb.isDisplay());
        Network nw = ApiDBUtils.findNetworkById(lb.getNetworkId());
        lbResponse.setNetworkId(nw.getUuid());
        populateOwner(lbResponse, lb);

        if (lb.getScheme() == Scheme.Internal) {
            lbResponse.setSourceIp(lb.getSourceIp().addr());
            Network network = ApiDBUtils.findNetworkById(lb.getNetworkId());
            lbResponse.setSourceIpNetworkId(network.getUuid());
        } else {
            IPAddressVO publicIp = ApiDBUtils.findIpAddressById(lb.getSourceIpAddressId());
            lbResponse.setSourceIp(publicIp.getAddress().addr());
            Network ntwk = ApiDBUtils.findNetworkById(publicIp.getNetworkId());
            lbResponse.setSourceIpNetworkId(ntwk.getUuid());
        }

        List<ApplicationLoadBalancerRuleResponse> ruleResponses = new ArrayList<ApplicationLoadBalancerRuleResponse>();
        ApplicationLoadBalancerRuleResponse ruleResponse = new ApplicationLoadBalancerRuleResponse();
        ruleResponse.setInstancePort(lb.getDefaultPortStart());
        ruleResponse.setSourcePort(lb.getSourcePortStart());
        FirewallRule.State stateToSet = lb.getState();
        if (stateToSet.equals(FirewallRule.State.Revoke)) {
            stateToSet = FirewallRule.State.Deleting;
        }
        ruleResponse.setState(stateToSet.toString());
        ruleResponse.setObjectName("loadbalancerrule");
        ruleResponses.add(ruleResponse);
        lbResponse.setLbRules(ruleResponses);

        List<ApplicationLoadBalancerInstanceResponse> instanceResponses = new ArrayList<ApplicationLoadBalancerInstanceResponse>();
        for (Map.Entry<Ip, UserVm> entry : lbInstances.entrySet()) {
            Ip ip = entry.getKey();
            UserVm vm = entry.getValue();
            ApplicationLoadBalancerInstanceResponse instanceResponse = new ApplicationLoadBalancerInstanceResponse();
            instanceResponse.setIpAddress(ip.addr());
            instanceResponse.setId(vm.getUuid());
            instanceResponse.setName(vm.getInstanceName());
            instanceResponse.setObjectName("loadbalancerinstance");
            instanceResponses.add(instanceResponse);
        }

        lbResponse.setLbInstances(instanceResponses);
        lbResponse.setTags(createResourceTagResponses(ResourceObjectType.LoadBalancer, lb.getId()));
        lbResponse.setObjectName("loadbalancer");
        return lbResponse;
    }

    @Override
    public FirewallResponse createIpv6FirewallRuleResponse(FirewallRule fwRule) {
        FirewallResponse response = new FirewallResponse();

        response.setId(fwRule.getUuid());
        response.setProtocol(fwRule.getProtocol());
        List<String> cidrs = ApiDBUtils.findFirewallSourceCidrs(fwRule.getId());
        response.setCidrList(StringUtils.join(cidrs, ","));
        List<String> destinationCidrs = ApiDBUtils.findFirewallDestCidrs(fwRule.getId());
        response.setDestCidr(StringUtils.join(destinationCidrs, ","));
        response.setTrafficType(fwRule.getTrafficType().toString());
        response.setProtocol(fwRule.getProtocol());
        response.setStartPort(fwRule.getSourcePortStart());
        response.setEndPort(fwRule.getSourcePortEnd());
        response.setIcmpCode(fwRule.getIcmpCode());
        response.setIcmpType(fwRule.getIcmpType());

        Network network = ApiDBUtils.findNetworkById(fwRule.getNetworkId());
        response.setNetworkId(network.getUuid());

        response.setForDisplay(fwRule.isDisplay());
        response.setTags(createResourceTagResponses(ResourceObjectType.FirewallRule, fwRule.getId()));

        response.setState(getFirewallState(fwRule.getState()));
        response.setObjectName("firewallrule");
        return response;
    }

    private void populateOwner(ControlledEntityResponse response, ControlledEntity object) {
        Account account = ApiDBUtils.findAccountById(object.getAccountId());

        if (account.getType() == Account.Type.PROJECT) {
            Project project = ApiDBUtils.findProjectByProjectAccountId(account.getId());
            response.setProjectId(project.getUuid());
            response.setProjectName(project.getName());
        } else {
            response.setAccountName(account.getAccountName());
        }
        populateDomain(response, object.getDomainId());
    }

    private void populateDomain(ControlledEntityResponse response, long domainId) {
        Domain domain = ApiDBUtils.findDomainById(domainId);
        if (domain == null) {
            return;
        }
        response.setDomainId(domain.getUuid());
        response.setDomainName(domain.getName());
        response.setDomainPath(ApiResponseHelper.getPrettyDomainPath(domain.getPath()));
    }

    private void addLoadBalancerPolicyOwner(LBStickinessResponse response, LoadBalancer lb) {
        Account account = ApiDBUtils.findAccountById(lb.getAccountId());
        if (account != null) {
            response.setAccountName(account.getAccountName());
            Domain domain = ApiDBUtils.findDomainById(account.getDomainId());
            if (domain != null) {
                response.setDomainId(domain.getUuid());
                response.setDomainName(domain.getName());
            }
        }
    }

    private void addLoadBalancerPolicyOwner(LBHealthCheckResponse response, LoadBalancer lb) {
        Account account = ApiDBUtils.findAccountById(lb.getAccountId());
        if (account != null) {
            response.setAccountName(account.getAccountName());
            Domain domain = ApiDBUtils.findDomainById(account.getDomainId());
            if (domain != null) {
                response.setDomainId(domain.getUuid());
                response.setDomainName(domain.getName());
            }
        }
    }

    private List<ResourceTagResponse> createResourceTagResponses(ResourceObjectType resourceType, long resourceId) {
        List<? extends ResourceTag> tags = ApiDBUtils.listByResourceTypeAndId(resourceType, resourceId);
        List<ResourceTagResponse> tagResponses = new ArrayList<ResourceTagResponse>();
        for (ResourceTag tag : tags) {
            ResourceTagResponse tagResponse = createResourceTagResponse(tag, true);
            CollectionUtils.addIgnoreNull(tagResponses, tagResponse);
        }
        return tagResponses;
    }

    private ResourceTagResponse createResourceTagResponse(ResourceTag resourceTag, boolean keyValueOnly) {
        ResourceTagJoinVO rto = ApiDBUtils.newResourceTagView(resourceTag);
        if (rto == null) {
            return null;
        }
        return ApiDBUtils.newResourceTagResponse(rto, keyValueOnly);
    }

    private String getFirewallState(FirewallRule.State state) {
        if (state.equals(FirewallRule.State.Revoke)) {
            return "Deleting";
        }
        return state.toString();
    }

    private String getNetworkAclState(NetworkACLItem.State state) {
        if (state.equals(NetworkACLItem.State.Revoke)) {
            return "Deleting";
        }
        return state.toString();
    }
}
