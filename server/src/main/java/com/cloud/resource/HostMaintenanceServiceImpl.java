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

import static com.cloud.configuration.ConfigurationManagerImpl.MIGRATE_VM_ACROSS_CLUSTERS;

import java.util.List;

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.ha.HighAvailabilityManager.WorkType;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Side-effect-free helpers that support the host maintenance state
 * machine, extracted from {@link ResourceManagerImpl}.
 *
 * @see HostMaintenanceService
 */
@Component
public class HostMaintenanceServiceImpl implements HostMaintenanceService {
    private static final Logger LOG = LogManager.getLogger(HostMaintenanceServiceImpl.class);

    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected ServiceOfferingDao serviceOfferingDao;
    @Inject
    protected DeploymentPlanningManager deploymentManager;
    @Inject
    protected VirtualMachineManager vmManager;
    @Inject
    protected HighAvailabilityManager haManager;
    @Inject
    protected HostLookupService hostLookupService;
    @Inject
    protected VMInstanceDao vmInstanceDao;

    @Override
    public boolean isMaintenanceLocalStrategyMigrate() {
        if (StringUtils.isBlank(ResourceManager.HOST_MAINTENANCE_LOCAL_STRATEGY.value())) {
            return false;
        }
        return ResourceManager.HOST_MAINTENANCE_LOCAL_STRATEGY.value().equalsIgnoreCase(WorkType.Migration.toString());
    }

    @Override
    public boolean isMaintenanceLocalStrategyForceStop() {
        if (StringUtils.isBlank(ResourceManager.HOST_MAINTENANCE_LOCAL_STRATEGY.value())) {
            return false;
        }
        return ResourceManager.HOST_MAINTENANCE_LOCAL_STRATEGY.value().equalsIgnoreCase(WorkType.ForceStop.toString());
    }

    @Override
    public boolean isMaintenanceLocalStrategyDefault() {
        return StringUtils.isBlank(ResourceManager.HOST_MAINTENANCE_LOCAL_STRATEGY.value())
                || ResourceManager.HOST_MAINTENANCE_LOCAL_STRATEGY.value().equalsIgnoreCase(State.Error.toString());
    }

    @Override
    public boolean isClusterWideMigrationPossible(Host host, List<VMInstanceVO> vms, List<HostVO> hosts) {
        if (MIGRATE_VM_ACROSS_CLUSTERS.valueIn(host.getDataCenterId())) {
            DataCenterVO zone = dataCenterDao.findById(host.getDataCenterId());
            LOG.info("Looking for hosts across different clusters in zone: {}", zone);
            Long podId = null;
            for (final VMInstanceVO vm : vms) {
                if (VirtualMachine.systemVMs.contains(vm.getType())) {
                    // SystemVMs can only be migrated to same pod
                    podId = host.getPodId();
                    break;
                }
            }
            hosts.addAll(hostLookupService.listAllUpAndEnabledHosts(Host.Type.Routing, null, podId, host.getDataCenterId()));
            if (CollectionUtils.isEmpty(hosts)) {
                LOG.warn("Unable to find a host for vm migration in zone: {}", zone);
                return false;
            }
            LOG.info("Found hosts in the zone for vm migration: " + hosts);
            if (HypervisorType.VMware.equals(host.getHypervisorType())) {
                LOG.debug("Skipping pool check of volumes on VMware environment because across-cluster vm migration is supported by vMotion");
                return true;
            }
            // Don't migrate vm if it has volumes on cluster-wide pool
            for (final VMInstanceVO vm : vms) {
                if (vmManager.checkIfVmHasClusterWideVolumes(vm.getId())) {
                    LOG.warn(String.format("VM %s cannot be migrated across cluster as it has volumes on cluster-wide pool", vm));
                    return false;
                }
            }
        } else {
            LOG.warn(String.format("VMs cannot be migrated across cluster since %s is false for zone ID: %d", MIGRATE_VM_ACROSS_CLUSTERS.key(), host.getDataCenterId()));
            return false;
        }
        return true;
    }

    @Override
    public void migrateAwayVmWithVolumes(HostVO host, VMInstanceVO vm) {
        final DataCenterDeployment plan = new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), null, null, null);
        ServiceOfferingVO offeringVO = serviceOfferingDao.findById(vm.getServiceOfferingId());
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, null, offeringVO, null, null);
        plan.setMigrationPlan(true);
        DeployDestination dest = getDeployDestination(vm, profile, plan, host);
        Host destHost = dest.getHost();

        try {
            vmManager.migrateWithStorage(vm.getUuid(), host.getId(), destHost.getId(), null);
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException(String.format("Maintenance failed, could not migrate VM (%s) with local storage from host (%s) to host (%s).",
                            vm, host, destHost), e);
        }
    }

    protected DeployDestination getDeployDestination(VMInstanceVO vm, VirtualMachineProfile profile, DataCenterDeployment plan, HostVO hostToAvoid) {
        DeployDestination dest;
        DeploymentPlanner.ExcludeList avoids = new DeploymentPlanner.ExcludeList();
        avoids.addHost(hostToAvoid.getId());
        try {
            dest = deploymentManager.planDeployment(profile, plan, avoids, null);
        } catch (InsufficientServerCapacityException e) {
            throw new CloudRuntimeException(String.format("Maintenance failed, could not find deployment destination for VM [id=%s, name=%s].", vm.getId(), vm.getInstanceName()),
                    e);
        }
        if (dest == null) {
            throw new CloudRuntimeException(String.format("Maintenance failed, could not find deployment destination for VM [id=%s, name=%s], using plan: %s.", vm.getId(), vm.getInstanceName(), plan));
        }
        return dest;
    }

    @Override
    public void handleVmForLastHostOrWithVGpu(HostVO host, VMInstanceVO vm) {
        // Migration is not supported for VGPU Vms so stop them.
        // for the last host in this cluster, destroy SSVM/CPVM and stop all other VMs
        if (VirtualMachine.Type.SecondaryStorageVm.equals(vm.getType())
                || VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
            LOG.error("Maintenance: VM is of type {}. Destroying VM {} immediately instead of migration.", vm.getType(), vm);
            haManager.scheduleDestroy(vm, host.getId(), HighAvailabilityManager.ReasonType.HostMaintenance);
            return;
        }
        LOG.error("Maintenance: No hosts available for migrations. Scheduling shutdown for VM {} instead of migration.", vm);
        haManager.scheduleStop(vm, host.getId(), WorkType.ForceStop);
    }

    @Override
    public void scheduleVmsRestart(Host host) {
        List<VMInstanceVO> allVmsOnHost = vmInstanceDao.listByHostId(host.getId());
        if (CollectionUtils.isEmpty(allVmsOnHost)) {
            LOG.debug("Host ({}) was marked as Degraded with no allocated VMs, no need to schedule VM restart", host);
        }

        LOG.debug("Host ({}) was marked as Degraded with a total of {} allocated VMs. Triggering HA to start VMs that have HA enabled.", host, allVmsOnHost.size());
        for (VMInstanceVO vm : allVmsOnHost) {
            State vmState = vm.getState();
            if (vmState == State.Starting || vmState == State.Running || vmState == State.Stopping) {
                haManager.scheduleRestart(vm, false, HighAvailabilityManager.ReasonType.HostDegraded);
            }
        }
    }
}
