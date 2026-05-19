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

import static com.cloud.hypervisor.Hypervisor.HypervisorType.Functionality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.capacity.CapacityManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.exception.AffinityConflictException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.gpu.GPU;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.hypervisor.kvm.dpdk.DpdkHelper;
import com.cloud.offering.DiskOffering;
import com.cloud.org.Cluster;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceState;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@Component
public class VmLiveMigrationOrchestrationServiceImpl implements VmLiveMigrationOrchestrationService {
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private AccountManager accountMgr;
    @Inject
    private CapacityManager capacityMgr;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    private DeploymentPlanningManager planningMgr;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private DpdkHelper dpdkHelper;
    @Inject
    private HostDao hostDao;
    @Inject
    private HostPodDao podDao;
    @Inject
    private HypervisorCapabilitiesDao hypervisorCapabilitiesDao;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private ResourceManager resourceMgr;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Inject
    private SnapshotHelper snapshotHelper;
    @Inject
    private StorageManager storageManager;
    @Inject
    private VirtualMachineManager itMgr;
    @Inject
    private VmMigrationDedicationService vmMigrationDedicationService;
    @Inject
    private VmMigrationValidator vmMigrationValidator;
    @Inject
    private VmStatsCollectionService vmStatsCollectionService;
    @Inject
    private VmVolumeLifecycleValidationService vmVolumeLifecycleValidationService;
    @Inject
    private VMSnapshotDao vmSnapshotDao;
    @Inject
    private UserVmDao vmDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private VolumeDao volsDao;

    @Override
    public boolean isVMUsingLocalStorage(VMInstanceVO vm) {
        List<VolumeVO> volumes = volsDao.findByInstance(vm.getId());
        return isAnyVmVolumeUsingLocalStorage(volumes);
    }

    @Override
    public VirtualMachine migrateVirtualMachine(Long vmId, Host destinationHost) throws ResourceUnavailableException, ConcurrentOperationException,
            ManagementServerException, VirtualMachineMigrationException {
        Account caller = CallContext.current().getCallingAccount();
        if (!accountMgr.isRootAdmin(caller.getId())) {
            if (logger.isDebugEnabled()) {
                logger.debug("Caller is not a root admin, permission denied to migrate the VM");
            }
            throw new PermissionDeniedException("No permission to migrate VM, Only Root Admin can migrate a VM!");
        }

        VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find the VM by id=" + vmId);
        }
        if (vm.getState() != State.Running) {
            if (logger.isDebugEnabled()) {
                logger.debug("VM is not Running, unable to migrate the vm " + vm);
            }
            InvalidParameterValueException ex = new InvalidParameterValueException("VM is not Running, unable to migrate the vm with specified id");
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        vmMigrationValidator.checkIfHostOfVMIsInPrepareForMaintenanceState(vm, "Migrate");

        if (serviceOfferingDetailsDao.findDetail(vm.getServiceOfferingId(), GPU.Keys.pciDevice.toString()) != null) {
            throw new InvalidParameterValueException("Live Migration of GPU enabled VM is not supported");
        }

        if (!vmMigrationValidator.isOnSupportedHypevisorForMigration(vm)) {
            logger.error(vm + " is not XenServer/VMware/KVM/Hyperv, cannot migrate this VM from hypervisor type " + vm.getHypervisorType());
            throw new InvalidParameterValueException("Unsupported Hypervisor Type for VM migration, we support XenServer/VMware/KVM/Hyperv only");
        }

        if (vm.getType().equals(VirtualMachine.Type.User) && vm.getHypervisorType().equals(HypervisorType.LXC)) {
            throw new InvalidParameterValueException("Unsupported Hypervisor Type for User VM migration, we support XenServer/VMware/KVM/Hyperv only");
        }

        if (isVMUsingLocalStorage(vm)) {
            logger.error(vm + " is using Local Storage, cannot migrate this VM.");
            throw new InvalidParameterValueException("Unsupported operation, VM uses Local storage, cannot migrate");
        }

        long srcHostId = vm.getHostId();
        Host srcHost = resourceMgr.getHost(srcHostId);
        if (srcHost == null) {
            throw new InvalidParameterValueException("Cannot migrate VM, host with id: " + srcHostId + " for VM not found");
        }

        DeployDestination dest;
        if (destinationHost == null) {
            dest = chooseVmMigrationDestination(vm, srcHost, null);
        } else {
            dest = checkVmMigrationDestination(vm, srcHost, destinationHost);
        }

        if (dest == null) {
            throw new CloudRuntimeException("Unable to find suitable destination to migrate VM " + vm.getInstanceName());
        }

        logger.info("Starting migration of VM {} from host {} to host {} ", vm.getInstanceName(), srcHostId, dest.getHost().getId());
        collectVmDiskAndNetworkStatistics(vmId, State.Running);
        itMgr.migrate(vm.getUuid(), srcHostId, dest);
        return findMigratedVm(vm.getId(), vm.getType());
    }

    private DeployDestination chooseVmMigrationDestination(VMInstanceVO vm, Host srcHost, Long poolId) {
        vm.setLastHostId(null);
        final ServiceOfferingVO offering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, null, offering, null, null);
        final Long srcHostId = srcHost.getId();
        final Host host = hostDao.findById(srcHostId);
        ExcludeList excludes = new ExcludeList();
        excludes.addHost(srcHostId);
        final DataCenterDeployment plan = itMgr.getMigrationDeployment(vm, host, poolId, excludes);
        try {
            return planningMgr.planDeployment(profile, plan, excludes, null);
        } catch (final AffinityConflictException e2) {
            logger.warn("Unable to create deployment, affinity rules associated to the VM conflict", e2);
            throw new CloudRuntimeException("Unable to create deployment, affinity rules associated to the VM conflict");
        } catch (final InsufficientServerCapacityException e3) {
            throw new CloudRuntimeException("Unable to find a server to migrate the vm to");
        }
    }

    private DeployDestination checkVmMigrationDestination(VMInstanceVO vm, Host srcHost, Host destinationHost) throws VirtualMachineMigrationException {
        if (destinationHost == null) {
            logger.error("Destination host is null for migration of VM: {}", vm.getInstanceName());
            return null;
        }
        if (destinationHost.getId() == srcHost.getId()) {
            throw new InvalidParameterValueException("Cannot migrate VM, VM is already present on this host, please specify valid destination host to migrate the VM");
        }

        if (destinationHost.getState() != com.cloud.host.Status.Up || destinationHost.getResourceState() != ResourceState.Enabled) {
            throw new InvalidParameterValueException("Cannot migrate VM, destination host is not in correct state, has status: " + destinationHost.getState() + ", state: "
                    + destinationHost.getResourceState());
        }

        if (vm.getType() != VirtualMachine.Type.User) {
            if (srcHost.getPodId() != null && !srcHost.getPodId().equals(destinationHost.getPodId())) {
                throw new InvalidParameterValueException("Cannot migrate the VM, destination host is not in the same pod as current host of the VM");
            }
        }

        if (dpdkHelper.isVMDpdkEnabled(vm.getId()) && !dpdkHelper.isHostDpdkEnabled(destinationHost.getId())) {
            throw new CloudRuntimeException("Cannot migrate VM, VM is DPDK enabled VM but destination host is not DPDK enabled");
        }

        HostVO destinationHostVO = hostDao.findById(destinationHost.getId());
        hostDao.loadHostTags(destinationHostVO);
        vmMigrationValidator.validateStrictHostTagCheck(vm, destinationHostVO);
        vmMigrationValidator.validateStorageAccessGroupsOnHosts(srcHost, destinationHost);

        checkHostsDedication(vm, srcHost.getId(), destinationHost.getId());

        DataCenterVO dcVO = dcDao.findById(destinationHost.getDataCenterId());
        HostPodVO pod = podDao.findById(destinationHost.getPodId());
        Cluster cluster = clusterDao.findById(destinationHost.getClusterId());
        DeployDestination dest = new DeployDestination(dcVO, pod, cluster, destinationHost);

        if (capacityMgr.checkIfHostReachMaxGuestLimit(destinationHostVO)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Host: {} already has max Running VMs(count includes system VMs), cannot migrate to this host", destinationHost);
            }
            throw new VirtualMachineMigrationException(String.format("Destination host: %s already has max Running VMs(count includes system VMs), cannot migrate to this host", destinationHost));
        }
        logger.debug("Checking if there are any ongoing snapshots volumes associated with VM {}", vm);
        if (vmVolumeLifecycleValidationService.checkStatusOfVolumeSnapshots(vm, null)) {
            throw new CloudRuntimeException("There is/are unbacked up snapshot(s) on volume(s) attached to this VM, VM Migration is not permitted, please try again later.");
        }
        logger.debug("Found no ongoing snapshots on volumes associated with the vm {}", vm);

        return dest;
    }

    private void checkHostsDedication(VMInstanceVO vm, long srcHostId, long destHostId) {
        vmMigrationDedicationService.checkHostsDedication(vm, srcHostId, destHostId);
    }

    @Override
    public boolean isAnyVmVolumeUsingLocalStorage(final List<VolumeVO> volumes) {
        for (VolumeVO vol : volumes) {
            DiskOfferingVO diskOffering = diskOfferingDao.findById(vol.getDiskOfferingId());
            if (diskOffering.isUseLocalStorage()) {
                return true;
            }
            StoragePoolVO storagePool = storagePoolDao.findById(vol.getPoolId());
            if (storagePool.isLocal()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isAllVmVolumesOnZoneWideStore(final List<VolumeVO> volumes) {
        if (CollectionUtils.isEmpty(volumes)) {
            return false;
        }
        for (Volume volume : volumes) {
            if (volume == null || volume.getPoolId() == null) {
                return false;
            }
            StoragePoolVO pool = storagePoolDao.findById(volume.getPoolId());
            if (pool == null || !ScopeType.ZONE.equals(pool.getScope())) {
                return false;
            }
        }
        return true;
    }

    private Pair<Host, Host> getHostsForMigrateVmWithStorage(VMInstanceVO vm, Host destinationHost) throws VirtualMachineMigrationException {
        long srcHostId = vm.getHostId();
        Host srcHost = resourceMgr.getHost(srcHostId);

        if (srcHost == null) {
            throw new InvalidParameterValueException("Cannot migrate VM, host with ID: " + srcHostId + " for VM not found");
        }

        if (destinationHost == null) {
            return new Pair<>(srcHost, null);
        }

        if (destinationHost.getId() == srcHostId) {
            throw new InvalidParameterValueException(String.format("Cannot migrate VM as it is already present on host %s (ID: %s), please specify valid destination host to migrate the VM",
                    destinationHost.getName(), destinationHost.getUuid()));
        }

        String srcHostVersion = srcHost.getHypervisorVersion();
        String destHostVersion = destinationHost.getHypervisorVersion();

        if (!srcHost.getHypervisorType().equals(destinationHost.getHypervisorType())) {
            throw new CloudRuntimeException("The source and destination hosts are not of the same type and version. Source hypervisor type and version: " +
                    srcHost.getHypervisorType().toString() + " " + srcHostVersion + ", Destination hypervisor type and version: " +
                    destinationHost.getHypervisorType().toString() + " " + destHostVersion);
        }

        if (!VirtualMachine.Type.User.equals(vm.getType())) {
            if (srcHost.getPodId() != null && !srcHost.getPodId().equals(destinationHost.getPodId())) {
                throw new InvalidParameterValueException("Cannot migrate the VM, destination host is not in the same pod as current host of the VM");
            }
        }

        if (HypervisorType.KVM.equals(srcHost.getHypervisorType())) {
            if (srcHostVersion == null) {
                srcHostVersion = "";
            }

            if (destHostVersion == null) {
                destHostVersion = "";
            }
        }

        if (!hypervisorCapabilitiesDao.isStorageMotionSupported(srcHost.getHypervisorType(), srcHostVersion)) {
            throw new CloudRuntimeException(String.format("Migration with storage isn't supported for source host %s (ID: %s) on hypervisor %s with version %s", srcHost.getName(), srcHost.getUuid(), srcHost.getHypervisorType(), srcHost.getHypervisorVersion()));
        }

        if (srcHostVersion == null || !srcHostVersion.equals(destHostVersion)) {
            if (!hypervisorCapabilitiesDao.isStorageMotionSupported(destinationHost.getHypervisorType(), destHostVersion)) {
                throw new CloudRuntimeException(String.format("Migration with storage isn't supported for target host %s (ID: %s) on hypervisor %s with version %s", destinationHost.getName(), destinationHost.getUuid(), destinationHost.getHypervisorType(), destinationHost.getHypervisorVersion()));
            }
        }

        if (destinationHost.getState() != com.cloud.host.Status.Up || destinationHost.getResourceState() != ResourceState.Enabled) {
            throw new CloudRuntimeException(String.format("Cannot migrate VM, destination host %s (ID: %s) is not in correct state, has status: %s, state: %s",
                    destinationHost.getName(), destinationHost.getUuid(), destinationHost.getState(), destinationHost.getResourceState()));
        }

        if (capacityMgr.checkIfHostReachMaxGuestLimit(destinationHost)) {
            throw new VirtualMachineMigrationException(String.format("Cannot migrate Instance as destination host %s (ID: %s) already has max running Instances (count includes system VMs)",
                    destinationHost.getName(), destinationHost.getUuid()));
        }

        vmMigrationValidator.validateStorageAccessGroupsOnHosts(srcHost, destinationHost);

        return new Pair<>(srcHost, destinationHost);
    }

    private List<VolumeVO> getVmVolumesForMigrateVmWithStorage(VMInstanceVO vm) {
        List<VolumeVO> vmVolumes = volsDao.findUsableVolumesForInstance(vm.getId());
        for (VolumeVO volume : vmVolumes) {
            if (volume.getState() != Volume.State.Ready) {
                throw new CloudRuntimeException(String.format("Volume %s (ID: %s) of the VM is not in Ready state. Cannot migrate the VM %s (ID: %s) with its volumes", volume.getName(), volume.getUuid(), vm.getInstanceName(), vm.getUuid()));
            }
        }
        return vmVolumes;
    }

    private Map<Long, Long> getVolumePoolMappingForMigrateVmWithStorage(VMInstanceVO vm, Map<String, String> volumeToPool) {
        Map<Long, Long> volToPoolObjectMap = new HashMap<>();

        List<VolumeVO> vmVolumes = getVmVolumesForMigrateVmWithStorage(vm);

        if (MapUtils.isNotEmpty(volumeToPool)) {
            for (Map.Entry<String, String> entry : volumeToPool.entrySet()) {
                VolumeVO volume = volsDao.findByUuid(entry.getKey());
                StoragePoolVO pool = storagePoolDao.findByUuid(entry.getValue());
                if (volume == null) {
                    throw new InvalidParameterValueException("There is no volume present with the given id " + entry.getKey());
                } else if (pool == null) {
                    throw new InvalidParameterValueException("There is no storage pool present with the given id " + entry.getValue());
                } else if (pool.isInMaintenance()) {
                    throw new InvalidParameterValueException("Cannot migrate volume " + volume + "to the destination storage pool " + pool.getName() +
                            " as the storage pool is in maintenance mode.");
                } else {
                    if (!vmVolumes.contains(volume)) {
                        throw new InvalidParameterValueException(String.format("Volume " + volume + " doesn't belong to the VM %s (ID: %s) that has to be migrated", vm.getInstanceName(), vm.getUuid()));
                    }
                    volToPoolObjectMap.put(volume.getId(), pool.getId());
                }
                HostVO host = hostDao.findById(vm.getHostId());
                if (!storageManager.checkIfHostAndStoragePoolHasCommonStorageAccessGroups(host, pool)) {
                    throw new InvalidParameterValueException(String.format("Destination pool %s for the volume %s does not have matching storage access groups as host %s", pool.getName(), volume.getName(), host.getName()));
                }

                HypervisorType hypervisorType = volsDao.getHypervisorType(volume.getId());
                try {
                    snapshotHelper.checkKvmVolumeSnapshotsOnlyInPrimaryStorage(volume, hypervisorType);
                } catch (CloudRuntimeException ex) {
                    throw new CloudRuntimeException(String.format("Unable to migrate %s to the destination storage pool [%s] due to [%s]", volume,
                            new ToStringBuilder(pool, ToStringStyle.JSON_STYLE).append("uuid", pool.getUuid()).append("name", pool.getName()).toString(), ex.getMessage()), ex);
                }

                if (hypervisorType.equals(HypervisorType.VMware)) {
                    try {
                        DiskOffering diskOffering = diskOfferingDao.findById(volume.getDiskOfferingId());
                        DiskProfile diskProfile = new DiskProfile(volume, diskOffering, volsDao.getHypervisorType(volume.getId()));
                        Pair<Volume, DiskProfile> volumeDiskProfilePair = new Pair<>(volume, diskProfile);
                        boolean isStoragePoolStoragepolicyCompliance = storageManager.isStoragePoolCompliantWithStoragePolicy(Arrays.asList(volumeDiskProfilePair), pool);
                        if (!isStoragePoolStoragepolicyCompliance) {
                            throw new CloudRuntimeException(String.format("Storage pool %s is not storage policy compliance with the volume %s", pool.getUuid(), volume.getUuid()));
                        }
                    } catch (StorageUnavailableException e) {
                        throw new CloudRuntimeException(String.format("Could not verify storage policy compliance against storage pool %s due to exception %s", pool.getUuid(), e.getMessage()));
                    }
                }
            }
        }
        return volToPoolObjectMap;
    }

    @Override
    public boolean isVmCanBeMigratedWithoutStorage(Host srcHost, Host destinationHost, List<VolumeVO> volumes,
            Map<String, String> volumeToPool) {
        return !isAnyVmVolumeUsingLocalStorage(volumes) &&
                MapUtils.isEmpty(volumeToPool) && destinationHost != null
                && (destinationHost.getClusterId().equals(srcHost.getClusterId()) || isAllVmVolumesOnZoneWideStore(volumes));
    }

    @Override
    public Host chooseVmMigrationDestinationUsingVolumePoolMap(VMInstanceVO vm, Host srcHost, Map<Long, Long> volToPoolObjectMap) {
        Long poolId = null;
        if (MapUtils.isNotEmpty(volToPoolObjectMap)) {
            poolId = new ArrayList<>(volToPoolObjectMap.values()).get(0);
        }
        DeployDestination deployDestination = chooseVmMigrationDestination(vm, srcHost, poolId);
        if (deployDestination == null || deployDestination.getHost() == null) {
            throw new CloudRuntimeException("Unable to find suitable destination to migrate VM " + vm.getInstanceName());
        }
        return deployDestination.getHost();
    }

    @Override
    public VirtualMachine migrateVirtualMachineWithVolume(Long vmId, Host destinationHost, Map<String, String> volumeToPool) throws ResourceUnavailableException,
            ConcurrentOperationException, ManagementServerException, VirtualMachineMigrationException {
        Account caller = CallContext.current().getCallingAccount();
        if (!accountMgr.isRootAdmin(caller.getId())) {
            if (logger.isDebugEnabled()) {
                logger.debug("Caller is not a root admin, permission denied to migrate the VM");
            }
            throw new PermissionDeniedException("No permission to migrate VM, Only Root Admin can migrate a VM!");
        }

        VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find the VM by ID " + vmId);
        }

        if (vm.getState() != State.Running) {
            if (logger.isDebugEnabled()) {
                logger.debug("VM is not Running, unable to migrate the vm " + vm);
            }
            CloudRuntimeException ex = new CloudRuntimeException(String.format("Unable to migrate the VM %s (ID: %s) as it is not in Running state", vm.getInstanceName(), vm.getUuid()));
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        if (serviceOfferingDetailsDao.findDetail(vm.getServiceOfferingId(), GPU.Keys.pciDevice.toString()) != null) {
            throw new InvalidParameterValueException("Live Migration of GPU enabled VM is not supported");
        }

        if (!vm.getHypervisorType().isFunctionalitySupported(Functionality.VmStorageMigration)) {
            throw new InvalidParameterValueException(
                    String.format("Unsupported hypervisor: %s for VM migration, we support [%s] only",
                            vm.getHypervisorType(),
                            HypervisorType.getListOfHypervisorsSupportingFunctionality(Functionality.VmStorageMigration)));
        }

        if (!vm.getHypervisorType().isFunctionalitySupported(Functionality.VmStorageMigrationWithSnapshots) &&
                CollectionUtils.isNotEmpty(vmSnapshotDao.findByVm(vmId))) {
            throw new InvalidParameterValueException("Instance with Instance Snapshots cannot be migrated with storage, please remove all Instance Snapshots");
        }

        Pair<Host, Host> sourceDestinationHosts = getHostsForMigrateVmWithStorage(vm, destinationHost);
        Host srcHost = sourceDestinationHosts.first();

        final List<VolumeVO> volumes = volsDao.findCreatedByInstance(vm.getId());
        if (isVmCanBeMigratedWithoutStorage(srcHost, destinationHost, volumes, volumeToPool)) {
            if (!VirtualMachine.Type.User.equals(vm.getType())) {
                return migrateVirtualMachine(vmId, destinationHost);
            }
            throw new InvalidParameterValueException(String.format("Migration of the VM: %s (ID: %s) from host %s (ID: %s) to destination host  %s (ID: %s) doesn't involve migrating the volumes",
                    vm.getInstanceName(), vm.getUuid(), srcHost.getName(), srcHost.getUuid(), destinationHost.getName(), destinationHost.getUuid()));
        }

        Map<Long, Long> volToPoolObjectMap = getVolumePoolMappingForMigrateVmWithStorage(vm, volumeToPool);

        if (destinationHost == null) {
            destinationHost = chooseVmMigrationDestinationUsingVolumePoolMap(vm, srcHost, volToPoolObjectMap);
        }

        checkHostsDedication(vm, srcHost.getId(), destinationHost.getId());

        itMgr.migrateWithStorage(vm.getUuid(), srcHost.getId(), destinationHost.getId(), volToPoolObjectMap);
        return findMigratedVm(vm.getId(), vm.getType());
    }

    private VirtualMachine findMigratedVm(long vmId, VirtualMachine.Type vmType) {
        if (VirtualMachine.Type.User.equals(vmType)) {
            return vmDao.findById(vmId);
        }
        return vmInstanceDao.findById(vmId);
    }

    private void collectVmDiskAndNetworkStatistics(Long vmId, State expectedState) {
        UserVmVO uservm = vmDao.findById(vmId);
        if (uservm != null) {
            collectVmDiskAndNetworkStatistics(uservm, expectedState);
        } else {
            logger.info("Skip collecting vmId {} disk and network statistics as it is not user vm", vmId);
        }
    }

    private void collectVmDiskAndNetworkStatistics(UserVm vm, State expectedState) {
        if (expectedState == null || expectedState == vm.getState()) {
            vmStatsCollectionService.collectVmDiskStatistics(vm);
            vmStatsCollectionService.collectVmNetworkStatistics(vm);
        } else {
            logger.warn(String.format("Skip collecting vm %s disk and network statistics as the expected vm state is %s but actual state is %s", vm, expectedState, vm.getState()));
        }
    }
}
