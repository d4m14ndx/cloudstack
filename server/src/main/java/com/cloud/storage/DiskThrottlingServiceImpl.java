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

import jakarta.inject.Inject;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.storage.to.VolumeObjectTO;

import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.configuration.Config;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.DiskProfile;

/**
 * Disk throttling rate resolution — extracted from
 * {@link StorageManagerImpl}.
 *
 * @see DiskThrottlingService
 */
@Component
public class DiskThrottlingServiceImpl implements DiskThrottlingService {

    @Inject
    private ConfigurationDao configDao;
    @Inject
    private EntityManager entityMgr;

    // get bytesReadRate from service_offering, disk_offering and vm.disk.throttling.bytes_read_rate
    @Override
    public Long getDiskBytesReadRate(final ServiceOffering offering, final DiskOffering diskOffering) {
        if ((diskOffering != null) && (diskOffering.getBytesReadRate() != null) && (diskOffering.getBytesReadRate() > 0)) {
            return diskOffering.getBytesReadRate();
        } else if ((diskOffering != null) && (diskOffering.getBytesReadRate() != null) && (diskOffering.getBytesReadRate() > 0)) {
            return diskOffering.getBytesReadRate();
        } else {
            Long bytesReadRate = Long.parseLong(configDao.getValue(Config.VmDiskThrottlingBytesReadRate.key()));
            if ((bytesReadRate > 0) && ((offering == null) || (!offering.isSystemUse()))) {
                return bytesReadRate;
            }
        }
        return 0L;
    }

    // get bytesWriteRate from service_offering, disk_offering and vm.disk.throttling.bytes_write_rate
    @Override
    public Long getDiskBytesWriteRate(final ServiceOffering offering, final DiskOffering diskOffering) {
        if ((diskOffering != null) && (diskOffering.getBytesWriteRate() != null) && (diskOffering.getBytesWriteRate() > 0)) {
            return diskOffering.getBytesWriteRate();
        } else {
            Long bytesWriteRate = Long.parseLong(configDao.getValue(Config.VmDiskThrottlingBytesWriteRate.key()));
            if ((bytesWriteRate > 0) && ((offering == null) || (!offering.isSystemUse()))) {
                return bytesWriteRate;
            }
        }
        return 0L;
    }

    // get iopsReadRate from service_offering, disk_offering and vm.disk.throttling.iops_read_rate
    @Override
    public Long getDiskIopsReadRate(final ServiceOffering offering, final DiskOffering diskOffering) {
        if ((diskOffering != null) && (diskOffering.getIopsReadRate() != null) && (diskOffering.getIopsReadRate() > 0)) {
            return diskOffering.getIopsReadRate();
        } else {
            Long iopsReadRate = Long.parseLong(configDao.getValue(Config.VmDiskThrottlingIopsReadRate.key()));
            if ((iopsReadRate > 0) && ((offering == null) || (!offering.isSystemUse()))) {
                return iopsReadRate;
            }
        }
        return 0L;
    }

    // get iopsWriteRate from service_offering, disk_offering and vm.disk.throttling.iops_write_rate
    @Override
    public Long getDiskIopsWriteRate(final ServiceOffering offering, final DiskOffering diskOffering) {
        if ((diskOffering != null) && (diskOffering.getIopsWriteRate() != null) && (diskOffering.getIopsWriteRate() > 0)) {
            return diskOffering.getIopsWriteRate();
        } else {
            Long iopsWriteRate = Long.parseLong(configDao.getValue(Config.VmDiskThrottlingIopsWriteRate.key()));
            if ((iopsWriteRate > 0) && ((offering == null) || (!offering.isSystemUse()))) {
                return iopsWriteRate;
            }
        }
        return 0L;
    }

    @Override
    public void setDiskProfileThrottling(DiskProfile dskCh, final ServiceOffering offering, final DiskOffering diskOffering) {
        dskCh.setBytesReadRate(getDiskBytesReadRate(offering, diskOffering));
        dskCh.setBytesWriteRate(getDiskBytesWriteRate(offering, diskOffering));
        dskCh.setIopsReadRate(getDiskIopsReadRate(offering, diskOffering));
        dskCh.setIopsWriteRate(getDiskIopsWriteRate(offering, diskOffering));
    }

    @Override
    public DiskTO getDiskWithThrottling(final DataTO volTO, final Volume.Type volumeType, final long deviceId, final String path, final long offeringId, final long diskOfferingId) {
        DiskTO disk = null;
        if (volTO != null && volTO instanceof VolumeObjectTO) {
            VolumeObjectTO volumeTO = (VolumeObjectTO)volTO;
            ServiceOffering offering = entityMgr.findById(ServiceOffering.class, offeringId);
            DiskOffering diskOffering = entityMgr.findById(DiskOffering.class, diskOfferingId);
            if (volumeType == Volume.Type.ROOT) {
                setVolumeObjectTOThrottling(volumeTO, offering, diskOffering);
            } else {
                setVolumeObjectTOThrottling(volumeTO, null, diskOffering);
            }
            disk = new DiskTO(volumeTO, deviceId, path, volumeType);
        } else {
            disk = new DiskTO(volTO, deviceId, path, volumeType);
        }
        return disk;
    }

    private void setVolumeObjectTOThrottling(VolumeObjectTO volumeTO, final ServiceOffering offering, final DiskOffering diskOffering) {
        volumeTO.setBytesReadRate(getDiskBytesReadRate(offering, diskOffering));
        volumeTO.setBytesWriteRate(getDiskBytesWriteRate(offering, diskOffering));
        volumeTO.setIopsReadRate(getDiskIopsReadRate(offering, diskOffering));
        volumeTO.setIopsWriteRate(getDiskIopsWriteRate(offering, diskOffering));
    }
}
