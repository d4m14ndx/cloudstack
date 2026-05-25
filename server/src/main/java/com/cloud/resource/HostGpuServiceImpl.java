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

import static org.apache.cloudstack.gpu.GpuService.GpuDetachOnStop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import org.apache.cloudstack.gpu.GpuService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetGPUStatsAnswer;
import com.cloud.agent.api.GetGPUStatsCommand;
import com.cloud.agent.api.UnsupportedAnswer;
import com.cloud.agent.api.VgpuTypesInfo;
import com.cloud.agent.api.to.GPUDeviceTO;
import com.cloud.gpu.GPU;
import com.cloud.gpu.GpuCardVO;
import com.cloud.gpu.HostGpuGroupsVO;
import com.cloud.gpu.VGPUTypesVO;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.gpu.dao.GpuCardDao;
import com.cloud.gpu.dao.HostGpuGroupsDao;
import com.cloud.gpu.dao.VGPUTypesDao;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;

/**
 * Host-level GPU operations extracted from {@link ResourceManagerImpl}.
 *
 * @see HostGpuService
 */
@Component
public class HostGpuServiceImpl implements HostGpuService {
    private static final Logger LOG = LogManager.getLogger(HostGpuServiceImpl.class);

    @Inject
    protected HostGpuGroupsDao hostGpuGroupsDao;
    @Inject
    protected VGPUTypesDao vgpuTypesDao;
    @Inject
    protected VgpuProfileDao vgpuProfileDao;
    @Inject
    private GpuCardDao gpuCardDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private AgentManager agentManager;
    @Inject
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Inject
    private GpuService gpuService;

    private SearchBuilder<HostGpuGroupsVO> gpuAvailability;

    @PostConstruct
    void init() {
        gpuAvailability = hostGpuGroupsDao.createSearchBuilder();
        gpuAvailability.and("hostId", gpuAvailability.entity().getHostId(), Op.EQ);
        gpuAvailability.and("groupName", gpuAvailability.entity().getGroupName(), Op.EQ);
        final SearchBuilder<VGPUTypesVO> join1 = vgpuTypesDao.createSearchBuilder();
        join1.and("vgpuType", join1.entity().getVgpuType(), Op.EQ);
        join1.and("remainingCapacity", join1.entity().getRemainingCapacity(), Op.GTEQ);
        gpuAvailability.join("groupId", join1, gpuAvailability.entity().getId(), join1.entity().getGpuGroupId(), JoinBuilder.JoinType.INNER);
        gpuAvailability.done();
    }

    @Override
    public boolean isHostGpuEnabled(final long hostId) {
        final SearchCriteria<HostGpuGroupsVO> sc = gpuAvailability.create();
        sc.setParameters("hostId", hostId);
        return !hostGpuGroupsDao.customSearch(sc, null).isEmpty();
    }

    @Override
    public List<HostGpuGroupsVO> listAvailableGPUDevice(final long hostId, final String groupName, final String vgpuType) {
        Filter searchFilter = new Filter(null, null);
        searchFilter.addOrderBy(VGPUTypesVO.class, "remainingCapacity", false, "groupId");
        final SearchCriteria<HostGpuGroupsVO> sc = gpuAvailability.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("groupName", groupName);
        sc.setJoinParameters("groupId", "vgpuType", vgpuType);
        return hostGpuGroupsDao.customSearch(sc, searchFilter);
    }

    /**
     * Check if host has GPU devices available
     *
     * @param host      the host to be checked
     * @param groupName gpuCard name
     * @param vgpuType  the VGPU type
     * @return true when the host has the capacity with given VGPU type
     */
    protected boolean isGPUDeviceAvailable(final Host host, final String groupName, final String vgpuType) {
        if (!listAvailableGPUDevice(host.getId(), groupName, vgpuType).isEmpty()) {
            return true;
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Host: {} does not have GPU device available", host);
            }
            return false;
        }
    }

    @Override
    public boolean isGPUDeviceAvailable(ServiceOffering offering, Host host, Long vmId) {
        // Check if GPU device is required by offering and host has the availability
        ServiceOfferingDetailsVO offeringDetails = null;
        if (offering.getVgpuProfileId() != null) {
            VgpuProfileVO vgpuProfile = vgpuProfileDao.findById(offering.getVgpuProfileId());
            if (vgpuProfile == null) {
                LOG.debug("Host {} does not have GPU devices available.", host);
                return false;
            }
            int gpuCount = offering.getGpuCount() != null ? offering.getGpuCount() : 1;

            if (!isGPUDeviceAvailable(host, vmId, vgpuProfile, gpuCount)) {
                LOG.debug("Host {} does not have required GPU devices available.", host);
                return false;
            }
        } else if ((offeringDetails = serviceOfferingDetailsDao.findDetail(offering.getId(), GPU.Keys.vgpuType.toString())) != null) {
            ServiceOfferingDetailsVO groupName = serviceOfferingDetailsDao.findDetail(offering.getId(), GPU.Keys.pciDevice.toString());
            if (!isGPUDeviceAvailable(host, groupName.getValue(), offeringDetails.getValue())) {
                LOG.debug("Host {} does not have required GPU devices available.", host);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isGPUDeviceAvailable(Host host, Long vmId, VgpuProfileVO vgpuProfile, int gpuCount) {
        if (host.getHypervisorType().equals(HypervisorType.XenServer)) {
            GpuCardVO gpuCard = gpuCardDao.findById(vgpuProfile.getCardId());
            String groupName = gpuCard.getGroupName();
            String vgpuType = vgpuProfile.getName();
            return isGPUDeviceAvailable(host, groupName, vgpuType);
        } else {
            return gpuService.isGPUDeviceAvailable(host, vmId, vgpuProfile, gpuCount);
        }
    }

    @Override
    public GPUDeviceTO getGPUDevice(VirtualMachine vm, long hostId, VgpuProfileVO vgpuProfile, int gpuCount) {
        HostVO host = hostDao.findById(vm.getHostId());
        if (host.getHypervisorType().equals(HypervisorType.XenServer)) {
            GpuCardVO gpuCard = gpuCardDao.findById(vgpuProfile.getCardId());
            String groupName = gpuCard.getGroupName();
            String vgpuType = vgpuProfile.getName();
            return getGPUDevice(vm.getHostId(), groupName, vgpuType);
        } else {
            return gpuService.getGPUDevice(vm, hostId, vgpuProfile, gpuCount);
        }
    }

    @Override
    public GPUDeviceTO getGPUDevice(final long hostId, final String groupName, final String vgpuType) {
        final List<HostGpuGroupsVO> gpuDeviceList = listAvailableGPUDevice(hostId, groupName, vgpuType);

        if (CollectionUtils.isEmpty(gpuDeviceList)) {
            final String errorMsg = String.format("Host %s does not have required GPU device or out of capacity. GPU group: %s, vGPU Type: %s", hostDao.findById(hostId), groupName, vgpuType);
            LOG.error(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }

        return new GPUDeviceTO(gpuDeviceList.get(0).getGroupName(), vgpuType, null);
    }

    @Override
    public void updateGPUDetails(final long hostId, final HashMap<String, HashMap<String, VgpuTypesInfo>> groupDetails) {
        // Update GPU group capacity
        final TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();
        hostGpuGroupsDao.persist(hostId, new ArrayList<>(groupDetails.keySet()));
        vgpuTypesDao.persist(hostId, groupDetails);
        txn.commit();
    }

    @Override
    public void updateGPUDetailsForVmStop(final VirtualMachine vm, final GPUDeviceTO gpuDevice) {
        HashMap<String, HashMap<String, VgpuTypesInfo>> groupDetails;
        if (gpuDevice == null || gpuDevice.getGpuDevices() != null) {
            HostVO host = hostDao.findById(vm.getHostId());
            if (GpuDetachOnStop.valueIn(vm.getDomainId())) {
                gpuService.deallocateAllGpuDevicesForVm(vm.getId());
            }
            groupDetails = gpuService.getGpuGroupDetailsFromGpuDevicesOnHost(host.getId());
        } else {
            groupDetails = gpuDevice.getGroupDetails();
        }
        updateGPUDetails(vm.getHostId(), groupDetails);
    }

    @Override
    public void updateGPUDetailsForVmStart(long hostId, long vmId, GPUDeviceTO gpuDevice) {
        HashMap<String, HashMap<String, VgpuTypesInfo>> groupDetails = gpuDevice.getGroupDetails();
        if (gpuDevice.getGpuDevices() != null) {
            gpuService.allocateGpuDevicesToVmOnHost(vmId, hostId, gpuDevice.getGpuDevices());
            groupDetails = gpuService.getGpuGroupDetailsFromGpuDevicesOnHost(hostId);
        }
        updateGPUDetails(hostId, groupDetails);
    }

    @Override
    public HashMap<String, HashMap<String, VgpuTypesInfo>> getGPUStatistics(final HostVO host) {
        final Answer answer = agentManager.easySend(host.getId(), new GetGPUStatsCommand(host.getGuid(), host.getName()));
        if (answer instanceof UnsupportedAnswer) {
            return null;
        }
        if (answer == null || !answer.getResult()) {
            final String msg = String.format("Unable to obtain GPU stats for %s", host);
            LOG.warn(msg);
            return null;
        } else if (answer instanceof GetGPUStatsAnswer) {
            GetGPUStatsAnswer gpuStatsAnswer = (GetGPUStatsAnswer) answer;
            return getGroupDetails(host, gpuStatsAnswer.getGpuDevices(), gpuStatsAnswer.getGroupDetails());
        }
        return null;
    }

    @Override
    public HashMap<String, HashMap<String, VgpuTypesInfo>> getGroupDetails(HostVO host, List<VgpuTypesInfo> gpuDevices, HashMap<String, HashMap<String, VgpuTypesInfo>> groupDetails) {
        HashMap<String, HashMap<String, VgpuTypesInfo>> finalGroupDetails;
        if (host.getId() > 0) {
            // The below method needs the host to be persisted in the DB to save the GPU devices for the host
            gpuService.addGpuDevicesToHost(host, gpuDevices);
        }
        if (CollectionUtils.isNotEmpty(gpuDevices)) {
            finalGroupDetails = gpuService.getGpuGroupDetailsFromGpuDevicesOnHost(host.getId());
        } else {
            finalGroupDetails = groupDetails;
        }
        return finalGroupDetails;
    }
}
