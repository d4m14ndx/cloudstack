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

import org.apache.cloudstack.api.command.admin.management.ListMgmtsCmd;
import org.apache.cloudstack.api.response.ManagementServerResponse;
import org.apache.cloudstack.api.response.PeerManagementServerNodeResponse;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.ManagementServerJoinDao;
import com.cloud.api.query.vo.ManagementServerJoinVO;
import com.cloud.cluster.ManagementServerHostPeerJoinVO;
import com.cloud.cluster.dao.ManagementServerHostPeerJoinDao;
import com.cloud.host.dao.HostDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

/**
 * Implementation of the management-server listing helpers extracted from
 * {@link QueryManagerImpl}.
 *
 * @see ManagementServerQueryService
 */
@Component
public class ManagementServerQueryServiceImpl implements ManagementServerQueryService {

    @Inject
    private ManagementServerJoinDao managementServerJoinDao;
    @Inject
    private ManagementServerHostPeerJoinDao mshostPeerJoinDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private AsyncJobManager jobManager;

    @Override
    public Pair<List<ManagementServerJoinVO>, Integer> listManagementServersInternal(ListMgmtsCmd cmd) {
        Long id = cmd.getId();
        String name = cmd.getHostName();
        String version = cmd.getVersion();
        String keyword = cmd.getKeyword();

        SearchBuilder<ManagementServerJoinVO> sb = managementServerJoinDao.createSearchBuilder();
        SearchCriteria<ManagementServerJoinVO> sc = sb.create();
        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }
        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.EQ, name);
        }
        if (version != null) {
            sc.addAnd("version", SearchCriteria.Op.EQ, version);
        }
        if (keyword != null) {
            sc.addAnd("version", SearchCriteria.Op.LIKE, "%" + keyword + "%");
        }
        return managementServerJoinDao.searchAndCount(sc, null);
    }

    @Override
    public ManagementServerResponse createManagementServerResponse(ManagementServerJoinVO mgmt, boolean listPeers) {
        ManagementServerResponse mgmtResponse = new ManagementServerResponse();
        mgmtResponse.setId(mgmt.getUuid());
        mgmtResponse.setName(mgmt.getName());
        mgmtResponse.setState(mgmt.getState());
        mgmtResponse.setVersion(mgmt.getVersion());
        mgmtResponse.setJavaVersion(mgmt.getJavaVersion());
        mgmtResponse.setJavaDistribution(mgmt.getJavaName());
        mgmtResponse.setOsDistribution(mgmt.getOsDistribution());
        mgmtResponse.setLastServerStart(mgmt.getLastJvmStart());
        mgmtResponse.setLastServerStop(mgmt.getLastJvmStop());
        mgmtResponse.setLastBoot(mgmt.getLastSystemBoot());
        if (listPeers) {
            List<ManagementServerHostPeerJoinVO> peers = mshostPeerJoinDao.listByOwnerMshostId(mgmt.getId());
            for (ManagementServerHostPeerJoinVO peer : peers) {
                mgmtResponse.addPeer(createPeerManagementServerNodeResponse(peer));
            }
        }
        List<String> lastAgents = hostDao.listByLastMs(mgmt.getMsid());
        mgmtResponse.setLastAgents(lastAgents);
        List<String> agents = hostDao.listByMs(mgmt.getMsid());
        mgmtResponse.setAgents(agents);
        mgmtResponse.setAgentsCount((long) agents.size());
        mgmtResponse.setPendingJobsCount(jobManager.countPendingNonPseudoJobs(mgmt.getMsid()));
        mgmtResponse.setServiceIp(mgmt.getServiceIP());
        mgmtResponse.setIpAddress(mgmt.getServiceIP());
        mgmtResponse.setObjectName("managementserver");
        return mgmtResponse;
    }

    private PeerManagementServerNodeResponse createPeerManagementServerNodeResponse(ManagementServerHostPeerJoinVO peer) {
        PeerManagementServerNodeResponse response = new PeerManagementServerNodeResponse();

        response.setState(peer.getPeerState());
        response.setLastUpdated(peer.getLastUpdateTime());

        response.setPeerId(peer.getPeerMshostUuid());
        response.setPeerName(peer.getPeerMshostName());
        response.setPeerMsId(String.valueOf(peer.getPeerMshostMsId()));
        response.setPeerRunId(String.valueOf(peer.getPeerMshostRunId()));
        response.setPeerState(peer.getPeerMshostState());
        response.setPeerServiceIp(peer.getPeerMshostServiceIp());
        response.setPeerServicePort(String.valueOf(peer.getPeerMshostServicePort()));

        response.setObjectName("peermanagementserver");
        return response;
    }
}
