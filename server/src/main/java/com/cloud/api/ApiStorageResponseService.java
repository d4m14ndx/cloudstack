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

import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.BucketResponse;
import org.apache.cloudstack.api.response.ImageStoreResponse;
import org.apache.cloudstack.api.response.ObjectStoreResponse;
import org.apache.cloudstack.api.response.SecondaryStorageHeuristicsResponse;
import org.apache.cloudstack.api.response.StorageNetworkIpRangeResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.secstorage.heuristics.Heuristic;
import org.apache.cloudstack.storage.object.Bucket;
import org.apache.cloudstack.storage.object.ObjectStore;

import com.cloud.dc.StorageNetworkIpRange;
import com.cloud.storage.ImageStore;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;

public interface ApiStorageResponseService {

    VolumeResponse createVolumeResponse(ResponseView view, Volume volume);

    StoragePoolResponse createStoragePoolResponse(StoragePool pool);

    ImageStoreResponse createImageStoreResponse(ImageStore os);

    StoragePoolResponse createStoragePoolForMigrationResponse(StoragePool pool);

    StorageNetworkIpRangeResponse createStorageNetworkIpRangeResponse(StorageNetworkIpRange result);

    SecondaryStorageHeuristicsResponse createSecondaryStorageSelectorResponse(Heuristic heuristic);

    ObjectStoreResponse createObjectStoreResponse(ObjectStore os);

    BucketResponse createBucketResponse(Bucket bucket);
}
