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

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.response.AutoScalePolicyResponse;
import org.apache.cloudstack.api.response.AutoScaleVmGroupResponse;
import org.apache.cloudstack.api.response.AutoScaleVmProfileResponse;
import org.apache.cloudstack.api.response.ConditionResponse;
import org.apache.cloudstack.api.response.ControlledEntityResponse;
import org.apache.cloudstack.api.response.CounterResponse;
import org.apache.cloudstack.context.CallContext;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.domain.Domain;
import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.as.AutoScalePolicy;
import com.cloud.network.as.AutoScaleVmGroup;
import com.cloud.network.as.AutoScaleVmProfile;
import com.cloud.network.as.AutoScaleVmProfileVO;
import com.cloud.network.as.Condition;
import com.cloud.network.as.ConditionVO;
import com.cloud.network.as.Counter;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offering.ServiceOffering;
import com.cloud.projects.Project;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.user.UserData;
import com.cloud.user.dao.UserDataDao;
import com.cloud.storage.VMTemplateVO;

@Component
public class ApiAutoscaleResponseService {

    @Inject
    protected AccountManager _accountMgr;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    NetworkServiceMapDao ntwkSrvcDao;
    @Inject
    UserDataDao userDataDao;

    public CounterResponse createCounterResponse(Counter counter) {
        CounterResponse response = new CounterResponse();
        response.setId(counter.getUuid());
        response.setSource(counter.getSource().toString());
        response.setName(counter.getName());
        response.setValue(counter.getValue());
        response.setProvider(counter.getProvider());
        response.setObjectName("counter");
        return response;
    }

    public ConditionResponse createConditionResponse(Condition condition) {
        ConditionResponse response = new ConditionResponse();
        response.setId(condition.getUuid());
        Counter counter = ApiDBUtils.getCounter(condition.getCounterId());
        response.setCounterId(counter.getUuid());
        response.setCounterName(counter.getName());
        CounterResponse counterResponse = createCounterResponse(counter);
        response.setCounterResponse(counterResponse);
        response.setRelationalOperator(condition.getRelationalOperator().toString());
        response.setThreshold(condition.getThreshold());
        response.setObjectName("condition");
        populateOwner(response, condition);
        return response;
    }

    public AutoScaleVmProfileResponse createAutoScaleVmProfileResponse(AutoScaleVmProfile profile) {
        AutoScaleVmProfileResponse response = new AutoScaleVmProfileResponse();
        response.setId(profile.getUuid());
        if (profile.getZoneId() != null) {
            DataCenter zone = ApiDBUtils.findZoneById(profile.getZoneId());
            if (zone != null) {
                response.setZoneId(zone.getUuid());
            }
        }
        if (profile.getServiceOfferingId() != null) {
            ServiceOffering so = ApiDBUtils.findServiceOfferingById(profile.getServiceOfferingId());
            if (so != null) {
                response.setServiceOfferingId(so.getUuid());
            }
        }
        if (profile.getTemplateId() != null) {
            VMTemplateVO template = ApiDBUtils.findTemplateById(profile.getTemplateId());
            if (template != null) {
                response.setTemplateId(template.getUuid());
                if (template.getUserDataOverridePolicy() != null) {
                    response.setUserDataPolicy(template.getUserDataOverridePolicy().toString());
                }
            }
        }
        response.setUserData(profile.getUserData());
        if (profile.getUserDataId() != null) {
            UserData userData = userDataDao.findById(profile.getUserDataId());
            if (userData != null) {
                response.setUserDataId(userData.getUuid());
                response.setUserDataName(userData.getName());
            }
        }
        response.setUserDataDetails(profile.getUserDataDetails());
        response.setOtherDeployParams(profile.getOtherDeployParamsList());
        response.setCounterParams(profile.getCounterParams());
        response.setExpungeVmGracePeriod(profile.getExpungeVmGracePeriod());
        User user = ApiDBUtils.findUserById(profile.getAutoScaleUserId());
        if (user != null) {
            response.setAutoscaleUserId(user.getUuid());
        }
        response.setObjectName("autoscalevmprofile");

        populateOwner(response, profile);
        return response;
    }

    public AutoScalePolicyResponse createAutoScalePolicyResponse(AutoScalePolicy policy) {
        AutoScalePolicyResponse response = new AutoScalePolicyResponse();
        response.setId(policy.getUuid());
        response.setName(policy.getName());
        response.setDuration(policy.getDuration());
        response.setQuietTime(policy.getQuietTime());
        response.setAction(policy.getAction().toString());
        List<ConditionVO> vos = ApiDBUtils.getAutoScalePolicyConditions(policy.getId());
        ArrayList<ConditionResponse> conditions = new ArrayList<ConditionResponse>(vos.size());
        for (ConditionVO vo : vos) {
            conditions.add(createConditionResponse(vo));
        }
        response.setConditions(conditions);
        response.setObjectName("autoscalepolicy");

        populateOwner(response, policy);
        return response;
    }

    public AutoScaleVmGroupResponse createAutoScaleVmGroupResponse(AutoScaleVmGroup vmGroup) {
        AutoScaleVmGroupResponse response = new AutoScaleVmGroupResponse();
        response.setId(vmGroup.getUuid());
        response.setName(vmGroup.getName());
        response.setMinMembers(vmGroup.getMinMembers());
        response.setMaxMembers(vmGroup.getMaxMembers());
        response.setState(vmGroup.getState().toString());
        response.setInterval(vmGroup.getInterval());
        response.setForDisplay(vmGroup.isDisplay());
        response.setCreated(vmGroup.getCreated());
        AutoScaleVmProfileVO profile = ApiDBUtils.findAutoScaleVmProfileById(vmGroup.getProfileId());
        if (profile != null) {
            response.setProfileId(profile.getUuid());
        }
        response.setAvailableVirtualMachineCount(ApiDBUtils.countAvailableVmsByGroupId(vmGroup.getId()));
        LoadBalancerVO fw = ApiDBUtils.findLoadBalancerById(vmGroup.getLoadBalancerId());
        if (fw != null) {
            response.setLoadBalancerId(fw.getUuid());

            NetworkVO network = ApiDBUtils.findNetworkById(fw.getNetworkId());

            if (network != null) {
                response.setNetworkName(network.getName());
                response.setNetworkId(network.getUuid());

                String provider = ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.Lb);
                if (provider != null) {
                    response.setLbProvider(provider);
                } else {
                    response.setLbProvider(Network.Provider.None.toString());
                }
            }

            IPAddressVO publicIp = ApiDBUtils.findIpAddressById(fw.getSourceIpAddressId());
            if (publicIp != null) {
                response.setPublicIpId(publicIp.getUuid());
                response.setPublicIp(publicIp.getAddress().addr());
                response.setPublicPort(Integer.toString(fw.getSourcePortStart()));
                response.setPrivatePort(Integer.toString(fw.getDefaultPortStart()));
            }
        }

        List<AutoScalePolicyResponse> scaleUpPoliciesResponse = new ArrayList<AutoScalePolicyResponse>();
        List<AutoScalePolicyResponse> scaleDownPoliciesResponse = new ArrayList<AutoScalePolicyResponse>();
        response.setScaleUpPolicies(scaleUpPoliciesResponse);
        response.setScaleDownPolicies(scaleDownPoliciesResponse);
        response.setObjectName("autoscalevmgroup");

        List<AutoScalePolicy> scaleUpPolicies = new ArrayList<AutoScalePolicy>();
        List<AutoScalePolicy> scaleDownPolicies = new ArrayList<AutoScalePolicy>();
        ApiDBUtils.getAutoScaleVmGroupPolicies(vmGroup.getId(), scaleUpPolicies, scaleDownPolicies);
        for (AutoScalePolicy autoScalePolicy : scaleUpPolicies) {
            scaleUpPoliciesResponse.add(createAutoScalePolicyResponse(autoScalePolicy));
        }
        for (AutoScalePolicy autoScalePolicy : scaleDownPolicies) {
            scaleDownPoliciesResponse.add(createAutoScalePolicyResponse(autoScalePolicy));
        }

        response.setHasAnnotation(annotationDao.hasAnnotations(vmGroup.getUuid(), AnnotationService.EntityType.AUTOSCALE_VM_GROUP.name(),
                _accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())));

        populateOwner(response, vmGroup);
        return response;
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

    protected void populateDomain(ControlledEntityResponse response, long domainId) {
        Domain domain = ApiDBUtils.findDomainById(domainId);
        if (domain == null) {
            return;
        }
        response.setDomainId(domain.getUuid());
        response.setDomainName(domain.getName());
        response.setDomainPath(ApiResponseHelper.getPrettyDomainPath(domain.getPath()));
    }
}
