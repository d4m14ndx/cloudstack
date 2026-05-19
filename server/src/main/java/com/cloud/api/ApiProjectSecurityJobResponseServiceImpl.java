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

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants.DomainDetails;
import org.apache.cloudstack.api.command.user.job.QueryAsyncJobResultCmd;
import org.apache.cloudstack.api.response.AsyncJobResponse;
import org.apache.cloudstack.api.response.EventResponse;
import org.apache.cloudstack.api.response.ProjectAccountResponse;
import org.apache.cloudstack.api.response.ProjectInvitationResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.SecurityGroupResponse;
import org.apache.cloudstack.api.response.SecurityGroupRuleResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.springframework.stereotype.Component;

import com.cloud.api.query.ResourceIdSupport;
import com.cloud.api.query.ViewResponseHelper;
import com.cloud.api.query.vo.AsyncJobJoinVO;
import com.cloud.api.query.vo.EventJoinVO;
import com.cloud.api.query.vo.ProjectAccountJoinVO;
import com.cloud.api.query.vo.ProjectInvitationJoinVO;
import com.cloud.api.query.vo.ProjectJoinVO;
import com.cloud.api.query.vo.SecurityGroupJoinVO;
import com.cloud.event.Event;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.security.SecurityGroup;
import com.cloud.network.security.SecurityRule;
import com.cloud.network.security.SecurityRule.SecurityRuleType;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectAccount;
import com.cloud.projects.ProjectInvitation;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.db.EntityManager;

@Component
public class ApiProjectSecurityJobResponseServiceImpl implements ApiProjectSecurityJobResponseService, ResourceIdSupport {

    @Inject
    private EntityManager entityManager;
    @Inject
    protected AccountManager accountManager;
    @Inject
    protected AsyncJobManager asyncJobManager;
    @Inject
    private AsyncJobDao asyncJobDao;
    @Inject
    private ApiResponseOwnerService apiResponseOwnerService;

    @Override
    public SecurityGroupResponse createSecurityGroupResponse(SecurityGroup group) {
        List<SecurityGroupJoinVO> viewSgs = ApiDBUtils.newSecurityGroupView(group);
        List<SecurityGroupResponse> listSgs = ViewResponseHelper.createSecurityGroupResponses(viewSgs);
        assert listSgs != null && listSgs.size() == 1 : "There should be one security group returned";
        return listSgs.get(0);
    }

    @Override
    public SecurityGroupResponse createSecurityGroupResponseFromSecurityGroupRule(List<? extends SecurityRule> securityRules) {
        SecurityGroupResponse response = new SecurityGroupResponse();

        if ((securityRules != null) && !securityRules.isEmpty()) {
            SecurityGroupJoinVO securityGroup = ApiDBUtils.findSecurityGroupViewById(securityRules.get(0).getSecurityGroupId()).get(0);
            response.setId(securityGroup.getUuid());
            response.setName(securityGroup.getName());
            response.setDescription(securityGroup.getDescription());

            apiResponseOwnerService.populateOwner(response, securityGroup);

            for (SecurityRule securityRule : securityRules) {
                SecurityGroupRuleResponse securityGroupData = new SecurityGroupRuleResponse();

                securityGroupData.setRuleId(securityRule.getUuid());
                securityGroupData.setProtocol(securityRule.getProtocol());
                if ("icmp".equalsIgnoreCase(securityRule.getProtocol())) {
                    securityGroupData.setIcmpType(securityRule.getStartPort());
                    securityGroupData.setIcmpCode(securityRule.getEndPort());
                } else {
                    securityGroupData.setStartPort(securityRule.getStartPort());
                    securityGroupData.setEndPort(securityRule.getEndPort());
                }

                Long allowedSecurityGroupId = securityRule.getAllowedNetworkId();
                if (allowedSecurityGroupId != null) {
                    List<SecurityGroupJoinVO> sgs = ApiDBUtils.findSecurityGroupViewById(allowedSecurityGroupId);
                    if (sgs != null && sgs.size() > 0) {
                        SecurityGroupJoinVO sg = sgs.get(0);
                        securityGroupData.setSecurityGroupName(sg.getName());
                        securityGroupData.setAccountName(sg.getAccountName());
                    }
                } else {
                    securityGroupData.setCidr(securityRule.getAllowedSourceIpCidr());
                }
                if (securityRule.getRuleType() == SecurityRuleType.IngressRule) {
                    securityGroupData.setObjectName("ingressrule");
                    response.addSecurityGroupIngressRule(securityGroupData);
                } else {
                    securityGroupData.setObjectName("egressrule");
                    response.addSecurityGroupEgressRule(securityGroupData);
                }
            }
            response.setObjectName("securitygroup");
        }
        return response;
    }

    @Override
    public Long getSecurityGroupId(String groupName, long accountId) {
        SecurityGroup sg = ApiDBUtils.getSecurityGroup(groupName, accountId);
        if (sg == null) {
            return null;
        } else {
            return sg.getId();
        }
    }

    @Override
    public ProjectResponse createProjectResponse(Project project) {
        List<ProjectJoinVO> viewPrjs = ApiDBUtils.newProjectView(project);
        List<ProjectResponse> listPrjs = ViewResponseHelper.createProjectResponse(
                EnumSet.of(DomainDetails.all), viewPrjs.toArray(new ProjectJoinVO[viewPrjs.size()]));
        assert listPrjs != null && listPrjs.size() == 1 : "There should be one project  returned";
        return listPrjs.get(0);
    }

    @Override
    public ProjectAccountResponse createProjectAccountResponse(ProjectAccount projectAccount) {
        ProjectAccountJoinVO vProj = ApiDBUtils.newProjectAccountView(projectAccount);
        List<ProjectAccountResponse> listProjs = ViewResponseHelper.createProjectAccountResponse(vProj);
        assert listProjs != null && listProjs.size() == 1 : "There should be one project account returned";
        return listProjs.get(0);
    }

    @Override
    public ProjectInvitationResponse createProjectInvitationResponse(ProjectInvitation invite) {
        ProjectInvitationJoinVO vInvite = ApiDBUtils.newProjectInvitationView(invite);
        return ApiDBUtils.newProjectInvitationResponse(vInvite);
    }

    @Override
    public EventResponse createEventResponse(Event event) {
        EventJoinVO vEvent = ApiDBUtils.newEventView(event);
        return ApiDBUtils.newEventResponse(vEvent);
    }

    @Override
    public AsyncJobResponse queryJobResult(final QueryAsyncJobResultCmd cmd) {
        ApiCommandResourceType resourceType = getResourceType(cmd.getResourceType());
        String resourceTypeName = Optional.ofNullable(resourceType).map(ApiCommandResourceType::name).orElse(null);

        Long resourceId = getResourceId(resourceType, cmd.getResourceId());
        Long jobId = cmd.getId();
        if (jobId == null && resourceId == null) {
            throw new InvalidParameterValueException("Expected parameter job id or parameters resource type and resource id");
        }

        final AsyncJob job = asyncJobDao.findJob(jobId, resourceId, resourceTypeName);
        if (job == null) {
            throw new InvalidParameterValueException("Unable to find a job by id " + jobId + " resource type "
                    + cmd.getResourceType() + " resource id " + cmd.getResourceId());
        }
        jobId = job.getId();

        final User userJobOwner = accountManager.getUserIncludingRemoved(job.getUserId());
        final Account jobOwner = accountManager.getAccount(userJobOwner.getAccountId());

        final Account caller = CallContext.current().getCallingAccount();
        //check permissions
        if (accountManager.isNormalUser(caller.getId())) {
            //regular users can see only jobs they own
            if (caller.getId() != jobOwner.getId()) {
                throw new PermissionDeniedException("Account " + caller + " is not authorized to see job id=" + job.getId());
            }
        } else if (accountManager.isDomainAdmin(caller.getId())) {
            accountManager.checkAccess(caller, null, true, jobOwner);
        }

        return createAsyncJobResponse(asyncJobManager.queryJob(jobId, true));
    }

    @Override
    public AsyncJobResponse createAsyncJobResponse(AsyncJob job) {
        AsyncJobJoinVO vJob = ApiDBUtils.newAsyncJobView(job);
        return ApiDBUtils.newAsyncJobResponse(vJob);
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public AccountManager getAccountManager() {
        return accountManager;
    }
}
