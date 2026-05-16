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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.eq;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.configuration.Config;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.DiskProfile;

/**
 * Focused tests for {@link DiskThrottlingServiceImpl} -- the Phase 4
 * extraction of disk-throttling-rate resolution out of
 * {@link StorageManagerImpl}.
 *
 * The same behavior is exercised through the manager's delegating
 * wrappers in {@link StorageManagerImplTest}; these tests target the
 * service directly so future refactors of the manager don't drop
 * coverage of the resolution rules.
 */
@RunWith(MockitoJUnitRunner.class)
public class DiskThrottlingServiceImplTest {

    @Mock
    private ConfigurationDao configDao;

    @Mock
    private EntityManager entityMgr;

    @InjectMocks
    private DiskThrottlingServiceImpl service;

    @Mock
    private ServiceOffering serviceOffering;

    @Mock
    private DiskOffering diskOffering;

    @Before
    public void setUp() {
        // Default: all global throttling keys return "0" (the production default).
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingBytesReadRate.key())).thenReturn("0");
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingBytesWriteRate.key())).thenReturn("0");
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingIopsReadRate.key())).thenReturn("0");
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingIopsWriteRate.key())).thenReturn("0");
    }

    // ---------------------------------------------------------------------
    // getDiskBytesReadRate
    // ---------------------------------------------------------------------

    @Test
    public void getDiskBytesReadRatePrefersDiskOfferingWhenSetAndPositive() {
        Mockito.when(diskOffering.getBytesReadRate()).thenReturn(1234L);

        Long actual = service.getDiskBytesReadRate(serviceOffering, diskOffering);

        assertEquals(Long.valueOf(1234L), actual);
        // Should not consult the global config when disk-offering already wins.
        Mockito.verify(configDao, Mockito.never()).getValue(Config.VmDiskThrottlingBytesReadRate.key());
    }

    @Test
    public void getDiskBytesReadRateFallsBackToGlobalConfigForUserVm() {
        Mockito.when(diskOffering.getBytesReadRate()).thenReturn(0L);
        Mockito.when(serviceOffering.isSystemUse()).thenReturn(false);
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingBytesReadRate.key())).thenReturn("500");

        Long actual = service.getDiskBytesReadRate(serviceOffering, diskOffering);

        assertEquals(Long.valueOf(500L), actual);
    }

    @Test
    public void getDiskBytesReadRateReturnsZeroForSystemVmEvenWhenGlobalSet() {
        // System VMs do NOT inherit the global default; result is 0.
        Mockito.when(diskOffering.getBytesReadRate()).thenReturn(null);
        Mockito.when(serviceOffering.isSystemUse()).thenReturn(true);
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingBytesReadRate.key())).thenReturn("500");

        Long actual = service.getDiskBytesReadRate(serviceOffering, diskOffering);

        assertEquals(Long.valueOf(0L), actual);
    }

    @Test
    public void getDiskBytesReadRateUsesGlobalWhenNoServiceOffering() {
        Mockito.when(diskOffering.getBytesReadRate()).thenReturn(null);
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingBytesReadRate.key())).thenReturn("750");

        Long actual = service.getDiskBytesReadRate(null, diskOffering);

        assertEquals(Long.valueOf(750L), actual);
    }

    @Test
    public void getDiskBytesReadRateReturnsZeroWhenDiskOfferingNullAndGlobalZero() {
        // diskOffering null exercises the null-guard branch; global is 0 by default.
        Long actual = service.getDiskBytesReadRate(serviceOffering, null);

        assertEquals(Long.valueOf(0L), actual);
    }

    // ---------------------------------------------------------------------
    // getDiskBytesWriteRate
    // ---------------------------------------------------------------------

    @Test
    public void getDiskBytesWriteRatePrefersDiskOffering() {
        Mockito.when(diskOffering.getBytesWriteRate()).thenReturn(4242L);

        assertEquals(Long.valueOf(4242L), service.getDiskBytesWriteRate(serviceOffering, diskOffering));
    }

    @Test
    public void getDiskBytesWriteRateFallsBackToGlobal() {
        Mockito.when(diskOffering.getBytesWriteRate()).thenReturn(0L);
        Mockito.when(serviceOffering.isSystemUse()).thenReturn(false);
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingBytesWriteRate.key())).thenReturn("800");

        assertEquals(Long.valueOf(800L), service.getDiskBytesWriteRate(serviceOffering, diskOffering));
    }

    // ---------------------------------------------------------------------
    // getDiskIopsReadRate
    // ---------------------------------------------------------------------

    @Test
    public void getDiskIopsReadRatePrefersDiskOffering() {
        Mockito.when(diskOffering.getIopsReadRate()).thenReturn(99L);

        assertEquals(Long.valueOf(99L), service.getDiskIopsReadRate(serviceOffering, diskOffering));
    }

    @Test
    public void getDiskIopsReadRateFallsBackToGlobalForUserVm() {
        Mockito.when(diskOffering.getIopsReadRate()).thenReturn(null);
        Mockito.when(serviceOffering.isSystemUse()).thenReturn(false);
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingIopsReadRate.key())).thenReturn("123");

        assertEquals(Long.valueOf(123L), service.getDiskIopsReadRate(serviceOffering, diskOffering));
    }

    @Test
    public void getDiskIopsReadRateReturnsZeroForSystemVmEvenWhenGlobalSet() {
        Mockito.when(diskOffering.getIopsReadRate()).thenReturn(null);
        Mockito.when(serviceOffering.isSystemUse()).thenReturn(true);
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingIopsReadRate.key())).thenReturn("500");

        assertEquals(Long.valueOf(0L), service.getDiskIopsReadRate(serviceOffering, diskOffering));
    }

    // ---------------------------------------------------------------------
    // getDiskIopsWriteRate
    // ---------------------------------------------------------------------

    @Test
    public void getDiskIopsWriteRatePrefersDiskOffering() {
        Mockito.when(diskOffering.getIopsWriteRate()).thenReturn(77L);

        assertEquals(Long.valueOf(77L), service.getDiskIopsWriteRate(serviceOffering, diskOffering));
    }

    @Test
    public void getDiskIopsWriteRateFallsBackToGlobalWhenNoServiceOffering() {
        Mockito.when(diskOffering.getIopsWriteRate()).thenReturn(0L);
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingIopsWriteRate.key())).thenReturn("42");

        assertEquals(Long.valueOf(42L), service.getDiskIopsWriteRate(null, diskOffering));
    }

    @Test
    public void getDiskIopsWriteRateReturnsZeroForSystemVm() {
        Mockito.when(diskOffering.getIopsWriteRate()).thenReturn(null);
        Mockito.when(serviceOffering.isSystemUse()).thenReturn(true);
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingIopsWriteRate.key())).thenReturn("999");

        assertEquals(Long.valueOf(0L), service.getDiskIopsWriteRate(serviceOffering, diskOffering));
    }

    // ---------------------------------------------------------------------
    // setDiskProfileThrottling
    // ---------------------------------------------------------------------

    @Test
    public void setDiskProfileThrottlingStampsAllFourRatesFromDiskOffering() {
        Mockito.when(diskOffering.getBytesReadRate()).thenReturn(10L);
        Mockito.when(diskOffering.getBytesWriteRate()).thenReturn(20L);
        Mockito.when(diskOffering.getIopsReadRate()).thenReturn(30L);
        Mockito.when(diskOffering.getIopsWriteRate()).thenReturn(40L);

        DiskProfile profile = Mockito.mock(DiskProfile.class);

        service.setDiskProfileThrottling(profile, serviceOffering, diskOffering);

        Mockito.verify(profile).setBytesReadRate(10L);
        Mockito.verify(profile).setBytesWriteRate(20L);
        Mockito.verify(profile).setIopsReadRate(30L);
        Mockito.verify(profile).setIopsWriteRate(40L);
    }

    @Test
    public void setDiskProfileThrottlingStampsZerosWhenNothingApplies() {
        // All disk-offering rates null and globals at 0 (the @Before default).
        DiskProfile profile = Mockito.mock(DiskProfile.class);

        service.setDiskProfileThrottling(profile, serviceOffering, diskOffering);

        Mockito.verify(profile).setBytesReadRate(0L);
        Mockito.verify(profile).setBytesWriteRate(0L);
        Mockito.verify(profile).setIopsReadRate(0L);
        Mockito.verify(profile).setIopsWriteRate(0L);
    }

    // ---------------------------------------------------------------------
    // getDiskWithThrottling
    // ---------------------------------------------------------------------

    @Test
    public void getDiskWithThrottlingNonVolumeObjectTOReturnsPlainDisk() {
        // When volTO is not a VolumeObjectTO we never touch the entityMgr and we
        // wrap the input directly without setting rates.
        DataTO plainTO = Mockito.mock(DataTO.class);

        DiskTO disk = service.getDiskWithThrottling(plainTO, Volume.Type.DATADISK, 1L, "/path", 7L, 8L);

        assertNotNull(disk);
        assertSame(plainTO, disk.getData());
        Mockito.verifyNoInteractions(entityMgr);
    }

    @Test
    public void getDiskWithThrottlingRootVolumeAppliesServiceAndDiskOffering() {
        VolumeObjectTO volumeTO = Mockito.mock(VolumeObjectTO.class);
        Mockito.when(entityMgr.findById(eq(ServiceOffering.class), eq(7L))).thenReturn(serviceOffering);
        Mockito.when(entityMgr.findById(eq(DiskOffering.class), eq(8L))).thenReturn(diskOffering);
        Mockito.when(diskOffering.getBytesReadRate()).thenReturn(11L);
        Mockito.when(diskOffering.getBytesWriteRate()).thenReturn(22L);
        Mockito.when(diskOffering.getIopsReadRate()).thenReturn(33L);
        Mockito.when(diskOffering.getIopsWriteRate()).thenReturn(44L);

        DiskTO disk = service.getDiskWithThrottling(volumeTO, Volume.Type.ROOT, 1L, "/p", 7L, 8L);

        assertNotNull(disk);
        Mockito.verify(volumeTO).setBytesReadRate(11L);
        Mockito.verify(volumeTO).setBytesWriteRate(22L);
        Mockito.verify(volumeTO).setIopsReadRate(33L);
        Mockito.verify(volumeTO).setIopsWriteRate(44L);
    }

    @Test
    public void getDiskWithThrottlingNonRootVolumeIgnoresServiceOfferingGlobal() {
        // For non-ROOT volumes we pass offering=null to the rate helpers, so a
        // system-use service-offering must NOT suppress the global default.
        VolumeObjectTO volumeTO = Mockito.mock(VolumeObjectTO.class);
        Mockito.when(entityMgr.findById(eq(ServiceOffering.class), eq(7L))).thenReturn(serviceOffering);
        Mockito.when(entityMgr.findById(eq(DiskOffering.class), eq(8L))).thenReturn(diskOffering);
        // serviceOffering.isSystemUse() is NOT consulted for non-ROOT volumes because
        // production code passes offering=null down to the rate helpers; that's the
        // contract this test asserts.
        Mockito.when(diskOffering.getBytesReadRate()).thenReturn(null);
        Mockito.when(diskOffering.getBytesWriteRate()).thenReturn(null);
        Mockito.when(diskOffering.getIopsReadRate()).thenReturn(null);
        Mockito.when(diskOffering.getIopsWriteRate()).thenReturn(null);
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingBytesReadRate.key())).thenReturn("100");
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingBytesWriteRate.key())).thenReturn("200");
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingIopsReadRate.key())).thenReturn("300");
        Mockito.when(configDao.getValue(Config.VmDiskThrottlingIopsWriteRate.key())).thenReturn("400");

        DiskTO disk = service.getDiskWithThrottling(volumeTO, Volume.Type.DATADISK, 1L, "/p", 7L, 8L);

        assertNotNull(disk);
        // Globals should win because we deliberately passed offering=null for non-ROOT.
        Mockito.verify(volumeTO).setBytesReadRate(100L);
        Mockito.verify(volumeTO).setBytesWriteRate(200L);
        Mockito.verify(volumeTO).setIopsReadRate(300L);
        Mockito.verify(volumeTO).setIopsWriteRate(400L);
    }

    @Test
    public void getDiskWithThrottlingNullVolumeTOReturnsWrappedDisk() {
        // A null DataTO is the "no real volume" path used by some snapshot flows;
        // we wrap and return without any rate-setting.
        DiskTO disk = service.getDiskWithThrottling(null, Volume.Type.DATADISK, 0L, null, 0L, 0L);

        assertNotNull(disk);
        Mockito.verifyNoInteractions(entityMgr);
    }
}
