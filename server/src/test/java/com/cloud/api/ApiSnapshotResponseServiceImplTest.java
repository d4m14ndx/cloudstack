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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.api.response.SnapshotPolicyResponse;
import org.apache.cloudstack.api.response.SnapshotResponse;
import org.apache.cloudstack.api.response.SnapshotScheduleResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.VMSnapshotResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenterVO;
import com.cloud.domain.DomainVO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.snapshot.SnapshotPolicy;
import com.cloud.storage.snapshot.SnapshotSchedule;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.uservm.UserVm;
import com.cloud.vm.snapshot.VMSnapshot;

@RunWith(MockitoJUnitRunner.class)
public class ApiSnapshotResponseServiceImplTest {

    private static final long CALLER_ACCOUNT_ID = 1L;
    private static final long OWNER_ACCOUNT_ID = 2L;
    private static final long DOMAIN_ID = 3L;

    @Mock
    private AccountManager accountManager;
    @Mock
    private AnnotationDao annotationDao;
    @Mock
    private ResourceTagDao resourceTagDao;
    @Mock
    private SnapshotDataFactory snapshotFactory;
    @Mock
    private SnapshotDataStoreDao snapshotStoreDao;
    @Mock
    private DataStoreManager dataStoreManager;

    private ApiSnapshotResponseServiceImpl service;

    @Before
    public void setUp() {
        service = new ApiSnapshotResponseServiceImpl();
        ReflectionTestUtils.setField(service, "accountManager", accountManager);
        ReflectionTestUtils.setField(service, "annotationDao", annotationDao);
        ReflectionTestUtils.setField(service, "resourceTagDao", resourceTagDao);
        ReflectionTestUtils.setField(service, "snapshotFactory", snapshotFactory);
        ReflectionTestUtils.setField(service, "snapshotStoreDao", snapshotStoreDao);
        ReflectionTestUtils.setField(service, "dataStoreManager", dataStoreManager);

        AccountVO callerAccount = new AccountVO("caller", 1L, "network-domain", Account.Type.ADMIN, "caller-uuid");
        callerAccount.setId(CALLER_ACCOUNT_ID);
        UserVO callerUser = new UserVO(1L, "caller-user", "password", "Caller", "User", "caller@example.com", "UTC", "caller-user-uuid", User.Source.UNKNOWN);
        CallContext.register(callerUser, callerAccount);
        when(accountManager.isRootAdmin(CALLER_ACCOUNT_ID)).thenReturn(false);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    @Test
    public void createSnapshotResponseBuildsSnapshotDetailsTagsAndAnnotations() {
        SnapshotInfo snapshot = Mockito.mock(SnapshotInfo.class);
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        ResourceTag tag = Mockito.mock(ResourceTag.class);
        ResourceTagJoinVO tagView = Mockito.mock(ResourceTagJoinVO.class);
        ResourceTagResponse tagResponse = new ResourceTagResponse();
        Date created = new Date(10_000L);

        when(snapshot.getUuid()).thenReturn("snapshot-uuid");
        when(snapshot.getId()).thenReturn(11L);
        when(snapshot.getVolumeId()).thenReturn(21L);
        when(snapshot.getRecurringType()).thenReturn(Snapshot.Type.HOURLY);
        when(snapshot.getCreated()).thenReturn(created);
        when(snapshot.getName()).thenReturn("snapshot-name");
        when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        when(snapshot.isRevertable()).thenReturn(true);
        when(snapshot.getPhysicalSize()).thenReturn(4096L);
        when(snapshot.getAccountId()).thenReturn(OWNER_ACCOUNT_ID);
        when(snapshot.getDomainId()).thenReturn(DOMAIN_ID);

        when(volume.getUuid()).thenReturn("volume-uuid");
        when(volume.getName()).thenReturn("volume-name");
        when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volume.getState()).thenReturn(Volume.State.Ready);
        when(volume.getSize()).thenReturn(8192L);
        when(volume.getDataCenterId()).thenReturn(31L);
        when(zone.getUuid()).thenReturn("zone-uuid");
        when(zone.getName()).thenReturn("zone-name");
        when(annotationDao.hasAnnotations("snapshot-uuid", AnnotationService.EntityType.SNAPSHOT.name(), false)).thenReturn(true);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            mockOwnerAndDomain();
            when(ApiDBUtils.findVolumeById(21L)).thenReturn(volume);
            when(ApiDBUtils.findZoneById(31L)).thenReturn(zone);
            when(ApiDBUtils.getSnapshotIntervalTypes(11L)).thenReturn("HOURLY");
            when(ApiDBUtils.getSnapshotLocationType(11L)).thenReturn("SECONDARY");
            List<? extends ResourceTag> tags = Collections.singletonList(tag);
            when(ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.Snapshot, 11L)).thenReturn((List)tags);
            when(ApiDBUtils.newResourceTagView(tag)).thenReturn(tagView);
            when(ApiDBUtils.newResourceTagResponse(tagView, true)).thenReturn(tagResponse);

            SnapshotResponse response = service.createSnapshotResponse(snapshot);

            assertEquals("snapshot-uuid", response.getObjectId());
            assertEquals("owner-account", response.getAccountName());
            assertEquals("domain-uuid", response.getDomainId());
            assertEquals("domain-name", ReflectionTestUtils.getField(response, "domainName"));
            assertEquals("ROOT/domain", ReflectionTestUtils.getField(response, "domainPath"));
            assertEquals("HOURLY", ReflectionTestUtils.getField(response, "snapshotType"));
            assertEquals("volume-uuid", ReflectionTestUtils.getField(response, "volumeId"));
            assertEquals("volume-name", ReflectionTestUtils.getField(response, "volumeName"));
            assertEquals(8192L, ReflectionTestUtils.getField(response, "virtualSize"));
            assertEquals("zone-uuid", ReflectionTestUtils.getField(response, "zoneId"));
            assertEquals("zone-name", ReflectionTestUtils.getField(response, "zoneName"));
            assertEquals(true, ReflectionTestUtils.getField(response, "revertable"));
            assertEquals(4096L, ReflectionTestUtils.getField(response, "physicalSize"));
            assertTrue(response.getTags().contains(tagResponse));
            assertTrue(response.hasAnnotation());
            assertEquals("snapshot", response.getObjectName());
        }
    }

    @Test
    public void createVMSnapshotResponseBuildsVmParentOwnerTagsAndAnnotations() {
        VMSnapshot vmSnapshot = Mockito.mock(VMSnapshot.class);
        VMSnapshot parent = Mockito.mock(VMSnapshot.class);
        UserVm vm = Mockito.mock(UserVm.class);
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        ResourceTag tag = Mockito.mock(ResourceTag.class);
        ResourceTagJoinVO tagView = Mockito.mock(ResourceTagJoinVO.class);
        ResourceTagResponse tagResponse = new ResourceTagResponse();
        Date created = new Date(20_000L);

        when(vmSnapshot.getUuid()).thenReturn("vm-snapshot-uuid");
        when(vmSnapshot.getId()).thenReturn(41L);
        when(vmSnapshot.getName()).thenReturn("vm-snapshot-name");
        when(vmSnapshot.getState()).thenReturn(VMSnapshot.State.Ready);
        when(vmSnapshot.getCreated()).thenReturn(created);
        when(vmSnapshot.getDescription()).thenReturn("description");
        when(vmSnapshot.getDisplayName()).thenReturn("display-name");
        when(vmSnapshot.getVmId()).thenReturn(51L);
        when(vmSnapshot.getParent()).thenReturn(61L);
        when(vmSnapshot.getAccountId()).thenReturn(OWNER_ACCOUNT_ID);
        when(vmSnapshot.getDomainId()).thenReturn(DOMAIN_ID);
        when(vmSnapshot.getCurrent()).thenReturn(true);
        when(vmSnapshot.getType()).thenReturn(VMSnapshot.Type.DiskAndMemory);
        when(parent.getUuid()).thenReturn("parent-uuid");
        when(parent.getDisplayName()).thenReturn("parent-name");

        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getDisplayName()).thenReturn("vm-display-name");
        when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(vm.getDataCenterId()).thenReturn(71L);
        when(zone.getUuid()).thenReturn("zone-uuid");
        when(zone.getName()).thenReturn("zone-name");
        List<? extends ResourceTag> tags = Collections.singletonList(tag);
        Mockito.doReturn(tags).when(resourceTagDao).listBy(41L, ResourceObjectType.VMSnapshot);
        when(annotationDao.hasAnnotations("vm-snapshot-uuid", AnnotationService.EntityType.VM_SNAPSHOT.name(), false)).thenReturn(true);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            mockOwnerAndDomain();
            when(ApiDBUtils.findUserVmById(51L)).thenReturn(vm);
            when(ApiDBUtils.findZoneById(71L)).thenReturn(zone);
            when(ApiDBUtils.getVMSnapshotById(61L)).thenReturn(parent);
            when(ApiDBUtils.newResourceTagView(tag)).thenReturn(tagView);
            when(ApiDBUtils.newResourceTagResponse(tagView, false)).thenReturn(tagResponse);

            VMSnapshotResponse response = service.createVMSnapshotResponse(vmSnapshot);

            assertEquals("vm-snapshot-uuid", response.getId());
            assertEquals("vm-snapshot-name", response.getName());
            assertEquals(VMSnapshot.State.Ready, response.getState());
            assertSame(created, response.getCreated());
            assertEquals("description", response.getDescription());
            assertEquals("display-name", response.getDisplayName());
            assertEquals("vm-uuid", response.getVirtualMachineId());
            assertEquals("vm-display-name", response.getVirtualMachineName());
            assertEquals("KVM", response.getHypervisor());
            assertEquals("zone-uuid", response.getZoneId());
            assertEquals("zone-name", response.getZoneName());
            assertEquals("parent-uuid", response.getParent());
            assertEquals("parent-name", response.getParentName());
            assertEquals("owner-account", ReflectionTestUtils.getField(response, "accountName"));
            assertEquals("domain-uuid", ReflectionTestUtils.getField(response, "domainId"));
            assertTrue(response.getTags().contains(tagResponse));
            assertTrue(response.hasAnnotation());
            assertEquals(true, response.getCurrent());
            assertEquals(VMSnapshot.Type.DiskAndMemory.toString(), response.getType());
            assertEquals("vmsnapshot", response.getObjectName());
        }
    }

    @Test
    public void createSnapshotPolicyResponseBuildsVolumeTagsZonesAndPools() {
        SnapshotPolicy policy = Mockito.mock(SnapshotPolicy.class);
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        ResourceTag tag = Mockito.mock(ResourceTag.class);
        ResourceTagJoinVO tagView = Mockito.mock(ResourceTagJoinVO.class);
        ResourceTagResponse tagResponse = new ResourceTagResponse();

        when(policy.getUuid()).thenReturn("policy-uuid");
        when(policy.getId()).thenReturn(81L);
        when(policy.getVolumeId()).thenReturn(91L);
        when(policy.getSchedule()).thenReturn("0 0 * * *");
        when(policy.getInterval()).thenReturn((short) 2);
        when(policy.getMaxSnaps()).thenReturn(7);
        when(policy.getTimezone()).thenReturn("UTC");
        when(policy.isDisplay()).thenReturn(true);
        when(volume.getUuid()).thenReturn("volume-uuid");
        when(volume.getName()).thenReturn("volume-name");
        when(zone.getUuid()).thenReturn("zone-uuid");
        when(zone.getName()).thenReturn("zone-name");
        when(pool.getUuid()).thenReturn("pool-uuid");
        when(pool.getName()).thenReturn("pool-name");
        List<? extends ResourceTag> tags = Collections.singletonList(tag);
        Mockito.doReturn(tags).when(resourceTagDao).listBy(81L, ResourceObjectType.SnapshotPolicy);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findVolumeById(91L)).thenReturn(volume);
            when(ApiDBUtils.newResourceTagView(tag)).thenReturn(tagView);
            when(ApiDBUtils.newResourceTagResponse(tagView, false)).thenReturn(tagResponse);
            when(ApiDBUtils.findSnapshotPolicyZones(policy, volume)).thenReturn(Collections.singletonList(zone));
            when(ApiDBUtils.findSnapshotPolicyPools(policy, volume)).thenReturn(Collections.singletonList(pool));

            SnapshotPolicyResponse response = service.createSnapshotPolicyResponse(policy);

            assertEquals("policy-uuid", response.getId());
            assertEquals("volume-uuid", response.getVolumeId());
            assertEquals("volume-name", ReflectionTestUtils.getField(response, "volumeName"));
            assertEquals("0 0 * * *", response.getSchedule());
            assertEquals(2, response.getIntervalType());
            assertEquals(7, response.getMaxSnaps());
            assertEquals("UTC", response.getTimezone());
            assertTrue(response.isForDisplay());
            assertTrue(response.getTags().contains(tagResponse));
            Set<ZoneResponse> zones = getField(response, "zones");
            Set<StoragePoolResponse> pools = getField(response, "storagePools");
            assertEquals("zone-uuid", zones.iterator().next().getId());
            assertEquals("zone-name", zones.iterator().next().getName());
            assertEquals("pool-uuid", pools.iterator().next().getId());
            assertEquals("pool-name", pools.iterator().next().getName());
            assertEquals("snapshotpolicy", response.getObjectName());
        }
    }

    @Test
    public void createSnapshotScheduleResponseBuildsVolumeAndPolicyReferences() {
        SnapshotSchedule schedule = Mockito.mock(SnapshotSchedule.class);
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        SnapshotPolicy policy = Mockito.mock(SnapshotPolicy.class);
        Date scheduled = new Date(30_000L);

        when(schedule.getUuid()).thenReturn("schedule-uuid");
        when(schedule.getVolumeId()).thenReturn(101L);
        when(schedule.getPolicyId()).thenReturn(102L);
        when(schedule.getScheduledTimestamp()).thenReturn(scheduled);
        when(volume.getUuid()).thenReturn("volume-uuid");
        when(policy.getUuid()).thenReturn("policy-uuid");

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findVolumeById(101L)).thenReturn(volume);
            when(ApiDBUtils.findSnapshotPolicyById(102L)).thenReturn(policy);

            SnapshotScheduleResponse response = service.createSnapshotScheduleResponse(schedule);

            assertEquals("schedule-uuid", response.getId());
            assertEquals("volume-uuid", response.getVolumeId());
            assertEquals("policy-uuid", response.getSnapshotPolicyId());
            assertSame(scheduled, response.getScheduled());
            assertEquals("snapshot", response.getObjectName());
        }
    }

    @Test
    public void getDataStoreRoleReturnsImageWhenSnapshotHasNoPrimaryStore() {
        Snapshot snapshot = Mockito.mock(Snapshot.class);
        when(snapshot.getId()).thenReturn(111L);
        when(snapshotStoreDao.findOneBySnapshotAndDatastoreRole(111L, DataStoreRole.Primary)).thenReturn(null);

        assertEquals(DataStoreRole.Image, ApiSnapshotResponseServiceImpl.getDataStoreRole(snapshot, snapshotStoreDao, dataStoreManager));
    }

    @Test
    public void getDataStoreRoleReturnsImageWhenPrimaryStoreRecordHasNoDataStore() {
        Snapshot snapshot = Mockito.mock(Snapshot.class);
        SnapshotDataStoreVO snapshotStore = Mockito.mock(SnapshotDataStoreVO.class);
        when(snapshot.getId()).thenReturn(112L);
        when(snapshotStoreDao.findOneBySnapshotAndDatastoreRole(112L, DataStoreRole.Primary)).thenReturn(snapshotStore);
        when(snapshotStore.getDataStoreId()).thenReturn(113L);
        when(dataStoreManager.getDataStore(113L, DataStoreRole.Primary)).thenReturn(null);

        assertEquals(DataStoreRole.Image, ApiSnapshotResponseServiceImpl.getDataStoreRole(snapshot, snapshotStoreDao, dataStoreManager));
    }

    @Test
    public void getDataStoreRoleReturnsImageWhenPrimaryDataStoreHasNoCapabilities() {
        Snapshot snapshot = Mockito.mock(Snapshot.class);
        SnapshotDataStoreVO snapshotStore = Mockito.mock(SnapshotDataStoreVO.class);
        DataStore dataStore = Mockito.mock(DataStore.class);
        when(snapshot.getId()).thenReturn(114L);
        when(snapshotStoreDao.findOneBySnapshotAndDatastoreRole(114L, DataStoreRole.Primary)).thenReturn(snapshotStore);
        when(snapshotStore.getDataStoreId()).thenReturn(115L);
        when(dataStoreManager.getDataStore(115L, DataStoreRole.Primary)).thenReturn(dataStore);
        when(dataStore.getDriver()).thenReturn(Mockito.mock(org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver.class));

        assertEquals(DataStoreRole.Image, ApiSnapshotResponseServiceImpl.getDataStoreRole(snapshot, snapshotStoreDao, dataStoreManager));
    }

    private void mockOwnerAndDomain() {
        Account ownerAccount = Mockito.mock(Account.class);
        DomainVO domain = Mockito.mock(DomainVO.class);
        when(ownerAccount.getType()).thenReturn(Account.Type.NORMAL);
        when(ownerAccount.getAccountName()).thenReturn("owner-account");
        when(domain.getUuid()).thenReturn("domain-uuid");
        when(domain.getName()).thenReturn("domain-name");
        when(domain.getPath()).thenReturn("/domain/");
        when(ApiDBUtils.findAccountById(OWNER_ACCOUNT_ID)).thenReturn(ownerAccount);
        when(ApiDBUtils.findDomainById(DOMAIN_ID)).thenReturn(domain);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String fieldName) {
        return (T) ReflectionTestUtils.getField(target, fieldName);
    }
}
