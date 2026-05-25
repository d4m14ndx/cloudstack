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

import static com.cloud.configuration.ConfigurationManagerImpl.MIGRATE_VM_ACROSS_CLUSTERS;

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.exception.AffinityConflictException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.ScopeType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmMigrateAwayPlanningServiceImpl implements VmMigrateAwayPlanningService {

    private static final Logger logger = LogManager.getLogger(VmMigrateAwayPlanningServiceImpl.class);

    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected ServiceOfferingDao offeringDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected VolumeDao volsDao;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected DeploymentPlanningManager dpMgr;
    @Inject
    protected HighAvailabilityManager haMgr;
    @Inject
    protected VmWorkJobQueueService vmWorkJobQueueService;
    @Inject
    @Lazy
    protected VirtualMachineManagerImpl virtualMachineManager;

    @Override
    public void migrateAway(final String vmUuid, final long srcHostId) throws InsufficientServerCapacityException {
        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            final VirtualMachine vm = vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = vmWorkJobQueueService.createPlaceHolderWork(vm.getId());
            try {
                try {
                    orchestrateMigrateAway(vmUuid, srcHostId, null);
                } catch (final InsufficientServerCapacityException e) {
                    logger.warn("Failed to deploy vm {} with original planner, sending HAPlanner", vmUuid);
                    orchestrateMigrateAway(vmUuid, srcHostId, haMgr.getHAPlanner());
                }
            } finally {
                vmWorkJobQueueService.expungePlaceHolderWork(placeHolder);
            }
        } else {
            final Outcome<VirtualMachine> outcome = vmWorkJobQueueService.migrateVmAwayThroughJobQueue(vmUuid, srcHostId);

            vmWorkJobQueueService.retrieveVmFromJobOutcome(outcome, vmUuid, "migrateVmAway");

            try {
                vmWorkJobQueueService.retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
            } catch (ResourceUnavailableException | InsufficientCapacityException ex) {
                throw new RuntimeException("Unexpected exception", ex);
            }
        }
    }

    @Override
    public void orchestrateMigrateAway(final String vmUuid, final long srcHostId, final DeploymentPlanner planner) throws InsufficientServerCapacityException {
        final VMInstanceVO vm = vmDao.findByUuid(vmUuid);
        if (vm == null) {
            String message = String.format("Unable to find VM with uuid [%s].", vmUuid);
            logger.warn(message);
            throw new CloudRuntimeException(message);
        }

        ServiceOfferingVO offeringVO = offeringDao.findById(vm.getId(), vm.getServiceOfferingId());
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, null, offeringVO, null, null);

        final Long hostId = vm.getHostId();
        if (hostId == null) {
            String message = String.format("Unable to migrate %s due to it does not have a host id.", vm.toString());
            logger.warn(message);
            throw new CloudRuntimeException(message);
        }

        final Host host = hostDao.findById(hostId);
        Long poolId = null;
        final List<VolumeVO> vols = volsDao.findReadyRootVolumesByInstance(vm.getId());
        for (final VolumeVO rootVolumeOfVm : vols) {
            final StoragePoolVO rootDiskPool = storagePoolDao.findById(rootVolumeOfVm.getPoolId());
            if (rootDiskPool != null) {
                poolId = rootDiskPool.getId();
            }
        }

        final ExcludeList excludes = new ExcludeList();
        excludes.addHost(hostId);
        DataCenterDeployment plan = getMigrationDeployment(vm, host, poolId, excludes);

        DeployDestination dest = null;
        while (true) {

            try {
                plan.setMigrationPlan(true);
                dest = dpMgr.planDeployment(profile, plan, excludes, planner);
            } catch (final AffinityConflictException e2) {
                String message = String.format("Unable to create deployment, affinity rules associated to the %s conflict.", vm.toString());
                logger.warn(message, e2);
                throw new CloudRuntimeException(message, e2);
            }
            if (dest == null) {
                logger.warn("Unable to find destination for migrating the vm {}", profile);
                throw new InsufficientServerCapacityException("Unable to find a server to migrate to.", DataCenter.class, host.getDataCenterId());
            }
            logger.debug("Found destination {} for migrating to.", dest);

            excludes.addHost(dest.getHost().getId());
            try {
                virtualMachineManager.migrate(vm, srcHostId, dest);
                return;
            } catch (ResourceUnavailableException | ConcurrentOperationException e) {
                logger.warn("Unable to migrate {} to {} due to [{}]", vm.toString(), dest.getHost().toString(), e.getMessage(), e);
            }

            try {
                virtualMachineManager.advanceStop(vmUuid, true);
                throw new CloudRuntimeException("Unable to migrate " + vm);
            } catch (final ResourceUnavailableException | ConcurrentOperationException | OperationTimedoutException e) {
                logger.error("Unable to stop {} due to [{}].", vm.toString(), e.getMessage(), e);
                throw new CloudRuntimeException("Unable to migrate " + vm);
            }
        }
    }

    /**
     * Check if the virtual machine has any volume in cluster-wide pool
     * @param vmId id of the virtual machine
     * @return true if volume exists on cluster-wide pool else false
     */
    @Override
    public boolean checkIfVmHasClusterWideVolumes(Long vmId) {
        final List<VolumeVO> volumesList = volsDao.findCreatedByInstance(vmId);

        return volumesList.parallelStream()
                .anyMatch(vol -> storagePoolDao.findById(vol.getPoolId()).getScope().equals(ScopeType.CLUSTER));

    }

    @Override
    public DataCenterDeployment getMigrationDeployment(final VirtualMachine vm, final Host host, final Long poolId, final ExcludeList excludes) {
        if (MIGRATE_VM_ACROSS_CLUSTERS.valueIn(host.getDataCenterId()) &&
                (HypervisorType.VMware.equals(host.getHypervisorType()) || !checkIfVmHasClusterWideVolumes(vm.getId()))) {
            logger.info("Searching for hosts in the zone for vm migration");
            List<Long> clustersToExclude = clusterDao.listAllClusterIds(host.getDataCenterId());
            List<ClusterVO> clusterList = clusterDao.listByDcHyType(host.getDataCenterId(), host.getHypervisorType().toString());
            for (ClusterVO cluster : clusterList) {
                clustersToExclude.remove(cluster.getId());
            }
            for (Long clusterId : clustersToExclude) {
                excludes.addCluster(clusterId);
            }
            if (VirtualMachine.systemVMs.contains(vm.getType())) {
                return new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), null, null, poolId, null);
            }
            return new DataCenterDeployment(host.getDataCenterId(), null, null, null, poolId, null);
        }
        return new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), null, poolId, null);
    }
}
