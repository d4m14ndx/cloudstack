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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.event.UsageEventVO;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.ScopeType;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class VmServiceOfferingUpgradeManagerImplTest {

    @Mock private VolumeDao volumeDao;
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock private VMTemplateDao templateDao;
    @Mock private ServiceOfferingDao serviceOfferingDao;
    @Mock private EntityManager entityMgr;
    @Mock private UserVmManager userVmManager;

    @InjectMocks
    private VmServiceOfferingUpgradeManagerImpl manager;

    private static final long VM_ID = 42L;
    private static final long OLD_OFFERING_ID = 100L;
    private static final long NEW_OFFERING_ID = 200L;
    private static final long POOL_ID = 11L;
    private static final long TEMPLATE_ID = 7L;
    private static final long ZONE_ID = 3L;

    // ---- isRootVolumeOnLocalStorage ----

    @Test
    public void isRootVolumeOnLocalStorageReturnsTrueForHostScope() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getPoolId()).thenReturn(POOL_ID);
        when(volumeDao.findByInstanceAndType(eq(VM_ID), any())).thenReturn(Collections.singletonList(volume));
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getScope()).thenReturn(ScopeType.HOST);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(pool);

        assertTrue(manager.isRootVolumeOnLocalStorage(VM_ID));
    }

    @Test
    public void isRootVolumeOnLocalStorageReturnsFalseForClusterScope() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getPoolId()).thenReturn(POOL_ID);
        when(volumeDao.findByInstanceAndType(eq(VM_ID), any())).thenReturn(Collections.singletonList(volume));
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getScope()).thenReturn(ScopeType.CLUSTER);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(pool);

        assertFalse(manager.isRootVolumeOnLocalStorage(VM_ID));
    }

    @Test
    public void isRootVolumeOnLocalStorageReturnsFalseForZoneScope() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getPoolId()).thenReturn(POOL_ID);
        when(volumeDao.findByInstanceAndType(eq(VM_ID), any())).thenReturn(Collections.singletonList(volume));
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getScope()).thenReturn(ScopeType.ZONE);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(pool);

        assertFalse(manager.isRootVolumeOnLocalStorage(VM_ID));
    }

    @Test
    public void isRootVolumeOnLocalStorageReturnsFalseWhenNoRootVolumeExists() {
        when(volumeDao.findByInstanceAndType(eq(VM_ID), any())).thenReturn(Collections.emptyList());

        assertFalse(manager.isRootVolumeOnLocalStorage(VM_ID));
        verify(storagePoolDao, never()).findById(anyLong());
    }

    @Test
    public void isRootVolumeOnLocalStorageReturnsFalseWhenRootVolumeHasNoPool() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getPoolId()).thenReturn(null);
        when(volumeDao.findByInstanceAndType(eq(VM_ID), any())).thenReturn(Collections.singletonList(volume));

        assertFalse(manager.isRootVolumeOnLocalStorage(VM_ID));
        verify(storagePoolDao, never()).findById(anyLong());
    }

    // ---- upgradeVmDb ----

    private VMInstanceVO mockVmForUpgrade() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(vm.getDataCenterId()).thenReturn(ZONE_ID);
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        return vm;
    }

    private ServiceOffering newOffering(long id, boolean dynamic, boolean haEnabled, boolean limitCpu) {
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getId()).thenReturn(id);
        when(offering.isDynamic()).thenReturn(dynamic);
        when(offering.isOfferHA()).thenReturn(haEnabled);
        when(offering.getLimitCpuUse()).thenReturn(limitCpu);
        return offering;
    }

    @Test
    public void upgradeVmDbMirrorsHaAndLimitCpuFlagsFromEntityManagerLookup() {
        VMInstanceVO vm = mockVmForUpgrade();
        ServiceOffering newOff = newOffering(NEW_OFFERING_ID, false, true, true);
        ServiceOffering currentOff = newOffering(OLD_OFFERING_ID, false, false, false);
        ServiceOffering resolvedNew = newOffering(NEW_OFFERING_ID, false, true, true);
        when(entityMgr.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(resolvedNew);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(mock(VMTemplateVO.class));
        when(userVmManager.checkIfDynamicScalingCanBeEnabled(any(), any(), any(), anyLong())).thenReturn(true);
        when(vmInstanceDao.update(eq(VM_ID), any())).thenReturn(true);

        assertTrue(manager.upgradeVmDb(VM_ID, newOff, currentOff));

        // setServiceOfferingId is invoked twice: once with the input id and again
        // after the entityMgr lookup mirrors flags into the local VO.
        verify(vm, times(2)).setServiceOfferingId(NEW_OFFERING_ID);
        verify(vm).setHaEnabled(true);
        verify(vm).setLimitCpuUse(true);
        verify(vm).setDynamicallyScalable(true);
        verify(vmInstanceDao).update(VM_ID, vm);
    }

    @Test
    public void upgradeVmDbReflectsDynamicScalingDisabled() {
        VMInstanceVO vm = mockVmForUpgrade();
        ServiceOffering newOff = newOffering(NEW_OFFERING_ID, false, false, false);
        ServiceOffering currentOff = newOffering(OLD_OFFERING_ID, false, false, false);
        when(entityMgr.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(newOff);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(mock(VMTemplateVO.class));
        when(userVmManager.checkIfDynamicScalingCanBeEnabled(any(), any(), any(), anyLong())).thenReturn(false);
        when(vmInstanceDao.update(eq(VM_ID), any())).thenReturn(true);

        manager.upgradeVmDb(VM_ID, newOff, currentOff);

        verify(vm).setDynamicallyScalable(false);
    }

    @Test
    public void upgradeVmDbSavesCustomDetailsWhenNewOfferingIsDynamic() {
        mockVmForUpgrade();
        ServiceOffering newOff = newOffering(NEW_OFFERING_ID, true, false, false);
        when(newOff.getCpu()).thenReturn(2);
        when(newOff.getSpeed()).thenReturn(1000);
        when(newOff.getRamSize()).thenReturn(4096);
        ServiceOffering currentOff = newOffering(OLD_OFFERING_ID, false, false, false);
        when(entityMgr.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(newOff);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(mock(VMTemplateVO.class));
        when(userVmManager.checkIfDynamicScalingCanBeEnabled(any(), any(), any(), anyLong())).thenReturn(true);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(new HashMap<>());
        ServiceOfferingVO unfilled = mock(ServiceOfferingVO.class);
        when(unfilled.getCpu()).thenReturn(null);
        when(unfilled.getSpeed()).thenReturn(null);
        when(unfilled.getRamSize()).thenReturn(null);
        when(serviceOfferingDao.findByIdIncludingRemoved(NEW_OFFERING_ID)).thenReturn(unfilled);
        when(vmInstanceDao.update(eq(VM_ID), any())).thenReturn(true);

        manager.upgradeVmDb(VM_ID, newOff, currentOff);

        ArgumentCaptor<List<VMInstanceDetailVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(vmInstanceDetailsDao).saveDetails(captor.capture());
        List<VMInstanceDetailVO> saved = captor.getValue();
        assertEquals(3, saved.size());
    }

    @Test
    public void upgradeVmDbRemovesCustomDetailsWhenDowngradingFromDynamicToStatic() {
        mockVmForUpgrade();
        ServiceOffering newOff = newOffering(NEW_OFFERING_ID, false, false, false);
        ServiceOffering currentOff = newOffering(OLD_OFFERING_ID, true, false, false);
        when(entityMgr.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(newOff);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(mock(VMTemplateVO.class));
        when(userVmManager.checkIfDynamicScalingCanBeEnabled(any(), any(), any(), anyLong())).thenReturn(false);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(new HashMap<>(Map.of("other", "v")));
        when(vmInstanceDao.update(eq(VM_ID), any())).thenReturn(true);

        manager.upgradeVmDb(VM_ID, newOff, currentOff);

        ArgumentCaptor<List<VMInstanceDetailVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(vmInstanceDetailsDao).saveDetails(captor.capture());
        List<VMInstanceDetailVO> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals("other", saved.get(0).getName());
    }

    @Test
    public void upgradeVmDbSkipsCustomDetailsHandlingWhenBothOfferingsStatic() {
        mockVmForUpgrade();
        ServiceOffering newOff = newOffering(NEW_OFFERING_ID, false, false, false);
        ServiceOffering currentOff = newOffering(OLD_OFFERING_ID, false, false, false);
        when(entityMgr.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(newOff);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(mock(VMTemplateVO.class));
        when(userVmManager.checkIfDynamicScalingCanBeEnabled(any(), any(), any(), anyLong())).thenReturn(false);
        when(vmInstanceDao.update(eq(VM_ID), any())).thenReturn(true);

        manager.upgradeVmDb(VM_ID, newOff, currentOff);

        verify(vmInstanceDetailsDao, never()).listDetailsKeyPairs(VM_ID);
        verify(vmInstanceDetailsDao, never()).saveDetails(any());
    }

    @Test
    public void upgradeVmDbPropagatesVmInstanceDaoUpdateResult() {
        mockVmForUpgrade();
        ServiceOffering newOff = newOffering(NEW_OFFERING_ID, false, false, false);
        ServiceOffering currentOff = newOffering(OLD_OFFERING_ID, false, false, false);
        when(entityMgr.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(newOff);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(mock(VMTemplateVO.class));
        when(userVmManager.checkIfDynamicScalingCanBeEnabled(any(), any(), any(), anyLong())).thenReturn(false);
        when(vmInstanceDao.update(eq(VM_ID), any())).thenReturn(false);

        assertFalse(manager.upgradeVmDb(VM_ID, newOff, currentOff));
    }

    // ---- removeCustomOfferingDetails ----

    @Test
    public void removeCustomOfferingDetailsStripsDynamicTrioAndPreservesOthers() {
        Map<String, String> details = new HashMap<>();
        details.put(UsageEventVO.DynamicParameters.cpuNumber.name(), "4");
        details.put(UsageEventVO.DynamicParameters.cpuSpeed.name(), "2000");
        details.put(UsageEventVO.DynamicParameters.memory.name(), "8192");
        details.put("keepMe", "yes");
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(details);

        manager.removeCustomOfferingDetails(VM_ID);

        ArgumentCaptor<List<VMInstanceDetailVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(vmInstanceDetailsDao).saveDetails(captor.capture());
        List<VMInstanceDetailVO> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals("keepMe", saved.get(0).getName());
        assertEquals("yes", saved.get(0).getValue());
    }

    @Test
    public void removeCustomOfferingDetailsWritesEmptyListWhenOnlyDynamicTrioPresent() {
        Map<String, String> details = new HashMap<>();
        details.put(UsageEventVO.DynamicParameters.cpuNumber.name(), "4");
        details.put(UsageEventVO.DynamicParameters.cpuSpeed.name(), "2000");
        details.put(UsageEventVO.DynamicParameters.memory.name(), "8192");
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(details);

        manager.removeCustomOfferingDetails(VM_ID);

        ArgumentCaptor<List<VMInstanceDetailVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(vmInstanceDetailsDao).saveDetails(captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    public void removeCustomOfferingDetailsHandlesEmptyMap() {
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(new HashMap<>());

        manager.removeCustomOfferingDetails(VM_ID);

        ArgumentCaptor<List<VMInstanceDetailVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(vmInstanceDetailsDao).saveDetails(captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    // ---- saveCustomOfferingDetails ----

    @Test
    public void saveCustomOfferingDetailsPersistsAllCustomFieldsWhenOfferingHasNoneFilled() {
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getId()).thenReturn(NEW_OFFERING_ID);
        when(offering.getCpu()).thenReturn(2);
        when(offering.getSpeed()).thenReturn(1500);
        when(offering.getRamSize()).thenReturn(4096);

        ServiceOfferingVO unfilled = mock(ServiceOfferingVO.class);
        when(unfilled.getCpu()).thenReturn(null);
        when(unfilled.getSpeed()).thenReturn(null);
        when(unfilled.getRamSize()).thenReturn(null);
        when(serviceOfferingDao.findByIdIncludingRemoved(NEW_OFFERING_ID)).thenReturn(unfilled);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(new HashMap<>());

        manager.saveCustomOfferingDetails(VM_ID, offering);

        ArgumentCaptor<List<VMInstanceDetailVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(vmInstanceDetailsDao).saveDetails(captor.capture());
        Map<String, String> saved = toMap(captor.getValue());
        assertEquals("2", saved.get(UsageEventVO.DynamicParameters.cpuNumber.name()));
        assertEquals("1500", saved.get(UsageEventVO.DynamicParameters.cpuSpeed.name()));
        assertEquals("4096", saved.get(UsageEventVO.DynamicParameters.memory.name()));
    }

    @Test
    public void saveCustomOfferingDetailsSkipsFieldsFilledOnUnderlyingOffering() {
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getId()).thenReturn(NEW_OFFERING_ID);
        when(offering.getCpu()).thenReturn(2);
        when(offering.getRamSize()).thenReturn(4096);

        ServiceOfferingVO unfilled = mock(ServiceOfferingVO.class);
        when(unfilled.getCpu()).thenReturn(null);
        when(unfilled.getSpeed()).thenReturn(1000);
        when(unfilled.getRamSize()).thenReturn(null);
        when(serviceOfferingDao.findByIdIncludingRemoved(NEW_OFFERING_ID)).thenReturn(unfilled);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(new HashMap<>());

        manager.saveCustomOfferingDetails(VM_ID, offering);

        ArgumentCaptor<List<VMInstanceDetailVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(vmInstanceDetailsDao).saveDetails(captor.capture());
        Map<String, String> saved = toMap(captor.getValue());
        assertEquals("2", saved.get(UsageEventVO.DynamicParameters.cpuNumber.name()));
        assertNull(saved.get(UsageEventVO.DynamicParameters.cpuSpeed.name()));
        assertEquals("4096", saved.get(UsageEventVO.DynamicParameters.memory.name()));
    }

    @Test
    public void saveCustomOfferingDetailsPreservesPreexistingNonCustomDetails() {
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getId()).thenReturn(NEW_OFFERING_ID);
        when(offering.getCpu()).thenReturn(4);
        when(offering.getSpeed()).thenReturn(2000);
        when(offering.getRamSize()).thenReturn(8192);

        ServiceOfferingVO unfilled = mock(ServiceOfferingVO.class);
        when(unfilled.getCpu()).thenReturn(null);
        when(unfilled.getSpeed()).thenReturn(null);
        when(unfilled.getRamSize()).thenReturn(null);
        when(serviceOfferingDao.findByIdIncludingRemoved(NEW_OFFERING_ID)).thenReturn(unfilled);
        Map<String, String> existing = new HashMap<>();
        existing.put("custom-tag", "foo");
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(existing);

        manager.saveCustomOfferingDetails(VM_ID, offering);

        ArgumentCaptor<List<VMInstanceDetailVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(vmInstanceDetailsDao).saveDetails(captor.capture());
        Map<String, String> saved = toMap(captor.getValue());
        assertEquals("foo", saved.get("custom-tag"));
        assertEquals("4", saved.get(UsageEventVO.DynamicParameters.cpuNumber.name()));
    }

    @Test
    public void saveCustomOfferingDetailsWritesNoDynamicTrioWhenAllFieldsFilledOnUnderlyingOffering() {
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getId()).thenReturn(NEW_OFFERING_ID);

        ServiceOfferingVO unfilled = mock(ServiceOfferingVO.class);
        when(unfilled.getCpu()).thenReturn(4);
        when(unfilled.getSpeed()).thenReturn(2000);
        when(unfilled.getRamSize()).thenReturn(4096);
        when(serviceOfferingDao.findByIdIncludingRemoved(NEW_OFFERING_ID)).thenReturn(unfilled);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(new HashMap<>());

        manager.saveCustomOfferingDetails(VM_ID, offering);

        ArgumentCaptor<List<VMInstanceDetailVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(vmInstanceDetailsDao).saveDetails(captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    private static Map<String, String> toMap(List<VMInstanceDetailVO> details) {
        Map<String, String> out = new HashMap<>();
        for (VMInstanceDetailVO d : details) {
            out.put(d.getName(), d.getValue());
        }
        return out;
    }
}
