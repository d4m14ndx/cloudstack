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
package com.cloud.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.resource.ListCapacityCmd;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.alert.AlertManager;
import com.cloud.api.ApiDBUtils;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityVO;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.capacity.dao.CapacityDaoImpl.SummedCapacity;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host.Type;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.org.Grouping.AllocationState;
import com.cloud.storage.StorageManager;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * @see InfrastructureUsageService
 */
@Component
public class InfrastructureUsageServiceImpl implements InfrastructureUsageService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private CapacityDao capacityDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    private StoragePoolTagsDao storagePoolTagsDao;
    @Inject
    private ResourceLimitService resourceLimitService;
    @Inject
    private StorageManager storageMgr;
    @Inject
    private BackupManager backupManager;
    @Inject
    private AlertManager alertMgr;
    @Inject
    private AccountManager accountMgr;

    protected List<String> getResourceLimitTagsForCapacityListing() {
        List<String> tags = new ArrayList<>();
        tags.add(null);
        tags.addAll(resourceLimitService.getResourceLimitHostTags());
        tags.addAll(resourceLimitService.getResourceLimitStorageTags());
        tags = tags.stream().distinct().collect(Collectors.toList());
        return tags;
    }

    protected Pair<Boolean, List<Long>> getStoragePoolIdsForCapacityListing(Integer capacityType, String tag) {
        if (StringUtils.isEmpty(tag)) {
            return new Pair<>(true, null);
        }
        Short type = capacityType == null ? null : capacityType.shortValue();
        if (type != null && !Capacity.STORAGE_CAPACITY_TYPES.contains(type)) {
            return new Pair<>(false, null);
        }
        List<Long> storagePoolIds = storagePoolTagsDao.listPoolIdsByTag(tag);
        return new Pair<>(CollectionUtils.isNotEmpty(storagePoolIds), storagePoolIds);
    }

    protected Pair<Boolean, List<Long>> getHostIdsForCapacityListing(Long zoneId, Long podId, Long clusterId, Integer capacityType, String tag) {
        if (StringUtils.isEmpty(tag)) {
            return new Pair<>(true, null);
        }
        Short type = capacityType == null ? null : capacityType.shortValue();
        if (type != null && Capacity.STORAGE_CAPACITY_TYPES.contains(type)) {
            return new Pair<>(false, null);
        }
        List<Long> hostIds = null;
        try {
            List<HostVO> hosts = hostDao.listByHostTag(Type.Routing, clusterId, podId, zoneId, tag);
            hostIds = hosts.stream().map(HostVO::getId).collect(Collectors.toList());
        } catch (CloudRuntimeException ignored) {}
        return new Pair<>(CollectionUtils.isNotEmpty(hostIds), hostIds);
    }

    protected List<SummedCapacity> getCapacitiesWithDetails(final Long zoneId, final Long podId, Long clusterId,
            final Integer capacityType, final String tag, int level, Long pageSize) {
        List<String> tags = new ArrayList<>();
        if (StringUtils.isNotEmpty(tag)) {
            tags.add(tag);
        } else {
            tags = getResourceLimitTagsForCapacityListing();
        }
        List<SummedCapacity> summedCapacities = new ArrayList<>();
        for (String t : tags) {
            List<SummedCapacity> taggedSummedCapacities = new ArrayList<>();
            Pair<Boolean, List<Long>> hostIdsForCapacity = getHostIdsForCapacityListing(zoneId, podId, clusterId, capacityType, t);
            Pair<Boolean, List<Long>> storagePoolIdsForCapacity = getStoragePoolIdsForCapacityListing(capacityType, t);
            if (hostIdsForCapacity.first() || storagePoolIdsForCapacity.first()) {
                final List<SummedCapacity> summedHostCapacities = capacityDao.listCapacitiesGroupedByLevelAndType(
                        capacityType, zoneId, podId, clusterId, level, hostIdsForCapacity.second(),
                        storagePoolIdsForCapacity.second(), pageSize);
                if (summedHostCapacities != null) {
                    taggedSummedCapacities.addAll(summedHostCapacities);
                }
            }
            if (storagePoolIdsForCapacity.first()) {
                List<SummedCapacity> summedStorageCapacities = getStorageCapacities(clusterId, podId, zoneId,
                        storagePoolIdsForCapacity.second(), capacityType == null ? null : capacityType.shortValue());
                if (summedStorageCapacities != null) {
                    taggedSummedCapacities.addAll(summedStorageCapacities);
                }
            }
            taggedSummedCapacities.forEach(x -> x.setTag(t));
            summedCapacities.addAll(taggedSummedCapacities);
        }
        return summedCapacities;
    }

    @Override
    public List<CapacityVO> listTopConsumedResources(final ListCapacityCmd cmd) {

        final Integer capacityType = cmd.getType();
        Long zoneId = cmd.getZoneId();
        final Long podId = cmd.getPodId();
        final Long clusterId = cmd.getClusterId();
        final Boolean fetchLatest = cmd.getFetchLatest();
        final String tag = cmd.getTag();

        if (clusterId != null) {
            throw new InvalidParameterValueException("Currently clusterId param is not supported");
        }
        zoneId = accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), zoneId);

        if (fetchLatest != null && fetchLatest) {
            alertMgr.recalculateCapacity();
        }

        int level = 3;
        if (zoneId == null && podId == null) {// Group by Zone, capacity type
            level = 1;
        } else if (podId == null) {// Group by Pod, capacity type
            level = 2;
        }

        final List<CapacityVO> capacities = new ArrayList<>();
        List<SummedCapacity> summedCapacities = getCapacitiesWithDetails(zoneId, podId, clusterId, capacityType, tag, level, cmd.getPageSizeVal());

        // Sort Capacities
        summedCapacities.sort((arg0, arg1) -> {
            if (arg0.getPercentUsed() < arg1.getPercentUsed()) {
                return 1;
            } else if (arg0.getPercentUsed().equals(arg1.getPercentUsed())) {
                return 0;
            }
            return -1;
        });


        int pageSize;
        try {
            pageSize = Integer.parseInt(cmd.getPageSizeVal().toString());
        } catch (final IllegalArgumentException e) {
            throw new InvalidParameterValueException("pageSize " + cmd.getPageSizeVal() + " is out of Integer range is not supported for this call");
        }

        summedCapacities = summedCapacities.subList(0, summedCapacities.size() < cmd.getPageSizeVal() ? summedCapacities.size() : pageSize);
        for (final SummedCapacity summedCapacity : summedCapacities) {
            final CapacityVO capacity = new CapacityVO(summedCapacity.getDataCenterId(), summedCapacity.getPodId(), summedCapacity.getClusterId(), summedCapacity.getCapacityType(),
                    summedCapacity.getPercentUsed());
            capacity.setUsedCapacity(summedCapacity.getUsedCapacity() + summedCapacity.getReservedCapacity());
            capacity.setTotalCapacity(summedCapacity.getTotalCapacity());
            capacity.setTag(summedCapacity.getTag());
            capacities.add(capacity);
        }
        return capacities;
    }

    protected List<SummedCapacity> getStorageCapacities(Long clusterId, Long podId, Long zoneId, List<Long> poolIds, Short capacityType) {
        List<Short> capacityTypes = Arrays.asList(Capacity.CAPACITY_TYPE_STORAGE, Capacity.CAPACITY_TYPE_SECONDARY_STORAGE,
                Capacity.CAPACITY_TYPE_BACKUP_STORAGE, Capacity.CAPACITY_TYPE_OBJECT_STORAGE);
        if (capacityType != null && !capacityTypes.contains(capacityType)) {
            return null;
        }
        if (capacityType != null) {
            capacityTypes = capacityTypes.stream().filter(x -> x.equals(capacityType)).collect(Collectors.toList());
        }
        if (CollectionUtils.isNotEmpty(poolIds)) {
            capacityTypes = capacityTypes.stream().filter(x -> x == Capacity.CAPACITY_TYPE_STORAGE).collect(Collectors.toList());
        }
        if (CollectionUtils.isEmpty(capacityTypes)) {
            return null;
        }
        final List<SummedCapacity> list = new ArrayList<>();
        List<DataCenterVO> dcList = new ArrayList<>();
        if (zoneId != null) {
            final DataCenterVO zone = ApiDBUtils.findZoneById(zoneId);
            if (zone == null || zone.getAllocationState() == AllocationState.Disabled) {
                return null;
            }
            dcList.add(zone);
        } else {
            dcList = dcDao.listEnabledZones();
            podId = null;
            clusterId = null;
        }
        for (DataCenterVO dc : dcList) {
            List<CapacityVO> capacities = new ArrayList<>();
            if (capacityTypes.contains(Capacity.CAPACITY_TYPE_SECONDARY_STORAGE)) {
                capacities.add(storageMgr.getSecondaryStorageUsedStats(null, dc.getId()));
            }
            if (capacityTypes.contains(Capacity.CAPACITY_TYPE_STORAGE)) {
                capacities.add(storageMgr.getStoragePoolUsedStats(dc.getId(), podId, clusterId, poolIds));
            }
            if (capacityTypes.contains(Capacity.CAPACITY_TYPE_OBJECT_STORAGE)) {
                capacities.add(storageMgr.getObjectStorageUsedStats(dc.getId()));
            }
            if (capacityTypes.contains(Capacity.CAPACITY_TYPE_BACKUP_STORAGE)) {
                capacities.add((CapacityVO) backupManager.getBackupStorageUsedStats(dc.getId()));
            }
            for (CapacityVO capacity : capacities) {
                if (capacity.getTotalCapacity() != 0) {
                    capacity.setUsedPercentage((float)capacity.getUsedCapacity() / capacity.getTotalCapacity());
                } else {
                    capacity.setUsedPercentage(0);
                }
                SummedCapacity summedCapacity = new SummedCapacity(capacity.getUsedCapacity(), capacity.getTotalCapacity(), capacity.getUsedPercentage(), capacity.getCapacityType(),
                        capacity.getDataCenterId(), capacity.getPodId(), capacity.getClusterId());
                list.add(summedCapacity);
            }
        }// End of for
        return list;
    }

    protected void addZoneWideCapacitiesByType(final Integer capacityType, Long zId, List<CapacityVO> taggedCapacities) {
        if (capacityType == null) {
            taggedCapacities.add(storageMgr.getSecondaryStorageUsedStats(null, zId));
            taggedCapacities.add(storageMgr.getObjectStorageUsedStats(zId));
            taggedCapacities.add((CapacityVO) backupManager.getBackupStorageUsedStats(zId));
            return;
        }

        if (capacityType == Capacity.CAPACITY_TYPE_SECONDARY_STORAGE) {
            taggedCapacities.add(storageMgr.getSecondaryStorageUsedStats(null, zId));
        } else if (capacityType == Capacity.CAPACITY_TYPE_OBJECT_STORAGE) {
            taggedCapacities.add(storageMgr.getObjectStorageUsedStats(zId));
        } else if (capacityType == Capacity.CAPACITY_TYPE_BACKUP_STORAGE) {
            taggedCapacities.add((CapacityVO) backupManager.getBackupStorageUsedStats(zId));
        }
    }

    protected List<CapacityVO> listCapacitiesWithDetails(final Long zoneId, final Long podId, Long clusterId,
             final Integer capacityType, final String tag, List<Long> dcList) {
        List<String> tags = new ArrayList<>();
        if (StringUtils.isNotEmpty(tag)) {
            tags.add(tag);
        } else {
            tags = getResourceLimitTagsForCapacityListing();
        }
        List<CapacityVO> capacities = new ArrayList<>();
        for (String t : tags) {
            List<CapacityVO> taggedCapacities = new ArrayList<>();
            Pair<Boolean, List<Long>> hostIdsForCapacity = getHostIdsForCapacityListing(zoneId, podId, clusterId, capacityType, t);
            Pair<Boolean, List<Long>> storagePoolIdsForCapacity = getStoragePoolIdsForCapacityListing(capacityType, t);
            if (hostIdsForCapacity.first() || storagePoolIdsForCapacity.first()) {
                final List<SummedCapacity> summedCapacities = capacityDao.findFilteredCapacityBy(capacityType,
                        zoneId, podId, clusterId, hostIdsForCapacity.second(), storagePoolIdsForCapacity.second());

                for (final SummedCapacity summedCapacity : summedCapacities) {
                    final CapacityVO capacity = new CapacityVO(null, summedCapacity.getDataCenterId(), summedCapacity.getPodId(), summedCapacity.getClusterId(),
                            summedCapacity.getUsedCapacity() + summedCapacity.getReservedCapacity(), summedCapacity.getTotalCapacity(), summedCapacity.getCapacityType());
                    capacity.setAllocatedCapacity(summedCapacity.getAllocatedCapacity());
                    taggedCapacities.add(capacity);
                }
            }
            for (final Long zId : dcList) {
                // op_host_Capacity contains only allocated stats and the real time
                // stats are stored "in memory".
                // List secondary, object and backup storage capacities only when the api is invoked for the zone layer.
                if (podId == null && clusterId == null && StringUtils.isEmpty(t)) {
                    addZoneWideCapacitiesByType(capacityType, zId, taggedCapacities);
                }
                if ((capacityType == null || capacityType == Capacity.CAPACITY_TYPE_STORAGE) && storagePoolIdsForCapacity.first()) {
                    taggedCapacities.add(storageMgr.getStoragePoolUsedStats(zId, podId, clusterId, storagePoolIdsForCapacity.second()));
                }
            }
            taggedCapacities.forEach(x -> x.setTag(t));
            capacities.addAll(taggedCapacities);
        }
        return capacities;

    }

    @Override
    public List<CapacityVO> listCapacities(final ListCapacityCmd cmd) {

        final Integer capacityType = cmd.getType();
        Long zoneId = cmd.getZoneId();
        final Long podId = cmd.getPodId();
        final Long clusterId = cmd.getClusterId();
        final Boolean fetchLatest = cmd.getFetchLatest();
        final String tag = cmd.getTag();

        zoneId = accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), zoneId);
        if (fetchLatest != null && fetchLatest) {
            alertMgr.recalculateCapacity();
        }
        List<Long> dcList = new ArrayList<>();
        if (zoneId != null) {
            dcList.add(zoneId);
        } else {
            if (podId == null && clusterId == null) {
                dcList.addAll(ApiDBUtils.listZones().stream().map(DataCenterVO::getId).collect(Collectors.toList()));
            } if (clusterId != null) {
                dcList.add(ApiDBUtils.findClusterById(clusterId).getDataCenterId());
            } else if (podId != null) {
                dcList.add(ApiDBUtils.findPodById(podId).getDataCenterId());
            }
        }
        return listCapacitiesWithDetails(zoneId, podId, clusterId, capacityType, tag, dcList);
    }

    @Override
    public long getMemoryOrCpuCapacityByHost(final Long hostId, final short capacityType) {
        final CapacityVO capacity = capacityDao.findByHostIdType(hostId, capacityType);
        return capacity == null ? 0 : capacity.getReservedCapacity() + capacity.getUsedCapacity();
    }
}
