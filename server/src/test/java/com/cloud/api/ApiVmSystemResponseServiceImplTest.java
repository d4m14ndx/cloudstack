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

import java.util.Date;
import java.util.EnumSet;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import org.apache.cloudstack.api.ApiConstants.VMDetails;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.response.DomainRouterResponse;
import org.apache.cloudstack.api.response.InstanceGroupResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.RouterHealthCheckResultResponse;
import org.apache.cloudstack.api.response.SystemVmInstanceResponse;
import org.apache.cloudstack.api.response.SystemVmResponse;
import org.apache.cloudstack.api.response.UpgradeRouterTemplateResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.framework.jobs.AsyncJob;

import com.cloud.api.query.ViewResponseHelper;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.api.query.vo.InstanceGroupJoinVO;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.host.HostVO;
import com.cloud.network.RouterHealthCheckResult;
import com.cloud.network.VirtualNetworkApplianceService;
import com.cloud.network.router.VirtualRouter;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.InstanceGroup;
import com.cloud.vm.VirtualMachine;

public class ApiVmSystemResponseServiceImplTest {

    private final ApiVmSystemResponseServiceImpl service = new ApiVmSystemResponseServiceImpl();

    @Test
    public void createUserVmResponseWithDetailsUsesJoinedViewResponse() {
        UserVm userVm = Mockito.mock(UserVm.class);
        UserVmJoinVO viewVm = Mockito.mock(UserVmJoinVO.class);
        List<UserVmJoinVO> viewVms = List.of(viewVm);
        EnumSet<VMDetails> details = EnumSet.of(VMDetails.nics);
        List<UserVmResponse> expected = List.of(new UserVmResponse());

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class);
                MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newUserVmView(userVm)).thenReturn(viewVms);
            viewResponseHelper.when(() -> ViewResponseHelper.createUserVmResponse(
                    Mockito.eq(ResponseObject.ResponseView.Full),
                    Mockito.eq("uservm"),
                    Mockito.eq(details),
                    Mockito.<UserVmJoinVO[]>any())).thenReturn(expected);

            List<UserVmResponse> response = service.createUserVmResponse(ResponseObject.ResponseView.Full, "uservm", details, userVm);

            Assert.assertSame(expected, response);
        }
    }

    @Test
    public void createUserVmResponseUsesJoinedViewResponse() {
        UserVm userVm = Mockito.mock(UserVm.class);
        UserVmJoinVO viewVm = Mockito.mock(UserVmJoinVO.class);
        List<UserVmJoinVO> viewVms = List.of(viewVm);
        List<UserVmResponse> expected = List.of(new UserVmResponse());

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class);
                MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newUserVmView(userVm)).thenReturn(viewVms);
            viewResponseHelper.when(() -> ViewResponseHelper.createUserVmResponse(
                    Mockito.eq(ResponseObject.ResponseView.Restricted),
                    Mockito.eq("virtualmachine"),
                    Mockito.<UserVmJoinVO[]>any())).thenReturn(expected);

            List<UserVmResponse> response = service.createUserVmResponse(ResponseObject.ResponseView.Restricted, "virtualmachine", userVm);

            Assert.assertSame(expected, response);
        }
    }

    @Test
    public void createDomainRouterResponseReturnsSingleJoinedRouterResponse() {
        VirtualRouter router = Mockito.mock(VirtualRouter.class);
        DomainRouterJoinVO viewRouter = Mockito.mock(DomainRouterJoinVO.class);
        DomainRouterResponse expected = new DomainRouterResponse();

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class);
                MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newDomainRouterView(router)).thenReturn(List.of(viewRouter));
            viewResponseHelper.when(() -> ViewResponseHelper.createDomainRouterResponse(Mockito.<DomainRouterJoinVO[]>any()))
                    .thenReturn(List.of(expected));

            DomainRouterResponse response = service.createDomainRouterResponse(router);

            Assert.assertSame(expected, response);
        }
    }

    @Test
    public void createInstanceGroupResponseUsesJoinedGroupResponse() {
        InstanceGroup group = Mockito.mock(InstanceGroup.class);
        InstanceGroupJoinVO viewGroup = Mockito.mock(InstanceGroupJoinVO.class);
        InstanceGroupResponse expected = new InstanceGroupResponse();

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.newInstanceGroupView(group)).thenReturn(viewGroup);
            apiDbUtils.when(() -> ApiDBUtils.newInstanceGroupResponse(viewGroup)).thenReturn(expected);

            InstanceGroupResponse response = service.createInstanceGroupResponse(group);

            Assert.assertSame(expected, response);
        }
    }

    @Test
    public void createSystemVmResponseReturnsSystemVmObjectForUnsupportedVmType() {
        VirtualMachine vm = Mockito.mock(VirtualMachine.class);
        Mockito.when(vm.getType()).thenReturn(VirtualMachine.Type.User);

        SystemVmResponse response = service.createSystemVmResponse(vm);

        Assert.assertEquals("systemvm", response.getObjectName());
    }

    @Test
    public void createSystemVmInstanceResponseIncludesHostAndRouterRole() {
        VirtualRouter router = Mockito.mock(VirtualRouter.class);
        Mockito.when(router.getUuid()).thenReturn("router-uuid");
        Mockito.when(router.getType()).thenReturn(VirtualMachine.Type.DomainRouter);
        Mockito.when(router.getHostName()).thenReturn("router-hostname");
        Mockito.when(router.getHostId()).thenReturn(10L);
        Mockito.when(router.getState()).thenReturn(VirtualMachine.State.Running);
        Mockito.when(router.getRole()).thenReturn(VirtualRouter.Role.INTERNAL_LB_VM);
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getUuid()).thenReturn("host-uuid");

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.findHostById(10L)).thenReturn(host);

            SystemVmInstanceResponse response = service.createSystemVmInstanceResponse(router);

            Assert.assertEquals("router-uuid", response.getId());
            Assert.assertEquals("domainrouter", response.getSystemVmType());
            Assert.assertEquals("router-hostname", response.getName());
            Assert.assertEquals("host-uuid", response.getHostId());
            Assert.assertEquals("Running", response.getState());
            Assert.assertEquals("INTERNAL_LB_VM", response.getRole());
            Assert.assertEquals("systemvminstance", response.getObjectName());
        }
    }

    @Test
    public void createUpgradeRouterTemplateResponseReturnsAsyncJobIds() {
        EntityManager entityManager = Mockito.mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityMgr", entityManager);
        AsyncJob job = Mockito.mock(AsyncJob.class);
        Mockito.when(job.getUuid()).thenReturn("job-uuid");
        Mockito.when(entityManager.findById(AsyncJob.class, 7L)).thenReturn(job);

        ListResponse<UpgradeRouterTemplateResponse> response = service.createUpgradeRouterTemplateResponse(List.of(7L));

        Assert.assertEquals(1, response.getResponses().size());
        Assert.assertEquals("job-uuid", response.getResponses().get(0).getJobId());
        Assert.assertEquals("asyncjobs", response.getResponses().get(0).getObjectName());
    }

    @Test
    public void createHealthCheckResponseMapsHealthCheckFields() {
        Date updated = new Date(1234L);
        RouterHealthCheckResult success = healthCheck("basic", "ping", VirtualNetworkApplianceService.RouterHealthStatus.SUCCESS, updated, "ok");
        RouterHealthCheckResult failed = healthCheck("advanced", "dns", VirtualNetworkApplianceService.RouterHealthStatus.FAILED, updated, "failed");

        List<RouterHealthCheckResultResponse> responses = service.createHealthCheckResponse(Mockito.mock(VirtualMachine.class), List.of(success, failed));

        Assert.assertEquals(2, responses.size());
        Assert.assertEquals("routerhealthchecks", responses.get(0).getObjectName());
        Assert.assertEquals("ping", responses.get(0).getCheckName());
        Assert.assertEquals("basic", responses.get(0).getCheckType());
        Assert.assertTrue(responses.get(0).getResult());
        Assert.assertEquals(VirtualNetworkApplianceService.RouterHealthStatus.SUCCESS, responses.get(0).getState());
        Assert.assertEquals(updated, responses.get(0).getLastUpdated());
        Assert.assertEquals("ok", responses.get(0).getDetails());
        Assert.assertFalse(responses.get(1).getResult());
        Assert.assertEquals(VirtualNetworkApplianceService.RouterHealthStatus.FAILED, responses.get(1).getState());
    }

    private RouterHealthCheckResult healthCheck(String type, String name, VirtualNetworkApplianceService.RouterHealthStatus status, Date updated, String details) {
        RouterHealthCheckResult result = Mockito.mock(RouterHealthCheckResult.class);
        Mockito.when(result.getCheckType()).thenReturn(type);
        Mockito.when(result.getCheckName()).thenReturn(name);
        Mockito.when(result.getCheckResult()).thenReturn(status);
        Mockito.when(result.getLastUpdateTime()).thenReturn(updated);
        Mockito.when(result.getParsedCheckDetails()).thenReturn(details);
        return result;
    }
}
