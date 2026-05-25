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
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.ApiConstants.DomainDetails;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.ExtractResponse;
import org.apache.cloudstack.api.response.TemplatePermissionsResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.ViewResponseHelper;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.domain.DomainVO;
import com.cloud.host.HostVO;
import com.cloud.projects.Project;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeVO;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;

@RunWith(MockitoJUnitRunner.class)
public class ApiTemplateIsoResponseServiceImplTest {

    private final ApiTemplateIsoResponseServiceImpl service = new ApiTemplateIsoResponseServiceImpl();

    @Mock
    private SnapshotDataStoreDao snapshotStoreDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private DataCenterVO zone;
    @Mock
    private Account account;
    @Mock
    private VolumeVO volume;
    @Mock
    private Snapshot snapshot;
    @Mock
    private VMTemplateVO template;
    @Mock
    private UserVm userVm;
    @Mock
    private HostVO host;
    @Mock
    private SnapshotDataStoreVO snapshotStore;
    @Mock
    private StoragePoolVO storagePool;
    @Mock
    private TemplateJoinVO templateJoin;
    @Mock
    private TemplateResponse templateResponse;
    @Mock
    private DomainVO domain;
    @Mock
    private Account templateOwner;
    @Mock
    private Account regularAccount;
    @Mock
    private Account projectAccount;
    @Mock
    private Project project;

    @Before
    public void setUp() {
        ReflectionTestUtils.setField(service, "_snapshotStoreDao", snapshotStoreDao);
        ReflectionTestUtils.setField(service, "_storagePoolDao", storagePoolDao);
    }

    @Test
    public void createVolumeExtractResponsePopulatesSharedExtractAndVolumeFields() {
        when(zone.getUuid()).thenReturn("zone-uuid");
        when(zone.getName()).thenReturn("zone-name");
        when(account.getUuid()).thenReturn("account-uuid");
        when(volume.getUuid()).thenReturn("volume-uuid");
        when(volume.getName()).thenReturn("volume-name");

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findZoneById(1L)).thenReturn(zone);
            apiDBUtils.when(() -> ApiDBUtils.findAccountById(2L)).thenReturn(account);
            apiDBUtils.when(() -> ApiDBUtils.findVolumeById(3L)).thenReturn(volume);

            ExtractResponse response = service.createVolumeExtractResponse(3L, 1L, 2L, "HTTP_DOWNLOAD", "https://download.example/volume");

            assertEquals("volume-uuid", response.getId());
            assertEquals("volume-name", response.getName());
            assertEquals("account-uuid", response.getAccountId());
            assertEquals("zone-uuid", response.getZoneId());
            assertEquals("zone-name", response.getZoneName());
            assertEquals("HTTP_DOWNLOAD", response.getMode());
            assertEquals("https://download.example/volume", response.getUrl());
            assertEquals("DOWNLOAD_URL_CREATED", response.getState());
            assertEquals("volume", response.getObjectName());
        }
    }

    @Test
    public void createTemplateResponsesResolvesSnapshotZoneFromPrimaryStoreWhenSnapshotVolumeIsMissing() {
        List<TemplateJoinVO> templateViews = Collections.singletonList(templateJoin);
        List<TemplateResponse> expectedResponses = Collections.singletonList(templateResponse);

        when(snapshot.getId()).thenReturn(7L);
        when(snapshot.getVolumeId()).thenReturn(8L);
        when(snapshotStore.getDataStoreId()).thenReturn(9L);
        when(storagePool.getDataCenterId()).thenReturn(10L);
        when(snapshotStoreDao.findOneBySnapshotAndDatastoreRole(7L, DataStoreRole.Primary)).thenReturn(snapshotStore);
        when(storagePoolDao.findById(9L)).thenReturn(storagePool);

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class);
             MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findSnapshotById(7L)).thenReturn(snapshot);
            apiDBUtils.when(() -> ApiDBUtils.findVolumeById(8L)).thenReturn(null);
            apiDBUtils.when(() -> ApiDBUtils.findTemplateById(5L)).thenReturn(template);
            apiDBUtils.when(() -> ApiDBUtils.newTemplateView(template, 10L, true)).thenReturn(templateViews);
            viewResponseHelper.when(() -> ViewResponseHelper.createTemplateResponse(
                    eq(java.util.EnumSet.of(DomainDetails.all)), eq(ResponseView.Full), any(TemplateJoinVO[].class)))
                    .thenReturn(expectedResponses);

            List<TemplateResponse> responses = service.createTemplateResponses(ResponseView.Full, 5L, 7L, null, true);

            assertSame(expectedResponses, responses);
        }
    }

    @Test
    public void createTemplateResponsesUsesVmHostZone() {
        List<TemplateJoinVO> templateViews = Collections.singletonList(templateJoin);
        List<TemplateResponse> expectedResponses = Collections.singletonList(templateResponse);

        when(userVm.getHostId()).thenReturn(null);
        when(userVm.getLastHostId()).thenReturn(11L);
        when(host.getDataCenterId()).thenReturn(12L);

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class);
             MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findUserVmById(13L)).thenReturn(userVm);
            apiDBUtils.when(() -> ApiDBUtils.findHostById(11L)).thenReturn(host);
            apiDBUtils.when(() -> ApiDBUtils.findTemplateById(5L)).thenReturn(template);
            apiDBUtils.when(() -> ApiDBUtils.newTemplateView(template, 12L, true)).thenReturn(templateViews);
            viewResponseHelper.when(() -> ViewResponseHelper.createTemplateResponse(
                    eq(java.util.EnumSet.of(DomainDetails.all)), eq(ResponseView.Restricted), any(TemplateJoinVO[].class)))
                    .thenReturn(expectedResponses);

            List<TemplateResponse> responses = service.createTemplateResponses(ResponseView.Restricted, 5L, 13L);

            assertSame(expectedResponses, responses);
        }
    }

    @Test
    public void createTemplatePermissionsResponseSeparatesAccountsAndProjectsForFullView() {
        when(template.getAccountId()).thenReturn(20L);
        when(template.getUuid()).thenReturn("template-uuid");
        when(template.isPublicTemplate()).thenReturn(true);
        when(templateOwner.getDomainId()).thenReturn(21L);
        when(domain.getUuid()).thenReturn("domain-uuid");
        when(regularAccount.getType()).thenReturn(Account.Type.NORMAL);
        when(projectAccount.getType()).thenReturn(Account.Type.PROJECT);
        when(projectAccount.getId()).thenReturn(22L);
        when(project.getUuid()).thenReturn("project-uuid");

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findTemplateById(5L)).thenReturn(template);
            apiDBUtils.when(() -> ApiDBUtils.findAccountById(20L)).thenReturn(templateOwner);
            apiDBUtils.when(() -> ApiDBUtils.findDomainById(21L)).thenReturn(domain);
            apiDBUtils.when(() -> ApiDBUtils.findAccountByNameDomain("regular", 21L)).thenReturn(regularAccount);
            apiDBUtils.when(() -> ApiDBUtils.findAccountByNameDomain("project", 21L)).thenReturn(projectAccount);
            apiDBUtils.when(() -> ApiDBUtils.findProjectByProjectAccountId(22L)).thenReturn(project);

            TemplatePermissionsResponse response = service.createTemplatePermissionsResponse(
                    ResponseView.Full, Arrays.asList("regular", "missing", "project"), 5L);

            assertEquals("template-uuid", ReflectionTestUtils.getField(response, "id"));
            assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(response, "publicTemplate"));
            assertEquals("domain-uuid", ReflectionTestUtils.getField(response, "domainId"));
            assertEquals(Collections.singletonList("regular"), ReflectionTestUtils.getField(response, "accountNames"));
            assertEquals(Collections.singletonList("project-uuid"), ReflectionTestUtils.getField(response, "projectIds"));
            assertEquals("templatepermission", response.getObjectName());
        }
    }

    @Test
    public void createImageExtractResponseLeavesObjectNameUnsetLikeExistingResponse() {
        when(account.getUuid()).thenReturn("account-uuid");
        when(template.getUuid()).thenReturn("template-uuid");
        when(template.getName()).thenReturn("template-name");

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findAccountById(2L)).thenReturn(account);
            apiDBUtils.when(() -> ApiDBUtils.findTemplateById(5L)).thenReturn(template);

            ExtractResponse response = service.createImageExtractResponse(5L, null, 2L, "HTTP_DOWNLOAD", "https://download.example/image");

            assertEquals("template-uuid", response.getId());
            assertEquals("template-name", response.getName());
            assertEquals("account-uuid", response.getAccountId());
            assertNull(response.getZoneId());
            assertEquals("HTTP_DOWNLOAD", response.getMode());
            assertEquals("https://download.example/image", response.getUrl());
            assertNull(response.getObjectName());
        }
    }
}
