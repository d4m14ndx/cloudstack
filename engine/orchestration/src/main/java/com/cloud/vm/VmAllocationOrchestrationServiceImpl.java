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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.deploy.DeploymentPlan;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.DiskOfferingInfo;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.storage.Volume;
import com.cloud.storage.Volume.Type;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmAllocationOrchestrationServiceImpl implements VmAllocationOrchestrationService {

    private static final Logger logger = LogManager.getLogger(VmAllocationOrchestrationServiceImpl.class);

    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected EntityManager entityMgr;
    @Inject
    protected NetworkOrchestrationService networkMgr;
    @Inject
    protected VolumeOrchestrationService volumeMgr;
    @Inject
    protected DiskOfferingDao diskOfferingDao;
    @Inject
    protected VMTemplateDao templateDao;
    @Inject
    protected VMTemplateZoneDao templateZoneDao;
    @Inject
    protected VolumeDao volsDao;
    protected StateMachine2<State, Event, VirtualMachine> stateMachine = State.getStateMachine();

    @Override
    public void allocate(final String vmInstanceName, final VirtualMachineTemplate template, final ServiceOffering serviceOffering,
            final DiskOfferingInfo rootDiskOfferingInfo, final List<DiskOfferingInfo> dataDiskOfferings, List<Long> dataDiskDeviceIds,
            final LinkedHashMap<? extends Network, List<? extends NicProfile>> auxiliaryNetworks, final DeploymentPlan plan,
            final HypervisorType hyperType, final Map<String, Map<Integer, String>> extraDhcpOptions,
            final Map<Long, DiskOffering> datadiskTemplateToDiskOfferingMap, Volume volume, Snapshot snapshot)
            throws InsufficientCapacityException {

        logger.info("Allocating Instance from Template: {} with hostname: {} and {} networks", template, vmInstanceName, auxiliaryNetworks.size());
        VMInstanceVO persistedVm = null;
        try {
            final VMInstanceVO vm = vmDao.findVMByInstanceName(vmInstanceName);
            final Account owner = entityMgr.findById(Account.class, vm.getAccountId());

            logger.debug("Allocating entries for VM: " + vm);

            vm.setDataCenterId(plan.getDataCenterId());
            if (plan.getPodId() != null) {
                vm.setPodIdToDeployIn(plan.getPodId());
            }
            assert plan.getClusterId() == null && plan.getPoolId() == null : "We currently don't support cluster and pool preset yet";
            persistedVm = vmDao.persist(vm);

            final VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(persistedVm, template, serviceOffering, null, null);

            Long rootDiskSize = rootDiskOfferingInfo.getSize();
            if (vm.getType().isUsedBySystem() && VirtualMachineManagerImpl.SystemVmRootDiskSize.value() != null
                    && VirtualMachineManagerImpl.SystemVmRootDiskSize.value() > 0L) {
                rootDiskSize = VirtualMachineManagerImpl.SystemVmRootDiskSize.value();
            }
            final Long rootDiskSizeFinal = rootDiskSize;

            logger.debug("Allocating NICs for {}", persistedVm);

            try {
                if (!vmProfile.getBootArgs().contains("ExternalLoadBalancerVm")) {
                    networkMgr.allocate(vmProfile, auxiliaryNetworks, extraDhcpOptions);
                }
            } catch (final ConcurrentOperationException e) {
                throw new CloudRuntimeException("Concurrent operation while trying to allocate resources for the VM", e);
            }

            logger.debug("Allocating disks for {}",  persistedVm);

            allocateRootVolume(persistedVm, template, rootDiskOfferingInfo, owner, rootDiskSizeFinal, volume, snapshot);

            CallContext volumeContext = CallContext.register(CallContext.current(), ApiCommandResourceType.Volume);
            try {
                if (dataDiskOfferings != null) {
                    int index = 0;
                    for (final DiskOfferingInfo dataDiskOfferingInfo : dataDiskOfferings) {
                        Long deviceId = dataDiskDeviceIds.get(index++);
                        String volumeName = deviceId == null ? "DATA-" + persistedVm.getId() : "DATA-" + persistedVm.getId() + "-" + String.valueOf(deviceId);
                        volumeMgr.allocateRawVolume(Type.DATADISK, volumeName, dataDiskOfferingInfo.getDiskOffering(), dataDiskOfferingInfo.getSize(),
                                dataDiskOfferingInfo.getMinIops(), dataDiskOfferingInfo.getMaxIops(), persistedVm, template, owner, deviceId, true);
                    }
                }
                if (datadiskTemplateToDiskOfferingMap != null && !datadiskTemplateToDiskOfferingMap.isEmpty()) {
                    Long diskNumber = 1L;
                    for (Entry<Long, DiskOffering> dataDiskTemplateToDiskOfferingMap : datadiskTemplateToDiskOfferingMap.entrySet()) {
                        DiskOffering diskOffering = dataDiskTemplateToDiskOfferingMap.getValue();
                        long diskOfferingSize = diskOffering.getDiskSize() / (1024 * 1024 * 1024);
                        VMTemplateVO dataDiskTemplate = templateDao.findById(dataDiskTemplateToDiskOfferingMap.getKey());
                        volumeMgr.allocateRawVolume(Type.DATADISK, "DATA-" + persistedVm.getId() + "-" + String.valueOf(diskNumber), diskOffering, diskOfferingSize, null, null,
                                persistedVm, dataDiskTemplate, owner, diskNumber, true);
                        diskNumber++;
                    }
                }
            } finally {
                CallContext.unregister();
            }

            logger.debug("Allocation completed for VM: " + persistedVm);
        } catch (InsufficientCapacityException | CloudRuntimeException e) {
            try {
                if (persistedVm != null) {
                    stateTransitTo(persistedVm, Event.OperationFailedToError, null);
                }
            } catch (NoTransitionException nte) {
                logger.error("Failed to transition {} in {} state to Error state", persistedVm, persistedVm.getState().toString());
            }
            throw e;
        }
    }

    @Override
    public void allocate(final String vmInstanceName, final VirtualMachineTemplate template, final ServiceOffering serviceOffering,
            final LinkedHashMap<? extends Network, List<? extends NicProfile>> networks, final DeploymentPlan plan,
            final HypervisorType hyperType, Volume volume, Snapshot snapshot) throws InsufficientCapacityException {
        DiskOffering diskOffering = diskOfferingDao.findById(serviceOffering.getDiskOfferingId());
        allocate(vmInstanceName, template, serviceOffering, new DiskOfferingInfo(diskOffering), new ArrayList<>(), new ArrayList<>(), networks, plan, hyperType, null, null, volume, snapshot);
    }

    @Override
    public void allocateRootVolume(VMInstanceVO vm, VirtualMachineTemplate template, DiskOfferingInfo rootDiskOfferingInfo,
            Account owner, Long rootDiskSizeFinal, Volume volume, Snapshot snapshot) {
        CallContext volumeContext = CallContext.register(CallContext.current(), ApiCommandResourceType.Volume);
        try {
            String rootVolumeName = String.format("ROOT-%s", vm.getId());
            if (template.getFormat() == ImageFormat.ISO) {
                volumeMgr.allocateRawVolume(Type.ROOT, rootVolumeName, rootDiskOfferingInfo.getDiskOffering(), rootDiskOfferingInfo.getSize(),
                        rootDiskOfferingInfo.getMinIops(), rootDiskOfferingInfo.getMaxIops(), vm, template, owner, null, true);
            } else if (Arrays.asList(ImageFormat.BAREMETAL, ImageFormat.EXTERNAL).contains(template.getFormat())) {
                logger.debug("{} has format [{}]. Skipping ROOT volume [{}] allocation.", template, template.getFormat(), rootVolumeName);
            } else {
                volumeMgr.allocateTemplatedVolumes(Type.ROOT, rootVolumeName, rootDiskOfferingInfo.getDiskOffering(), rootDiskSizeFinal,
                        rootDiskOfferingInfo.getMinIops(), rootDiskOfferingInfo.getMaxIops(), template, vm, owner, volume, snapshot);
            }
        } finally {
            CallContext.unregister();
        }
    }

    @Override
    public void checkIfTemplateNeededForCreatingVmVolumes(VMInstanceVO vm) {
        final List<VolumeVO> existingRootVolumes = volsDao.findReadyRootVolumesByInstance(vm.getId());
        if (CollectionUtils.isNotEmpty(existingRootVolumes)) {
            return;
        }
        final VMTemplateVO template = templateDao.findById(vm.getTemplateId());
        if (template == null) {
            String msg = "Template for the VM instance can not be found, VM instance configuration needs to be updated";
            logger.error("{}. Template ID: {} seems to be removed", msg, vm.getTemplateId());
            throw new CloudRuntimeException(msg);
        }
        final VMTemplateZoneVO templateZoneVO = templateZoneDao.findByZoneTemplate(vm.getDataCenterId(), template.getId());
        if (templateZoneVO == null) {
            String msg = "Template for the VM instance can not be found in the zone ID: %s, VM instance configuration needs to be updated";
            logger.error("{}. {}", msg, template);
            throw new CloudRuntimeException(msg);
        }
    }

    protected boolean stateTransitTo(final VMInstanceVO vm, final Event event, final Long hostId) throws NoTransitionException {
        return stateMachine.transitTo(vm, event, new Pair<>(vm.getHostId(), hostId), vmDao);
    }
}
