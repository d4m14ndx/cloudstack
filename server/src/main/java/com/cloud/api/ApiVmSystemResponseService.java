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

import com.cloud.network.RouterHealthCheckResult;
import com.cloud.network.router.VirtualRouter;
import com.cloud.uservm.UserVm;
import com.cloud.vm.InstanceGroup;
import com.cloud.vm.VirtualMachine;

public interface ApiVmSystemResponseService {

    List<UserVmResponse> createUserVmResponse(ResponseView view, String objectName, EnumSet<VMDetails> details, UserVm... userVms);

    List<UserVmResponse> createUserVmResponse(ResponseView view, String objectName, UserVm... userVms);

    DomainRouterResponse createDomainRouterResponse(VirtualRouter router);

    SystemVmResponse createSystemVmResponse(VirtualMachine vm);

    InstanceGroupResponse createInstanceGroupResponse(InstanceGroup group);

    SystemVmInstanceResponse createSystemVmInstanceResponse(VirtualMachine vm);

    ListResponse<UpgradeRouterTemplateResponse> createUpgradeRouterTemplateResponse(List<Long> jobIds);

    List<RouterHealthCheckResultResponse> createHealthCheckResponse(VirtualMachine router, List<RouterHealthCheckResult> healthCheckResults);
}
