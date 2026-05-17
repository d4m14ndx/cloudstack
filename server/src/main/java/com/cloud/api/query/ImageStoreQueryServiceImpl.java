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

import org.apache.cloudstack.api.command.admin.storage.ListImageStoresCmd;
import org.apache.cloudstack.api.command.admin.storage.ListSecondaryStagingStoresCmd;
import org.apache.cloudstack.context.CallContext;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.ImageStoreJoinDao;
import com.cloud.api.query.vo.ImageStoreJoinVO;
import com.cloud.storage.DataStoreRole;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;

/**
 * Implementation of image-store and secondary-staging-store listing
 * helpers extracted from {@link QueryManagerImpl}.
 *
 * @see ImageStoreQueryService
 */
@Component
public class ImageStoreQueryServiceImpl implements ImageStoreQueryService {

    @Inject
    private AccountManager accountMgr;

    @Inject
    private ImageStoreJoinDao imageStoreJoinDao;

    @Override
    public Pair<List<ImageStoreJoinVO>, Integer> searchForImageStoresInternal(ListImageStoresCmd cmd) {

        Long zoneId = accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), cmd.getZoneId());
        Object id = cmd.getId();
        Object name = cmd.getStoreName();
        String provider = cmd.getProvider();
        String protocol = cmd.getProtocol();
        Object keyword = cmd.getKeyword();
        Long startIndex = cmd.getStartIndex();
        Long pageSize = cmd.getPageSizeVal();
        Boolean readonly = cmd.getReadonly();

        Filter searchFilter = new Filter(ImageStoreJoinVO.class, "id", Boolean.TRUE, startIndex, pageSize);

        SearchBuilder<ImageStoreJoinVO> sb = imageStoreJoinDao.createSearchBuilder();
        sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct
        // ids
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getZoneId(), SearchCriteria.Op.EQ);
        sb.and("protocol", sb.entity().getProtocol(), SearchCriteria.Op.EQ);
        sb.and("provider", sb.entity().getProviderName(), SearchCriteria.Op.EQ);
        sb.and("role", sb.entity().getRole(), SearchCriteria.Op.EQ);
        sb.and("readonly", sb.entity().isReadonly(), Op.EQ);

        SearchCriteria<ImageStoreJoinVO> sc = sb.create();
        sc.setParameters("role", DataStoreRole.Image);

        if (keyword != null) {
            SearchCriteria<ImageStoreJoinVO> ssc = imageStoreJoinDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("providerName", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }
        if (provider != null) {
            sc.setParameters("provider", provider);
        }
        if (protocol != null) {
            sc.setParameters("protocol", protocol);
        }
        if (readonly != null) {
            sc.setParameters("readonly", readonly);
        }

        // search Store details by ids
        Pair<List<ImageStoreJoinVO>, Integer> uniqueStorePair = imageStoreJoinDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueStorePair.second();
        if (count == 0) {
            // empty result
            return uniqueStorePair;
        }
        List<ImageStoreJoinVO> uniqueStores = uniqueStorePair.first();
        Long[] vrIds = new Long[uniqueStores.size()];
        int i = 0;
        for (ImageStoreJoinVO v : uniqueStores) {
            vrIds[i++] = v.getId();
        }
        List<ImageStoreJoinVO> vrs = imageStoreJoinDao.searchByIds(vrIds);
        return new Pair<>(vrs, count);
    }

    @Override
    public Pair<List<ImageStoreJoinVO>, Integer> searchForCacheStoresInternal(ListSecondaryStagingStoresCmd cmd) {

        Long zoneId = accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), cmd.getZoneId());
        Object id = cmd.getId();
        Object name = cmd.getStoreName();
        String provider = cmd.getProvider();
        String protocol = cmd.getProtocol();
        Object keyword = cmd.getKeyword();
        Long startIndex = cmd.getStartIndex();
        Long pageSize = cmd.getPageSizeVal();

        Filter searchFilter = new Filter(ImageStoreJoinVO.class, "id", Boolean.TRUE, startIndex, pageSize);

        SearchBuilder<ImageStoreJoinVO> sb = imageStoreJoinDao.createSearchBuilder();
        sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct
        // ids
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getZoneId(), SearchCriteria.Op.EQ);
        sb.and("protocol", sb.entity().getProtocol(), SearchCriteria.Op.EQ);
        sb.and("provider", sb.entity().getProviderName(), SearchCriteria.Op.EQ);
        sb.and("role", sb.entity().getRole(), SearchCriteria.Op.EQ);

        SearchCriteria<ImageStoreJoinVO> sc = sb.create();
        sc.setParameters("role", DataStoreRole.ImageCache);

        if (keyword != null) {
            SearchCriteria<ImageStoreJoinVO> ssc = imageStoreJoinDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("provider", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }
        if (provider != null) {
            sc.setParameters("provider", provider);
        }
        if (protocol != null) {
            sc.setParameters("protocol", protocol);
        }

        // search Store details by ids
        Pair<List<ImageStoreJoinVO>, Integer> uniqueStorePair = imageStoreJoinDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueStorePair.second();
        if (count == 0) {
            // empty result
            return uniqueStorePair;
        }
        List<ImageStoreJoinVO> uniqueStores = uniqueStorePair.first();
        Long[] vrIds = new Long[uniqueStores.size()];
        int i = 0;
        for (ImageStoreJoinVO v : uniqueStores) {
            vrIds[i++] = v.getId();
        }
        List<ImageStoreJoinVO> vrs = imageStoreJoinDao.searchByIds(vrIds);
        return new Pair<>(vrs, count);
    }
}
