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
package com.cloud.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.response.ControlledEntityResponse;
import org.apache.cloudstack.api.response.ControlledViewEntityResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.api.response.SnapshotPolicyResponse;
import org.apache.cloudstack.api.response.SnapshotResponse;
import org.apache.cloudstack.api.response.SnapshotScheduleResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.VMSnapshotResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreCapabilities;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.domain.Domain;
import com.cloud.projects.Project;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.GuestOS;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.snapshot.SnapshotPolicy;
import com.cloud.storage.snapshot.SnapshotSchedule;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.snapshot.VMSnapshot;

@Component
public class ApiSnapshotResponseServiceImpl implements ApiSnapshotResponseService {

    protected Logger logger = LogManager.getLogger(ApiSnapshotResponseServiceImpl.class);

    @Inject
    protected AccountManager accountManager;
    @Inject
    protected AnnotationDao annotationDao;
    @Inject
    protected ResourceTagDao resourceTagDao;
    @Inject
    protected SnapshotDataFactory snapshotFactory;
    @Inject
    protected SnapshotDataStoreDao snapshotStoreDao;
    @Inject
    protected DataStoreManager dataStoreManager;

    @Override
    public SnapshotResponse createSnapshotResponse(Snapshot snapshot) {
        SnapshotResponse snapshotResponse = new SnapshotResponse();
        snapshotResponse.setId(snapshot.getUuid());

        populateOwner(snapshotResponse, snapshot);

        VolumeVO volume = ApiDBUtils.findVolumeById(snapshot.getVolumeId());
        String snapshotTypeStr = snapshot.getRecurringType().name();
        snapshotResponse.setSnapshotType(snapshotTypeStr);
        if (volume != null) {
            snapshotResponse.setVolumeId(volume.getUuid());
            snapshotResponse.setVolumeName(volume.getName());
            snapshotResponse.setVolumeType(volume.getVolumeType().name());
            snapshotResponse.setVolumeState(volume.getState().name());
            snapshotResponse.setVirtualSize(volume.getSize());
            DataCenter zone = ApiDBUtils.findZoneById(volume.getDataCenterId());
            if (zone != null) {
                snapshotResponse.setZoneId(zone.getUuid());
                snapshotResponse.setZoneName(zone.getName());
            }

            if (volume.getVolumeType() == Volume.Type.ROOT && volume.getInstanceId() != null) {
                VMInstanceVO instance = ApiDBUtils.findVMInstanceById(volume.getInstanceId());
                if (instance != null) {
                    GuestOS guestOs = ApiDBUtils.findGuestOSById(instance.getGuestOSId());
                    if (guestOs != null) {
                        snapshotResponse.setOsTypeId(guestOs.getUuid());
                        snapshotResponse.setOsDisplayName(guestOs.getDisplayName());
                    }
                }
            }
        }
        snapshotResponse.setCreated(snapshot.getCreated());
        snapshotResponse.setName(snapshot.getName());
        snapshotResponse.setIntervalType(ApiDBUtils.getSnapshotIntervalTypes(snapshot.getId()));
        snapshotResponse.setState(snapshot.getState());
        snapshotResponse.setLocationType(ApiDBUtils.getSnapshotLocationType(snapshot.getId()));

        SnapshotInfo snapshotInfo = null;

        if (snapshot instanceof SnapshotInfo) {
            snapshotInfo = (SnapshotInfo)snapshot;
        } else {
            DataStoreRole dataStoreRole = getDataStoreRole(snapshot, snapshotStoreDao, dataStoreManager);

            snapshotInfo = snapshotFactory.getSnapshotWithRoleAndZone(snapshot.getId(), dataStoreRole, volume.getDataCenterId());
        }

        if (snapshotInfo == null) {
            logger.debug("Unable to find info for image store snapshot with uuid " + snapshot.getUuid());
            snapshotResponse.setRevertable(false);
        } else {
            snapshotResponse.setRevertable(snapshotInfo.isRevertable());
            snapshotResponse.setPhysicalSize(snapshotInfo.getPhysicalSize());
        }

        List<? extends ResourceTag> tags = ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.Snapshot, snapshot.getId());
        List<ResourceTagResponse> tagResponses = new ArrayList<ResourceTagResponse>();
        for (ResourceTag tag : tags) {
            ResourceTagResponse tagResponse = createResourceTagResponse(tag, true);
            CollectionUtils.addIgnoreNull(tagResponses, tagResponse);
        }
        snapshotResponse.setTags(new HashSet<>(tagResponses));
        snapshotResponse.setHasAnnotation(annotationDao.hasAnnotations(snapshot.getUuid(), AnnotationService.EntityType.SNAPSHOT.name(),
                accountManager.isRootAdmin(CallContext.current().getCallingAccount().getId())));

        snapshotResponse.setObjectName("snapshot");
        return snapshotResponse;
    }

    public static DataStoreRole getDataStoreRole(Snapshot snapshot, SnapshotDataStoreDao snapshotStoreDao, DataStoreManager dataStoreMgr) {
        SnapshotDataStoreVO snapshotStore = snapshotStoreDao.findOneBySnapshotAndDatastoreRole(snapshot.getId(), DataStoreRole.Primary);

        if (snapshotStore == null) {
            return DataStoreRole.Image;
        }

        long storagePoolId = snapshotStore.getDataStoreId();
        DataStore dataStore = dataStoreMgr.getDataStore(storagePoolId, DataStoreRole.Primary);
        if (dataStore == null) {
            return DataStoreRole.Image;
        }

        Map<String, String> mapCapabilities = dataStore.getDriver().getCapabilities();

        if (mapCapabilities != null) {
            String value = mapCapabilities.get(DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT.toString());
            boolean supportsStorageSystemSnapshots = Boolean.getBoolean(value);

            if (supportsStorageSystemSnapshots) {
                return DataStoreRole.Primary;
            }
        }

        return DataStoreRole.Image;
    }

    @Override
    public VMSnapshotResponse createVMSnapshotResponse(VMSnapshot vmSnapshot) {
        VMSnapshotResponse vmSnapshotResponse = new VMSnapshotResponse();
        vmSnapshotResponse.setId(vmSnapshot.getUuid());
        vmSnapshotResponse.setName(vmSnapshot.getName());
        vmSnapshotResponse.setState(vmSnapshot.getState());
        vmSnapshotResponse.setCreated(vmSnapshot.getCreated());
        vmSnapshotResponse.setDescription(vmSnapshot.getDescription());
        vmSnapshotResponse.setDisplayName(vmSnapshot.getDisplayName());
        UserVm vm = ApiDBUtils.findUserVmById(vmSnapshot.getVmId());
        if (vm != null) {
            vmSnapshotResponse.setVirtualMachineId(vm.getUuid());
            vmSnapshotResponse.setVirtualMachineName(StringUtils.isEmpty(vm.getDisplayName()) ? vm.getHostName() : vm.getDisplayName());
            vmSnapshotResponse.setHypervisor(vm.getHypervisorType().getHypervisorDisplayName());
            DataCenterVO datacenter = ApiDBUtils.findZoneById(vm.getDataCenterId());
            if (datacenter != null) {
                vmSnapshotResponse.setZoneId(datacenter.getUuid());
                vmSnapshotResponse.setZoneName(datacenter.getName());
            }
        }
        if (vmSnapshot.getParent() != null) {
            VMSnapshot vmSnapshotParent = ApiDBUtils.getVMSnapshotById(vmSnapshot.getParent());
            if (vmSnapshotParent != null) {
                vmSnapshotResponse.setParent(vmSnapshotParent.getUuid());
                vmSnapshotResponse.setParentName(vmSnapshotParent.getDisplayName());
            }
        }
        populateOwner(vmSnapshotResponse, vmSnapshot);

        List<? extends ResourceTag> tags = resourceTagDao.listBy(vmSnapshot.getId(), ResourceObjectType.VMSnapshot);
        List<ResourceTagResponse> tagResponses = new ArrayList<ResourceTagResponse>();
        for (ResourceTag tag : tags) {
            ResourceTagResponse tagResponse = createResourceTagResponse(tag, false);
            CollectionUtils.addIgnoreNull(tagResponses, tagResponse);
        }
        vmSnapshotResponse.setTags(new HashSet<>(tagResponses));
        vmSnapshotResponse.setHasAnnotation(annotationDao.hasAnnotations(vmSnapshot.getUuid(), AnnotationService.EntityType.VM_SNAPSHOT.name(),
                accountManager.isRootAdmin(CallContext.current().getCallingAccount().getId())));

        vmSnapshotResponse.setCurrent(vmSnapshot.getCurrent());
        vmSnapshotResponse.setType(vmSnapshot.getType().toString());
        vmSnapshotResponse.setObjectName("vmsnapshot");
        return vmSnapshotResponse;
    }

    @Override
    public SnapshotPolicyResponse createSnapshotPolicyResponse(SnapshotPolicy policy) {
        SnapshotPolicyResponse policyResponse = new SnapshotPolicyResponse();
        policyResponse.setId(policy.getUuid());
        Volume vol = ApiDBUtils.findVolumeById(policy.getVolumeId());
        if (vol != null) {
            policyResponse.setVolumeId(vol.getUuid());
            policyResponse.setVolumeName(vol.getName());
        }
        policyResponse.setSchedule(policy.getSchedule());
        policyResponse.setIntervalType(policy.getInterval());
        policyResponse.setMaxSnaps(policy.getMaxSnaps());
        policyResponse.setTimezone(policy.getTimezone());
        policyResponse.setForDisplay(policy.isDisplay());
        policyResponse.setObjectName("snapshotpolicy");

        List<? extends ResourceTag> tags = resourceTagDao.listBy(policy.getId(), ResourceObjectType.SnapshotPolicy);
        List<ResourceTagResponse> tagResponses = new ArrayList<ResourceTagResponse>();
        for (ResourceTag tag : tags) {
            ResourceTagResponse tagResponse = createResourceTagResponse(tag, false);
            CollectionUtils.addIgnoreNull(tagResponses, tagResponse);
        }
        policyResponse.setTags(new HashSet<>(tagResponses));
        List<ZoneResponse> zoneResponses = new ArrayList<>();
        List<DataCenterVO> zones = ApiDBUtils.findSnapshotPolicyZones(policy, vol);
        for (DataCenterVO zone : zones) {
            ZoneResponse zoneResponse = new ZoneResponse();
            zoneResponse.setId(zone.getUuid());
            zoneResponse.setName(zone.getName());
            zoneResponse.setTags(null);
            zoneResponses.add(zoneResponse);
        }
        policyResponse.setZones(new HashSet<>(zoneResponses));
        List<StoragePoolResponse> poolResponses = new ArrayList<>();
        List<StoragePoolVO> pools = ApiDBUtils.findSnapshotPolicyPools(policy, vol);
        for (StoragePoolVO pool : pools) {
            StoragePoolResponse storagePoolResponse = new StoragePoolResponse();
            storagePoolResponse.setId(pool.getUuid());
            storagePoolResponse.setName(pool.getName());
            poolResponses.add(storagePoolResponse);
        }
        policyResponse.setStoragePools(new HashSet<>(poolResponses));

        return policyResponse;
    }

    @Override
    public SnapshotScheduleResponse createSnapshotScheduleResponse(SnapshotSchedule snapshotSchedule) {
        SnapshotScheduleResponse response = new SnapshotScheduleResponse();
        response.setId(snapshotSchedule.getUuid());
        if (snapshotSchedule.getVolumeId() != null) {
            Volume vol = ApiDBUtils.findVolumeById(snapshotSchedule.getVolumeId());
            if (vol != null) {
                response.setVolumeId(vol.getUuid());
            }
        }
        if (snapshotSchedule.getPolicyId() != null) {
            SnapshotPolicy policy = ApiDBUtils.findSnapshotPolicyById(snapshotSchedule.getPolicyId());
            if (policy != null) {
                response.setSnapshotPolicyId(policy.getUuid());
            }
        }
        response.setScheduled(snapshotSchedule.getScheduledTimestamp());

        response.setObjectName("snapshot");
        return response;
    }

    protected ResourceTagResponse createResourceTagResponse(ResourceTag resourceTag, boolean keyValueOnly) {
        ResourceTagJoinVO rto = ApiDBUtils.newResourceTagView(resourceTag);
        if (rto == null) {
            return null;
        }
        return ApiDBUtils.newResourceTagResponse(rto, keyValueOnly);
    }

    private void populateOwner(ControlledEntityResponse response, ControlledEntity object) {
        Account account = ApiDBUtils.findAccountById(object.getAccountId());

        if (account.getType() == Account.Type.PROJECT) {
            Project project = ApiDBUtils.findProjectByProjectAccountId(account.getId());
            response.setProjectId(project.getUuid());
            response.setProjectName(project.getName());
        } else {
            response.setAccountName(account.getAccountName());
        }
        populateDomain(response, object.getDomainId());
    }

    private void populateOwner(ControlledViewEntityResponse response, ControlledEntity object) {
        Account account = ApiDBUtils.findAccountById(object.getAccountId());

        if (account.getType() == Account.Type.PROJECT) {
            Project project = ApiDBUtils.findProjectByProjectAccountId(account.getId());
            response.setProjectId(project.getUuid());
            response.setProjectName(project.getName());
        } else {
            response.setAccountName(account.getAccountName());
        }

        populateDomain(response, object.getDomainId());
    }

    private void populateDomain(ControlledEntityResponse response, long domainId) {
        Domain domain = ApiDBUtils.findDomainById(domainId);
        if (domain == null) {
            return;
        }
        response.setDomainId(domain.getUuid());
        response.setDomainName(domain.getName());
        response.setDomainPath(getPrettyDomainPath(domain.getPath()));
    }

    private void populateDomain(ControlledViewEntityResponse response, long domainId) {
        Domain domain = ApiDBUtils.findDomainById(domainId);
        if (domain == null) {
            return;
        }
        response.setDomainId(domain.getUuid());
        response.setDomainName(domain.getName());
        response.setDomainPath(getPrettyDomainPath(domain.getPath()));
    }

    private String getPrettyDomainPath(String path) {
        if (path == null) {
            return null;
        }
        StringBuilder domainPath = new StringBuilder("ROOT");
        (domainPath.append(path)).deleteCharAt(domainPath.length() - 1);
        return domainPath.toString();
    }
}
