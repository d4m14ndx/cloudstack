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

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.host.ListHostTagsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageTagsCmd;
import org.apache.cloudstack.api.response.HostTagResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageTagResponse;
import org.springframework.stereotype.Component;

import com.cloud.host.HostTagVO;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.storage.StoragePoolTagVO;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;

@Component
public class StorageAndHostTagQueryServiceImpl implements StorageAndHostTagQueryService {

    @Inject
    private StoragePoolTagsDao storageTagDao;

    @Inject
    private HostTagsDao hostTagDao;

    @Override
    public ListResponse<StorageTagResponse> searchForStorageTags(ListStorageTagsCmd cmd) {
        Pair<List<StoragePoolTagVO>, Integer> result = searchForStorageTagsInternal();
        ListResponse<StorageTagResponse> response = new ListResponse<>();
        List<StorageTagResponse> tagResponses = ViewResponseHelper.createStorageTagResponse(result.first().toArray(new StoragePoolTagVO[0]));

        response.setResponses(tagResponses, result.second());

        return response;
    }

    @Override
    public Pair<List<StoragePoolTagVO>, Integer> searchForStorageTagsInternal() {
        Filter searchFilter = new Filter(StoragePoolTagVO.class, "id", Boolean.TRUE, null, null);

        SearchBuilder<StoragePoolTagVO> sb = storageTagDao.createSearchBuilder();

        sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct

        SearchCriteria<StoragePoolTagVO> sc = sb.create();

        // search storage tag details by ids
        Pair<List<StoragePoolTagVO>, Integer> uniqueTagPair = storageTagDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueTagPair.second();

        if (count == 0) {
            return uniqueTagPair;
        }

        List<StoragePoolTagVO> uniqueTags = uniqueTagPair.first();
        Long[] vrIds = new Long[uniqueTags.size()];
        int i = 0;

        for (StoragePoolTagVO v : uniqueTags) {
            vrIds[i++] = v.getId();
        }

        List<StoragePoolTagVO> vrs = storageTagDao.searchByIds(vrIds);

        return new Pair<>(vrs, count);
    }

    @Override
    public ListResponse<HostTagResponse> searchForHostTags(ListHostTagsCmd cmd) {
        Pair<List<HostTagVO>, Integer> result = searchForHostTagsInternal();
        ListResponse<HostTagResponse> response = new ListResponse<>();
        List<HostTagResponse> tagResponses = ViewResponseHelper.createHostTagResponse(result.first().toArray(new HostTagVO[0]));

        response.setResponses(tagResponses, result.second());

        return response;
    }

    @Override
    public Pair<List<HostTagVO>, Integer> searchForHostTagsInternal() {
        Filter searchFilter = new Filter(HostTagVO.class, "id", Boolean.TRUE, null, null);

        SearchBuilder<HostTagVO> sb = hostTagDao.createSearchBuilder();

        sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct

        SearchCriteria<HostTagVO> sc = sb.create();

        // search host tag details by ids
        Pair<List<HostTagVO>, Integer> uniqueTagPair = hostTagDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueTagPair.second();

        if (count == 0) {
            return uniqueTagPair;
        }

        List<HostTagVO> uniqueTags = uniqueTagPair.first();
        Long[] vrIds = new Long[uniqueTags.size()];
        int i = 0;

        for (HostTagVO v : uniqueTags) {
            vrIds[i++] = v.getId();
        }

        List<HostTagVO> vrs = hostTagDao.searchByIds(vrIds);

        return new Pair<>(vrs, count);
    }
}
