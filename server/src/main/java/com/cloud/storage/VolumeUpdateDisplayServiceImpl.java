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

import java.util.Collections;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.springframework.stereotype.Component;

import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;

@Component
public class VolumeUpdateDisplayServiceImpl implements VolumeUpdateDisplayService {

    @Inject
    protected AccountManager accountMgr;
    @Inject
    protected VolumeDao volsDao;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected ResourceLimitService resourceLimitMgr;
    @Inject
    protected DiskOfferingDao diskOfferingDao;

    @Override
    public Volume updateVolume(long volumeId, String path, String state, Long storageId,
                               Boolean displayVolume, Boolean deleteProtection,
                               String customId, long entityOwnerId, String chainInfo, String name) {

        Account caller = CallContext.current().getCallingAccount();
        if (!accountMgr.isRootAdmin(caller.getId())) {
            if (path != null || state != null || storageId != null || displayVolume != null || customId != null || chainInfo != null) {
                throw new InvalidParameterValueException("The domain admin and normal user are " +
                        "not allowed to update volume except volume name & delete protection");
            }
        }

        VolumeVO volume = volsDao.findById(volumeId);

        if (volume == null) {
            throw new InvalidParameterValueException("The volume id doesn't exist");
        }

        /* Does the caller have authority to act on this volume? */
        accountMgr.checkAccess(caller, null, true, volume);

        if (path != null) {
            volume.setPath(path);
        }

        if (chainInfo != null) {
            volume.setChainInfo(chainInfo);
        }

        if (state != null) {
            try {
                Volume.State volumeState = Volume.State.valueOf(state);
                volume.setState(volumeState);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Invalid volume state specified");
            }
        }

        if (storageId != null) {
            StoragePool pool = storagePoolDao.findById(storageId);
            if (pool.getDataCenterId() != volume.getDataCenterId()) {
                throw new InvalidParameterValueException("Invalid storageId specified; refers to the pool outside of the volume's zone");
            }
            if (pool.getPoolType() == Storage.StoragePoolType.DatastoreCluster) {
                List<StoragePoolVO> childDatastores = storagePoolDao.listChildStoragePoolsInDatastoreCluster(storageId);
                Collections.shuffle(childDatastores);
                volume.setPoolId(childDatastores.get(0).getId());
                volume.setPoolType(childDatastores.get(0).getPoolType());
            } else {
                volume.setPoolId(pool.getId());
                volume.setPoolType(pool.getPoolType());
            }
        }

        if (customId != null) {
            volume.setUuid(customId);
        }

        if (name != null) {
            volume.setName(name);
        }

        if (deleteProtection != null) {
            volume.setDeleteProtection(deleteProtection);
        }

        updateDisplay(volume, displayVolume);

        volsDao.update(volumeId, volume);

        return volume;
    }

    @Override
    public void updateDisplay(Volume volume, Boolean displayVolume) {
        // 1. Resource limit changes
        updateResourceCount(volume, displayVolume);

        // 2. generate usage event if not in destroyed state
        saveUsageEvent(volume, displayVolume);

        // 3. Set the flag
        if (displayVolume != null && displayVolume != volume.isDisplayVolume()) {
            // FIXME - Confused - typecast for now.
            ((VolumeVO)volume).setDisplayVolume(displayVolume);
            volsDao.update(volume.getId(), (VolumeVO)volume);
        }
    }

    private void updateResourceCount(Volume volume, Boolean displayVolume) {
        // Update only when the flag has changed.
        if (displayVolume != null && displayVolume != volume.isDisplayVolume()) {
            if (Boolean.FALSE.equals(displayVolume)) {
                resourceLimitMgr.decrementVolumeResourceCount(volume.getAccountId(), true, volume.getSize(), diskOfferingDao.findById(volume.getDiskOfferingId()));
            } else {
                resourceLimitMgr.incrementVolumeResourceCount(volume.getAccountId(), true, volume.getSize(), diskOfferingDao.findById(volume.getDiskOfferingId()));
            }
        }
    }

    private void saveUsageEvent(Volume volume, Boolean displayVolume) {

        // Update only when the flag has changed  &&  only when volume in a non-destroyed state.
        if ((displayVolume != null && displayVolume != volume.isDisplayVolume()) && !isVolumeDestroyed(volume)) {
            if (displayVolume) {
                // flag turned 1 equivalent to freshly created volume
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_CREATE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(), volume.getDiskOfferingId(),
                        volume.getTemplateId(), volume.getSize(), Volume.class.getName(), volume.getUuid(), volume.getInstanceId(), displayVolume);
            } else {
                // flag turned 0 equivalent to deleting a volume
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DELETE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(), Volume.class.getName(),
                        volume.getUuid());
            }
        }
    }

    private boolean isVolumeDestroyed(Volume volume) {
        if (volume.getState() == Volume.State.Destroy || volume.getState() == Volume.State.Expunging && volume.getState() == Volume.State.Expunged) {
            return true;
        }
        return false;
    }
}
