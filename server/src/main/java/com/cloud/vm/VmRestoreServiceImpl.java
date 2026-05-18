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

import static org.apache.cloudstack.api.ApiConstants.MAX_IOPS;
import static org.apache.cloudstack.api.ApiConstants.MIN_IOPS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventVO;
import com.cloud.event.UsageEventUtils;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.DiskOffering;
import com.cloud.server.ManagementService;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachineProfile.Param;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.resourcelimit.ReservationHelper;

@Component
public class VmRestoreServiceImpl implements VmRestoreService {

    private static final long GiB_TO_BYTES = 1024L * 1024L * 1024L;

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private UserDao _userDao;
    @Inject
    private UserVmDao _vmDao;
    @Inject
    private AccountDao _accountDao;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private VMTemplateDao _templateDao;
    @Inject
    private TemplateDataStoreDao _templateStoreDao;
    @Inject
    private VolumeDao _volsDao;
    @Inject
    private VMSnapshotDao _vmSnapshotDao;
    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    private VirtualMachineManager _itMgr;
    @Inject
    private VolumeApiService _volumeService;
    @Inject
    private VolumeOrchestrationService volumeMgr;
    @Inject
    private ResourceLimitService _resourceLimitMgr;
    @Inject
    private UsageEventDao _usageEventDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private VolumeDataFactory volFactory;
    @Inject
    private VolumeService _volService;
    @Inject
    private ManagementService _mgr;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private VmRootVolumeStorageCleanupService vmRootVolumeStorageCleanupService;
    @Inject
    private VmPasswordSSHKeyResetService vmPasswordSSHKeyResetService;

    @Override
    public UserVm restoreVMInternal(Account caller, UserVmVO vm, Long newTemplateId, Long rootDiskOfferingId,
            boolean expunge, Map<String, String> details)
            throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException {
        return _itMgr.restoreVirtualMachine(vm.getId(), newTemplateId, rootDiskOfferingId, expunge, details);
    }

    @Override
    public UserVm restoreVMInternal(Account caller, UserVmVO vm)
            throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException {
        return restoreVMInternal(caller, vm, null, null, false, null);
    }

    private VMTemplateVO getRestoreVirtualMachineTemplate(Account caller, Long newTemplateId, List<VolumeVO> rootVols, UserVmVO vm) {
        VMTemplateVO template = null;
        if (CollectionUtils.isNotEmpty(rootVols)) {
            VolumeVO root = rootVols.get(0);
            Long templateId = root.getTemplateId();
            boolean isISO = false;
            if (templateId == null) {
                isISO = true;
                templateId = vm.getIsoId();
            }
            if (newTemplateId != null) {
                template = _templateDao.findById(newTemplateId);
                _accountMgr.checkAccess(caller, null, true, template);
                if (isISO) {
                    if (!template.getFormat().equals(ImageFormat.ISO)) {
                        throw new InvalidParameterValueException("VM has been created using an ISO therefore it can not be re-installed with a template");
                    }
                } else if (template.getFormat().equals(ImageFormat.ISO)) {
                    throw new InvalidParameterValueException("Invalid template id provided to restore the VM ");
                }
            } else {
                if (isISO && templateId == null) {
                    throw new CloudRuntimeException("Cannot restore the VM since there is no ISO attached to VM");
                }
                template = _templateDao.findById(templateId);
                if (template == null) {
                    InvalidParameterValueException ex = new InvalidParameterValueException("Cannot find template/ISO for specified volumeid and vmId");
                    ex.addProxyObject(vm.getUuid(), "vmId");
                    ex.addProxyObject(root.getUuid(), "volumeId");
                    throw ex;
                }
            }
        }

        return template;
    }

    @Override
    public UserVm restoreVirtualMachine(Account caller, long vmId, Long newTemplateId, Long rootDiskOfferingId,
            boolean expunge, Map<String, String> details)
            throws InsufficientCapacityException, ResourceUnavailableException {
        Long userId = caller.getId();
        _userDao.findById(userId);
        UserVmVO vm = _vmDao.findById(vmId);
        Account owner = _accountDao.findById(vm.getAccountId());
        boolean needRestart = false;

        if (owner == null) {
            throw new InvalidParameterValueException("The owner of " + vm + " does not exist: " + vm.getAccountId());
        }

        if (owner.getState() == Account.State.DISABLED) {
            throw new PermissionDeniedException(String.format("The owner of %s is disabled: %s", vm, owner));
        }

        if (vm.getState() != VirtualMachine.State.Running && vm.getState() != VirtualMachine.State.Stopped) {
            throw new CloudRuntimeException("Vm " + vm.getUuid() + " currently in " + vm.getState() + " state, restore vm can only execute when VM in Running or Stopped");
        }

        if (vm.getState() == VirtualMachine.State.Running) {
            needRestart = true;
        }

        VMTemplateVO currentTemplate = _templateDao.findById(vm.getTemplateId());
        List<VolumeVO> rootVols = _volsDao.findByInstanceAndType(vmId, Volume.Type.ROOT);
        if (rootVols.isEmpty()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Can not find root volume for VM " + vm.getUuid());
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }
        if (rootVols.size() > 1 && currentTemplate != null && !currentTemplate.isDeployAsIs()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("There are " + rootVols.size() + " root volumes for VM " + vm.getUuid());
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        List<VMSnapshotVO> vmSnapshots = _vmSnapshotDao.findByVm(vmId);
        if (vmSnapshots.size() > 0) {
            throw new InvalidParameterValueException("Unable to restore Instance, please remove Instance Snapshots before restoring Instance");
        }

        VMTemplateVO template = getRestoreVirtualMachineTemplate(caller, newTemplateId, rootVols, vm);
        DiskOffering diskOffering = rootDiskOfferingId != null ? _diskOfferingDao.findById(rootDiskOfferingId) : null;

        List<Reserver> reservations = new ArrayList<>();
        try {
            checkRestoreVmFromTemplate(vm, template, rootVols, diskOffering, details, reservations);

            if (needRestart) {
                try {
                    _itMgr.stop(vm.getUuid());
                } catch (ResourceUnavailableException e) {
                    logger.debug("Stop vm {} failed", vm, e);
                    CloudRuntimeException ex = new CloudRuntimeException("Stop vm failed for specified vmId");
                    ex.addProxyObject(vm.getUuid(), "vmId");
                    throw ex;
                }
            }

            for (VolumeVO root : rootVols) {
                if (!Volume.State.Allocated.equals(root.getState()) || newTemplateId != null || diskOffering != null) {
                    _volumeService.validateDestroyVolume(root, caller, Volume.State.Allocated.equals(root.getState()) || expunge, false);
                    final UserVmVO userVm = vm;
                    Pair<UserVmVO, Volume> vmAndNewVol = Transaction.execute(new TransactionCallbackWithException<Pair<UserVmVO, Volume>, CloudRuntimeException>() {
                        @Override
                        public Pair<UserVmVO, Volume> doInTransaction(TransactionStatus status) throws CloudRuntimeException {
                            Long templateId = root.getTemplateId();
                            boolean isISO = false;
                            if (templateId == null) {
                                isISO = true;
                                templateId = userVm.getIsoId();
                            }

                            Volume newVol;
                            if (newTemplateId != null) {
                                if (isISO) {
                                    newVol = volumeMgr.allocateDuplicateVolume(root, diskOffering, null);
                                    userVm.setIsoId(newTemplateId);
                                    userVm.setGuestOSId(template.getGuestOSId());
                                    userVm.setTemplateId(newTemplateId);
                                } else {
                                    newVol = volumeMgr.allocateDuplicateVolume(root, diskOffering, newTemplateId);
                                    userVm.setGuestOSId(template.getGuestOSId());
                                    userVm.setTemplateId(newTemplateId);
                                }
                                updateVMDynamicallyScalabilityUsingTemplate(userVm, newTemplateId);
                            } else {
                                newVol = volumeMgr.allocateDuplicateVolume(root, diskOffering, null);
                            }

                            getRootVolumeSizeForVmRestore(newVol, template, userVm, diskOffering, details, true);
                            volumeMgr.saveVolumeDetails(newVol.getDiskOfferingId(), newVol.getId());
                            newVol = _volsDao.findById(newVol.getId());

                            try {
                                _resourceLimitMgr.incrementVolumeResourceCount(userVm.getAccountId(), newVol.isDisplay(),
                                        newVol.getSize(), diskOffering != null ? diskOffering : _diskOfferingDao.findById(newVol.getDiskOfferingId()));
                            } catch (CloudRuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                logger.error("Unable to restore VM {}", userVm, e);
                                throw new CloudRuntimeException(e);
                            }

                            UsageEventVO usageEvent = new UsageEventVO(EventTypes.EVENT_VOLUME_CREATE, newVol.getAccountId(), newVol.getDataCenterId(),
                                    newVol.getId(), newVol.getName(), newVol.getDiskOfferingId(), template.getId(), newVol.getSize());
                            _usageEventDao.persist(usageEvent);

                            return new Pair<>(userVm, newVol);
                        }
                    });

                    vm = vmAndNewVol.first();
                    Volume newVol = vmAndNewVol.second();

                    vmRootVolumeStorageCleanupService.cleanupRootVolumeOnManagedStorage(vm, root);

                    _volsDao.attachVolume(newVol.getId(), vmId, newVol.getDeviceId());
                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_ATTACH, newVol.getAccountId(), newVol.getDataCenterId(), newVol.getId(),
                            newVol.getName(), newVol.getDiskOfferingId(), newVol.getTemplateId(), newVol.getSize(), Volume.class.getName(),
                            newVol.getUuid(), vmId, newVol.isDisplay());

                    _volsDao.detachVolume(root.getId());
                    destroyVolumeInContext(vm, Volume.State.Allocated.equals(root.getState()) || expunge, root);

                    if (currentTemplate.getId() != template.getId() && VirtualMachine.Type.User.equals(vm.type)
                            && !VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
                        ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
                        _resourceLimitMgr.updateVmResourceCountForTemplateChange(vm.getAccountId(), vm.isDisplay(), serviceOffering, currentTemplate, template);
                    }

                    if (vm.getHypervisorType() == HypervisorType.VMware) {
                        VolumeInfo volumeInStorage = volFactory.getVolume(root.getId());
                        if (volumeInStorage != null) {
                            logger.info("Expunging volume {} from primary data store", root);
                            AsyncCallFuture<VolumeApiResult> future = _volService.expungeVolumeAsync(volFactory.getVolume(root.getId()));
                            try {
                                future.get();
                            } catch (Exception e) {
                                logger.debug("Failed to expunge volume: {}", root, e);
                            }
                        }
                    }
                }
            }

            Map<VirtualMachineProfile.Param, Object> params = null;
            String password = null;

            if (template.isEnablePassword()) {
                password = _mgr.generateRandomPassword();
                boolean result = vmPasswordSSHKeyResetService.resetVMPasswordInternal(vmId, password);
                if (!result) {
                    throw new CloudRuntimeException("VM reset is completed but failed to reset password for the virtual machine ");
                }
                vm.setPassword(password);
            }
            if (needRestart) {
                try {
                    if (Objects.nonNull(password)) {
                        params = new HashMap<>();
                        params.put(Param.VmPassword, password);
                    }
                    _itMgr.start(vm.getUuid(), params);
                    vm = _vmDao.findById(vmId);
                    if (template.isEnablePassword()) {
                        vm.setPassword(password);
                        if (vm.isUpdateParameters()) {
                            vm.setUpdateParameters(false);
                            _vmDao.loadDetails(vm);
                            if (vm.getDetail(VmDetailConstants.PASSWORD) != null) {
                                vmInstanceDetailsDao.removeDetail(vm.getId(), VmDetailConstants.PASSWORD);
                            }
                            _vmDao.update(vm.getId(), vm);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Unable to start VM " + vm.getUuid(), e);
                    CloudRuntimeException ex = new CloudRuntimeException("Unable to start VM with specified id" + e.getMessage());
                    ex.addProxyObject(vm.getUuid(), "vmId");
                    throw ex;
                }
            }

            logger.debug("Restore VM {} done successfully", vm);
            return vm;
        } catch (ResourceAllocationException e) {
            logger.error("Failed to restore VM {} due to {}", vm, e.getMessage(), e);
            throw new CloudRuntimeException("Failed to restore VM " + vm.getUuid() + " due to " + e.getMessage(), e);
        } finally {
            ReservationHelper.closeAll(reservations);
        }
    }

    @Override
    public Long getRootVolumeSizeForVmRestore(Volume vol, VMTemplateVO template, UserVmVO userVm, DiskOffering diskOffering,
            Map<String, String> details, boolean update) {
        VolumeVO resizedVolume = (VolumeVO) vol;
        Long size = null;
        if (template != null && template.getSize() != null) {
            VMInstanceDetailVO vmRootDiskSizeDetail = vmInstanceDetailsDao.findDetail(userVm.getId(), VmDetailConstants.ROOT_DISK_SIZE);
            if (vmRootDiskSizeDetail == null) {
                size = template.getSize();
            } else {
                long rootDiskSize = Long.parseLong(vmRootDiskSizeDetail.getValue()) * GiB_TO_BYTES;
                if (template.getSize() >= rootDiskSize) {
                    size = template.getSize();
                    if (update) {
                        vmInstanceDetailsDao.remove(vmRootDiskSizeDetail.getId());
                    }
                } else {
                    size = rootDiskSize;
                }
            }
            if (update) {
                resizedVolume.setSize(size);
            }
        }

        if (diskOffering != null) {
            if (update) {
                resizedVolume.setDiskOfferingId(diskOffering.getId());
            }
            if (!diskOffering.isCustomized()) {
                size = diskOffering.getDiskSize();
                if (update) {
                    resizedVolume.setSize(diskOffering.getDiskSize());
                }
            }

            if (update) {
                if (diskOffering.getMinIops() != null) {
                    resizedVolume.setMinIops(diskOffering.getMinIops());
                }
                if (diskOffering.getMaxIops() != null) {
                    resizedVolume.setMaxIops(diskOffering.getMaxIops());
                }
            }
        }

        if (MapUtils.isNotEmpty(details)) {
            if (StringUtils.isNumeric(details.get(VmDetailConstants.ROOT_DISK_SIZE))) {
                Long rootDiskSize = Long.parseLong(details.get(VmDetailConstants.ROOT_DISK_SIZE)) * GiB_TO_BYTES;
                size = rootDiskSize;
                if (update) {
                    resizedVolume.setSize(rootDiskSize);
                }
                VMInstanceDetailVO vmRootDiskSizeDetail = vmInstanceDetailsDao.findDetail(userVm.getId(), VmDetailConstants.ROOT_DISK_SIZE);
                if (update) {
                    if (vmRootDiskSizeDetail != null) {
                        vmRootDiskSizeDetail.setValue(details.get(VmDetailConstants.ROOT_DISK_SIZE));
                        vmInstanceDetailsDao.update(vmRootDiskSizeDetail.getId(), vmRootDiskSizeDetail);
                    } else {
                        vmInstanceDetailsDao.persist(new VMInstanceDetailVO(userVm.getId(), VmDetailConstants.ROOT_DISK_SIZE,
                                details.get(VmDetailConstants.ROOT_DISK_SIZE), true));
                    }
                }
            }
            if (update) {
                String minIops = details.get(MIN_IOPS);
                String maxIops = details.get(MAX_IOPS);

                if (StringUtils.isNumeric(minIops)) {
                    resizedVolume.setMinIops(Long.parseLong(minIops));
                }
                if (StringUtils.isNumeric(maxIops)) {
                    resizedVolume.setMinIops(Long.parseLong(maxIops));
                }
            }
        }
        if (update) {
            _volsDao.update(resizedVolume.getId(), resizedVolume);
        }
        return size;
    }

    private void updateVMDynamicallyScalabilityUsingTemplate(UserVmVO vm, Long newTemplateId) {
        ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getServiceOfferingId());
        VMTemplateVO newTemplate = _templateDao.findById(newTemplateId);
        boolean dynamicScalingEnabled = checkIfDynamicScalingCanBeEnabled(vm, serviceOffering, newTemplate, vm.getDataCenterId());
        vm.setDynamicallyScalable(dynamicScalingEnabled);
        _vmDao.update(vm.getId(), vm);
    }

    private void checkRestoreVmFromTemplate(UserVmVO vm, VMTemplateVO template, List<VolumeVO> rootVolumes,
            DiskOffering newDiskOffering, Map<String, String> details, List<Reserver> reservations)
            throws ResourceAllocationException {
        TemplateDataStoreVO tmplStore;
        if (!template.isDirectDownload()) {
            tmplStore = _templateStoreDao.findByTemplateZoneReady(template.getId(), vm.getDataCenterId());
            if (tmplStore == null) {
                throw new InvalidParameterValueException("Cannot restore the vm as the template " + template.getUuid() + " isn't available in the zone");
            }
        } else {
            tmplStore = _templateStoreDao.findByTemplate(template.getId(), DataStoreRole.Image);
            if (tmplStore == null || !tmplStore.getDownloadState().equals(VMTemplateStorageResourceAssoc.Status.BYPASSED)) {
                throw new InvalidParameterValueException("Cannot restore the vm as the bypassed template " + template.getUuid() + " isn't available in the zone");
            }
        }

        AccountVO owner = _accountDao.findByIdIncludingRemoved(vm.getAccountId());
        if (vm.getTemplateId() != template.getId()) {
            ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
            VMTemplateVO currentTemplate = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());
            _resourceLimitMgr.checkVmResourceLimitsForTemplateChange(owner, vm.isDisplay(), serviceOffering, currentTemplate, template, reservations);
        }

        for (Volume vol : rootVolumes) {
            Long newSize = getRootVolumeSizeForVmRestore(vol, template, vm, newDiskOffering, details, false);
            if (newSize == null) {
                newSize = vol.getSize();
            }
            if (newDiskOffering != null || !vol.getSize().equals(newSize)) {
                DiskOffering currentOffering = _diskOfferingDao.findById(vol.getDiskOfferingId());
                _resourceLimitMgr.checkVolumeResourceLimitForDiskOfferingChange(owner, vol.isDisplay(), vol.getSize(), newSize,
                        currentOffering, newDiskOffering, reservations);
            }
        }
    }

    private boolean checkIfDynamicScalingCanBeEnabled(VirtualMachine vm, com.cloud.offering.ServiceOffering offering,
            com.cloud.template.VirtualMachineTemplate template, Long zoneId) {
        boolean canEnableDynamicScaling = (vm != null ? vm.isDynamicallyScalable() : true)
                && offering.isDynamicScalingEnabled() && template.isDynamicallyScalable()
                && UserVmManager.EnableDynamicallyScaleVm.valueIn(zoneId);
        if (!canEnableDynamicScaling) {
            logger.info("VM cannot be configured to be dynamically scalable if any of the service offering's dynamic scaling property, template's dynamic scaling property or global setting is false");
        }

        return canEnableDynamicScaling;
    }

    private void destroyVolumeInContext(UserVmVO vm, boolean expunge, VolumeVO volume) {
        CallContext volumeContext = CallContext.register(CallContext.current(), ApiCommandResourceType.Volume);
        volumeContext.setEventDetails("Volume Type: " + volume.getVolumeType() + " Volume ID: " + volume.getUuid() + " Instance ID: " + vm.getUuid());
        volumeContext.setEventResourceType(ApiCommandResourceType.Volume);
        volumeContext.setEventResourceId(volume.getId());
        try {
            Volume result = _volumeService.destroyVolume(volume.getId(), CallContext.current().getCallingAccount(), expunge, false);

            if (result == null) {
                logger.error("DestroyVM remove volume - failed to delete volume {} from instance {}", volume, vm);
            }
        } finally {
            CallContext.unregister();
        }
    }
}
