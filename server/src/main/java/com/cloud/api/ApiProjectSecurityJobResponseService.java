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

import java.util.List;

import org.apache.cloudstack.api.command.user.job.QueryAsyncJobResultCmd;
import org.apache.cloudstack.api.response.AsyncJobResponse;
import org.apache.cloudstack.api.response.EventResponse;
import org.apache.cloudstack.api.response.ProjectAccountResponse;
import org.apache.cloudstack.api.response.ProjectInvitationResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.SecurityGroupResponse;
import org.apache.cloudstack.framework.jobs.AsyncJob;

import com.cloud.event.Event;
import com.cloud.network.security.SecurityGroup;
import com.cloud.network.security.SecurityRule;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectAccount;
import com.cloud.projects.ProjectInvitation;

public interface ApiProjectSecurityJobResponseService {

    SecurityGroupResponse createSecurityGroupResponse(SecurityGroup group);

    SecurityGroupResponse createSecurityGroupResponseFromSecurityGroupRule(List<? extends SecurityRule> securityRules);

    Long getSecurityGroupId(String groupName, long accountId);

    ProjectResponse createProjectResponse(Project project);

    ProjectAccountResponse createProjectAccountResponse(ProjectAccount projectAccount);

    ProjectInvitationResponse createProjectInvitationResponse(ProjectInvitation invite);

    EventResponse createEventResponse(Event event);

    AsyncJobResponse queryJobResult(QueryAsyncJobResultCmd cmd);

    AsyncJobResponse createAsyncJobResponse(AsyncJob job);
}
