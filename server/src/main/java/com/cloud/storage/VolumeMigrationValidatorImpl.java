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
package com.cloud.storage;

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.volume.MigrateVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine.State;

/**
 * Pre-flight validation helpers for the volume migration flow —
 * extracted from {@link VolumeApiServiceImpl}.
 *
 * @see VolumeMigrationValidator
 */
@Component
public class VolumeMigrationValidatorImpl implements VolumeMigrationValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(VolumeMigrationValidatorImpl.class);

    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private AccountManager accountManager;

    @Override
    public void checkVmStateForMigration(VMInstanceVO vm, VolumeVO vol) {
        List<State> suitableVmStatesForMigration = List.of(State.Stopped, State.Running, State.Shutdown);

        if (!suitableVmStatesForMigration.contains(vm.getState())) {
            LOGGER.debug(String.format(
                    "Unable to migrate volume: [%s] Id: [%s] because the VM: [%s] Id: [%s] is in state [%s], which is not supported for migration.",
                    vol.getName(), vol.getId(), vm.getInstanceName(), vm.getUuid(), vm.getState()
            ));

            throw new CloudRuntimeException(String.format(
                    "Volume migration is not allowed when the VM is in the %s state. Supported states are: %s.",
                    vm.getState(), suitableVmStatesForMigration
            ));
        }
    }

    @Override
    public boolean isSourceOrDestNotOnStorPool(StoragePoolVO storagePoolVO, StoragePoolVO destinationStoragePoolVo) {
        return storagePoolVO.getPoolType() != Storage.StoragePoolType.StorPool
                || destinationStoragePoolVo.getPoolType() != Storage.StoragePoolType.StorPool;
    }

    @Override
    public boolean isSourceAndDestOnStorPool(StoragePoolVO storagePoolVO, StoragePoolVO destinationStoragePoolVo) {
        return storagePoolVO.getPoolType() == Storage.StoragePoolType.StorPool
                && destinationStoragePoolVo.getPoolType() == Storage.StoragePoolType.StorPool;
    }

    @Override
    public DiskOfferingVO retrieveAndValidateNewDiskOffering(MigrateVolumeCmd cmd) {
        Long newDiskOfferingId = cmd.getNewDiskOfferingId();
        if (newDiskOfferingId == null) {
            return null;
        }
        DiskOfferingVO newDiskOffering = diskOfferingDao.findById(newDiskOfferingId);
        if (newDiskOffering == null) {
            throw new InvalidParameterValueException(String.format("The disk offering informed is not valid [id=%s].", newDiskOfferingId));
        }
        if (newDiskOffering.getRemoved() != null) {
            throw new InvalidParameterValueException(String.format("We cannot assign a removed disk offering [id=%s] to a volume. ", newDiskOffering.getUuid()));
        }
        Account caller = CallContext.current().getCallingAccount();
        DataCenter zone = null;
        Volume volume = volumeDao.findById(cmd.getId());
        if (volume == null) {
            throw new InvalidParameterValueException(String.format("Provided volume id is not valid: %s", cmd.getId()));
        }
        zone = dataCenterDao.findById(volume.getDataCenterId());

        accountManager.checkAccess(caller, newDiskOffering, zone);
        return newDiskOffering;
    }
}
