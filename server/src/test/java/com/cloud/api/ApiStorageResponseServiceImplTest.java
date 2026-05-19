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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;

import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.BucketResponse;
import org.apache.cloudstack.api.response.ImageStoreResponse;
import org.apache.cloudstack.api.response.ObjectStoreResponse;
import org.apache.cloudstack.api.response.SecondaryStorageHeuristicsResponse;
import org.apache.cloudstack.api.response.StorageNetworkIpRangeResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.secstorage.heuristics.Heuristic;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.object.Bucket;
import org.apache.cloudstack.storage.object.ObjectStore;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.vo.ImageStoreJoinVO;
import com.cloud.api.query.vo.StoragePoolJoinVO;
import com.cloud.api.query.vo.VolumeJoinVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.StorageNetworkIpRange;
import com.cloud.storage.ImageStore;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;

@RunWith(MockitoJUnitRunner.class)
public class ApiStorageResponseServiceImplTest {

    @Mock
    private ObjectStoreDao objectStoreDao;
    @Mock
    private ApiResponseOwnerService apiResponseOwnerService;

    private ApiStorageResponseServiceImpl service;

    @Before
    public void setUp() {
        service = new ApiStorageResponseServiceImpl();
        ReflectionTestUtils.setField(service, "objectStoreDao", objectStoreDao);
        ReflectionTestUtils.setField(service, "apiResponseOwnerService", apiResponseOwnerService);
    }

    @Test
    public void createVolumeResponseUsesVolumeViewResponse() {
        Volume volume = Mockito.mock(Volume.class);
        VolumeJoinVO volumeView = Mockito.mock(VolumeJoinVO.class);
        VolumeResponse expectedResponse = new VolumeResponse();
        when(volumeView.getId()).thenReturn(1L);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.newVolumeView(volume)).thenReturn(Collections.singletonList(volumeView));
            when(ApiDBUtils.newVolumeResponse(ResponseView.Full, volumeView)).thenReturn(expectedResponse);

            VolumeResponse response = service.createVolumeResponse(ResponseView.Full, volume);

            assertSame(expectedResponse, response);
        }
    }

    @Test
    public void createStoragePoolResponseUsesStoragePoolViewResponse() {
        StoragePool pool = Mockito.mock(StoragePool.class);
        StoragePoolJoinVO poolView = Mockito.mock(StoragePoolJoinVO.class);
        StoragePoolResponse expectedResponse = new StoragePoolResponse();
        when(poolView.getId()).thenReturn(2L);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.newStoragePoolView(pool)).thenReturn(Collections.singletonList(poolView));
            when(ApiDBUtils.newStoragePoolResponse(poolView, false)).thenReturn(expectedResponse);

            StoragePoolResponse response = service.createStoragePoolResponse(pool);

            assertSame(expectedResponse, response);
        }
    }

    @Test
    public void createImageStoreResponseUsesImageStoreViewResponse() {
        ImageStore imageStore = Mockito.mock(ImageStore.class);
        ImageStoreJoinVO imageStoreView = Mockito.mock(ImageStoreJoinVO.class);
        ImageStoreResponse expectedResponse = new ImageStoreResponse();
        when(imageStoreView.getId()).thenReturn(3L);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.newImageStoreView(imageStore)).thenReturn(Collections.singletonList(imageStoreView));
            when(ApiDBUtils.newImageStoreResponse(imageStoreView)).thenReturn(expectedResponse);

            ImageStoreResponse response = service.createImageStoreResponse(imageStore);

            assertSame(expectedResponse, response);
        }
    }

    @Test
    public void createStoragePoolForMigrationResponseUsesMigrationViewResponse() {
        StoragePool pool = Mockito.mock(StoragePool.class);
        StoragePoolJoinVO poolView = Mockito.mock(StoragePoolJoinVO.class);
        StoragePoolResponse expectedResponse = new StoragePoolResponse();
        when(poolView.getId()).thenReturn(4L);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.newStoragePoolView(pool)).thenReturn(Collections.singletonList(poolView));
            when(ApiDBUtils.newStoragePoolForMigrationResponse(poolView)).thenReturn(expectedResponse);

            StoragePoolResponse response = service.createStoragePoolForMigrationResponse(pool);

            assertSame(expectedResponse, response);
        }
    }

    @Test
    public void createStorageNetworkIpRangeResponseCopiesRangeFields() {
        StorageNetworkIpRange range = Mockito.mock(StorageNetworkIpRange.class);
        when(range.getUuid()).thenReturn("range-uuid");
        when(range.getVlan()).thenReturn(200);
        when(range.getEndIp()).thenReturn("192.0.2.20");
        when(range.getStartIp()).thenReturn("192.0.2.10");
        when(range.getPodUuid()).thenReturn("pod-uuid");
        when(range.getZoneUuid()).thenReturn("zone-uuid");
        when(range.getNetworkUuid()).thenReturn("network-uuid");
        when(range.getNetmask()).thenReturn("255.255.255.0");
        when(range.getGateway()).thenReturn("192.0.2.1");

        StorageNetworkIpRangeResponse response = service.createStorageNetworkIpRangeResponse(range);

        assertEquals("range-uuid", ReflectionTestUtils.getField(response, "uuid"));
        assertEquals(200, ReflectionTestUtils.getField(response, "vlan"));
        assertEquals("192.0.2.20", ReflectionTestUtils.getField(response, "endIp"));
        assertEquals("192.0.2.10", ReflectionTestUtils.getField(response, "startIp"));
        assertEquals("pod-uuid", ReflectionTestUtils.getField(response, "podUuid"));
        assertEquals("zone-uuid", ReflectionTestUtils.getField(response, "zoneUuid"));
        assertEquals("network-uuid", ReflectionTestUtils.getField(response, "networkUuid"));
        assertEquals("255.255.255.0", ReflectionTestUtils.getField(response, "netmask"));
        assertEquals("192.0.2.1", ReflectionTestUtils.getField(response, "gateway"));
        assertEquals("storagenetworkiprange", response.getObjectName());
    }

    @Test
    public void createSecondaryStorageSelectorResponsePopulatesHeuristicAndZone() {
        Heuristic heuristic = Mockito.mock(Heuristic.class);
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Date created = new Date(1_000L);
        Date removed = new Date(2_000L);
        when(heuristic.getZoneId()).thenReturn(10L);
        when(heuristic.getUuid()).thenReturn("heuristic-uuid");
        when(heuristic.getName()).thenReturn("selector");
        when(heuristic.getDescription()).thenReturn("description");
        when(heuristic.getType()).thenReturn("volume");
        when(heuristic.getHeuristicRule()).thenReturn("rule");
        when(heuristic.getCreated()).thenReturn(created);
        when(heuristic.getRemoved()).thenReturn(removed);
        when(zone.getUuid()).thenReturn("zone-uuid");

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findZoneById(10L)).thenReturn(zone);

            SecondaryStorageHeuristicsResponse response = service.createSecondaryStorageSelectorResponse(heuristic);

            assertEquals("heuristic-uuid", response.getId());
            assertEquals("selector", response.getName());
            assertEquals("description", response.getDescription());
            assertEquals("zone-uuid", response.getZoneId());
            assertEquals("volume", response.getType());
            assertEquals("rule", response.getHeuristicRule());
            assertSame(created, response.getCreated());
            assertSame(removed, response.getRemoved());
            assertEquals("secondarystorageheuristics", response.getResponseName());
        }
    }

    @Test
    public void createObjectStoreResponseCopiesObjectStoreFields() {
        ObjectStore objectStore = Mockito.mock(ObjectStore.class);
        when(objectStore.getUuid()).thenReturn("object-store-uuid");
        when(objectStore.getName()).thenReturn("object-store");
        when(objectStore.getProviderName()).thenReturn("provider");

        ObjectStoreResponse response = service.createObjectStoreResponse(objectStore);

        assertEquals("object-store-uuid", response.getId());
        assertEquals("object-store", response.getName());
        assertEquals("provider", response.getProviderName());
        assertEquals("objectstore", response.getObjectName());
    }

    @Test
    public void createBucketResponseCopiesBucketStoreAndOwnerFields() {
        Bucket bucket = Mockito.mock(Bucket.class);
        ObjectStoreVO objectStore = Mockito.mock(ObjectStoreVO.class);
        Date created = new Date(3_000L);
        when(bucket.getName()).thenReturn("bucket");
        when(bucket.getUuid()).thenReturn("bucket-uuid");
        when(bucket.getCreated()).thenReturn(created);
        when(bucket.getState()).thenReturn(Bucket.State.Created);
        when(bucket.getSize()).thenReturn(4096L);
        when(bucket.getQuota()).thenReturn(10);
        when(bucket.isVersioning()).thenReturn(true);
        when(bucket.isEncryption()).thenReturn(true);
        when(bucket.isObjectLock()).thenReturn(false);
        when(bucket.getPolicy()).thenReturn("policy");
        when(bucket.getBucketURL()).thenReturn("https://bucket.example");
        when(bucket.getAccessKey()).thenReturn("access-key");
        when(bucket.getSecretKey()).thenReturn("secret-key");
        when(bucket.getObjectStoreId()).thenReturn(50L);
        when(bucket.getAccountId()).thenReturn(60L);
        when(objectStoreDao.findById(50L)).thenReturn(objectStore);
        when(objectStore.getUuid()).thenReturn("object-store-uuid");
        when(objectStore.getName()).thenReturn("object-store");
        when(objectStore.getProviderName()).thenReturn("provider");

        BucketResponse response = service.createBucketResponse(bucket);

        assertEquals("bucket", response.getName());
        assertEquals("bucket-uuid", response.getId());
        assertSame(created, response.getCreated());
        assertEquals("Created", response.getState());
        assertEquals(4096L, response.getSize());
        assertEquals(10L, response.getQuota());
        assertEquals(true, response.isVersioning());
        assertEquals(true, response.isEncryption());
        assertEquals(false, response.isObjectLock());
        assertEquals("policy", response.getPolicy());
        assertEquals("https://bucket.example", response.getBucketURL());
        assertEquals("access-key", response.getAccessKey());
        assertEquals("secret-key", response.getSecretKey());
        assertEquals("object-store-uuid", response.getObjectStoragePoolId());
        assertEquals("object-store", response.getObjectStoragePool());
        assertEquals("provider", response.getProvider());
        assertEquals("bucket", response.getObjectName());
        verify(apiResponseOwnerService).populateAccount(response, 60L);
    }
}
