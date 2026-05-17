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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.admin.systemvm.DestroySystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.RebootSystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.StopSystemVmCmd;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.event.ActionEventUtils;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.storage.secondary.SecondaryStorageVmManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.ConsoleProxyVO;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.ConsoleProxyDao;
import com.cloud.vm.dao.SecondaryStorageVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class SystemVmLifecycleServiceImplTest {

    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private ConsoleProxyDao consoleProxyDao;
    @Mock
    private SecondaryStorageVmDao secStorageVmDao;
    @Mock
    private ConsoleProxyManager consoleProxyManager;
    @Mock
    private SecondaryStorageVmManager secStorageVmManager;
    @Mock
    private VirtualMachineManager itManager;
    @Mock
    private VolumeDataStoreDao volumeStoreDao;
    @Mock
    private ImageStoreDao imgStoreDao;
    @Mock
    private TemplateDataStoreDao vmTemplateStoreDao;

    @InjectMocks
    private SystemVmLifecycleServiceImpl service = new SystemVmLifecycleServiceImpl();

    private AutoCloseable closeable;
    private MockedStatic<ActionEventUtils> actionEventUtilsMock;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        // The dispatch methods emit a nested ActionEvent before delegating to
        // the type manager; we don't care about the event payload in these
        // tests, only that the dispatch succeeds, so stub the static to a
        // no-op rather than wiring a full EntityManager.
        actionEventUtilsMock = Mockito.mockStatic(ActionEventUtils.class);
    }

    @After
    public void tearDown() throws Exception {
        if (actionEventUtilsMock != null) {
            actionEventUtilsMock.close();
        }
        closeable.close();
    }

    // --- findSystemVMTypeById --------------------------------------------

    @Test
    public void findSystemVMTypeByIdReturnsConsoleProxyType() {
        VMInstanceVO vm = mockSystemVm(10L, VirtualMachine.Type.ConsoleProxy);
        when(vmInstanceDao.findByIdTypes(eq(10L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);

        assertEquals(VirtualMachine.Type.ConsoleProxy, service.findSystemVMTypeById(10L));
    }

    @Test
    public void findSystemVMTypeByIdReturnsSecondaryStorageType() {
        VMInstanceVO vm = mockSystemVm(11L, VirtualMachine.Type.SecondaryStorageVm);
        when(vmInstanceDao.findByIdTypes(eq(11L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);

        assertEquals(VirtualMachine.Type.SecondaryStorageVm, service.findSystemVMTypeById(11L));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void findSystemVMTypeByIdThrowsWhenMissing() {
        when(vmInstanceDao.findByIdTypes(eq(12L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(null);

        service.findSystemVMTypeById(12L);
    }

    // --- startSystemVM ---------------------------------------------------

    @Test
    public void startSystemVMDelegatesToConsoleProxyForCpvm() {
        VMInstanceVO vm = mockSystemVm(20L, VirtualMachine.Type.ConsoleProxy);
        when(vmInstanceDao.findByIdTypes(eq(20L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        ConsoleProxyVO started = mock(ConsoleProxyVO.class);
        when(consoleProxyManager.startProxy(20L, true)).thenReturn(started);

        assertSame(started, service.startSystemVM(20L));
        verify(consoleProxyManager).startProxy(20L, true);
        verify(secStorageVmManager, never()).startSecStorageVm(anyLong());
    }

    @Test
    public void startSystemVMDelegatesToSecondaryStorageForSsvm() {
        VMInstanceVO vm = mockSystemVm(21L, VirtualMachine.Type.SecondaryStorageVm);
        when(vmInstanceDao.findByIdTypes(eq(21L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        SecondaryStorageVmVO started = mock(SecondaryStorageVmVO.class);
        when(secStorageVmManager.startSecStorageVm(21L)).thenReturn(started);

        assertSame(started, service.startSystemVM(21L));
        verify(secStorageVmManager).startSecStorageVm(21L);
        verify(consoleProxyManager, never()).startProxy(anyLong(), eq(true));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void startSystemVMThrowsWhenInstanceMissing() {
        when(vmInstanceDao.findByIdTypes(eq(22L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(null);

        service.startSystemVM(22L);
    }

    // --- stopSystemVM ----------------------------------------------------

    @Test
    public void stopSystemVMStopsConsoleProxy() throws Exception {
        VMInstanceVO vm = mockSystemVm(30L, VirtualMachine.Type.ConsoleProxy);
        when(vm.getUuid()).thenReturn("cpvm-uuid");
        when(vmInstanceDao.findByIdTypes(eq(30L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        ConsoleProxyVO stopped = mock(ConsoleProxyVO.class);
        when(consoleProxyDao.findById(30L)).thenReturn(stopped);
        StopSystemVmCmd cmd = mock(StopSystemVmCmd.class);
        when(cmd.getId()).thenReturn(30L);
        when(cmd.isForced()).thenReturn(false);

        assertSame(stopped, service.stopSystemVM(cmd));
        verify(itManager).advanceStop("cpvm-uuid", false);
    }

    @Test
    public void stopSystemVMForcedStopsSecondaryStorage() throws Exception {
        VMInstanceVO vm = mockSystemVm(31L, VirtualMachine.Type.SecondaryStorageVm);
        when(vm.getUuid()).thenReturn("ssvm-uuid");
        when(vmInstanceDao.findByIdTypes(eq(31L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        SecondaryStorageVmVO stopped = mock(SecondaryStorageVmVO.class);
        when(secStorageVmDao.findById(31L)).thenReturn(stopped);
        StopSystemVmCmd cmd = mock(StopSystemVmCmd.class);
        when(cmd.getId()).thenReturn(31L);
        when(cmd.isForced()).thenReturn(true);

        assertSame(stopped, service.stopSystemVM(cmd));
        verify(itManager).advanceStop("ssvm-uuid", true);
    }

    @Test
    public void stopSystemVMWrapsAgentTimeoutAsRuntime() throws Exception {
        VMInstanceVO vm = mockSystemVm(32L, VirtualMachine.Type.ConsoleProxy);
        when(vm.getUuid()).thenReturn("cpvm-uuid-32");
        when(vmInstanceDao.findByIdTypes(eq(32L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        org.mockito.Mockito.doThrow(new OperationTimedoutException(
                new com.cloud.agent.api.Command[0], 1L, 0L, 0, false))
                .when(itManager).advanceStop("cpvm-uuid-32", false);
        StopSystemVmCmd cmd = mock(StopSystemVmCmd.class);
        when(cmd.getId()).thenReturn(32L);
        when(cmd.isForced()).thenReturn(false);

        try {
            service.stopSystemVM(cmd);
            fail("expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().contains("Unable to stop"));
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void stopSystemVMThrowsWhenInstanceMissing()
            throws ResourceUnavailableException, ConcurrentOperationException {
        when(vmInstanceDao.findByIdTypes(eq(33L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(null);
        StopSystemVmCmd cmd = mock(StopSystemVmCmd.class);
        when(cmd.getId()).thenReturn(33L);

        service.stopSystemVM(cmd);
    }

    // --- rebootSystemVM --------------------------------------------------

    @Test
    public void rebootSystemVMWarmRebootConsoleProxy() {
        VMInstanceVO vm = mockSystemVm(40L, VirtualMachine.Type.ConsoleProxy);
        when(vmInstanceDao.findByIdTypes(eq(40L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        ConsoleProxyVO rebooted = mock(ConsoleProxyVO.class);
        when(consoleProxyDao.findById(40L)).thenReturn(rebooted);
        RebootSystemVmCmd cmd = mock(RebootSystemVmCmd.class);
        when(cmd.getId()).thenReturn(40L);
        when(cmd.isForced()).thenReturn(false);

        assertSame(rebooted, service.rebootSystemVM(cmd));
        verify(consoleProxyManager).rebootProxy(40L);
    }

    @Test
    public void rebootSystemVMForcedRebootConsoleProxyStopsAndStarts() throws Exception {
        VMInstanceVO vm = mockSystemVm(41L, VirtualMachine.Type.ConsoleProxy);
        when(vm.getUuid()).thenReturn("cpvm-uuid-41");
        when(vmInstanceDao.findByIdTypes(eq(41L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        ConsoleProxyVO restarted = mock(ConsoleProxyVO.class);
        when(consoleProxyManager.startProxy(41L, true)).thenReturn(restarted);
        RebootSystemVmCmd cmd = mock(RebootSystemVmCmd.class);
        when(cmd.getId()).thenReturn(41L);
        when(cmd.isForced()).thenReturn(true);

        assertSame(restarted, service.rebootSystemVM(cmd));
        verify(itManager).advanceStop("cpvm-uuid-41", false);
        verify(consoleProxyManager).startProxy(41L, true);
        // warm reboot path must not be triggered
        verify(consoleProxyManager, never()).rebootProxy(41L);
    }

    @Test
    public void rebootSystemVMWarmRebootSecondaryStorage() {
        VMInstanceVO vm = mockSystemVm(42L, VirtualMachine.Type.SecondaryStorageVm);
        when(vmInstanceDao.findByIdTypes(eq(42L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        SecondaryStorageVmVO rebooted = mock(SecondaryStorageVmVO.class);
        when(secStorageVmDao.findById(42L)).thenReturn(rebooted);
        RebootSystemVmCmd cmd = mock(RebootSystemVmCmd.class);
        when(cmd.getId()).thenReturn(42L);
        when(cmd.isForced()).thenReturn(false);

        assertSame(rebooted, service.rebootSystemVM(cmd));
        verify(secStorageVmManager).rebootSecStorageVm(42L);
    }

    @Test
    public void rebootSystemVMForcedRebootSecondaryStorageStopsAndStarts() throws Exception {
        VMInstanceVO vm = mockSystemVm(43L, VirtualMachine.Type.SecondaryStorageVm);
        when(vm.getUuid()).thenReturn("ssvm-uuid-43");
        when(vmInstanceDao.findByIdTypes(eq(43L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        SecondaryStorageVmVO restarted = mock(SecondaryStorageVmVO.class);
        when(secStorageVmManager.startSecStorageVm(43L)).thenReturn(restarted);
        RebootSystemVmCmd cmd = mock(RebootSystemVmCmd.class);
        when(cmd.getId()).thenReturn(43L);
        when(cmd.isForced()).thenReturn(true);

        assertSame(restarted, service.rebootSystemVM(cmd));
        verify(itManager).advanceStop("ssvm-uuid-43", false);
        verify(secStorageVmManager).startSecStorageVm(43L);
        verify(secStorageVmManager, never()).rebootSecStorageVm(43L);
    }

    @Test
    public void rebootSystemVMWrapsResourceUnavailableAsRuntime() throws Exception {
        VMInstanceVO vm = mockSystemVm(44L, VirtualMachine.Type.ConsoleProxy);
        when(vm.getUuid()).thenReturn("cpvm-uuid-44");
        when(vmInstanceDao.findByIdTypes(eq(44L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        org.mockito.Mockito.doThrow(new ResourceUnavailableException(
                "agent down", VirtualMachine.class, 44L))
                .when(itManager).advanceStop("cpvm-uuid-44", false);
        RebootSystemVmCmd cmd = mock(RebootSystemVmCmd.class);
        when(cmd.getId()).thenReturn(44L);
        when(cmd.isForced()).thenReturn(true);

        try {
            service.rebootSystemVM(cmd);
            fail("expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().contains("Unable to reboot"));
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rebootSystemVMThrowsWhenInstanceMissing() {
        when(vmInstanceDao.findByIdTypes(eq(45L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(null);
        RebootSystemVmCmd cmd = mock(RebootSystemVmCmd.class);
        when(cmd.getId()).thenReturn(45L);

        service.rebootSystemVM(cmd);
    }

    // --- destroySystemVM -------------------------------------------------

    @Test
    public void destroySystemVMDelegatesConsoleProxyDestroy() {
        VMInstanceVO vm = mockSystemVm(50L, VirtualMachine.Type.ConsoleProxy);
        when(vmInstanceDao.findByIdTypes(eq(50L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        ConsoleProxyVO proxy = mock(ConsoleProxyVO.class);
        when(consoleProxyDao.findById(50L)).thenReturn(proxy);
        when(consoleProxyManager.destroyProxy(50L)).thenReturn(true);
        DestroySystemVmCmd cmd = mock(DestroySystemVmCmd.class);
        when(cmd.getId()).thenReturn(50L);

        assertSame(proxy, service.destroySystemVM(cmd));
        verify(consoleProxyManager).destroyProxy(50L);
        // SSVM cleanup path must not be reached for a CPVM destroy
        verify(volumeStoreDao, never()).listVolumeDownloadUrlsByZoneId(anyLong());
    }

    @Test
    public void destroySystemVMReturnsNullWhenConsoleProxyDestroyRefuses() {
        VMInstanceVO vm = mockSystemVm(51L, VirtualMachine.Type.ConsoleProxy);
        when(vmInstanceDao.findByIdTypes(eq(51L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        when(consoleProxyDao.findById(51L)).thenReturn(mock(ConsoleProxyVO.class));
        when(consoleProxyManager.destroyProxy(51L)).thenReturn(false);
        DestroySystemVmCmd cmd = mock(DestroySystemVmCmd.class);
        when(cmd.getId()).thenReturn(51L);

        assertNull(service.destroySystemVM(cmd));
    }

    @Test
    public void destroySystemVMDelegatesSecondaryStorageDestroyAndCleansDownloadUrls() {
        VMInstanceVO vm = mockSystemVm(52L, VirtualMachine.Type.SecondaryStorageVm);
        when(vmInstanceDao.findByIdTypes(eq(52L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(vm);
        SecondaryStorageVmVO ssvm = mock(SecondaryStorageVmVO.class);
        when(ssvm.getDataCenterId()).thenReturn(700L);
        when(secStorageVmDao.findById(52L)).thenReturn(ssvm);
        when(secStorageVmManager.destroySecStorageVm(52L)).thenReturn(true);
        // Zone has one volume with a download URL and one image store with one template.
        VolumeDataStoreVO volRec = mock(VolumeDataStoreVO.class);
        when(volRec.getId()).thenReturn(800L);
        when(volumeStoreDao.listVolumeDownloadUrlsByZoneId(700L))
                .thenReturn(Collections.singletonList(volRec));
        ImageStoreVO imgStore = mock(ImageStoreVO.class);
        when(imgStore.getId()).thenReturn(900L);
        when(imgStoreDao.listStoresByZoneId(700L)).thenReturn(Collections.singletonList(imgStore));
        TemplateDataStoreVO tplRec = mock(TemplateDataStoreVO.class);
        when(tplRec.getId()).thenReturn(901L);
        when(vmTemplateStoreDao.listTemplateDownloadUrlsByStoreId(900L))
                .thenReturn(Collections.singletonList(tplRec));
        DestroySystemVmCmd cmd = mock(DestroySystemVmCmd.class);
        when(cmd.getId()).thenReturn(52L);

        assertSame(ssvm, service.destroySystemVM(cmd));
        // Cleanup happened before the manager destroy call.
        verify(volRec).setExtractUrl(null);
        verify(volumeStoreDao).update(800L, volRec);
        verify(tplRec).setExtractUrl(null);
        verify(tplRec).setExtractUrlCreated(null);
        verify(vmTemplateStoreDao).update(901L, tplRec);
        verify(secStorageVmManager).destroySecStorageVm(52L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void destroySystemVMThrowsWhenInstanceMissing() {
        when(vmInstanceDao.findByIdTypes(eq(53L),
                eq(VirtualMachine.Type.ConsoleProxy),
                eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(null);
        DestroySystemVmCmd cmd = mock(DestroySystemVmCmd.class);
        when(cmd.getId()).thenReturn(53L);

        service.destroySystemVM(cmd);
    }

    // --- cleanupDownloadUrlsInZone helper directly -----------------------

    @Test
    public void cleanupDownloadUrlsInZoneWalksAllImageStoreTemplates() {
        ImageStoreVO storeA = mock(ImageStoreVO.class);
        when(storeA.getId()).thenReturn(1100L);
        ImageStoreVO storeB = mock(ImageStoreVO.class);
        when(storeB.getId()).thenReturn(1101L);
        when(imgStoreDao.listStoresByZoneId(99L)).thenReturn(List.of(storeA, storeB));
        TemplateDataStoreVO tplA = mock(TemplateDataStoreVO.class);
        when(tplA.getId()).thenReturn(1200L);
        when(vmTemplateStoreDao.listTemplateDownloadUrlsByStoreId(1100L))
                .thenReturn(Collections.singletonList(tplA));
        TemplateDataStoreVO tplB = mock(TemplateDataStoreVO.class);
        when(tplB.getId()).thenReturn(1201L);
        when(vmTemplateStoreDao.listTemplateDownloadUrlsByStoreId(1101L))
                .thenReturn(Collections.singletonList(tplB));
        when(volumeStoreDao.listVolumeDownloadUrlsByZoneId(99L))
                .thenReturn(Collections.emptyList());

        service.cleanupDownloadUrlsInZone(99L);

        verify(tplA).setExtractUrl(null);
        verify(tplA).setExtractUrlCreated(null);
        verify(vmTemplateStoreDao).update(1200L, tplA);
        verify(tplB).setExtractUrl(null);
        verify(tplB).setExtractUrlCreated(null);
        verify(vmTemplateStoreDao).update(1201L, tplB);
        // No volumes in this zone, the volume update path must stay quiet.
        verify(volumeStoreDao, never()).update(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void cleanupDownloadUrlsInZoneNoOpWhenEmpty() {
        when(volumeStoreDao.listVolumeDownloadUrlsByZoneId(98L))
                .thenReturn(Collections.emptyList());
        when(imgStoreDao.listStoresByZoneId(98L)).thenReturn(Collections.emptyList());

        service.cleanupDownloadUrlsInZone(98L);

        verify(volumeStoreDao, never()).update(anyLong(), org.mockito.ArgumentMatchers.any());
        verify(vmTemplateStoreDao, never()).update(anyLong(), org.mockito.ArgumentMatchers.any());
        verify(vmTemplateStoreDao, times(0)).listTemplateDownloadUrlsByStoreId(anyLong());
    }

    // --- helpers ---------------------------------------------------------

    private VMInstanceVO mockSystemVm(final long id, final VirtualMachine.Type type) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(id);
        when(vm.getType()).thenReturn(type);
        assertNotNull(vm);
        return vm;
    }

    @SuppressWarnings("unchecked")
    private static <T> T mock(Class<T> cls) {
        return org.mockito.Mockito.mock(cls);
    }
}
