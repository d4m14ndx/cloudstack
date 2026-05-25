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
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.response.ControlledEntityResponse;
import org.apache.cloudstack.api.response.ControlledViewEntityResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.vo.ControlledViewEntity;
import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.domain.DomainVO;
import com.cloud.projects.Project;
import com.cloud.server.ResourceTag;
import com.cloud.user.Account;

@RunWith(MockitoJUnitRunner.class)
public class ApiResponseOwnerServiceImplTest {

    private final ApiResponseOwnerService service = new ApiResponseOwnerServiceImpl();

    @Mock
    private ControlledEntity controlledEntity;
    @Mock
    private ControlledViewEntity controlledViewEntity;
    @Mock
    private Account account;
    @Mock
    private Project project;
    @Mock
    private DomainVO domain;
    @Mock
    private ResourceTagJoinVO resourceTag;

    @Test
    public void getPrettyDomainPathAddsRootPrefixAndDropsTrailingSlash() {
        assertEquals("ROOT/customer/engineering", service.getPrettyDomainPath("/customer/engineering/"));
        assertEquals("ROOT", service.getPrettyDomainPath("/"));
        assertNull(service.getPrettyDomainPath(null));
    }

    @Test
    public void populateOwnerSetsNormalAccountAndDomainFields() {
        CapturingResponse response = new CapturingResponse();
        when(controlledEntity.getAccountId()).thenReturn(10L);
        when(controlledEntity.getDomainId()).thenReturn(20L);
        when(account.getType()).thenReturn(Account.Type.NORMAL);
        when(account.getAccountName()).thenReturn("account-name");
        mockDomain("domain-uuid", "domain-name", "/customer/");

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findAccountById(10L)).thenReturn(account);
            apiDBUtils.when(() -> ApiDBUtils.findDomainById(20L)).thenReturn(domain);

            service.populateOwner((ControlledEntityResponse) response, controlledEntity);
        }

        assertEquals("account-name", response.accountName);
        assertNull(response.projectId);
        assertNull(response.projectName);
        assertEquals("domain-uuid", response.domainId);
        assertEquals("domain-name", response.domainName);
        assertEquals("ROOT/customer", response.domainPath);
    }

    @Test
    public void populateOwnerSetsProjectFieldsForProjectAccount() {
        CapturingResponse response = new CapturingResponse();
        when(controlledEntity.getAccountId()).thenReturn(10L);
        when(controlledEntity.getDomainId()).thenReturn(20L);
        when(account.getType()).thenReturn(Account.Type.PROJECT);
        when(account.getId()).thenReturn(30L);
        when(project.getUuid()).thenReturn("project-uuid");
        when(project.getName()).thenReturn("project-name");
        mockDomain("domain-uuid", "domain-name", "/customer/project/");

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findAccountById(10L)).thenReturn(account);
            apiDBUtils.when(() -> ApiDBUtils.findProjectByProjectAccountId(30L)).thenReturn(project);
            apiDBUtils.when(() -> ApiDBUtils.findDomainById(20L)).thenReturn(domain);

            service.populateOwner((ControlledEntityResponse) response, controlledEntity);
        }

        assertNull(response.accountName);
        assertEquals("project-uuid", response.projectId);
        assertEquals("project-name", response.projectName);
        assertEquals("domain-uuid", response.domainId);
        assertEquals("domain-name", response.domainName);
        assertEquals("ROOT/customer/project", response.domainPath);
    }

    @Test
    public void populateOwnerCopiesControlledViewEntityFieldsWithoutLookups() {
        CapturingResponse response = new CapturingResponse();
        when(controlledViewEntity.getAccountType()).thenReturn(Account.Type.PROJECT);
        when(controlledViewEntity.getProjectUuid()).thenReturn("project-uuid");
        when(controlledViewEntity.getProjectName()).thenReturn("project-name");
        when(controlledViewEntity.getDomainUuid()).thenReturn("domain-uuid");
        when(controlledViewEntity.getDomainName()).thenReturn("domain-name");
        when(controlledViewEntity.getDomainPath()).thenReturn("/customer/project/");

        service.populateOwner((ControlledViewEntityResponse) response, controlledViewEntity);

        assertNull(response.accountName);
        assertEquals("project-uuid", response.projectId);
        assertEquals("project-name", response.projectName);
        assertEquals("domain-uuid", response.domainId);
        assertEquals("domain-name", response.domainName);
        assertEquals("ROOT/customer/project", response.domainPath);
    }

    @Test
    public void populateAccountSetsProjectAndAccountNameWhenProjectExists() {
        CapturingResponse response = new CapturingResponse();
        when(account.getType()).thenReturn(Account.Type.PROJECT);
        when(account.getId()).thenReturn(30L);
        when(account.getAccountName()).thenReturn("project-account");
        when(project.getUuid()).thenReturn("project-uuid");
        when(project.getName()).thenReturn("project-name");

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findAccountById(10L)).thenReturn(account);
            apiDBUtils.when(() -> ApiDBUtils.findProjectByProjectAccountId(30L)).thenReturn(project);

            service.populateAccount(response, 10L);
        }

        assertEquals("project-account", response.accountName);
        assertEquals("project-uuid", response.projectId);
        assertEquals("project-name", response.projectName);
    }

    @Test
    public void populateDomainLeavesResponseUnchangedWhenDomainIsMissing() {
        CapturingResponse response = new CapturingResponse();

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findDomainById(20L)).thenReturn(null);

            service.populateDomain((ControlledEntityResponse) response, 20L);
        }

        assertNull(response.domainId);
        assertNull(response.domainName);
        assertNull(response.domainPath);
    }

    @Test
    public void populateDomainTagsAddsConvertedTagResponses() {
        DomainResponse response = new DomainResponse();
        ResourceTagResponse tagResponse = new ResourceTagResponse();
        tagResponse.setKey("owner");

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.listResourceTagViewByResourceUUID("domain-uuid", ResourceTag.ResourceObjectType.Domain))
                    .thenReturn(List.of(resourceTag));
            apiDBUtils.when(() -> ApiDBUtils.newResourceTagResponse(resourceTag, true)).thenReturn(tagResponse);

            service.populateDomainTags("domain-uuid", response);
        }

        assertEquals(1, response.getTags().size());
        assertSame(tagResponse, response.getTags().iterator().next());
    }

    @Test
    public void populateDomainTagsLeavesTagsUnsetWhenNoTagsExist() {
        DomainResponse response = new DomainResponse();

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.listResourceTagViewByResourceUUID("domain-uuid", ResourceTag.ResourceObjectType.Domain))
                    .thenReturn(Collections.emptyList());

            service.populateDomainTags("domain-uuid", response);
        }

        assertNull(response.getTags());
    }

    private void mockDomain(String uuid, String name, String path) {
        when(domain.getUuid()).thenReturn(uuid);
        when(domain.getName()).thenReturn(name);
        when(domain.getPath()).thenReturn(path);
    }

    private static class CapturingResponse implements ControlledEntityResponse, ControlledViewEntityResponse {
        String accountName;
        String projectId;
        String projectName;
        String domainId;
        String domainName;
        String domainPath;

        @Override
        public void setAccountName(String accountName) {
            this.accountName = accountName;
        }

        @Override
        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        @Override
        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        @Override
        public void setDomainId(String domainId) {
            this.domainId = domainId;
        }

        @Override
        public void setDomainName(String domainName) {
            this.domainName = domainName;
        }

        @Override
        public void setDomainPath(String domainPath) {
            this.domainPath = domainPath;
        }
    }
}
