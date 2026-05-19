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
import java.util.Objects;
import java.util.function.Consumer;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.ControlledEntityResponse;
import org.apache.cloudstack.api.response.IPAddressResponse;
import org.apache.cloudstack.api.response.IpQuarantineResponse;
import org.apache.cloudstack.api.response.NicSecondaryIpResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.api.response.VlanIpRangeResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.configuration.ConfigurationService;
import com.cloud.dc.DataCenter;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanDetailsVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDetailsDao;
import com.cloud.domain.Domain;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.PublicIpQuarantine;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.vpc.Vpc;
import com.cloud.projects.Project;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicSecondaryIp;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.dao.NicSecondaryIpVO;

@Component
public class ApiAddressVlanResponseServiceImpl implements ApiAddressVlanResponseService {

    protected Logger logger = LogManager.getLogger(ApiAddressVlanResponseServiceImpl.class);

    @Inject
    private EntityManager _entityMgr;
    @Inject
    NetworkModel _ntwkModel;
    @Inject
    protected AccountManager _accountMgr;
    @Inject
    private IPAddressDao userIpAddressDao;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    FirewallRulesDao firewallRulesDao;
    @Inject
    VlanDetailsDao vlanDetailsDao;

    @Override
    public VlanIpRangeResponse createVlanIpRangeResponse(Vlan vlan) {
        return createVlanIpRangeResponse(VlanIpRangeResponse.class, vlan);
    }

    @Override
    public VlanIpRangeResponse createVlanIpRangeResponse(Class<? extends VlanIpRangeResponse> subClass, Vlan vlan) {
        try {
            Long podId = ApiDBUtils.getPodIdForVlan(vlan.getId());

            VlanIpRangeResponse vlanResponse = subClass.newInstance();
            vlanResponse.setId(vlan.getUuid());
            if (vlan.getVlanType() != null) {
                vlanResponse.setForVirtualNetwork(vlan.getVlanType().equals(VlanType.VirtualNetwork));
            }
            vlanResponse.setVlan(vlan.getVlanTag());
            DataCenter zone = ApiDBUtils.findZoneById(vlan.getDataCenterId());
            if (zone != null) {
                vlanResponse.setZoneId(zone.getUuid());
            }

            if (podId != null) {
                HostPodVO pod = ApiDBUtils.findPodById(podId);
                if (pod != null) {
                    vlanResponse.setPodId(pod.getUuid());
                    vlanResponse.setPodName(pod.getName());
                }
            }

            String gateway = vlan.getVlanGateway();
            String netmask = vlan.getVlanNetmask();
            vlanResponse.setGateway(gateway);
            vlanResponse.setNetmask(netmask);
            if (StringUtils.isNotEmpty(gateway) && StringUtils.isNotEmpty(netmask)) {
                vlanResponse.setCidr(NetUtils.getCidrFromGatewayAndNetmask(gateway, netmask));
            }

            String ipRange = vlan.getIpRange();
            if (ipRange != null) {
                String[] range = ipRange.split("-");
                vlanResponse.setStartIp(range[0]);
                vlanResponse.setEndIp(range[1]);
            }

            vlanResponse.setIp6Gateway(vlan.getIp6Gateway());
            vlanResponse.setIp6Cidr(vlan.getIp6Cidr());

            String ip6Range = vlan.getIp6Range();
            if (ip6Range != null) {
                String[] range = ip6Range.split("-");
                vlanResponse.setStartIpv6(range[0]);
                vlanResponse.setEndIpv6(range[1]);
            }

            if (vlan.getNetworkId() != null) {
                Network nw = ApiDBUtils.findNetworkById(vlan.getNetworkId());
                if (nw != null) {
                    vlanResponse.setNetworkId(nw.getUuid());
                }
            }
            Account owner = ApiDBUtils.getVlanAccount(vlan.getId());
            if (owner != null) {
                populateAccount(vlanResponse, owner.getId());
                populateDomain(vlanResponse, owner.getDomainId());
            } else {
                Domain domain = ApiDBUtils.getVlanDomain(vlan.getId());
                if (domain != null) {
                    populateDomain(vlanResponse, domain.getId());
                } else {
                    Long networkId = vlan.getNetworkId();
                    if (networkId != null) {
                        Network network = _ntwkModel.getNetwork(networkId);
                        if (network != null && TrafficType.Guest.equals(network.getTrafficType())) {
                            Long accountId = network.getAccountId();
                            populateAccount(vlanResponse, accountId);
                            populateDomain(vlanResponse, ApiDBUtils.findAccountById(accountId).getDomainId());
                        }
                    }
                }
            }

            if (vlan.getPhysicalNetworkId() != null) {
                PhysicalNetwork pnw = ApiDBUtils.findPhysicalNetworkById(vlan.getPhysicalNetworkId());
                if (pnw != null) {
                    vlanResponse.setPhysicalNetworkId(pnw.getUuid());
                }
            }
            vlanResponse.setForSystemVms(isForSystemVms(vlan.getId()));
            vlanResponse.setProvider(getProviderFromVlanDetailKey(vlan));
            vlanResponse.setObjectName("vlan");
            return vlanResponse;
        } catch (InstantiationException | IllegalAccessException e) {
            throw new CloudRuntimeException("Failed to create Vlan IP Range response", e);
        }
    }

    @Override
    public IPAddressResponse createIPAddressResponse(ResponseView view, IpAddress ipAddr) {
        VlanVO vlan = ApiDBUtils.findVlanById(ipAddr.getVlanId());
        boolean forVirtualNetworks = vlan.getVlanType().equals(VlanType.VirtualNetwork);
        long zoneId = ipAddr.getDataCenterId();

        IPAddressResponse ipResponse = new IPAddressResponse();
        ipResponse.setId(ipAddr.getUuid());
        ipResponse.setIpAddress(ipAddr.getAddress().toString());
        if (ipAddr.getAllocatedTime() != null) {
            ipResponse.setAllocated(ipAddr.getAllocatedTime());
        }
        DataCenter zone = ApiDBUtils.findZoneById(ipAddr.getDataCenterId());
        if (zone != null) {
            ipResponse.setZoneId(zone.getUuid());
            ipResponse.setZoneName(zone.getName());
        }
        ipResponse.setSourceNat(ipAddr.isSourceNat());
        ipResponse.setIsSystem(ipAddr.getSystem());

        if (ipAddr.getAllocatedToAccountId() != null) {
            populateOwner(ipResponse, ipAddr);
        }

        ipResponse.setForVirtualNetwork(forVirtualNetworks);
        ipResponse.setStaticNat(ipAddr.isOneToOneNat());

        addVmDetailsInIpResponse(ipResponse, ipAddr);
        if (ipAddr.getVmIp() != null) {
            ipResponse.setVirtualMachineIp(ipAddr.getVmIp());
        }

        if (ipAddr.getAssociatedWithNetworkId() != null) {
            Network ntwk = ApiDBUtils.findNetworkById(ipAddr.getAssociatedWithNetworkId());
            if (ntwk != null) {
                ipResponse.setAssociatedNetworkId(ntwk.getUuid());
                ipResponse.setAssociatedNetworkName(ntwk.getName());
            }
        }

        setVpcIdInResponse(ipAddr.getVpcId(), ipResponse::setVpcId, ipResponse::setVpcName);

        Long vlanNetworkId = ApiDBUtils.getVlanNetworkId(ipAddr.getVlanId());

        Long networkId;
        if (vlanNetworkId != null) {
            networkId = vlanNetworkId;
        } else {
            networkId = ApiDBUtils.getPublicNetworkIdByZone(zoneId);
        }

        if (networkId != null) {
            NetworkVO nw = ApiDBUtils.findNetworkById(networkId);
            if (nw != null) {
                ipResponse.setNetworkId(nw.getUuid());
                ipResponse.setNetworkName(nw.getName());
            }
        }
        ipResponse.setState(ipAddr.getState().toString());

        if (ipAddr.getPhysicalNetworkId() != null) {
            PhysicalNetworkVO pnw = ApiDBUtils.findPhysicalNetworkById(ipAddr.getPhysicalNetworkId());
            if (pnw != null) {
                ipResponse.setPhysicalNetworkId(pnw.getUuid());
            }
        }

        showVmInfoForSharedNetworks(forVirtualNetworks, ipAddr, ipResponse);

        if (view == ResponseView.Full) {
            VlanVO vl = ApiDBUtils.findVlanById(ipAddr.getVlanId());
            if (vl != null) {
                ipResponse.setVlanId(vl.getUuid());
                ipResponse.setVlanName(vl.getVlanTag());
            }
        }

        if (ipAddr.getSystem()) {
            if (ipAddr.isOneToOneNat()) {
                ipResponse.setPurpose(IpAddress.Purpose.StaticNat.toString());
            } else {
                ipResponse.setPurpose(IpAddress.Purpose.Lb.toString());
            }
        }

        ipResponse.setForDisplay(ipAddr.isDisplay());

        ipResponse.setPortable(ipAddr.isPortable());
        ipResponse.setForSystemVms(ipAddr.isForSystemVms());
        if (Objects.nonNull(getProviderFromVlanDetailKey(vlan))) {
            ipResponse.setForProvider(true);
        }

        List<? extends ResourceTag> tags = ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.PublicIpAddress, ipAddr.getId());
        List<ResourceTagResponse> tagResponses = new ArrayList<ResourceTagResponse>();
        for (ResourceTag tag : tags) {
            ResourceTagResponse tagResponse = createResourceTagResponse(tag, true);
            CollectionUtils.addIgnoreNull(tagResponses, tagResponse);
        }
        ipResponse.setTags(tagResponses);
        ipResponse.setHasAnnotation(annotationDao.hasAnnotations(ipAddr.getUuid(), AnnotationService.EntityType.PUBLIC_IP_ADDRESS.name(),
                _accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())));

        ipResponse.setHasRules(firewallRulesDao.countRulesByIpId(ipAddr.getId()) > 0);
        ipResponse.setObjectName("ipaddress");
        return ipResponse;
    }

    @Override
    public NicSecondaryIpResponse createSecondaryIPToNicResponse(NicSecondaryIp result) {
        NicSecondaryIpResponse response = new NicSecondaryIpResponse();
        NicVO nic = _entityMgr.findById(NicVO.class, result.getNicId());
        NetworkVO network = _entityMgr.findById(NetworkVO.class, result.getNetworkId());
        response.setId(result.getUuid());
        ApiAddressVlanResponseService.setResponseIpAddress(result, response);
        response.setNicId(nic.getUuid());
        response.setNwId(network.getUuid());
        response.setObjectName("nicsecondaryip");
        return response;
    }

    @Override
    public IpQuarantineResponse createQuarantinedIpsResponse(PublicIpQuarantine quarantinedIp) {
        IpQuarantineResponse quarantinedIpsResponse = new IpQuarantineResponse();
        String ipAddress = userIpAddressDao.findById(quarantinedIp.getPublicIpAddressId()).getAddress().toString();
        Account previousOwner = _accountMgr.getAccount(quarantinedIp.getPreviousOwnerId());

        quarantinedIpsResponse.setId(quarantinedIp.getUuid());
        quarantinedIpsResponse.setPublicIpAddress(ipAddress);
        quarantinedIpsResponse.setPreviousOwnerId(previousOwner.getUuid());
        quarantinedIpsResponse.setPreviousOwnerName(previousOwner.getName());
        quarantinedIpsResponse.setCreated(quarantinedIp.getCreated());
        quarantinedIpsResponse.setRemoved(quarantinedIp.getRemoved());
        quarantinedIpsResponse.setEndDate(quarantinedIp.getEndDate());
        quarantinedIpsResponse.setRemovalReason(quarantinedIp.getRemovalReason());
        if (quarantinedIp.getRemoverAccountId() != null) {
            Account removerAccount = _accountMgr.getAccount(quarantinedIp.getRemoverAccountId());
            quarantinedIpsResponse.setRemoverAccountId(removerAccount.getUuid());
        }
        quarantinedIpsResponse.setResponseName("quarantinedip");

        return quarantinedIpsResponse;
    }

    protected String getProviderFromVlanDetailKey(Vlan vlan) {
        for (Map.Entry<String, String> entry : ConfigurationService.ProviderDetailKeyMap.entrySet()) {
            VlanDetailsVO vlanDetail = vlanDetailsDao.findDetail(vlan.getId(), entry.getValue());
            if (Objects.nonNull(vlanDetail) && "true".equals(vlanDetail.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Return true if vlan IP range is dedicated for system vms (SSVM and CPVM), false if not.
     */
    protected boolean isForSystemVms(long vlanId) {
        SearchBuilder<IPAddressVO> sb = userIpAddressDao.createSearchBuilder();
        sb.and("vlanId", sb.entity().getVlanId(), SearchCriteria.Op.EQ);
        SearchCriteria<IPAddressVO> sc = sb.create();
        sc.setParameters("vlanId", vlanId);
        IPAddressVO userIpAddresVO = userIpAddressDao.findOneBy(sc);
        return userIpAddresVO != null ? userIpAddresVO.isForSystemVms() : false;
    }

    protected void addVmDetailsInIpResponse(IPAddressResponse response, IpAddress ipAddress) {
        if (ipAddress.getAllocatedToAccountId() != null && ipAddress.getAllocatedToAccountId() == Account.ACCOUNT_ID_SYSTEM) {
            NicVO nic = ApiDBUtils.findByIp4AddressAndNetworkId(ipAddress.getAddress().toString(), ipAddress.getNetworkId());
            if (nic != null) {
                addSystemVmInfoToIpResponse(nic, response);
            }
        }
        if (ipAddress.isForRouter()) {
            response.setVirtualMachineType(Type.DomainRouter.toString());
        }
        if (ipAddress.getAssociatedWithVmId() != null) {
            addUserVmDetailsInIpResponse(response, ipAddress);
        }
    }

    protected void addSystemVmInfoToIpResponse(NicVO nic, IPAddressResponse ipResponse) {
        final boolean isAdmin = Account.Type.ADMIN.equals(CallContext.current().getCallingAccount().getType());
        if (!isAdmin) {
            return;
        }
        try {
            nic.getInstanceId();
        } catch (NullPointerException ex) {
            return;
        }

        VirtualMachine vm = ApiDBUtils.findVMInstanceById(nic.getInstanceId());
        if (vm == null) {
            return;
        }
        ipResponse.setVirtualMachineId(vm.getUuid());
        ipResponse.setVirtualMachineName(vm.getHostName());
        ipResponse.setVirtualMachineType(vm.getType().toString());
    }

    protected void addUserVmDetailsInIpResponse(IPAddressResponse response, IpAddress ipAddress) {
        VirtualMachine vm = ApiDBUtils.findVMInstanceById(ipAddress.getAssociatedWithVmId());
        if (vm == null) {
            return;
        }
        if (vm.getType().equals(Type.User)) {
            UserVm userVm = ApiDBUtils.findUserVmById(ipAddress.getAssociatedWithVmId());
            if (userVm != null) {
                response.setVirtualMachineId(userVm.getUuid());
                response.setVirtualMachineName(userVm.getHostName());
                response.setVirtualMachineType(userVm.getType().toString());
                response.setVirtualMachineDisplayName(ObjectUtils.firstNonNull(userVm.getDisplayName(), userVm.getHostName()));
            }
        } else if (vm.getType().equals(Type.DomainRouter)) {
            final boolean isAdmin = Account.Type.ADMIN.equals(CallContext.current().getCallingAccount().getType());
            if (isAdmin) {
                response.setVirtualMachineId(vm.getUuid());
                response.setVirtualMachineName(vm.getHostName());
            }
            response.setVirtualMachineType(vm.getType().toString());
        }
    }

    protected void setVpcIdInResponse(Long vpcId, Consumer<String> vpcUuidSetter, Consumer<String> vpcNameSetter) {
        if (vpcId != null) {
            Vpc vpc = ApiDBUtils.findVpcById(vpcId);
            if (vpc != null) {
                try {
                    _accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, false, vpc);
                    vpcUuidSetter.accept(vpc.getUuid());
                } catch (PermissionDeniedException e) {
                    logger.debug("Not setting the vpcId to the response because the caller does not have access to the VPC");
                }
                vpcNameSetter.accept(vpc.getName());
            }
        }
    }

    protected void showVmInfoForSharedNetworks(boolean forVirtualNetworks, IpAddress ipAddr, IPAddressResponse ipResponse) {
        if (!forVirtualNetworks) {
            NicVO nic = ApiDBUtils.findByIp4AddressAndNetworkId(ipAddr.getAddress().toString(), ipAddr.getNetworkId());

            if (nic == null) {
                NicSecondaryIpVO secondaryIp =
                        ApiDBUtils.findSecondaryIpByIp4AddressAndNetworkId(ipAddr.getAddress().toString(), ipAddr.getNetworkId());
                if (secondaryIp != null) {
                    UserVm vm = ApiDBUtils.findUserVmById(secondaryIp.getVmId());
                    if (vm != null) {
                        ipResponse.setVirtualMachineId(vm.getUuid());
                        ipResponse.setVirtualMachineName(vm.getHostName());
                        if (vm.getDisplayName() != null) {
                            ipResponse.setVirtualMachineDisplayName(vm.getDisplayName());
                        } else {
                            ipResponse.setVirtualMachineDisplayName(vm.getHostName());
                        }
                    }
                }
            } else if (nic.getVmType() == VirtualMachine.Type.User) {
                UserVm vm = ApiDBUtils.findUserVmById(nic.getInstanceId());
                if (vm != null) {
                    ipResponse.setVirtualMachineId(vm.getUuid());
                    ipResponse.setVirtualMachineName(vm.getHostName());
                    if (vm.getDisplayName() != null) {
                        ipResponse.setVirtualMachineDisplayName(vm.getDisplayName());
                    } else {
                        ipResponse.setVirtualMachineDisplayName(vm.getHostName());
                    }
                }
            } else if (nic.getVmType() == Type.DomainRouter) {
                VirtualMachine vm = ApiDBUtils.findVMInstanceById(nic.getInstanceId());
                if (vm != null) {
                    ipResponse.setVirtualMachineId(vm.getUuid());
                    ipResponse.setVirtualMachineName(vm.getHostName());
                    ipResponse.setVirtualMachineType(vm.getType().toString());
                }
            } else if (nic.getVmType().isUsedBySystem()) {
                ipResponse.setIsSystem(true);
                addSystemVmInfoToIpResponse(nic, ipResponse);
            }
        }
    }

    protected void populateOwner(ControlledEntityResponse response, ControlledEntity object) {
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

    protected void populateAccount(ControlledEntityResponse response, long accountId) {
        Account account = ApiDBUtils.findAccountById(accountId);
        if (account == null) {
            logger.debug("Unable to find account with id: " + accountId);
        } else if (account.getType() == Account.Type.PROJECT) {
            Project project = ApiDBUtils.findProjectByProjectAccountId(account.getId());
            if (project != null) {
                response.setProjectId(project.getUuid());
                response.setProjectName(project.getName());
                response.setAccountName(account.getAccountName());
            } else {
                logger.debug("Unable to find project with id: " + account.getId());
            }
        } else {
            response.setAccountName(account.getAccountName());
        }
    }

    protected void populateDomain(ControlledEntityResponse response, long domainId) {
        Domain domain = ApiDBUtils.findDomainById(domainId);
        if (domain == null) {
            return;
        }
        response.setDomainId(domain.getUuid());
        response.setDomainName(domain.getName());
        response.setDomainPath(ApiResponseHelper.getPrettyDomainPath(domain.getPath()));
    }

    protected ResourceTagResponse createResourceTagResponse(ResourceTag resourceTag, boolean keyValueOnly) {
        ResourceTagJoinVO rto = ApiDBUtils.newResourceTagView(resourceTag);
        if (rto == null) {
            return null;
        }
        return ApiDBUtils.newResourceTagResponse(rto, keyValueOnly);
    }
}
