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
package com.cloud.resource;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetHostStatsAnswer;
import com.cloud.agent.api.GetHostStatsCommand;
import com.cloud.agent.api.UnsupportedAnswer;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.Host.Type;
import com.cloud.host.HostStats;
import com.cloud.host.HostTagVO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.StringUtils;
import com.cloud.utils.db.QueryBuilder;
import com.cloud.utils.db.SearchCriteria.Op;

/**
 * Filtered host listing and per-host metadata reads extracted from
 * {@link ResourceManagerImpl}.
 *
 * @see HostLookupService
 */
@Component
public class HostLookupServiceImpl implements HostLookupService {
    private static final Logger LOG = LogManager.getLogger(HostLookupServiceImpl.class);

    @Inject
    protected HostDao hostDao;
    @Inject
    protected HostDetailsDao hostDetailsDao;
    @Inject
    protected HostTagsDao hostTagsDao;
    @Inject
    protected AgentManager agentManager;
    @Inject
    protected HighAvailabilityManager haManager;

    @Override
    public List<HostVO> listAllUpAndEnabledHosts(final Type type, final Long clusterId, final Long podId, final long dcId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        if (type != null) {
            sc.and(sc.entity().getType(), Op.EQ, type);
        }
        if (clusterId != null) {
            sc.and(sc.entity().getClusterId(), Op.EQ, clusterId);
        }
        if (podId != null) {
            sc.and(sc.entity().getPodId(), Op.EQ, podId);
        }
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        sc.and(sc.entity().getStatus(), Op.EQ, com.cloud.host.Status.Up);
        sc.and(sc.entity().getResourceState(), Op.EQ, ResourceState.Enabled);
        return sc.list();
    }

    @Override
    public List<HostVO> listAllHosts(final Type type, final Long clusterId, final Long podId, final long dcId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        if (type != null) {
            sc.and(sc.entity().getType(), Op.EQ, type);
        }
        if (clusterId != null) {
            sc.and(sc.entity().getClusterId(), Op.EQ, clusterId);
        }
        if (podId != null) {
            sc.and(sc.entity().getPodId(), Op.EQ, podId);
        }
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        return sc.list();
    }

    @Override
    public List<HostVO> listAllUpHosts(Type type, Long clusterId, Long podId, long dcId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        if (type != null) {
            sc.and(sc.entity().getType(), Op.EQ, type);
        }
        if (clusterId != null) {
            sc.and(sc.entity().getClusterId(), Op.EQ, clusterId);
        }
        if (podId != null) {
            sc.and(sc.entity().getPodId(), Op.EQ, podId);
        }
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        sc.and(sc.entity().getStatus(), Op.EQ, com.cloud.host.Status.Up);
        return sc.list();
    }

    @Override
    public List<HostVO> listAllUpAndEnabledNonHAHosts(final Type type, final Long clusterId, final Long podId, final long dcId) {
        final String haTag = haManager.getHaTag();
        return hostDao.listAllUpAndEnabledNonHAHosts(type, clusterId, podId, dcId, haTag);
    }

    @Override
    public List<HostVO> listAllUpAndEnabledHostsInOneZoneByType(final Type type, final long dcId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getType(), Op.EQ, type);
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        sc.and(sc.entity().getStatus(), Op.EQ, com.cloud.host.Status.Up);
        sc.and(sc.entity().getResourceState(), Op.EQ, ResourceState.Enabled);
        return sc.list();
    }

    @Override
    public List<HostVO> listAllNotInMaintenanceHostsInOneZone(final Type type, final Long dcId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        if (dcId != null) {
            sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        }
        sc.and(sc.entity().getType(), Op.EQ, type);
        sc.and(sc.entity().getResourceState(), Op.NIN,
                ResourceState.Maintenance,
                ResourceState.ErrorInMaintenance,
                ResourceState.ErrorInPrepareForMaintenance,
                ResourceState.PrepareForMaintenance,
                ResourceState.Error);
        return sc.list();
    }

    @Override
    public List<HostVO> listAllUpAndEnabledHostsInOneZoneByHypervisor(final HypervisorType type, final long dcId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getHypervisorType(), Op.EQ, type);
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        sc.and(sc.entity().getStatus(), Op.EQ, com.cloud.host.Status.Up);
        sc.and(sc.entity().getResourceState(), Op.EQ, ResourceState.Enabled);
        return sc.list();
    }

    @Override
    public List<HostVO> listAllUpHostsInOneZoneByHypervisor(final HypervisorType type, final long dcId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getHypervisorType(), Op.EQ, type);
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        sc.and(sc.entity().getStatus(), Op.EQ, com.cloud.host.Status.Up);
        return sc.list();
    }

    @Override
    public List<HostVO> listAllUpAndEnabledHostsInOneZone(final long dcId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        sc.and(sc.entity().getStatus(), Op.EQ, com.cloud.host.Status.Up);
        sc.and(sc.entity().getResourceState(), Op.EQ, ResourceState.Enabled);
        return sc.list();
    }

    @Override
    public List<HostVO> listAllHostsInOneZoneNotInClusterByHypervisor(final HypervisorType type, final long dcId, final long clusterId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getHypervisorType(), Op.EQ, type);
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        sc.and(sc.entity().getClusterId(), Op.NEQ, clusterId);
        sc.and(sc.entity().getStatus(), Op.EQ, com.cloud.host.Status.Up);
        return sc.list();
    }

    @Override
    public List<HostVO> listAllHostsInOneZoneNotInClusterByHypervisors(List<HypervisorType> types, final long dcId, final long clusterId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getHypervisorType(), Op.IN, types);
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        sc.and(sc.entity().getClusterId(), Op.NEQ, clusterId);
        sc.and(sc.entity().getStatus(), Op.EQ, com.cloud.host.Status.Up);
        return sc.list();
    }

    @Override
    public HostStats getHostStatistics(final Host host) {
        final Answer answer = agentManager.easySend(host.getId(), new GetHostStatsCommand(host.getGuid(), host.getName(), host.getId()));

        if (answer instanceof UnsupportedAnswer) {
            return null;
        }

        if (answer == null || !answer.getResult()) {
            LOG.warn("Unable to obtain {} statistics.", host);
            return null;
        } else {

            // now construct the result object
            if (answer instanceof GetHostStatsAnswer) {
                return ((GetHostStatsAnswer)answer).getHostStats();
            }
        }
        return null;
    }

    @Override
    public Long getGuestOSCategoryId(final long hostId) {
        final HostVO host = hostDao.findById(hostId);
        if (host == null) {
            return null;
        } else {
            hostDao.loadDetails(host);
            final DetailVO detail = hostDetailsDao.findDetail(hostId, "guest.os.category.id");
            if (detail == null) {
                return null;
            } else {
                return Long.parseLong(detail.getValue());
            }
        }
    }

    @Override
    public String getHostTags(final long hostId) {
        final List<String> hostTags = hostTagsDao.getHostTags(hostId).parallelStream().map(HostTagVO::getTag).collect(Collectors.toList());
        return StringUtils.listToCsvTags(hostTags);
    }
}
