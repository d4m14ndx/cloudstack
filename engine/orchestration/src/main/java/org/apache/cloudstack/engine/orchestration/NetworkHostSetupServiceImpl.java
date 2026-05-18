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
package org.apache.cloudstack.engine.orchestration;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.CheckNetworkAnswer;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.alert.AlertManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.ConnectionException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetworkSetupInfo;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeVO;
import com.cloud.network.dao.PhysicalNetworkVO;

@Component
public class NetworkHostSetupServiceImpl implements NetworkHostSetupService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected PhysicalNetworkDao physicalNetworkDao;
    @Inject
    protected PhysicalNetworkTrafficTypeDao physicalNetworkTrafficTypeDao;
    @Inject
    protected AgentManager agentManager;
    @Inject
    protected AlertManager alertManager;

    @Override
    public void processConnect(final Host host, final StartupCommand cmd, final boolean forRebalance) throws ConnectionException {
        if (!(cmd instanceof StartupRoutingCommand) || cmd.isConnectionTransferred()) {
            return;
        }
        final StartupRoutingCommand startup = (StartupRoutingCommand) cmd;
        final DataCenterVO dc = resolveDataCenter(startup);
        final long dcId = dc.getId();
        final HypervisorType hypervisorType = startup.getHypervisorType();

        logger.debug("Host's hypervisorType is: {}", hypervisorType);

        final List<PhysicalNetworkSetupInfo> networkInfoList = buildNetworkSetupInfo(dcId, hypervisorType);
        checkNetworkSetup(host, networkInfoList, dcId);
    }

    private DataCenterVO resolveDataCenter(final StartupRoutingCommand startup) {
        final String dataCenter = startup.getDataCenter();
        DataCenterVO dc = dataCenterDao.findByName(dataCenter);
        if (dc == null) {
            try {
                final long dcId = Long.parseLong(dataCenter);
                dc = dataCenterDao.findById(dcId);
            } catch (final NumberFormatException e) {
            }
        }
        if (dc == null) {
            throw new IllegalArgumentException("Host " + startup.getPrivateIpAddress() + " sent incorrect data center: " + dataCenter);
        }
        return dc;
    }

    private List<PhysicalNetworkSetupInfo> buildNetworkSetupInfo(final long dcId, final HypervisorType hypervisorType) {
        final List<PhysicalNetworkSetupInfo> networkInfoList = new ArrayList<>();
        final List<PhysicalNetworkVO> physicalNtwkList = physicalNetworkDao.listByZone(dcId);
        for (final PhysicalNetworkVO pNtwk : physicalNtwkList) {
            final String publicName = physicalNetworkTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Public, hypervisorType);
            final String privateName = physicalNetworkTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Management, hypervisorType);
            final String guestName = physicalNetworkTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Guest, hypervisorType);
            final String storageName = physicalNetworkTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Storage, hypervisorType);
            // String controlName = physicalNetworkTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Control, hypervisorType);
            final PhysicalNetworkSetupInfo info = new PhysicalNetworkSetupInfo();
            info.setPhysicalNetworkId(pNtwk.getId());
            info.setGuestNetworkName(guestName);
            info.setPrivateNetworkName(privateName);
            info.setPublicNetworkName(publicName);
            info.setStorageNetworkName(storageName);
            final PhysicalNetworkTrafficTypeVO mgmtTraffic = physicalNetworkTrafficTypeDao.findBy(pNtwk.getId(), TrafficType.Management);
            if (mgmtTraffic != null) {
                final String vlan = mgmtTraffic.getVlan();
                info.setMgmtVlan(vlan);
            }
            networkInfoList.add(info);
        }
        return networkInfoList;
    }

    private void checkNetworkSetup(final Host host, final List<PhysicalNetworkSetupInfo> networkInfoList, final long dcId) throws ConnectionException {
        logger.debug("Sending CheckNetworkCommand to check the Network is setup correctly on Agent");
        final CheckNetworkCommand nwCmd = new CheckNetworkCommand(networkInfoList);

        final CheckNetworkAnswer answer = (CheckNetworkAnswer) agentManager.easySend(host.getId(), nwCmd);

        if (answer == null) {
            logger.warn("Unable to get an answer to the CheckNetworkCommand from agent: {}", host);
            throw new ConnectionException(true, String.format("Unable to get an answer to the CheckNetworkCommand from agent: %s", host));
        }

        if (!answer.getResult()) {
            logger.warn("Unable to setup agent {} due to {}", host, answer.getDetails());
            final String msg = "Incorrect Network setup on agent, Reinitialize agent after network names are setup, details : " + answer.getDetails();
            alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, dcId, host.getPodId(), msg, msg);
            throw new ConnectionException(true, msg);
        } else {
            if (answer.needReconnect()) {
                throw new ConnectionException(false, "Reinitialize agent after network setup.");
            }
            logger.debug("Network setup is correct on Agent");
            return;
        }
    }
}
