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

import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.db.QueryBuilder;
import com.cloud.utils.db.SearchCriteria.Op;

/**
 * Read-only host lookup and listing extracted from {@link ResourceManagerImpl}.
 *
 * @see HostQueryService
 */
@Component
public class HostQueryServiceImpl implements HostQueryService {

    @Override
    public List<HostVO> findDirectlyConnectedHosts() {
        /* The resource column is not null for direct connected resource */
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getResource(), Op.NNULL);
        sc.and(sc.entity().getResourceState(), Op.NIN, ResourceState.Disabled);
        return sc.list();
    }

    @Override
    public List<HostVO> findHostByGuid(final long dcId, final String guid) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        sc.and(sc.entity().getGuid(), Op.EQ, guid);
        return sc.list();
    }

    @Override
    public HostVO findHostByGuid(final String guid) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getGuid(), Op.EQ, guid);
        sc.and(sc.entity().getRemoved(), Op.NULL);
        return sc.find();
    }

    @Override
    public HostVO findHostByGuidPrefix(final String guid) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getGuid(), Op.LIKE, guid + "%");
        sc.and(sc.entity().getRemoved(), Op.NULL);
        return sc.find();
    }

    @Override
    public HostVO findHostByName(final String name) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getName(), Op.EQ, name);
        sc.and(sc.entity().getRemoved(), Op.NULL);
        return sc.find();
    }

    @Override
    public List<HostVO> listAllHostsInCluster(final long clusterId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getClusterId(), Op.EQ, clusterId);
        return sc.list();
    }

    @Override
    public List<HostVO> listHostsInClusterByStatus(final long clusterId, final Status status) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getClusterId(), Op.EQ, clusterId);
        sc.and(sc.entity().getStatus(), Op.EQ, status);
        return sc.list();
    }

    @Override
    public List<HostVO> listAllHostsInOneZoneByType(final Host.Type type, final long dcId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getType(), Op.EQ, type);
        sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        return sc.list();
    }

    @Override
    public List<HostVO> listAllHostsInAllZonesByType(final Host.Type type) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getType(), Op.EQ, type);
        return sc.list();
    }

    @Override
    public HostVO findOneRandomRunningHostByHypervisor(final HypervisorType type, final Long dcId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getHypervisorType(), Op.EQ, type);
        sc.and(sc.entity().getType(), Op.EQ, Host.Type.Routing);
        sc.and(sc.entity().getStatus(), Op.EQ, Status.Up);
        sc.and(sc.entity().getResourceState(), Op.EQ, ResourceState.Enabled);
        if (dcId != null) {
            sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        }
        sc.and(sc.entity().getRemoved(), Op.NULL);
        final List<HostVO> hosts = sc.list();
        if (CollectionUtils.isEmpty(hosts)) {
            return null;
        }
        Collections.shuffle(hosts, new Random(System.currentTimeMillis()));
        return hosts.get(0);
    }
}
