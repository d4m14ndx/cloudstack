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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;
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
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.ViewResponseHelper;
import com.cloud.api.query.vo.AsyncJobJoinVO;
import com.cloud.api.query.vo.EventJoinVO;
import com.cloud.api.query.vo.ProjectAccountJoinVO;
import com.cloud.api.query.vo.ProjectInvitationJoinVO;
import com.cloud.api.query.vo.ProjectJoinVO;
import com.cloud.api.query.vo.SecurityGroupJoinVO;
import com.cloud.event.Event;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.security.SecurityGroup;
import com.cloud.network.security.SecurityRule;
import com.cloud.network.security.SecurityRule.SecurityRuleType;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectAccount;
import com.cloud.projects.ProjectInvitation;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.db.EntityManager;

@RunWith(MockitoJUnitRunner.class)
public class ApiProjectSecurityJobResponseServiceImplTest {

    private static final long CALLER_ACCOUNT_ID = 10L;
    private static final long OTHER_ACCOUNT_ID = 11L;
    private static final long JOB_USER_ID = 20L;
    private static final long JOB_ID = 30L;

    @Mock
    private AccountManager accountManager;
    @Mock
    private EntityManager entityManager;
    @Mock
    private AsyncJobManager asyncJobManager;
    @Mock
    private AsyncJobDao asyncJobDao;
    @Mock
    private ApiResponseOwnerService ownerService;

    private ApiProjectSecurityJobResponseServiceImpl service;

    @Before
    public void setUp() {
        service = new ApiProjectSecurityJobResponseServiceImpl();
        ReflectionTestUtils.setField(service, "accountManager", accountManager);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "asyncJobManager", asyncJobManager);
        ReflectionTestUtils.setField(service, "asyncJobDao", asyncJobDao);
        ReflectionTestUtils.setField(service, "apiResponseOwnerService", ownerService);

        AccountVO callerAccount = new AccountVO("caller", 1L, "network-domain", Account.Type.NORMAL, "caller-uuid");
        callerAccount.setId(CALLER_ACCOUNT_ID);
        UserVO callerUser = new UserVO(1L, "caller-user", "password", "Caller", "User", "caller@example.com", "UTC", "caller-user-uuid", User.Source.UNKNOWN);
        CallContext.register(callerUser, callerAccount);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    @Test
    public void createSecurityGroupResponseUsesViewResponseHelper() {
        SecurityGroup securityGroup = Mockito.mock(SecurityGroup.class);
        SecurityGroupJoinVO securityGroupView = Mockito.mock(SecurityGroupJoinVO.class);
        SecurityGroupResponse expected = new SecurityGroupResponse();

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class);
             MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newSecurityGroupView(securityGroup)).thenReturn(Collections.singletonList(securityGroupView));
            viewResponseHelper.when(() -> ViewResponseHelper.createSecurityGroupResponses(Collections.singletonList(securityGroupView)))
                    .thenReturn(Collections.singletonList(expected));

            assertSame(expected, service.createSecurityGroupResponse(securityGroup));
        }
    }

    @Test
    public void createSecurityGroupResponseFromSecurityGroupRulePreservesOwnerAndRuleContext() {
        SecurityRule ingressRule = Mockito.mock(SecurityRule.class);
        SecurityRule egressRule = Mockito.mock(SecurityRule.class);
        SecurityGroupJoinVO securityGroupView = Mockito.mock(SecurityGroupJoinVO.class);
        SecurityGroupJoinVO allowedSecurityGroupView = Mockito.mock(SecurityGroupJoinVO.class);

        when(ingressRule.getSecurityGroupId()).thenReturn(101L);
        when(ingressRule.getUuid()).thenReturn("ingress-rule-uuid");
        when(ingressRule.getProtocol()).thenReturn("icmp");
        when(ingressRule.getStartPort()).thenReturn(8);
        when(ingressRule.getEndPort()).thenReturn(0);
        when(ingressRule.getAllowedNetworkId()).thenReturn(null);
        when(ingressRule.getAllowedSourceIpCidr()).thenReturn("10.1.0.0/24");
        when(ingressRule.getRuleType()).thenReturn(SecurityRuleType.IngressRule);

        when(egressRule.getUuid()).thenReturn("egress-rule-uuid");
        when(egressRule.getProtocol()).thenReturn("tcp");
        when(egressRule.getStartPort()).thenReturn(80);
        when(egressRule.getEndPort()).thenReturn(443);
        when(egressRule.getAllowedNetworkId()).thenReturn(202L);
        when(egressRule.getRuleType()).thenReturn(SecurityRuleType.EgressRule);

        when(securityGroupView.getUuid()).thenReturn("security-group-uuid");
        when(securityGroupView.getName()).thenReturn("security-group-name");
        when(securityGroupView.getDescription()).thenReturn("security-group-description");
        when(allowedSecurityGroupView.getName()).thenReturn("allowed-security-group");
        when(allowedSecurityGroupView.getAccountName()).thenReturn("allowed-account");

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.findSecurityGroupViewById(101L)).thenReturn(Collections.singletonList(securityGroupView));
            apiDbUtils.when(() -> ApiDBUtils.findSecurityGroupViewById(202L)).thenReturn(Collections.singletonList(allowedSecurityGroupView));

            SecurityGroupResponse response = service.createSecurityGroupResponseFromSecurityGroupRule(List.of(ingressRule, egressRule));

            assertEquals("security-group-uuid", response.getId());
            assertEquals("security-group-name", ReflectionTestUtils.getField(response, "name"));
            assertEquals("security-group-description", ReflectionTestUtils.getField(response, "description"));
            assertEquals("securitygroup", response.getObjectName());
            verify(ownerService).populateOwner(response, securityGroupView);

            Set<SecurityGroupRuleResponse> ingressRules = (Set<SecurityGroupRuleResponse>)ReflectionTestUtils.getField(response, "ingressRules");
            SecurityGroupRuleResponse ingress = ingressRules.iterator().next();
            assertEquals("ingress-rule-uuid", ingress.getRuleId());
            assertEquals("icmp", ingress.getProtocol());
            assertEquals(Integer.valueOf(8), ingress.getIcmpType());
            assertEquals(Integer.valueOf(0), ingress.getIcmpCode());
            assertEquals("10.1.0.0/24", ingress.getCidr());
            assertEquals("ingressrule", ingress.getObjectName());

            Set<SecurityGroupRuleResponse> egressRules = (Set<SecurityGroupRuleResponse>)ReflectionTestUtils.getField(response, "egressRules");
            SecurityGroupRuleResponse egress = egressRules.iterator().next();
            assertEquals("egress-rule-uuid", egress.getRuleId());
            assertEquals("tcp", egress.getProtocol());
            assertEquals(Integer.valueOf(80), egress.getStartPort());
            assertEquals(Integer.valueOf(443), egress.getEndPort());
            assertEquals("allowed-security-group", egress.getSecurityGroupName());
            assertEquals("allowed-account", egress.getAccountName());
            assertEquals("egressrule", egress.getObjectName());
        }
    }

    @Test
    public void createSecurityGroupResponseFromSecurityGroupRuleReturnsEmptyResponseForEmptyRules() {
        SecurityGroupResponse response = service.createSecurityGroupResponseFromSecurityGroupRule(Collections.emptyList());

        assertNull(response.getObjectName());
    }

    @Test
    public void getSecurityGroupIdReturnsNullWhenSecurityGroupDoesNotExist() {
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.getSecurityGroup("missing", CALLER_ACCOUNT_ID)).thenReturn(null);

            assertNull(service.getSecurityGroupId("missing", CALLER_ACCOUNT_ID));
        }
    }

    @Test
    public void getSecurityGroupIdReturnsSecurityGroupId() {
        SecurityGroup securityGroup = Mockito.mock(SecurityGroup.class);
        when(securityGroup.getId()).thenReturn(101L);

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.getSecurityGroup("default", CALLER_ACCOUNT_ID)).thenReturn(securityGroup);

            assertEquals(Long.valueOf(101L), service.getSecurityGroupId("default", CALLER_ACCOUNT_ID));
        }
    }

    @Test
    public void createProjectResponseUsesDomainDetailsView() {
        Project project = Mockito.mock(Project.class);
        ProjectJoinVO projectView = Mockito.mock(ProjectJoinVO.class);
        ProjectResponse expected = new ProjectResponse();

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class);
             MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newProjectView(project)).thenReturn(Collections.singletonList(projectView));
            viewResponseHelper.when(() -> ViewResponseHelper.createProjectResponse(eq(EnumSet.of(DomainDetails.all)), any(ProjectJoinVO[].class)))
                    .thenReturn(Collections.singletonList(expected));

            assertSame(expected, service.createProjectResponse(project));
        }
    }

    @Test
    public void createProjectAccountResponseUsesViewResponseHelper() {
        ProjectAccount projectAccount = Mockito.mock(ProjectAccount.class);
        ProjectAccountJoinVO projectAccountView = Mockito.mock(ProjectAccountJoinVO.class);
        ProjectAccountResponse expected = new ProjectAccountResponse();

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class);
             MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newProjectAccountView(projectAccount)).thenReturn(projectAccountView);
            viewResponseHelper.when(() -> ViewResponseHelper.createProjectAccountResponse(projectAccountView)).thenReturn(Collections.singletonList(expected));

            assertSame(expected, service.createProjectAccountResponse(projectAccount));
        }
    }

    @Test
    public void createProjectInvitationResponseUsesApiDbUtils() {
        ProjectInvitation invitation = Mockito.mock(ProjectInvitation.class);
        ProjectInvitationJoinVO invitationView = Mockito.mock(ProjectInvitationJoinVO.class);
        ProjectInvitationResponse expected = new ProjectInvitationResponse();

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newProjectInvitationView(invitation)).thenReturn(invitationView);
            apiDbUtils.when(() -> ApiDBUtils.newProjectInvitationResponse(invitationView)).thenReturn(expected);

            assertSame(expected, service.createProjectInvitationResponse(invitation));
        }
    }

    @Test
    public void createEventResponseUsesApiDbUtils() {
        Event event = Mockito.mock(Event.class);
        EventJoinVO eventView = Mockito.mock(EventJoinVO.class);
        EventResponse expected = new EventResponse();

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newEventView(event)).thenReturn(eventView);
            apiDbUtils.when(() -> ApiDBUtils.newEventResponse(eventView)).thenReturn(expected);

            assertSame(expected, service.createEventResponse(event));
        }
    }

    @Test
    public void createAsyncJobResponseUsesApiDbUtils() {
        AsyncJob job = Mockito.mock(AsyncJob.class);
        AsyncJobJoinVO jobView = Mockito.mock(AsyncJobJoinVO.class);
        AsyncJobResponse expected = new AsyncJobResponse();

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newAsyncJobView(job)).thenReturn(jobView);
            apiDbUtils.when(() -> ApiDBUtils.newAsyncJobResponse(jobView)).thenReturn(expected);

            assertSame(expected, service.createAsyncJobResponse(job));
        }
    }

    @Test
    public void queryJobResultReturnsQueriedJobResponseForOwningNormalUser() {
        QueryAsyncJobResultCmd cmd = Mockito.mock(QueryAsyncJobResultCmd.class);
        AsyncJobVO foundJob = Mockito.mock(AsyncJobVO.class);
        AsyncJobVO queriedJob = Mockito.mock(AsyncJobVO.class);
        AsyncJobJoinVO queriedJobView = Mockito.mock(AsyncJobJoinVO.class);
        AsyncJobResponse expected = new AsyncJobResponse();
        User jobUser = Mockito.mock(User.class);
        Account jobOwner = Mockito.mock(Account.class);

        when(cmd.getId()).thenReturn(JOB_ID);
        when(foundJob.getId()).thenReturn(JOB_ID);
        when(foundJob.getUserId()).thenReturn(JOB_USER_ID);
        when(jobUser.getAccountId()).thenReturn(CALLER_ACCOUNT_ID);
        when(jobOwner.getId()).thenReturn(CALLER_ACCOUNT_ID);
        when(accountManager.getUserIncludingRemoved(JOB_USER_ID)).thenReturn(jobUser);
        when(accountManager.getAccount(CALLER_ACCOUNT_ID)).thenReturn(jobOwner);
        when(accountManager.isNormalUser(CALLER_ACCOUNT_ID)).thenReturn(true);
        when(asyncJobDao.findJob(JOB_ID, null, null)).thenReturn(foundJob);
        when(asyncJobManager.queryJob(JOB_ID, true)).thenReturn(queriedJob);

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newAsyncJobView(queriedJob)).thenReturn(queriedJobView);
            apiDbUtils.when(() -> ApiDBUtils.newAsyncJobResponse(queriedJobView)).thenReturn(expected);

            assertSame(expected, service.queryJobResult(cmd));
            verify(asyncJobDao).findJob(JOB_ID, null, null);
            verify(asyncJobManager).queryJob(JOB_ID, true);
        }
    }

    @Test(expected = PermissionDeniedException.class)
    public void queryJobResultDeniesNormalUserAccessToOtherAccountJob() {
        QueryAsyncJobResultCmd cmd = Mockito.mock(QueryAsyncJobResultCmd.class);
        AsyncJobVO foundJob = Mockito.mock(AsyncJobVO.class);
        User jobUser = Mockito.mock(User.class);
        Account jobOwner = Mockito.mock(Account.class);

        when(cmd.getId()).thenReturn(JOB_ID);
        when(foundJob.getId()).thenReturn(JOB_ID);
        when(foundJob.getUserId()).thenReturn(JOB_USER_ID);
        when(jobUser.getAccountId()).thenReturn(OTHER_ACCOUNT_ID);
        when(jobOwner.getId()).thenReturn(OTHER_ACCOUNT_ID);
        when(accountManager.getUserIncludingRemoved(JOB_USER_ID)).thenReturn(jobUser);
        when(accountManager.getAccount(OTHER_ACCOUNT_ID)).thenReturn(jobOwner);
        when(accountManager.isNormalUser(CALLER_ACCOUNT_ID)).thenReturn(true);
        when(asyncJobDao.findJob(JOB_ID, null, null)).thenReturn(foundJob);

        service.queryJobResult(cmd);
    }

    @Test
    public void queryJobResultChecksResourceAccessBeforeFindingJob() {
        QueryAsyncJobResultCmd cmd = Mockito.mock(QueryAsyncJobResultCmd.class);
        SecurityGroup securityGroup = Mockito.mock(SecurityGroup.class);
        AsyncJobVO foundJob = Mockito.mock(AsyncJobVO.class);
        AsyncJobVO queriedJob = Mockito.mock(AsyncJobVO.class);
        AsyncJobJoinVO queriedJobView = Mockito.mock(AsyncJobJoinVO.class);
        AsyncJobResponse expected = new AsyncJobResponse();
        User jobUser = Mockito.mock(User.class);
        Account jobOwner = Mockito.mock(Account.class);
        String resourceUuid = "11111111-2222-3333-4444-555555555555";

        when(cmd.getId()).thenReturn(null);
        when(cmd.getResourceType()).thenReturn("SecurityGroup");
        when(cmd.getResourceId()).thenReturn(resourceUuid);
        when(securityGroup.getId()).thenReturn(101L);
        when(securityGroup.getAccountId()).thenReturn(CALLER_ACCOUNT_ID);
        when(entityManager.findByUuidIncludingRemoved(SecurityGroup.class, resourceUuid)).thenReturn(securityGroup);
        when(foundJob.getId()).thenReturn(JOB_ID);
        when(foundJob.getUserId()).thenReturn(JOB_USER_ID);
        when(jobUser.getAccountId()).thenReturn(CALLER_ACCOUNT_ID);
        when(jobOwner.getId()).thenReturn(CALLER_ACCOUNT_ID);
        when(accountManager.getUserIncludingRemoved(JOB_USER_ID)).thenReturn(jobUser);
        when(accountManager.getAccount(CALLER_ACCOUNT_ID)).thenReturn(jobOwner);
        when(accountManager.isRootAdmin(CALLER_ACCOUNT_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ACCOUNT_ID)).thenReturn(true);
        when(asyncJobDao.findJob(null, 101L, "SecurityGroup")).thenReturn(foundJob);
        when(asyncJobManager.queryJob(JOB_ID, true)).thenReturn(queriedJob);

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newAsyncJobView(queriedJob)).thenReturn(queriedJobView);
            apiDbUtils.when(() -> ApiDBUtils.newAsyncJobResponse(queriedJobView)).thenReturn(expected);

            assertSame(expected, service.queryJobResult(cmd));
            verify(accountManager).checkAccess(CallContext.current().getCallingAccount(), AccessType.ListEntry, true, securityGroup);
            verify(asyncJobDao).findJob(null, 101L, "SecurityGroup");
        }
    }
}
