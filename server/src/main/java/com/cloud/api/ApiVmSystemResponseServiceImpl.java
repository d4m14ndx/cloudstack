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
import java.util.EnumSet;
import java.util.List;

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiConstants.VMDetails;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.DomainRouterResponse;
import org.apache.cloudstack.api.response.InstanceGroupResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.RouterHealthCheckResultResponse;
import org.apache.cloudstack.api.response.SystemVmInstanceResponse;
import org.apache.cloudstack.api.response.SystemVmResponse;
import org.apache.cloudstack.api.response.UpgradeRouterTemplateResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.jobs.AsyncJob;

import com.cloud.api.query.ViewResponseHelper;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.api.query.vo.InstanceGroupJoinVO;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Vlan;
import com.cloud.host.ControlState;
import com.cloud.host.Host;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.RouterHealthCheckResult;
import com.cloud.network.router.VirtualRouter;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.VMTemplateVO;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.ConsoleProxyVO;
import com.cloud.vm.InstanceGroup;
import com.cloud.vm.NicProfile;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;

@Component
public class ApiVmSystemResponseServiceImpl implements ApiVmSystemResponseService {

    @Inject
    private EntityManager entityMgr;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    protected AccountManager accountManager;

    @Override
    public List<UserVmResponse> createUserVmResponse(ResponseView view, String objectName, EnumSet<VMDetails> details, UserVm... userVms) {
        List<UserVmJoinVO> viewVms = ApiDBUtils.newUserVmView(userVms);
        return ViewResponseHelper.createUserVmResponse(view, objectName, details, viewVms.toArray(new UserVmJoinVO[viewVms.size()]));

    }

    @Override
    public List<UserVmResponse> createUserVmResponse(ResponseView view, String objectName, UserVm... userVms) {
        List<UserVmJoinVO> viewVms = ApiDBUtils.newUserVmView(userVms);
        return ViewResponseHelper.createUserVmResponse(view, objectName, viewVms.toArray(new UserVmJoinVO[viewVms.size()]));
    }

    @Override
    public DomainRouterResponse createDomainRouterResponse(VirtualRouter router) {
        List<DomainRouterJoinVO> viewVrs = ApiDBUtils.newDomainRouterView(router);
        List<DomainRouterResponse> listVrs = ViewResponseHelper.createDomainRouterResponse(viewVrs.toArray(new DomainRouterJoinVO[viewVrs.size()]));
        assert listVrs != null && listVrs.size() == 1 : "There should be one virtual router returned";
        return listVrs.get(0);
    }

    @Override
    public SystemVmResponse createSystemVmResponse(VirtualMachine vm) {
        SystemVmResponse vmResponse = new SystemVmResponse();
        if (vm.getType() == Type.SecondaryStorageVm || vm.getType() == Type.ConsoleProxy || vm.getType() == Type.DomainRouter || vm.getType() == Type.NetScalerVm) {
            vmResponse.setId(vm.getUuid());
            vmResponse.setSystemVmType(vm.getType().toString().toLowerCase());
            vmResponse.setName(vm.getHostName());

            if (vm.getPodIdToDeployIn() != null) {
                HostPodVO pod = ApiDBUtils.findPodById(vm.getPodIdToDeployIn());
                if (pod != null) {
                    vmResponse.setPodId(pod.getUuid());
                    vmResponse.setPodName(pod.getName());
                }
            }
            VMTemplateVO template = ApiDBUtils.findTemplateById(vm.getTemplateId());
            if (template != null) {
                vmResponse.setTemplateId(template.getUuid());
                vmResponse.setTemplateName(template.getName());
                vmResponse.setArch(template.getArch().getType());
            }
            vmResponse.setCreated(vm.getCreated());
            vmResponse.setHypervisor(vm.getHypervisorType().getHypervisorDisplayName());

            ServiceOffering serviceOffering = ApiDBUtils.findServiceOfferingById(vm.getServiceOfferingId());
            if (serviceOffering != null) {
                vmResponse.setServiceOfferingId(serviceOffering.getUuid());
                vmResponse.setServiceOfferingName(serviceOffering.getName());
            }

            if (vm.getHostId() != null) {
                Host host = ApiDBUtils.findHostById(vm.getHostId());
                if (host != null) {
                    vmResponse.setHostId(host.getUuid());
                    vmResponse.setHostName(host.getName());
                    vmResponse.setHostControlState(ControlState.getControlState(host.getStatus(), host.getResourceState()).toString());
                }
            }

            if (VirtualMachine.systemVMs.contains(vm.getType())) {
                Host systemVmHost = ApiDBUtils.findHostByTypeNameAndZoneId(vm.getDataCenterId(), vm.getHostName(),
                        Type.SecondaryStorageVm.equals(vm.getType()) ? Host.Type.SecondaryStorageVM : Host.Type.ConsoleProxy);
                if (systemVmHost != null) {
                    vmResponse.setAgentState(systemVmHost.getStatus());
                    vmResponse.setDisconnectedOn(systemVmHost.getDisconnectedOn());
                    vmResponse.setVersion(systemVmHost.getVersion());
                }
            }

            if (vm.getState() != null) {
                vmResponse.setState(vm.getState().toString());
            }

            vmResponse.setDynamicallyScalable(vm.isDynamicallyScalable());
            if (vm.getType() == Type.ConsoleProxy) {
                ConsoleProxyVO proxy = ApiDBUtils.findConsoleProxy(vm.getId());
                if (proxy != null) {
                    vmResponse.setActiveViewerSessions(proxy.getActiveSession());
                }
            }

            DataCenter zone = ApiDBUtils.findZoneById(vm.getDataCenterId());
            if (zone != null) {
                vmResponse.setZoneId(zone.getUuid());
                vmResponse.setZoneName(zone.getName());
                vmResponse.setDns1(zone.getDns1());
                vmResponse.setDns2(zone.getDns2());
            }

            vmResponse.setHasAnnotation(annotationDao.hasAnnotations(vm.getUuid(), AnnotationService.EntityType.SYSTEM_VM.name(),
                    accountManager.isRootAdmin(CallContext.current().getCallingAccount().getId())));
            List<NicProfile> nicProfiles = ApiDBUtils.getNics(vm);
            for (NicProfile singleNicProfile : nicProfiles) {
                Network network = ApiDBUtils.findNetworkById(singleNicProfile.getNetworkId());
                if (network != null) {
                    if (network.getTrafficType() == TrafficType.Management) {
                        vmResponse.setPrivateIp(singleNicProfile.getIPv4Address());
                        vmResponse.setPrivateMacAddress(singleNicProfile.getMacAddress());
                        vmResponse.setPrivateNetmask(singleNicProfile.getIPv4Netmask());
                    } else if (network.getTrafficType() == TrafficType.Control) {
                        vmResponse.setLinkLocalIp(singleNicProfile.getIPv4Address());
                        vmResponse.setLinkLocalMacAddress(singleNicProfile.getMacAddress());
                        vmResponse.setLinkLocalNetmask(singleNicProfile.getIPv4Netmask());
                    } else if (network.getTrafficType() == TrafficType.Public) {
                        vmResponse.setPublicIp(singleNicProfile.getIPv4Address());
                        vmResponse.setPublicMacAddress(singleNicProfile.getMacAddress());
                        vmResponse.setPublicNetmask(singleNicProfile.getIPv4Netmask());
                        vmResponse.setGateway(singleNicProfile.getIPv4Gateway());
                    } else if (network.getTrafficType() == TrafficType.Guest) {
                        /*
                          * In basic zone, public ip has TrafficType.Guest in case EIP service is not enabled.
                          * When EIP service is enabled in the basic zone, system VM by default get the public
                          * IP allocated for EIP. So return the guest/public IP accordingly.
                          * */
                        NetworkOffering networkOffering = ApiDBUtils.findNetworkOfferingById(network.getNetworkOfferingId());
                        if (networkOffering.isElasticIp()) {
                            IpAddress ip = ApiDBUtils.findIpByAssociatedVmId(vm.getId());
                            if (ip != null) {
                                Vlan vlan = ApiDBUtils.findVlanById(ip.getVlanId());
                                vmResponse.setPublicIp(ip.getAddress().addr());
                                vmResponse.setPublicNetmask(vlan.getVlanNetmask());
                                vmResponse.setGateway(vlan.getVlanGateway());
                            }
                        } else {
                            vmResponse.setPublicIp(singleNicProfile.getIPv4Address());
                            vmResponse.setPublicMacAddress(singleNicProfile.getMacAddress());
                            vmResponse.setPublicNetmask(singleNicProfile.getIPv4Netmask());
                            vmResponse.setGateway(singleNicProfile.getIPv4Gateway());
                        }
                    } else if (network.getTrafficType() == TrafficType.Storage) {
                        vmResponse.setStorageIp(singleNicProfile.getIPv4Address());
                    }
                }
            }
        }
        vmResponse.setObjectName("systemvm");
        return vmResponse;
    }

    @Override
    public InstanceGroupResponse createInstanceGroupResponse(InstanceGroup group) {
        InstanceGroupJoinVO vgroup = ApiDBUtils.newInstanceGroupView(group);
        return ApiDBUtils.newInstanceGroupResponse(vgroup);
    }

    @Override
    public SystemVmInstanceResponse createSystemVmInstanceResponse(VirtualMachine vm) {
        SystemVmInstanceResponse vmResponse = new SystemVmInstanceResponse();
        vmResponse.setId(vm.getUuid());
        vmResponse.setSystemVmType(vm.getType().toString().toLowerCase());
        vmResponse.setName(vm.getHostName());
        if (vm.getHostId() != null) {
            Host host = ApiDBUtils.findHostById(vm.getHostId());
            if (host != null) {
                vmResponse.setHostId(host.getUuid());
            }
        }
        if (vm.getState() != null) {
            vmResponse.setState(vm.getState().toString());
        }
        if (vm.getType() == Type.DomainRouter) {
            VirtualRouter router = (VirtualRouter)vm;
            if (router.getRole() != null) {
                vmResponse.setRole(router.getRole().toString());
            }
        }
        vmResponse.setObjectName("systemvminstance");
        return vmResponse;
    }

    @Override
    public ListResponse<UpgradeRouterTemplateResponse> createUpgradeRouterTemplateResponse(List<Long> jobIds) {
        ListResponse<UpgradeRouterTemplateResponse> response = new ListResponse<UpgradeRouterTemplateResponse>();
        List<UpgradeRouterTemplateResponse> responses = new ArrayList<UpgradeRouterTemplateResponse>();
        for (Long jobId : jobIds) {
            UpgradeRouterTemplateResponse routerResponse = new UpgradeRouterTemplateResponse();
            AsyncJob job = entityMgr.findById(AsyncJob.class, jobId);
            routerResponse.setJobId((job.getUuid()));
            routerResponse.setObjectName("asyncjobs");
            responses.add(routerResponse);
        }
        response.setResponses(responses);
        return response;
    }

    @Override
    public List<RouterHealthCheckResultResponse> createHealthCheckResponse(VirtualMachine router, List<RouterHealthCheckResult> healthCheckResults) {
        List<RouterHealthCheckResultResponse> responses = new ArrayList<>(healthCheckResults.size());
        for (RouterHealthCheckResult hcResult : healthCheckResults) {
            RouterHealthCheckResultResponse healthCheckResponse = new RouterHealthCheckResultResponse();
            healthCheckResponse.setObjectName("routerhealthchecks");
            healthCheckResponse.setCheckName(hcResult.getCheckName());
            healthCheckResponse.setCheckType(hcResult.getCheckType());
            switch (hcResult.getCheckResult()) {
                case SUCCESS:
                    healthCheckResponse.setResult(true);
                    break;
                case FAILED:
                    healthCheckResponse.setResult(false);
                    break;
                default:
                    // no result if not definite
            }
            healthCheckResponse.setState(hcResult.getCheckResult());
            healthCheckResponse.setLastUpdated(hcResult.getLastUpdateTime());
            healthCheckResponse.setDetails(hcResult.getParsedCheckDetails());
            responses.add(healthCheckResponse);
        }
        return responses;
    }
}
