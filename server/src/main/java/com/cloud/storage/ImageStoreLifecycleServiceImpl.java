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

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.admin.storage.CreateSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteImageStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateImageStoreCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ImageStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateService;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.cpu.CPU;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.DiscoveryException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.router.VirtualNetworkApplianceManager;
import com.cloud.org.Grouping;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.upgrade.SystemVmTemplateRegistration;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Secondary image store and cache (staging) store lifecycle —
 * extracted from {@link StorageManagerImpl}.
 *
 * @see ImageStoreLifecycleService
 */
@Component
public class ImageStoreLifecycleServiceImpl implements ImageStoreLifecycleService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected ImageStoreDao imageStoreDao;
    @Inject
    protected ImageStoreDetailsDao imageStoreDetailsDao;
    @Inject
    protected DataStoreManager dataStoreMgr;
    @Inject
    protected DataStoreProviderManager dataStoreProviderMgr;
    @Inject
    protected DataCenterDao dcDao;
    @Inject
    protected AccountManager accountMgr;
    @Inject
    protected TemplateService imageSrv;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected VMTemplateDao vmTemplateDao;
    @Inject
    protected VMTemplateZoneDao vmTemplateZoneDao;
    @Inject
    protected TemplateDataStoreDao templateStoreDao;
    @Inject
    protected SnapshotDataStoreDao snapshotStoreDao;
    @Inject
    protected VolumeDataStoreDao volumeStoreDao;
    @Inject
    protected TemplateJoinDao templateViewDao;
    @Inject
    protected AnnotationDao annotationDao;
    @Inject
    protected ImageStoreDetailsUtil imageStoreDetailsUtil;

    @Override
    public ImageStore discoverImageStore(String name, String url, String providerName, Long zoneId, Map details) throws IllegalArgumentException, DiscoveryException, InvalidParameterValueException {
        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(providerName);

        if (storeProvider == null) {
            storeProvider = dataStoreProviderMgr.getDefaultImageDataStoreProvider();
            if (storeProvider == null) {
                throw new InvalidParameterValueException("can't find image store provider: " + providerName);
            }
            providerName = storeProvider.getName(); // ignored passed provider name and use default image store provider name
        }

        ScopeType scopeType = ScopeType.ZONE;
        if (zoneId == null) {
            scopeType = ScopeType.REGION;
        }

        if (name == null) {
            name = url;
        }

        ImageStoreVO imageStore = imageStoreDao.findByName(name);
        if (imageStore != null) {
            throw new InvalidParameterValueException("The image store with name " + name + " already exists, try creating with another name");
        }

        // check if scope is supported by store provider
        if (!((ImageStoreProvider)storeProvider).isScopeSupported(scopeType)) {
            throw new InvalidParameterValueException("Image store provider " + providerName + " does not support scope " + scopeType);
        }

        // check if we have already image stores from other different providers,
        // we currently are not supporting image stores from different
        // providers co-existing
        List<ImageStoreVO> imageStores = imageStoreDao.listImageStores();
        for (ImageStoreVO store : imageStores) {
            if (!store.getProviderName().equalsIgnoreCase(providerName)) {
                throw new InvalidParameterValueException("You can only add new image stores from the same provider " + store.getProviderName() + " already added");
            }
        }

        if (zoneId != null) {
            // Check if the zone exists in the system
            DataCenterVO zone = dcDao.findById(zoneId);
            if (zone == null) {
                throw new InvalidParameterValueException("Can't find zone by id " + zoneId);
            }

            Account account = CallContext.current().getCallingAccount();
            if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !accountMgr.isRootAdmin(account.getId())) {
                PermissionDeniedException ex = new PermissionDeniedException("Cannot perform this operation, Zone with specified id is currently disabled");
                ex.addProxyObject(zone.getUuid(), "dcId");
                throw ex;
            }
        }

        Map<String, Object> params = new HashMap<>();
        params.put("zoneId", zoneId);
        params.put("url", url);
        params.put("name", name);
        params.put("details", details);
        params.put("scope", scopeType);
        params.put("providerName", storeProvider.getName());
        params.put("role", DataStoreRole.Image);

        DataStoreLifeCycle lifeCycle = storeProvider.getDataStoreLifeCycle();

        DataStore store;
        try {
            store = lifeCycle.initialize(params);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to add data store: " + e.getMessage(), e);
            }
            throw new CloudRuntimeException("Failed to add data store: " + e.getMessage(), e);
        }

        if (((ImageStoreProvider)storeProvider).needDownloadSysTemplate()) {
            // trigger system vm template download
            imageSrv.downloadBootstrapSysTemplate(store);
        } else {
            // populate template_store_ref table
            imageSrv.addSystemVMTemplatesToSecondary(store);
            imageSrv.handleTemplateSync(store);
            registerSystemVmTemplateOnFirstNfsStore(zoneId, providerName, url, store);
        }

        // associate builtin template with zones associated with this image store
        associateCrosszoneTemplatesToZone(zoneId);

        // duplicate cache store records to region wide storage
        if (scopeType == ScopeType.REGION) {
            duplicateCacheStoreRecordsToRegionStore(store.getId());
        }

        return (ImageStore)dataStoreMgr.getDataStore(store.getId(), DataStoreRole.Image);
    }

    protected void registerSystemVmTemplateForHypervisorArch(final HypervisorType hypervisorType,
                 final CPU.CPUArch arch, final Long zoneId, final String url, final DataStore store,
                 final SystemVmTemplateRegistration systemVmTemplateRegistration, final String filePath,
                 final Pair<String, Long> storeUrlAndId, final String nfsVersion) {
        if (HypervisorType.Simulator.equals(hypervisorType)) {
            return;
        }
        String templateName = getValidTemplateName(zoneId, hypervisorType);
        VMTemplateVO registeredTemplate = systemVmTemplateRegistration.getRegisteredTemplate(templateName,
                hypervisorType, arch, url);
        TemplateDataStoreVO templateDataStoreVO = null;
        if (registeredTemplate != null) {
            templateDataStoreVO = templateStoreDao.findByStoreTemplate(store.getId(), registeredTemplate.getId());
            if (templateDataStoreVO != null) {
                try {
                    if (systemVmTemplateRegistration.validateIfSeeded(templateDataStoreVO, url,
                            templateDataStoreVO.getInstallPath(), nfsVersion)) {
                        return;
                    }
                } catch (Exception e) {
                    logger.error("Failed to validated if template is seeded", e);
                }
            }
        }
        SystemVmTemplateRegistration.mountStore(storeUrlAndId.first(), filePath, nfsVersion);
        if (registeredTemplate != null) {
            systemVmTemplateRegistration.validateAndAddTemplateToStore(registeredTemplate, templateDataStoreVO, zoneId,
                    storeUrlAndId.second(), filePath);
        } else {
            systemVmTemplateRegistration.validateAndRegisterNewTemplate(hypervisorType, arch, templateName, zoneId,
                    storeUrlAndId.second(), filePath);
        }
    }

    protected String getValidTemplateName(Long zoneId, HypervisorType hType) {
        String templateName = null;
        if (hType.equals(HypervisorType.KVM)) {
            templateName = VirtualNetworkApplianceManager.RouterTemplateKvm.valueIn(zoneId);
        } else if (hType.equals(HypervisorType.XenServer)) {
            templateName = VirtualNetworkApplianceManager.RouterTemplateXen.valueIn(zoneId);
        } else if (hType.equals(HypervisorType.VMware)) {
            templateName = VirtualNetworkApplianceManager.RouterTemplateVmware.valueIn(zoneId);
        } else if (hType.equals(HypervisorType.Hyperv)) {
            templateName = VirtualNetworkApplianceManager.RouterTemplateHyperV.valueIn(zoneId);
        } else if (hType.equals(HypervisorType.LXC)) {
            templateName = VirtualNetworkApplianceManager.RouterTemplateLxc.valueIn(zoneId);
        }
        return templateName;
    }

    protected void registerSystemVmTemplateOnFirstNfsStore(Long zoneId, String providerName, String url, DataStore store) {
        if (zoneId == null || !DataStoreProvider.NFS_IMAGE.equals(providerName)) {
            logger.debug("Skipping system VM template registration as either zoneId is null or {} " +
                    "provider is not NFS", store);
            return;
        }
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                List<ImageStoreVO> stores = imageStoreDao.listAllStoresInZoneExceptId(zoneId, providerName,
                        DataStoreRole.Image, store.getId());
                if (CollectionUtils.isEmpty(stores)) {
                    List<Pair<HypervisorType, CPU.CPUArch>> hypervisorArchTypes =
                            clusterDao.listDistinctHypervisorsAndArchExcludingExternalType(zoneId);
                    TransactionLegacy txn = TransactionLegacy.open("AutomaticTemplateRegister");
                    SystemVmTemplateRegistration systemVmTemplateRegistration = new SystemVmTemplateRegistration();
                    String filePath = null;
                    try {
                        filePath = Files.createTempDirectory(SystemVmTemplateRegistration.TEMPORARY_SECONDARY_STORE)
                                .toString();
                        Pair<String, Long> storeUrlAndId = new Pair<>(url, store.getId());
                        String nfsVersion = imageStoreDetailsUtil.getNfsVersion(store.getId());
                        for (Pair<HypervisorType, CPU.CPUArch> hypervisorArchType : hypervisorArchTypes) {
                            try {
                                registerSystemVmTemplateForHypervisorArch(hypervisorArchType.first(),
                                        hypervisorArchType.second(), zoneId, url, store,
                                        systemVmTemplateRegistration, filePath, storeUrlAndId, nfsVersion);
                            } catch (CloudRuntimeException e) {
                                SystemVmTemplateRegistration.unmountStore(filePath);
                                logger.error("Failed to register system VM template for hypervisor: {} {}",
                                        hypervisorArchType.first().name(), hypervisorArchType.second().name(), e);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Failed to register systemVM template(s) due to: ", e);
                    } finally {
                        SystemVmTemplateRegistration.unmountStore(filePath);
                        txn.close();
                    }
                }
            }
        });
    }

    @Override
    public ImageStore migrateToObjectStore(String name, String url, String providerName, Map<String, String> details) throws DiscoveryException, InvalidParameterValueException {
        // check if current cloud is ready to migrate, we only support cloud with only NFS secondary storages
        List<ImageStoreVO> imgStores = imageStoreDao.listImageStores();
        List<ImageStoreVO> nfsStores = new ArrayList<>();
        if (imgStores != null && imgStores.size() > 0) {
            for (ImageStoreVO store : imgStores) {
                if (!store.getProviderName().equals(DataStoreProvider.NFS_IMAGE)) {
                    throw new InvalidParameterValueException("We only support migrate NFS secondary storage to use object store!");
                } else {
                    nfsStores.add(store);
                }
            }
        }
        // convert all NFS secondary storage to staging store
        if (nfsStores != null && nfsStores.size() > 0) {
            for (ImageStoreVO store : nfsStores) {
                long storeId = store.getId();

                accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), store.getDataCenterId());

                DataStoreProvider provider = dataStoreProviderMgr.getDataStoreProvider(store.getProviderName());
                DataStoreLifeCycle lifeCycle = provider.getDataStoreLifeCycle();
                DataStore secStore = dataStoreMgr.getDataStore(storeId, DataStoreRole.Image);
                lifeCycle.migrateToObjectStore(secStore);
                // update store_role in template_store_ref and snapshot_store_ref to ImageCache
                templateStoreDao.updateStoreRoleToCachce(storeId);
                snapshotStoreDao.updateStoreRoleToCache(storeId);
            }
        }
        // add object store
        return discoverImageStore(name, url, providerName, null, details);
    }

    @Override
    public ImageStore updateImageStore(UpdateImageStoreCmd cmd) {
        return updateImageStoreStatus(cmd.getId(), cmd.getName(), cmd.getReadonly(), cmd.getCapacityBytes());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_UPDATE_IMAGE_STORE_ACCESS_STATE,
            eventDescription = "image store access updated")
    public ImageStore updateImageStoreStatus(Long id, String name, Boolean readonly, Long capacityBytes) {
        // Input validation
        ImageStoreVO imageStoreVO = imageStoreDao.findById(id);
        if (imageStoreVO == null) {
            throw new IllegalArgumentException("Unable to find image store with ID: " + id);
        }
        if (com.cloud.utils.StringUtils.isNotBlank(name)) {
            imageStoreVO.setName(name);
        }
        if (capacityBytes != null) {
            imageStoreVO.setTotalSize(capacityBytes);
        }
        if (readonly != null) {
            imageStoreVO.setReadonly(readonly);
        }
        imageStoreDao.update(id, imageStoreVO);
        return imageStoreVO;
    }

    @Override
    public ImageStore updateImageStoreStatus(Long id, Boolean readonly) {
        return updateImageStoreStatus(id, null, readonly, null);
    }

    protected void duplicateCacheStoreRecordsToRegionStore(long storeId) {
        templateStoreDao.duplicateCacheRecordsOnRegionStore(storeId);
        snapshotStoreDao.duplicateCacheRecordsOnRegionStore(storeId);
        volumeStoreDao.duplicateCacheRecordsOnRegionStore(storeId);
    }

    protected void associateCrosszoneTemplatesToZone(Long zoneId) {
        VMTemplateZoneVO tmpltZone;

        List<VMTemplateVO> allTemplates = vmTemplateDao.listAll();
        List<Long> dcIds = new ArrayList<>();
        if (zoneId != null) {
            dcIds.add(zoneId);
        } else {
            List<DataCenterVO> dcs = dcDao.listAll();
            if (dcs != null) {
                for (DataCenterVO dc : dcs) {
                    dcIds.add(dc.getId());
                }
            }
        }

        for (VMTemplateVO vt : allTemplates) {
            if (vt.isCrossZones()) {
                for (Long dcId : dcIds) {
                    tmpltZone = vmTemplateZoneDao.findByZoneTemplate(dcId, vt.getId());
                    if (tmpltZone == null) {
                        VMTemplateZoneVO vmTemplateZone = new VMTemplateZoneVO(dcId, vt.getId(), new Date());
                        vmTemplateZoneDao.persist(vmTemplateZone);
                    }
                }
            }
        }
    }

    @Override
    public boolean deleteImageStore(DeleteImageStoreCmd cmd) {
        final long storeId = cmd.getId();
        // Verify that image store exists
        ImageStoreVO store = imageStoreDao.findById(storeId);
        if (store == null) {
            throw new InvalidParameterValueException("Image store with id " + storeId + " doesn't exist");
        }
        accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), store.getDataCenterId());

        // Verify that there are no live snapshot, template, volume on the image
        // store to be deleted
        List<SnapshotDataStoreVO> snapshots = snapshotStoreDao.listByStoreId(storeId, DataStoreRole.Image);
        if (snapshots != null && snapshots.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete image store with active snapshots backup!");
        }
        List<VolumeDataStoreVO> volumes = volumeStoreDao.listByStoreId(storeId);
        if (volumes != null && volumes.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete image store with active volumes backup!");
        }

        // search if there are user templates stored on this image store, excluding system, builtin templates
        List<TemplateJoinVO> templates = templateViewDao.listActiveTemplates(storeId);
        if (templates != null && templates.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete image store with active Templates backup!");
        }

        // ready to delete
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // first delete from image_store_details table, we need to do that since
                // we are not actually deleting record from main
                // image_data_store table, so delete cascade will not work
                imageStoreDetailsDao.deleteDetails(storeId);
                snapshotStoreDao.deletePrimaryRecordsForStore(storeId, DataStoreRole.Image);
                volumeStoreDao.deletePrimaryRecordsForStore(storeId);
                templateStoreDao.deletePrimaryRecordsForStore(storeId);
                annotationDao.removeByEntityType(AnnotationService.EntityType.SECONDARY_STORAGE.name(), store.getUuid());
                imageStoreDao.remove(storeId);
            }
        });

        return true;
    }

    @Override
    public ImageStore createSecondaryStagingStore(CreateSecondaryStagingStoreCmd cmd) {
        String providerName = cmd.getProviderName();
        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(providerName);

        if (storeProvider == null) {
            storeProvider = dataStoreProviderMgr.getDefaultCacheDataStoreProvider();
            if (storeProvider == null) {
                throw new InvalidParameterValueException("can't find cache store provider: " + providerName);
            }
        }

        Long dcId = cmd.getZoneId();

        ScopeType scopeType = null;
        String scope = cmd.getScope();
        if (scope != null) {
            try {
                scopeType = Enum.valueOf(ScopeType.class, scope.toUpperCase());

            } catch (Exception e) {
                throw new InvalidParameterValueException("invalid scope for cache store " + scope);
            }

            if (scopeType != ScopeType.ZONE) {
                throw new InvalidParameterValueException("Only zone wide cache storage is supported");
            }
        }

        if (scopeType == ScopeType.ZONE && dcId == null) {
            throw new InvalidParameterValueException("zone id can't be null, if scope is zone");
        }

        // Check if the zone exists in the system
        DataCenterVO zone = dcDao.findById(dcId);
        if (zone == null) {
            throw new InvalidParameterValueException("Can't find zone by id " + dcId);
        }

        Account account = CallContext.current().getCallingAccount();
        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !accountMgr.isRootAdmin(account.getId())) {
            PermissionDeniedException ex = new PermissionDeniedException("Cannot perform this operation, Zone with specified id is currently disabled");
            ex.addProxyObject(zone.getUuid(), "dcId");
            throw ex;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("zoneId", dcId);
        params.put("url", cmd.getUrl());
        params.put("name", cmd.getUrl());
        params.put("details", cmd.getDetails());
        params.put("scope", scopeType);
        params.put("providerName", storeProvider.getName());
        params.put("role", DataStoreRole.ImageCache);

        DataStoreLifeCycle lifeCycle = storeProvider.getDataStoreLifeCycle();
        DataStore store = null;
        try {
            store = lifeCycle.initialize(params);
        } catch (Exception e) {
            logger.debug("Failed to add data store: " + e.getMessage(), e);
            throw new CloudRuntimeException("Failed to add data store: " + e.getMessage(), e);
        }

        return (ImageStore)dataStoreMgr.getDataStore(store.getId(), DataStoreRole.ImageCache);
    }

    @Override
    public boolean deleteSecondaryStagingStore(DeleteSecondaryStagingStoreCmd cmd) {
        final long storeId = cmd.getId();
        // Verify that cache store exists
        ImageStoreVO store = imageStoreDao.findById(storeId);
        if (store == null) {
            throw new InvalidParameterValueException("Cache store with id " + storeId + " doesn't exist");
        }
        accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), store.getDataCenterId());

        // Verify that there are no live snapshot, template, volume on the cache
        // store that is currently referenced
        List<SnapshotDataStoreVO> snapshots = snapshotStoreDao.listActiveOnCache(storeId);
        if (snapshots != null && snapshots.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete cache store with staging snapshots currently in use!");
        }
        List<VolumeDataStoreVO> volumes = volumeStoreDao.listActiveOnCache(storeId);
        if (volumes != null && volumes.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete cache store with staging Volumes currently in use!");
        }

        List<TemplateDataStoreVO> templates = templateStoreDao.listActiveOnCache(storeId);
        if (templates != null && templates.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete cache store with staging Templates currently in use!");
        }

        // ready to delete
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                // first delete from image_store_details table, we need to do that since
                // we are not actually deleting record from main
                // image_data_store table, so delete cascade will not work
                imageStoreDetailsDao.deleteDetails(storeId);
                snapshotStoreDao.deletePrimaryRecordsForStore(storeId, DataStoreRole.ImageCache);
                volumeStoreDao.deletePrimaryRecordsForStore(storeId);
                templateStoreDao.deletePrimaryRecordsForStore(storeId);
                imageStoreDao.remove(storeId);
            }
        });

        return true;
    }
}
