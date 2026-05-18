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
package com.cloud.api.query;

import java.util.List;

import org.apache.cloudstack.api.command.admin.host.ListHostTagsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageTagsCmd;
import org.apache.cloudstack.api.response.HostTagResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageTagResponse;

import com.cloud.host.HostTagVO;
import com.cloud.storage.StoragePoolTagVO;
import com.cloud.utils.Pair;

public interface StorageAndHostTagQueryService {

    ListResponse<StorageTagResponse> searchForStorageTags(ListStorageTagsCmd cmd);

    Pair<List<StoragePoolTagVO>, Integer> searchForStorageTagsInternal();

    ListResponse<HostTagResponse> searchForHostTags(ListHostTagsCmd cmd);

    Pair<List<HostTagVO>, Integer> searchForHostTagsInternal();
}
