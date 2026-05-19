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

import jakarta.inject.Inject;

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
import org.springframework.stereotype.Component;

import com.cloud.api.query.ViewResponseHelper;
import com.cloud.api.query.vo.ImageStoreJoinVO;
import com.cloud.api.query.vo.StoragePoolJoinVO;
import com.cloud.api.query.vo.VolumeJoinVO;
import com.cloud.dc.StorageNetworkIpRange;
import com.cloud.storage.ImageStore;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;

@Component
public class ApiStorageResponseServiceImpl implements ApiStorageResponseService {

    private static final ApiResponseOwnerService STATIC_OWNER_SERVICE = new ApiResponseOwnerServiceImpl();

    @Inject
    ObjectStoreDao objectStoreDao;
    @Inject
    ApiResponseOwnerService apiResponseOwnerService;

    @Override
    public VolumeResponse createVolumeResponse(ResponseView view, Volume volume) {
        List<VolumeJoinVO> viewVrs = ApiDBUtils.newVolumeView(volume);
        List<VolumeResponse> listVrs = ViewResponseHelper.createVolumeResponse(view, viewVrs.toArray(new VolumeJoinVO[viewVrs.size()]));
        assert listVrs != null && listVrs.size() == 1 : "There should be one volume returned";
        return listVrs.get(0);
    }

    @Override
    public StoragePoolResponse createStoragePoolResponse(StoragePool pool) {
        List<StoragePoolJoinVO> viewPools = ApiDBUtils.newStoragePoolView(pool);
        List<StoragePoolResponse> listPools = ViewResponseHelper.createStoragePoolResponse(false, viewPools.toArray(new StoragePoolJoinVO[viewPools.size()]));
        assert listPools != null && listPools.size() == 1 : "There should be one storage pool returned";
        return listPools.get(0);
    }

    @Override
    public ImageStoreResponse createImageStoreResponse(ImageStore os) {
        List<ImageStoreJoinVO> viewStores = ApiDBUtils.newImageStoreView(os);
        List<ImageStoreResponse> listStores = ViewResponseHelper.createImageStoreResponse(viewStores.toArray(new ImageStoreJoinVO[viewStores.size()]));
        assert listStores != null && listStores.size() == 1 : "There should be one image data store returned";
        return listStores.get(0);
    }

    @Override
    public StoragePoolResponse createStoragePoolForMigrationResponse(StoragePool pool) {
        List<StoragePoolJoinVO> viewPools = ApiDBUtils.newStoragePoolView(pool);
        List<StoragePoolResponse> listPools = ViewResponseHelper.createStoragePoolForMigrationResponse(viewPools.toArray(new StoragePoolJoinVO[viewPools.size()]));
        assert listPools != null && listPools.size() == 1 : "There should be one storage pool returned";
        return listPools.get(0);
    }

    @Override
    public StorageNetworkIpRangeResponse createStorageNetworkIpRangeResponse(StorageNetworkIpRange result) {
        StorageNetworkIpRangeResponse response = new StorageNetworkIpRangeResponse();
        response.setUuid(result.getUuid());
        response.setVlan(result.getVlan());
        response.setEndIp(result.getEndIp());
        response.setStartIp(result.getStartIp());
        response.setPodUuid(result.getPodUuid());
        response.setZoneUuid(result.getZoneUuid());
        response.setNetworkUuid(result.getNetworkUuid());
        response.setNetmask(result.getNetmask());
        response.setGateway(result.getGateway());
        response.setObjectName("storagenetworkiprange");
        return response;
    }

    @Override
    public SecondaryStorageHeuristicsResponse createSecondaryStorageSelectorResponse(Heuristic heuristic) {
        String zoneUuid = ApiDBUtils.findZoneById(heuristic.getZoneId()).getUuid();
        SecondaryStorageHeuristicsResponse secondaryStorageHeuristicsResponse = new SecondaryStorageHeuristicsResponse(heuristic.getUuid(), heuristic.getName(),
                heuristic.getDescription(), zoneUuid, heuristic.getType(), heuristic.getHeuristicRule(), heuristic.getCreated(), heuristic.getRemoved());
        secondaryStorageHeuristicsResponse.setResponseName("secondarystorageheuristics");

        return secondaryStorageHeuristicsResponse;
    }

    @Override
    public ObjectStoreResponse createObjectStoreResponse(ObjectStore os) {
        ObjectStoreResponse objectStoreResponse = new ObjectStoreResponse();
        objectStoreResponse.setId(os.getUuid());
        objectStoreResponse.setName(os.getName());
        objectStoreResponse.setProviderName(os.getProviderName());
        objectStoreResponse.setObjectName("objectstore");
        return objectStoreResponse;
    }

    @Override
    public BucketResponse createBucketResponse(Bucket bucket) {
        BucketResponse bucketResponse = new BucketResponse();
        bucketResponse.setName(bucket.getName());
        bucketResponse.setId(bucket.getUuid());
        bucketResponse.setCreated(bucket.getCreated());
        bucketResponse.setState(bucket.getState());
        bucketResponse.setSize(bucket.getSize());
        if (bucket.getQuota() != null) {
            bucketResponse.setQuota(bucket.getQuota());
        }
        bucketResponse.setVersioning(bucket.isVersioning());
        bucketResponse.setEncryption(bucket.isEncryption());
        bucketResponse.setObjectLock(bucket.isObjectLock());
        bucketResponse.setPolicy(bucket.getPolicy());
        bucketResponse.setBucketURL(bucket.getBucketURL());
        bucketResponse.setAccessKey(bucket.getAccessKey());
        bucketResponse.setSecretKey(bucket.getSecretKey());
        ObjectStoreVO objectStoreVO = objectStoreDao.findById(bucket.getObjectStoreId());
        bucketResponse.setObjectStoragePoolId(objectStoreVO.getUuid());
        bucketResponse.setObjectStoragePool(objectStoreVO.getName());
        bucketResponse.setObjectName("bucket");
        bucketResponse.setProvider(objectStoreVO.getProviderName());
        getApiResponseOwnerService().populateAccount(bucketResponse, bucket.getAccountId());
        return bucketResponse;
    }

    protected ApiResponseOwnerService getApiResponseOwnerService() {
        return apiResponseOwnerService != null ? apiResponseOwnerService : STATIC_OWNER_SERVICE;
    }
}
