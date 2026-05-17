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
package com.cloud.vm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVmDiskStatsAnswer;
import com.cloud.agent.api.GetVmDiskStatsCommand;
import com.cloud.agent.api.GetVmNetworkStatsAnswer;
import com.cloud.agent.api.GetVmNetworkStatsCommand;
import com.cloud.agent.api.GetVmStatsAnswer;
import com.cloud.agent.api.GetVmStatsCommand;
import com.cloud.agent.api.VmDiskStatsEntry;
import com.cloud.agent.api.VmNetworkStatsEntry;
import com.cloud.agent.api.VmStatsEntry;
import com.cloud.host.Host;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Per-host VM statistics collector — extracted from
 * {@link VirtualMachineManagerImpl}.
 *
 * @see VmStatsCollector
 */
@Component
public class VmStatsCollectorImpl implements VmStatsCollector {

    private static final Logger logger = LogManager.getLogger(VmStatsCollectorImpl.class);

    @Inject
    private AgentManager agentMgr;
    @Inject
    private VMInstanceDao vmInstanceDao;

    @Override
    public HashMap<Long, ? extends VmStats> getVirtualMachineStatistics(Host host, List<Long> vmIds) {
        HashMap<Long, VmStatsEntry> vmStatsById = new HashMap<>();
        if (CollectionUtils.isEmpty(vmIds)) {
            return vmStatsById;
        }
        Map<String, Long> vmMap = vmInstanceDao.getNameIdMapForVmIds(vmIds);
        return getVirtualMachineStatistics(host, vmMap);
    }

    @Override
    public HashMap<Long, ? extends VmStats> getVirtualMachineStatistics(Host host, Map<String, Long> vmInstanceNameIdMap) {
        HashMap<Long, VmStatsEntry> vmStatsById = new HashMap<>();
        if (MapUtils.isEmpty(vmInstanceNameIdMap)) {
            return vmStatsById;
        }
        Answer answer = agentMgr.easySend(host.getId(), new GetVmStatsCommand(
                new ArrayList<>(vmInstanceNameIdMap.keySet()), host.getGuid(), host.getName()));
        if (answer == null || !answer.getResult()) {
            logger.warn("Unable to obtain VM statistics.");
            return vmStatsById;
        }
        HashMap<String, VmStatsEntry> vmStatsByName = ((GetVmStatsAnswer) answer).getVmStatsMap();
        if (vmStatsByName == null) {
            logger.warn("Unable to obtain VM statistics.");
            return vmStatsById;
        }
        for (Map.Entry<String, VmStatsEntry> entry : vmStatsByName.entrySet()) {
            vmStatsById.put(vmInstanceNameIdMap.get(entry.getKey()), entry.getValue());
        }
        return vmStatsById;
    }

    @Override
    public HashMap<Long, List<? extends VmDiskStats>> getVmDiskStatistics(Host host, Map<String, Long> vmInstanceNameIdMap) {
        HashMap<Long, List<? extends VmDiskStats>> vmDiskStatsById = new HashMap<>();
        if (MapUtils.isEmpty(vmInstanceNameIdMap)) {
            return vmDiskStatsById;
        }
        Answer answer = agentMgr.easySend(host.getId(), new GetVmDiskStatsCommand(
                new ArrayList<>(vmInstanceNameIdMap.keySet()), host.getGuid(), host.getName()));
        if (answer == null || !answer.getResult()) {
            logger.warn("Unable to obtain VM disk statistics.");
            return vmDiskStatsById;
        }
        HashMap<String, List<VmDiskStatsEntry>> vmDiskStatsByName = ((GetVmDiskStatsAnswer) answer).getVmDiskStatsMap();
        if (vmDiskStatsByName == null) {
            logger.warn("Unable to obtain VM disk statistics.");
            return vmDiskStatsById;
        }
        for (Map.Entry<String, List<VmDiskStatsEntry>> entry : vmDiskStatsByName.entrySet()) {
            vmDiskStatsById.put(vmInstanceNameIdMap.get(entry.getKey()), entry.getValue());
        }
        return vmDiskStatsById;
    }

    @Override
    public HashMap<Long, List<? extends VmNetworkStats>> getVmNetworkStatistics(Host host, Map<String, Long> vmInstanceNameIdMap) {
        HashMap<Long, List<? extends VmNetworkStats>> vmNetworkStatsById = new HashMap<>();
        if (MapUtils.isEmpty(vmInstanceNameIdMap)) {
            return vmNetworkStatsById;
        }
        Answer answer = agentMgr.easySend(host.getId(), new GetVmNetworkStatsCommand(
                new ArrayList<>(vmInstanceNameIdMap.keySet()), host.getGuid(), host.getName()));
        if (answer == null || !answer.getResult()) {
            logger.warn("Unable to obtain VM network statistics.");
            return vmNetworkStatsById;
        }
        HashMap<String, List<VmNetworkStatsEntry>> vmNetworkStatsByName = ((GetVmNetworkStatsAnswer) answer).getVmNetworkStatsMap();
        if (vmNetworkStatsByName == null) {
            logger.warn("Unable to obtain VM network statistics.");
            return vmNetworkStatsById;
        }
        for (Map.Entry<String, List<VmNetworkStatsEntry>> entry : vmNetworkStatsByName.entrySet()) {
            vmNetworkStatsById.put(vmInstanceNameIdMap.get(entry.getKey()), entry.getValue());
        }
        return vmNetworkStatsById;
    }
}
