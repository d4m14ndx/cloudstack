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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.query.QueryService;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.SnapshotJoinDao;
import com.cloud.api.query.vo.SnapshotJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;

/**
 * Implementation of snapshot-listing helpers extracted from
 * {@link QueryManagerImpl}.
 *
 * @see SnapshotQueryService
 */
@Component
public class SnapshotQueryServiceImpl extends MutualExclusiveIdsManagerBase implements SnapshotQueryService {

    @Inject
    private AccountManager accountMgr;
    @Inject
    private SnapshotJoinDao snapshotJoinDao;
    @Inject
    private SnapshotDataStoreDao snapshotDataStoreDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private ResourceTagDao resourceTagDao;

    @Override
    public Pair<List<SnapshotJoinVO>, Integer> searchForSnapshotsWithParams(
            Long id, List<Long> ids,
            final Long volumeId, final String name, final String keyword, final Map<String, String> tags,
            final String snapshotTypeStr, final String intervalTypeStr,
            final Long zoneId, final String locationTypeStr,
            final boolean isShowUnique,
            final String accountName, Long domainId, final Long projectId,
            final Long storagePoolId, final Long imageStoreId,
            final Long startIndex, final Long pageSize,
            final boolean listAll, boolean isRecursive,
            final Account caller) {

        ids = getIdsListFromCmd(id, ids);

        Snapshot.LocationType locationType = null;
        if (locationTypeStr != null) {
            try {
                locationType = Snapshot.LocationType.valueOf(locationTypeStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new InvalidParameterValueException(String.format("Invalid %s specified, %s",
                        ApiConstants.LOCATION_TYPE, locationTypeStr));
            }
        }

        Filter searchFilter = new Filter(SnapshotJoinVO.class, "snapshotStorePair",
                QueryService.SortKeyAscending.value(), startIndex, pageSize);

        List<Long> permittedAccountIds = new ArrayList<>();
        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject =
                new Ternary<>(domainId, isRecursive, null);
        accountMgr.buildACLSearchParameters(caller, id, accountName, projectId,
                permittedAccountIds, domainIdRecursiveListProject, listAll, false);
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        domainId = domainIdRecursiveListProject.first();
        isRecursive = domainIdRecursiveListProject.second();

        // Verify parameters
        if (volumeId != null) {
            VolumeVO volume = volumeDao.findById(volumeId);
            if (volume != null) {
                accountMgr.checkAccess(caller, null, true, volume);
            }
        }

        SearchBuilder<SnapshotJoinVO> sb = snapshotJoinDao.createSearchBuilder();
        if (isShowUnique) {
            sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct snapshotId
        } else {
            sb.select(null, Func.DISTINCT, sb.entity().getSnapshotStorePair()); // select distinct (snapshotId, store_role, store_id) key
        }
        accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccountIds, listProjectResourcesCriteria);
        sb.and("statusNEQ", sb.entity().getStatus(), SearchCriteria.Op.NEQ); //exclude those Destroyed snapshot, not showing on UI
        sb.and("volumeId", sb.entity().getVolumeId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("idIN", sb.entity().getId(), SearchCriteria.Op.IN);
        sb.and("snapshotTypeEQ", sb.entity().getSnapshotType(), SearchCriteria.Op.IN);
        sb.and("snapshotTypeNEQ", sb.entity().getSnapshotType(), SearchCriteria.Op.NIN);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("locationType", sb.entity().getStoreRole(), SearchCriteria.Op.EQ);
        sb.and("imageStoreId", sb.entity().getStoreId(), SearchCriteria.Op.EQ);

        if (tags != null && !tags.isEmpty()) {
            SearchBuilder<ResourceTagVO> tagSearch = resourceTagDao.createSearchBuilder();
            for (int count = 0; count < tags.size(); count++) {
                tagSearch.or().op("key" + count, tagSearch.entity().getKey(), SearchCriteria.Op.EQ);
                tagSearch.and("value" + count, tagSearch.entity().getValue(), SearchCriteria.Op.EQ);
                tagSearch.cp();
            }
            tagSearch.and("resourceType", tagSearch.entity().getResourceType(), SearchCriteria.Op.EQ);
            sb.groupBy(sb.entity().getId());
            sb.join("tagSearch", tagSearch, sb.entity().getId(), tagSearch.entity().getResourceId(), JoinBuilder.JoinType.INNER);
        }

        if (storagePoolId != null) {
            SearchBuilder<SnapshotDataStoreVO> storagePoolSb = snapshotDataStoreDao.createSearchBuilder();
            storagePoolSb.and("poolId", storagePoolSb.entity().getDataStoreId(), SearchCriteria.Op.EQ);
            storagePoolSb.and("role", storagePoolSb.entity().getRole(), SearchCriteria.Op.EQ);
            sb.join("storagePoolSb", storagePoolSb, sb.entity().getId(), storagePoolSb.entity().getSnapshotId(), JoinBuilder.JoinType.INNER);
        }

        SearchCriteria<SnapshotJoinVO> sc = sb.create();
        accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccountIds, listProjectResourcesCriteria);

        sc.setParameters("statusNEQ", Snapshot.State.Destroyed);

        if (imageStoreId != null) {
            sc.setParameters("imageStoreId", imageStoreId);
            locationType = Snapshot.LocationType.SECONDARY;
        }

        if (storagePoolId != null) {
            sc.setJoinParameters("storagePoolSb", "poolId", storagePoolId);
            sc.setJoinParameters("storagePoolSb", "role", DataStoreRole.Image);
        }

        if (volumeId != null) {
            sc.setParameters("volumeId", volumeId);
        }

        if (tags != null && !tags.isEmpty()) {
            int count = 0;
            sc.setJoinParameters("tagSearch", "resourceType", ResourceObjectType.Snapshot.toString());
            for (String key : tags.keySet()) {
                sc.setJoinParameters("tagSearch", "key" + count, key);
                sc.setJoinParameters("tagSearch", "value" + count, tags.get(key));
                count++;
            }
        }

        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }

        setIdsListToSearchCriteria(sc, ids);

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (locationType != null) {
            sc.setParameters("locationType", Snapshot.LocationType.PRIMARY.equals(locationType)
                    ? locationType.name() : DataStoreRole.Image.name());
        }

        if (keyword != null) {
            SearchCriteria<SnapshotJoinVO> ssc = snapshotJoinDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (snapshotTypeStr != null) {
            Snapshot.Type snapshotType = SnapshotVO.getSnapshotType(snapshotTypeStr);
            if (snapshotType == null) {
                throw new InvalidParameterValueException("Unsupported snapshot type " + snapshotTypeStr);
            }
            if (snapshotType == Snapshot.Type.RECURRING) {
                sc.setParameters("snapshotTypeEQ", Snapshot.Type.HOURLY.ordinal(),
                        Snapshot.Type.DAILY.ordinal(), Snapshot.Type.WEEKLY.ordinal(),
                        Snapshot.Type.MONTHLY.ordinal());
            } else {
                sc.setParameters("snapshotTypeEQ", snapshotType.ordinal());
            }
        } else if (intervalTypeStr != null && volumeId != null) {
            Snapshot.Type type = SnapshotVO.getSnapshotType(intervalTypeStr);
            if (type == null) {
                throw new InvalidParameterValueException("Unsupported snapshot interval type " + intervalTypeStr);
            }
            sc.setParameters("snapshotTypeEQ", type.ordinal());
        } else {
            // Show only MANUAL and RECURRING snapshot types
            sc.setParameters("snapshotTypeNEQ", Snapshot.Type.TEMPLATE.ordinal(), Snapshot.Type.GROUP.ordinal());
        }

        Pair<List<SnapshotJoinVO>, Integer> snapshotDataPair;
        if (isShowUnique) {
            snapshotDataPair = snapshotJoinDao.searchAndDistinctCount(sc, searchFilter,
                    new String[]{"snapshot_view.id"});
        } else {
            snapshotDataPair = snapshotJoinDao.searchAndDistinctCount(sc, searchFilter,
                    new String[]{"snapshot_view.snapshot_store_pair"});
        }

        Integer count = snapshotDataPair.second();
        if (count == 0) {
            return snapshotDataPair;
        }
        List<SnapshotJoinVO> snapshotData = snapshotDataPair.first();
        List<SnapshotJoinVO> snapshots;
        if (isShowUnique) {
            snapshots = snapshotJoinDao.findByDistinctIds(zoneId,
                    snapshotData.stream().map(SnapshotJoinVO::getId).toArray(Long[]::new));
        } else {
            snapshots = snapshotJoinDao.searchBySnapshotStorePair(
                    snapshotData.stream().map(SnapshotJoinVO::getSnapshotStorePair).toArray(String[]::new));
        }
        return new Pair<>(snapshots, count);
    }
}
