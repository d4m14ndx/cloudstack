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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.response.CapacityResponse;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.PodResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityVO;
import com.cloud.capacity.dao.CapacityDaoImpl.SummedCapacity;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Vlan;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.org.Cluster;
import com.cloud.org.Grouping;
import com.cloud.org.Managed;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;

@RunWith(MockitoJUnitRunner.class)
public class ApiHostZoneCapacityResponseServiceImplTest {

    private static final long ACCOUNT_ID = 3L;
    private static final long ZONE_ID = 10L;
    private static final long POD_ID = 11L;
    private static final long CLUSTER_ID = 12L;

    @Mock
    private AnnotationDao annotationDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private ClusterDetailsDao clusterDetailsDao;

    private ApiHostZoneCapacityResponseServiceImpl service;

    @Before
    public void setUp() {
        service = new ApiHostZoneCapacityResponseServiceImpl();
        ReflectionTestUtils.setField(service, "annotationDao", annotationDao);
        ReflectionTestUtils.setField(service, "_accountMgr", accountManager);
        ReflectionTestUtils.setField(service, "_clusterDetailsDao", clusterDetailsDao);

        AccountVO account = new AccountVO("test-account", 1L, "network-domain", Account.Type.NORMAL, "account-uuid");
        account.setId(ACCOUNT_ID);
        UserVO user = new UserVO(ACCOUNT_ID, "test-user", "password", "first", "last", "email", "timezone", "user-uuid", User.Source.UNKNOWN);
        CallContext.register(user, account);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    @Test
    public void createPodResponseHydratesIpRangesCapacitiesAndAnnotations() {
        HostPodVO pod = new HostPodVO("pod-a", ZONE_ID, "10.0.0.1", "10.0.0.0", 24, "10.0.0.10-10.0.0.20-1-200,10.0.0.30-10.0.0.40");
        ReflectionTestUtils.setField(pod, "id", POD_ID);
        pod.setUuid("pod-uuid");
        pod.setStorageAccessGroups("pod-storage");
        DataCenterVO zone = newZone();
        SummedCapacity cpuCapacity = new SummedCapacity(50L, 5L, 200L, Capacity.CAPACITY_TYPE_CPU, null, POD_ID, ZONE_ID);
        CapacityVO storageStats = new CapacityVO(null, ZONE_ID, POD_ID, null, 25L, 100L, Capacity.CAPACITY_TYPE_STORAGE);

        when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        when(annotationDao.hasAnnotations("pod-uuid", AnnotationService.EntityType.POD.name(), true)).thenReturn(true);
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findZoneById(ZONE_ID)).thenReturn(zone);
            when(ApiDBUtils.getCapacityByClusterPodZone(null, POD_ID, null)).thenReturn(List.of(cpuCapacity));
            when(ApiDBUtils.getStoragePoolUsedStats(null, null, POD_ID, ZONE_ID)).thenReturn(storageStats);

            PodResponse response = service.createPodResponse(pod, true);

            assertEquals("pod-uuid", response.getId());
            assertEquals("pod-a", response.getName());
            assertEquals("zone-uuid", response.getZoneId());
            assertEquals("zone-a", response.getZoneName());
            assertEquals("255.255.255.0", response.getNetmask());
            assertEquals(List.of("10.0.0.10", "10.0.0.30"), response.getStartIp());
            assertEquals(List.of("10.0.0.20", "10.0.0.40"), response.getEndIp());
            assertEquals(List.of("1", "0"), response.getForSystemVms());
            assertEquals(List.of(BroadcastDomainType.Vlan.toUri("200").toString(), BroadcastDomainType.Vlan.toUri(Vlan.UNTAGGED).toString()), response.getVlanId());
            assertEquals("pod-storage", response.getStorageAccessGroups());
            assertEquals("zone-storage", response.getZoneStorageAccessGroups());
            assertTrue(response.hasAnnotation());

            CapacityResponse cpuResponse = findCapacity(response.getCapacities(), Capacity.CAPACITY_TYPE_CPU);
            assertEquals(Long.valueOf(55L), cpuResponse.getCapacityUsed());
            assertEquals(Long.valueOf(200L), cpuResponse.getCapacityTotal());
            assertEquals("27.5", cpuResponse.getPercentUsed());
            CapacityResponse statsResponse = findCapacity(response.getCapacities(), Capacity.CAPACITY_TYPE_STORAGE);
            assertEquals(Long.valueOf(25L), statsResponse.getCapacityUsed());
            assertEquals(Long.valueOf(100L), statsResponse.getCapacityTotal());
            assertEquals("25", statsResponse.getPercentUsed());
        }
    }

    @Test
    public void getDataCenterCapacityResponseSubtractsNonSharedStorageAndIncludesZoneStats() {
        SummedCapacity storageAllocated = new SummedCapacity(100L, 20L, 500L, Capacity.CAPACITY_TYPE_STORAGE_ALLOCATED, null, null, ZONE_ID);
        SummedCapacity nonSharedStorage = new SummedCapacity(30L, 0L, 50L, Capacity.CAPACITY_TYPE_STORAGE_ALLOCATED, null, null, ZONE_ID);
        CapacityVO primaryStorageStats = new CapacityVO(null, ZONE_ID, null, null, 5L, 20L, Capacity.CAPACITY_TYPE_STORAGE);
        CapacityVO secondaryStorageStats = new CapacityVO(null, ZONE_ID, null, null, 6L, 30L, Capacity.CAPACITY_TYPE_SECONDARY_STORAGE);
        CapacityVO objectStorageStats = new CapacityVO(null, ZONE_ID, null, null, 0L, 0L, Capacity.CAPACITY_TYPE_OBJECT_STORAGE);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.getCapacityByClusterPodZone(ZONE_ID, null, null)).thenReturn(List.of(storageAllocated));
            when(ApiDBUtils.findNonSharedStorageForClusterPodZone(ZONE_ID, null, null)).thenReturn(List.of(nonSharedStorage));
            when(ApiDBUtils.getStoragePoolUsedStats(null, null, null, ZONE_ID)).thenReturn(primaryStorageStats);
            when(ApiDBUtils.getSecondaryStorageUsedStats(null, ZONE_ID)).thenReturn(secondaryStorageStats);
            when(ApiDBUtils.getObjectStorageUsedStats(ZONE_ID)).thenReturn(objectStorageStats);

            List<CapacityResponse> responses = service.getDataCenterCapacityResponse(ZONE_ID);

            assertEquals(4, responses.size());
            CapacityResponse allocated = findCapacity(responses, Capacity.CAPACITY_TYPE_STORAGE_ALLOCATED);
            assertEquals(Long.valueOf(70L), allocated.getCapacityUsed());
            assertEquals(Long.valueOf(450L), allocated.getCapacityTotal());
            assertEquals("15.56", allocated.getPercentUsed());
            CapacityResponse primaryStats = findCapacity(responses, Capacity.CAPACITY_TYPE_STORAGE);
            assertEquals(Long.valueOf(5L), primaryStats.getCapacityUsed());
            assertEquals(Long.valueOf(20L), primaryStats.getCapacityTotal());
            assertEquals("25", primaryStats.getPercentUsed());
            CapacityResponse objectStats = findCapacity(responses, Capacity.CAPACITY_TYPE_OBJECT_STORAGE);
            assertEquals("0", objectStats.getPercentUsed());
        }
    }

    @Test
    public void createClusterResponseHydratesCapacityStorageGroupsDetailsAndAnnotations() {
        ClusterVO cluster = new ClusterVO(ZONE_ID, POD_ID, "cluster-a");
        ReflectionTestUtils.setField(cluster, "id", CLUSTER_ID);
        cluster.setUuid("cluster-uuid");
        cluster.setHypervisorType(Hypervisor.HypervisorType.KVM.toString());
        cluster.setClusterType(Cluster.ClusterType.CloudManaged);
        cluster.setAllocationState(Grouping.AllocationState.Enabled);
        cluster.setManagedState(Managed.ManagedState.Managed);
        cluster.setStorageAccessGroups("cluster-storage");
        HostPodVO pod = new HostPodVO("pod-a", ZONE_ID, "10.0.0.1", "10.0.0.0", 24, "");
        ReflectionTestUtils.setField(pod, "id", POD_ID);
        pod.setUuid("pod-uuid");
        pod.setStorageAccessGroups("pod-storage");
        DataCenterVO zone = newZone();
        SummedCapacity memoryCapacity = new SummedCapacity(40L, 10L, 200L, Capacity.CAPACITY_TYPE_MEMORY, CLUSTER_ID, POD_ID, ZONE_ID);
        CapacityVO storageStats = new CapacityVO(null, ZONE_ID, POD_ID, CLUSTER_ID, 33L, 99L, Capacity.CAPACITY_TYPE_STORAGE);

        when(clusterDetailsDao.findDetails(CLUSTER_ID)).thenReturn(Map.of("safe", "value", "username", "hidden"));
        when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        when(annotationDao.hasAnnotations("cluster-uuid", AnnotationService.EntityType.CLUSTER.name(), true)).thenReturn(true);
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findPodById(POD_ID)).thenReturn(pod);
            when(ApiDBUtils.findZoneById(ZONE_ID)).thenReturn(zone);
            when(ApiDBUtils.findClusterDetails(CLUSTER_ID, "cpuOvercommitRatio")).thenReturn("2.0");
            when(ApiDBUtils.findClusterDetails(CLUSTER_ID, "memoryOvercommitRatio")).thenReturn("1.5");
            when(ApiDBUtils.getCapacityByClusterPodZone(null, null, CLUSTER_ID)).thenReturn(List.of(memoryCapacity));
            when(ApiDBUtils.getStoragePoolUsedStats(null, CLUSTER_ID, POD_ID, ZONE_ID)).thenReturn(storageStats);

            ClusterResponse response = service.createClusterResponse(cluster, true);

            assertEquals(CLUSTER_ID, response.getInternalId());
            assertEquals("cluster-uuid", response.getId());
            assertEquals("cluster-a", response.getName());
            assertEquals("pod-uuid", response.getPodId());
            assertEquals("zone-uuid", response.getZoneId());
            assertEquals("KVM", response.getHypervisorType());
            assertEquals("2.0", response.getCpuOvercommitRatio());
            assertEquals("1.5", response.getMemoryOvercommitRatio());
            assertEquals("cluster-storage", response.getStorageAccessGroups());
            assertEquals("pod-storage", response.getPodStorageAccessGroups());
            assertEquals("zone-storage", response.getZoneStorageAccessGroups());
            assertEquals("value", response.getResourceDetails().get("safe"));
            assertFalse(response.getResourceDetails().containsKey("username"));
            assertTrue(response.hasAnnotation());

            CapacityResponse memoryResponse = findCapacity(response.getCapacities(), Capacity.CAPACITY_TYPE_MEMORY);
            assertEquals(Long.valueOf(50L), memoryResponse.getCapacityUsed());
            assertEquals(Long.valueOf(200L), memoryResponse.getCapacityTotal());
            assertEquals("25", memoryResponse.getPercentUsed());
            assertSame(storageStats.getCapacityType(), findCapacity(response.getCapacities(), Capacity.CAPACITY_TYPE_STORAGE).getCapacityType());
        }
    }

    @Test
    public void createMinimalPodResponsePopulatesIdNameAndObjectName() {
        HostPodVO pod = new HostPodVO("pod-b", ZONE_ID, "10.0.0.1", "10.0.0.0", 24, "");
        pod.setUuid("pod-b-uuid");

        PodResponse response = service.createMinimalPodResponse(pod);

        assertEquals("pod-b-uuid", response.getId());
        assertEquals("pod-b", response.getName());
        assertEquals("pod", response.getObjectName());
    }

    @Test
    public void createMinimalClusterResponsePopulatesIdNameAndObjectName() {
        ClusterVO cluster = new ClusterVO(ZONE_ID, POD_ID, "cluster-b");
        cluster.setUuid("cluster-b-uuid");

        ClusterResponse response = service.createMinimalClusterResponse(cluster);

        assertEquals("cluster-b-uuid", response.getId());
        assertEquals("cluster-b", response.getName());
        assertEquals("cluster", response.getObjectName());
    }

    @Test
    public void createPodResponseWithFalseCapacitiesSkipsCapacityLookup() {
        HostPodVO pod = new HostPodVO("pod-c", ZONE_ID, "10.0.0.1", "10.0.0.0", 24, "");
        ReflectionTestUtils.setField(pod, "id", POD_ID);
        pod.setUuid("pod-c-uuid");
        DataCenterVO zone = newZone();

        when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(false);
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findZoneById(ZONE_ID)).thenReturn(zone);

            PodResponse response = service.createPodResponse(pod, false);

            assertEquals("pod-c-uuid", response.getId());
            assertEquals("pod-c", response.getName());
        }
    }

    @Test
    public void createClusterResponseWithFalseCapacitiesSkipsCapacityLookup() {
        ClusterVO cluster = new ClusterVO(ZONE_ID, POD_ID, "cluster-c");
        ReflectionTestUtils.setField(cluster, "id", CLUSTER_ID);
        cluster.setUuid("cluster-c-uuid");
        cluster.setHypervisorType(Hypervisor.HypervisorType.KVM.toString());
        cluster.setClusterType(Cluster.ClusterType.CloudManaged);
        cluster.setAllocationState(Grouping.AllocationState.Enabled);
        cluster.setManagedState(Managed.ManagedState.Managed);
        HostPodVO pod = new HostPodVO("pod-a", ZONE_ID, "10.0.0.1", "10.0.0.0", 24, "");
        ReflectionTestUtils.setField(pod, "id", POD_ID);
        pod.setUuid("pod-uuid");
        DataCenterVO zone = newZone();

        when(clusterDetailsDao.findDetails(CLUSTER_ID)).thenReturn(Map.of());
        when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(false);
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findPodById(POD_ID)).thenReturn(pod);
            when(ApiDBUtils.findZoneById(ZONE_ID)).thenReturn(zone);
            when(ApiDBUtils.findClusterDetails(CLUSTER_ID, "cpuOvercommitRatio")).thenReturn(null);
            when(ApiDBUtils.findClusterDetails(CLUSTER_ID, "memoryOvercommitRatio")).thenReturn(null);

            ClusterResponse response = service.createClusterResponse(cluster, false);

            assertEquals("cluster-c-uuid", response.getId());
            assertEquals("cluster-c", response.getName());
        }
    }

    @Test
    public void createClusterResponseHidesSensitiveDetailsForNonAdmin() {
        ClusterVO cluster = new ClusterVO(ZONE_ID, POD_ID, "cluster-d");
        ReflectionTestUtils.setField(cluster, "id", CLUSTER_ID);
        cluster.setUuid("cluster-d-uuid");
        cluster.setHypervisorType(Hypervisor.HypervisorType.KVM.toString());
        cluster.setClusterType(Cluster.ClusterType.CloudManaged);
        cluster.setAllocationState(Grouping.AllocationState.Enabled);
        cluster.setManagedState(Managed.ManagedState.Managed);
        HostPodVO pod = new HostPodVO("pod-a", ZONE_ID, "10.0.0.1", "10.0.0.0", 24, "");
        ReflectionTestUtils.setField(pod, "id", POD_ID);
        pod.setUuid("pod-uuid");
        DataCenterVO zone = newZone();

        when(clusterDetailsDao.findDetails(CLUSTER_ID)).thenReturn(Map.of("safe", "value", "password", "secret"));
        when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(false);
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findPodById(POD_ID)).thenReturn(pod);
            when(ApiDBUtils.findZoneById(ZONE_ID)).thenReturn(zone);
            when(ApiDBUtils.findClusterDetails(CLUSTER_ID, "cpuOvercommitRatio")).thenReturn(null);
            when(ApiDBUtils.findClusterDetails(CLUSTER_ID, "memoryOvercommitRatio")).thenReturn(null);

            ClusterResponse response = service.createClusterResponse(cluster, false);

            assertEquals("value", response.getResourceDetails().get("safe"));
            assertFalse(response.getResourceDetails().containsKey("password"));
        }
    }

    @Test
    public void getDataCenterCapacityResponseWithZeroTotalReturnsZeroPercentUsed() {
        SummedCapacity memory = new SummedCapacity(0L, 0L, 0L, Capacity.CAPACITY_TYPE_MEMORY, null, null, ZONE_ID);
        CapacityVO storageStats = new CapacityVO(null, ZONE_ID, null, null, 0L, 0L, Capacity.CAPACITY_TYPE_STORAGE);
        CapacityVO secondaryStats = new CapacityVO(null, ZONE_ID, null, null, 0L, 0L, Capacity.CAPACITY_TYPE_SECONDARY_STORAGE);
        CapacityVO objectStats = new CapacityVO(null, ZONE_ID, null, null, 0L, 0L, Capacity.CAPACITY_TYPE_OBJECT_STORAGE);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.getCapacityByClusterPodZone(ZONE_ID, null, null)).thenReturn(List.of(memory));
            when(ApiDBUtils.findNonSharedStorageForClusterPodZone(ZONE_ID, null, null)).thenReturn(List.of());
            when(ApiDBUtils.getStoragePoolUsedStats(null, null, null, ZONE_ID)).thenReturn(storageStats);
            when(ApiDBUtils.getSecondaryStorageUsedStats(null, ZONE_ID)).thenReturn(secondaryStats);
            when(ApiDBUtils.getObjectStorageUsedStats(ZONE_ID)).thenReturn(objectStats);

            List<CapacityResponse> responses = service.getDataCenterCapacityResponse(ZONE_ID);

            CapacityResponse memResponse = findCapacity(responses, Capacity.CAPACITY_TYPE_MEMORY);
            assertEquals("0", memResponse.getPercentUsed());
        }
    }

    @Test
    public void createPodResponseAnnotationFalseWhenNoAnnotations() {
        HostPodVO pod = new HostPodVO("pod-d", ZONE_ID, "10.0.0.1", "10.0.0.0", 24, "");
        ReflectionTestUtils.setField(pod, "id", POD_ID);
        pod.setUuid("pod-d-uuid");
        DataCenterVO zone = newZone();
        CapacityVO storageStats = new CapacityVO(null, ZONE_ID, POD_ID, null, 0L, 0L, Capacity.CAPACITY_TYPE_STORAGE);

        when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        when(annotationDao.hasAnnotations("pod-d-uuid", AnnotationService.EntityType.POD.name(), true)).thenReturn(false);
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findZoneById(ZONE_ID)).thenReturn(zone);
            when(ApiDBUtils.getCapacityByClusterPodZone(null, POD_ID, null)).thenReturn(List.of());
            when(ApiDBUtils.getStoragePoolUsedStats(null, null, POD_ID, ZONE_ID)).thenReturn(storageStats);

            PodResponse response = service.createPodResponse(pod, true);

            assertFalse(response.hasAnnotation());
        }
    }

    private DataCenterVO newZone() {
        DataCenterVO zone = new DataCenterVO(ZONE_ID, "zone-a", "description", "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1",
                "10.1.0.0/16", "example.com", 1L, DataCenter.NetworkType.Advanced, "zone-token", "example.com");
        zone.setUuid("zone-uuid");
        zone.setStorageAccessGroups("zone-storage");
        return zone;
    }

    private CapacityResponse findCapacity(List<CapacityResponse> capacities, short type) {
        return capacities.stream()
                .filter(capacityResponse -> capacityResponse.getCapacityType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing capacity type " + type));
    }
}
