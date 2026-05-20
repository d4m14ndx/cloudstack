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

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.as.AutoScaleManager;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.db.GlobalLock;
import com.cloud.vm.dao.UserVmDao;

@Component
public class VmExpungeOrchestrationServiceImpl implements VmExpungeOrchestrationService {

    private static final int DEFAULT_EXPUNGE_INTERVAL = 86400;
    private static final int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION = 3;

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private UserVmDao vmDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VirtualMachineManager virtualMachineManager;
    @Inject
    private BackupManager backupManager;
    @Inject
    private AutoScaleManager autoScaleManager;
    @Inject
    private VmExpungeResourceCleanupService vmExpungeResourceCleanupService;
    @Inject
    private VmExpungeFailureTransitionService vmExpungeFailureTransitionService;

    private int expungeInterval;
    private int expungeDelay;

    @Override
    public void configure(Map<String, String> configs) {
        String time = configs.get("expunge.interval");
        expungeInterval = NumbersUtil.parseInt(time, DEFAULT_EXPUNGE_INTERVAL);
        time = configs.get("expunge.delay");
        expungeDelay = NumbersUtil.parseInt(time, expungeInterval);
    }

    @Override
    public void scheduleExpungeTask(ScheduledExecutorService executor, ManagerOperations managerOperations) {
        executor.scheduleWithFixedDelay(new ExpungeTask(managerOperations), expungeInterval, expungeInterval, TimeUnit.SECONDS);
    }

    @Override
    public boolean expunge(UserVmVO vm) {
        vm = vmDao.acquireInLockTable(vm.getId());
        if (vm == null) {
            return false;
        }
        try {

            backupManager.checkAndRemoveBackupOfferingBeforeExpunge(vm);

            autoScaleManager.removeVmFromVmGroup(vm.getId());

            vmExpungeResourceCleanupService.releaseNetworkResourcesOnExpunge(vm.getId());

            List<VolumeVO> rootVol = volumeDao.findByInstanceAndType(vm.getId(), Volume.Type.ROOT);
            // expunge the vm
            virtualMachineManager.advanceExpunge(vm.getUuid());

            // Only if vm is not expunged already, cleanup it's resources
            if (vm.getRemoved() == null) {
                // Cleanup vm resources - all the PF/LB/StaticNat rules
                // associated with vm
                logger.debug("Starting cleaning up vm " + vm + " resources...");
                if (vmExpungeResourceCleanupService.cleanupVmResources(vm)) {
                    logger.debug("Successfully cleaned up vm " + vm + " resources as a part of expunge process");
                } else {
                    logger.warn("Failed to cleanup resources as a part of vm " + vm + " expunge");
                    return false;
                }

                if (vm.getUserDataId() != null) {
                    vm.setUserDataId(null);
                    vmDao.update(vm.getId(), vm);
                }

                vmDao.remove(vm.getId());
            }

            return true;

        } catch (ResourceUnavailableException e) {
            logger.warn("Unable to expunge  " + vm, e);
            return false;
        } catch (OperationTimedoutException e) {
            logger.warn("Operation time out on expunging " + vm, e);
            return false;
        } catch (ConcurrentOperationException e) {
            logger.warn("Concurrent operations on expunging " + vm, e);
            return false;
        } finally {
            vmDao.releaseFromLockTable(vm.getId());
        }
    }

    @Override
    public void transitionExpungingToError(long vmId) {
        vmExpungeFailureTransitionService.transitionExpungingToError(vmId);
    }

    protected void runScheduledExpunge(ManagerOperations managerOperations) {
        List<UserVmVO> vms = vmDao.findDestroyedVms(new Date(System.currentTimeMillis() - ((long)expungeDelay << 10)));
        if (logger.isInfoEnabled()) {
            if (vms.size() == 0) {
                logger.trace("Found " + vms.size() + " Instances to expunge.");
            } else {
                logger.info("Found " + vms.size() + " Instances to expunge.");
            }
        }
        for (UserVmVO vm : vms) {
            try {
                managerOperations.expungeVm(vm.getId());
            } catch (Exception e) {
                logger.warn("Unable to expunge " + vm, e);
            }
        }
    }

    private class ExpungeTask extends ManagedContextRunnable {
        private final ManagerOperations managerOperations;

        private ExpungeTask(ManagerOperations managerOperations) {
            this.managerOperations = managerOperations;
        }

        @Override
        protected void runInContext() {
            GlobalLock scanLock = GlobalLock.getInternLock("UserVMExpunge");
            try {
                if (scanLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                    try {
                        runScheduledExpunge(managerOperations);
                    } catch (Exception e) {
                        logger.error("Caught the following Exception", e);
                    } finally {
                        scanLock.unlock();
                    }
                }
            } finally {
                scanLock.releaseRef();
            }
        }
    }
}
