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
package com.cloud.server;

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.cluster.ListClustersCmd;
import org.apache.cloudstack.api.command.admin.host.ListHostsCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.cpu.CPU;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.Host;
import com.cloud.host.HostTagVO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Cluster;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

/**
 * @see ClusterHostQueryService
 */
@Component
public class ClusterHostQueryServiceImpl implements ClusterHostQueryService {

    @Inject
    protected AccountManager _accountMgr;
    @Inject
    protected ClusterDao _clusterDao;
    @Inject
    protected HostDao _hostDao;
    @Inject
    protected HighAvailabilityManager _haMgr;
    @Inject
    protected HostTagsDao _hostTagsDao;

    @Override
    public List<? extends Cluster> searchForClusters(long zoneId, final Long startIndex, final Long pageSizeVal, final String hypervisorType) {
        final Filter searchFilter = new Filter(ClusterVO.class, "id", true, startIndex, pageSizeVal);
        final SearchCriteria<ClusterVO> sc = _clusterDao.createSearchCriteria();

        zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), zoneId);

        sc.addAnd("dataCenterId", SearchCriteria.Op.EQ, zoneId);
        sc.addAnd("hypervisorType", SearchCriteria.Op.EQ, hypervisorType);

        return _clusterDao.search(sc, searchFilter);
    }

    @Override
    public Pair<List<? extends Cluster>, Integer> searchForClusters(final ListClustersCmd cmd) {
        final Object id = cmd.getId();
        final Object name = cmd.getClusterName();
        final Object podId = cmd.getPodId();
        Long zoneId = cmd.getZoneId();
        final String hypervisorType = cmd.getHypervisorType();
        final Object clusterType = cmd.getClusterType();
        final Object allocationState = cmd.getAllocationState();
        final String keyword = cmd.getKeyword();
        final CPU.CPUArch arch = cmd.getArch();
        final String storageAccessGroup = cmd.getStorageAccessGroup();
        zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), zoneId);

        final Filter searchFilter = new Filter(ClusterVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());

        final SearchBuilder<ClusterVO> sb = _clusterDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("podId", sb.entity().getPodId(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("hypervisorType", sb.entity().getHypervisorType(), SearchCriteria.Op.EQ);
        sb.and("clusterType", sb.entity().getClusterType(), SearchCriteria.Op.EQ);
        sb.and("allocationState", sb.entity().getAllocationState(), SearchCriteria.Op.EQ);
        sb.and("arch", sb.entity().getArch(), SearchCriteria.Op.EQ);
        if (storageAccessGroup != null) {
            sb.and().op("storageAccessGroupExact", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.EQ);
            sb.or("storageAccessGroupPrefix", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.or("storageAccessGroupSuffix", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.or("storageAccessGroupMiddle", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.cp();
        }

        final SearchCriteria<ClusterVO> sc = sb.create();
        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (podId != null) {
            sc.setParameters("podId", podId);
        }

        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }

        if (hypervisorType != null) {
            final String hypervisorSearch = HypervisorType.getType(hypervisorType).toString();
            sc.setParameters("hypervisorType", hypervisorSearch);
        }

        if (clusterType != null) {
            sc.setParameters("clusterType", clusterType);
        }

        if (allocationState != null) {
            sc.setParameters("allocationState", allocationState);
        }

        if (keyword != null) {
            final SearchCriteria<ClusterVO> ssc = _clusterDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("hypervisorType", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (arch != null) {
            sc.setParameters("arch", arch);
        }

        if (storageAccessGroup != null) {
            sc.setParameters("storageAccessGroupExact", storageAccessGroup);
            sc.setParameters("storageAccessGroupPrefix", storageAccessGroup + ",%");
            sc.setParameters("storageAccessGroupSuffix", "%," + storageAccessGroup);
            sc.setParameters("storageAccessGroupMiddle", "%," + storageAccessGroup + ",%");
        }

        final Pair<List<ClusterVO>, Integer> result = _clusterDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public Pair<List<? extends Host>, Integer> searchForServers(final ListHostsCmd cmd) {
        final Long zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), cmd.getZoneId());
        final Object name = cmd.getHostName();
        final Object type = cmd.getType();
        final Object state = cmd.getState();
        final Object pod = cmd.getPodId();
        final Object cluster = cmd.getClusterId();
        final Object id = cmd.getId();
        final Object keyword = cmd.getKeyword();
        final Object resourceState = cmd.getResourceState();
        final Object haHosts = cmd.getHaHost();

        final Pair<List<HostVO>, Integer> result = searchForServers(cmd.getStartIndex(), cmd.getPageSizeVal(), name, type, state, zoneId, pod, cluster,
                id, keyword, resourceState, haHosts, null, null);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public Pair<List<HostVO>, Integer> searchForServers(final Long startIndex, final Long pageSize, final Object name, final Object type,
            final Object state, final Object zone, final Object pod, final Object cluster, final Object id, final Object keyword,
            final Object resourceState, final Object haHosts, final Object hypervisorType, final Object hypervisorVersion, final Object... excludes) {
        final Filter searchFilter = new Filter(HostVO.class, "id", Boolean.TRUE, startIndex, pageSize);

        final SearchBuilder<HostVO> sb = _hostDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("idsNotIn", sb.entity().getId(), SearchCriteria.Op.NOTIN);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("type", sb.entity().getType(), SearchCriteria.Op.LIKE);
        sb.and("status", sb.entity().getStatus(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("podId", sb.entity().getPodId(), SearchCriteria.Op.EQ);
        sb.and("clusterId", sb.entity().getClusterId(), SearchCriteria.Op.EQ);
        sb.and("resourceState", sb.entity().getResourceState(), SearchCriteria.Op.EQ);
        sb.and("hypervisorType", sb.entity().getHypervisorType(), SearchCriteria.Op.EQ);
        sb.and("hypervisorVersion", sb.entity().getHypervisorVersion(), SearchCriteria.Op.GTEQ);

        final String haTag = _haMgr.getHaTag();
        if (haHosts != null && StringUtils.isNotEmpty(haTag)) {
            final SearchBuilder<HostTagVO> hostTagSearch = _hostTagsDao.createSearchBuilder();
            if ((Boolean) haHosts) {
                hostTagSearch.and().op("tag", hostTagSearch.entity().getTag(), SearchCriteria.Op.EQ);
            } else {
                hostTagSearch.and().op("tag", hostTagSearch.entity().getTag(), SearchCriteria.Op.NEQ);
                hostTagSearch.or("tagNull", hostTagSearch.entity().getTag(), SearchCriteria.Op.NULL);
            }

            hostTagSearch.cp();
            sb.join("hostTagSearch", hostTagSearch, sb.entity().getId(), hostTagSearch.entity().getHostId(), JoinBuilder.JoinType.LEFTOUTER);
        }

        final SearchCriteria<HostVO> sc = sb.create();

        if (keyword != null) {
            final SearchCriteria<HostVO> ssc = _hostDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("status", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("type", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }
        if (excludes != null && excludes.length > 0) {
            sc.setParameters("idsNotIn", excludes);
        }
        if (name != null) {
            sc.setParameters("name", name);
        }
        if (type != null) {
            sc.setParameters("type", "%" + type);
        }
        if (state != null) {
            sc.setParameters("status", state);
        }
        if (zone != null) {
            sc.setParameters("dataCenterId", zone);
        }
        if (pod != null) {
            sc.setParameters("podId", pod);
        }
        if (cluster != null) {
            sc.setParameters("clusterId", cluster);
        }
        if (hypervisorType != null) {
            sc.setParameters("hypervisorType", hypervisorType);
        }
        if (hypervisorVersion != null) {
            sc.setParameters("hypervisorVersion", hypervisorVersion);
        }
        if (resourceState != null) {
            sc.setParameters("resourceState", resourceState);
        }
        if (haHosts != null && StringUtils.isNotEmpty(haTag)) {
            sc.setJoinParameters("hostTagSearch", "tag", haTag);
        }

        return _hostDao.searchAndCount(sc, searchFilter);
    }
}
