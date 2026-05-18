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

package org.apache.cloudstack.vm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.CheckConvertInstanceAnswer;
import com.cloud.agent.api.CheckConvertInstanceCommand;
import com.cloud.agent.api.ConvertInstanceAnswer;
import com.cloud.agent.api.ConvertInstanceCommand;
import com.cloud.agent.api.ImportConvertedInstanceAnswer;
import com.cloud.agent.api.ImportConvertedInstanceCommand;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.RemoteInstanceTO;
import com.cloud.dc.ClusterVO;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VmDetailConstants;

@RunWith(MockitoJUnitRunner.class)
public class VmwareToKvmConversionServiceImplTest {

    @Spy
    @InjectMocks
    private VmwareToKvmConversionServiceImpl service;

    @Mock
    private AgentManager agentManager;
    @Mock
    private HostDao hostDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Mock
    private VolumeApiService volumeApiService;
    @Mock
    private HypervisorGuruManager hypervisorGuruManager;
    @Mock
    private ImageStoreDao imageStoreDao;
    @Mock
    private DataStoreManager dataStoreManager;

    private AutoCloseable closeable;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    // -----------------------------------------------------------------------
    // addServiceOfferingDetailsToParams
    // -----------------------------------------------------------------------

    @Test
    public void testAddServiceOfferingDetailsNullOfferingIsNoop() {
        Map<String, String> params = new HashMap<>();
        service.addServiceOfferingDetailsToParams(params, null);
        Assert.assertTrue(params.isEmpty());
    }

    @Test
    public void testAddServiceOfferingDetailsFixedOffering() {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.getCpu()).thenReturn(4);
        when(offering.getSpeed()).thenReturn(2000);
        when(offering.getRamSize()).thenReturn(4096);
        Map<String, String> params = new HashMap<>();
        service.addServiceOfferingDetailsToParams(params, offering);
        Assert.assertEquals("4", params.get(VmDetailConstants.CPU_NUMBER));
        Assert.assertEquals("2000", params.get(VmDetailConstants.CPU_SPEED));
        Assert.assertEquals("4096", params.get(VmDetailConstants.MEMORY));
    }

    @Test
    public void testAddServiceOfferingDetailsCustomConstrainedFallsBackToMinDetails() {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.getCpu()).thenReturn(null);
        when(offering.getSpeed()).thenReturn(null);
        when(offering.getRamSize()).thenReturn(null);
        Map<String, String> details = new HashMap<>();
        details.put(ApiConstants.MIN_CPU_NUMBER, "2");
        details.put(ApiConstants.MIN_MEMORY, "2048");
        when(offering.getDetails()).thenReturn(details);
        Map<String, String> params = new HashMap<>();
        service.addServiceOfferingDetailsToParams(params, offering);
        Assert.assertEquals("2", params.get(VmDetailConstants.CPU_NUMBER));
        Assert.assertEquals("2048", params.get(VmDetailConstants.MEMORY));
    }

    // -----------------------------------------------------------------------
    // checkConversionStoragePool
    // -----------------------------------------------------------------------

    @Test
    public void testCheckConversionStoragePoolNullIdNoForceIsNoop() {
        service.checkConversionStoragePool(null, false);
        Mockito.verifyNoInteractions(primaryDataStoreDao);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testCheckConversionStoragePoolNullIdWithForceThrows() {
        service.checkConversionStoragePool(null, true);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testCheckConversionStoragePoolMissingPoolThrows() {
        when(primaryDataStoreDao.findById(99L)).thenReturn(null);
        service.checkConversionStoragePool(99L, false);
    }

    @Test
    public void testCheckConversionStoragePoolNfsWithForceIsValid() {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(primaryDataStoreDao.findById(5L)).thenReturn(pool);
        service.checkConversionStoragePool(5L, true);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testCheckConversionStoragePoolRbdWithForceThrows() {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        when(primaryDataStoreDao.findById(5L)).thenReturn(pool);
        service.checkConversionStoragePool(5L, true);
    }

    // -----------------------------------------------------------------------
    // validateSelectedConversionStoragePoolForVddk
    // -----------------------------------------------------------------------

    @Test
    public void testValidateSelectedConversionStoragePoolForVddkSkipsWhenVddkFalse() {
        service.validateSelectedConversionStoragePoolForVddk(false, 1L, mock(ServiceOfferingVO.class), Collections.emptyMap());
        Mockito.verifyNoInteractions(primaryDataStoreDao, diskOfferingDao);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testValidateSelectedConversionStoragePoolForVddkRootDiskNotCompatibleThrows() {
        long poolId = 10L;
        StoragePoolVO pool = mock(StoragePoolVO.class);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        DiskOfferingVO rootOffering = mock(DiskOfferingVO.class);
        when(primaryDataStoreDao.findById(poolId)).thenReturn(pool);
        when(offering.getDiskOfferingId()).thenReturn(20L);
        when(diskOfferingDao.findById(20L)).thenReturn(rootOffering);
        when(volumeApiService.doesStoragePoolSupportDiskOffering(pool, rootOffering)).thenReturn(false);
        service.validateSelectedConversionStoragePoolForVddk(true, poolId, offering, Collections.emptyMap());
    }

    // -----------------------------------------------------------------------
    // applyVddkOverridesFromDetails
    // -----------------------------------------------------------------------

    @Test
    public void testApplyVddkOverridesFromDetailsNullDetailsIsNoop() {
        ConvertInstanceCommand cmd = mock(ConvertInstanceCommand.class);
        service.applyVddkOverridesFromDetails(cmd, null);
        Mockito.verifyNoInteractions(cmd);
    }

    @Test
    public void testApplyVddkOverridesFromDetailsPopulatesCommand() {
        ConvertInstanceCommand cmd = mock(ConvertInstanceCommand.class);
        Map<String, String> details = new HashMap<>();
        details.put(Host.HOST_VDDK_LIB_DIR, "/usr/lib/vmware-vix-disklib");
        details.put("vddk.transports", "nbdssl");
        details.put("vddk.thumbprint", "AA:BB:CC");
        service.applyVddkOverridesFromDetails(cmd, details);
        verify(cmd).setVddkLibDir("/usr/lib/vmware-vix-disklib");
        verify(cmd).setVddkTransports("nbdssl");
        verify(cmd).setVddkThumbprint("AA:BB:CC");
    }

    // -----------------------------------------------------------------------
    // getStoragePoolWithTags
    // -----------------------------------------------------------------------

    @Test
    public void testGetStoragePoolWithTagsEmptyTagsReturnsFirst() {
        StoragePoolVO p1 = mock(StoragePoolVO.class);
        StoragePoolVO p2 = mock(StoragePoolVO.class);
        StoragePoolVO result = service.getStoragePoolWithTags(List.of(p1, p2), "");
        Assert.assertSame(p1, result);
    }

    @Test
    public void testGetStoragePoolWithTagsMatchingPoolReturned() {
        StoragePoolVO p1 = mock(StoragePoolVO.class);
        StoragePoolVO p2 = mock(StoragePoolVO.class);
        when(volumeApiService.doesStoragePoolSupportDiskOfferingTags(p1, "ssd")).thenReturn(false);
        when(volumeApiService.doesStoragePoolSupportDiskOfferingTags(p2, "ssd")).thenReturn(true);
        StoragePoolVO result = service.getStoragePoolWithTags(List.of(p1, p2), "ssd");
        Assert.assertSame(p2, result);
    }

    @Test
    public void testGetStoragePoolWithTagsNoMatchReturnsNull() {
        StoragePoolVO p1 = mock(StoragePoolVO.class);
        when(volumeApiService.doesStoragePoolSupportDiskOfferingTags(p1, "nvme")).thenReturn(false);
        StoragePoolVO result = service.getStoragePoolWithTags(List.of(p1), "nvme");
        Assert.assertNull(result);
    }

    // -----------------------------------------------------------------------
    // filterHostsWithVddkSupport
    // -----------------------------------------------------------------------

    @Test
    public void testFilterHostsWithVddkSupportFiltersCorrectly() {
        HostVO h1 = mock(HostVO.class);
        HostVO h2 = mock(HostVO.class);
        Mockito.lenient().doNothing().when(hostDao).loadDetails(any());
        when(h1.getDetail(Host.HOST_VDDK_SUPPORT)).thenReturn("true");
        when(h2.getDetail(Host.HOST_VDDK_SUPPORT)).thenReturn("false");
        List<HostVO> result = service.filterHostsWithVddkSupport(List.of(h1, h2));
        Assert.assertEquals(1, result.size());
        Assert.assertSame(h1, result.get(0));
    }

    // -----------------------------------------------------------------------
    // createParamsForRemoveClonedInstance
    // -----------------------------------------------------------------------

    @Test
    public void testCreateParamsForRemoveClonedInstanceBuildsMap() {
        Map<String, String> params = service.createParamsForRemoveClonedInstance(
                "vc.example.com", "DC1", "admin", "secret", "vm-src");
        Assert.assertEquals("vc.example.com", params.get(VmDetailConstants.VMWARE_VCENTER_HOST));
        Assert.assertEquals("DC1", params.get(VmDetailConstants.VMWARE_DATACENTER_NAME));
        Assert.assertEquals("admin", params.get(VmDetailConstants.VMWARE_VCENTER_USERNAME));
        Assert.assertEquals("secret", params.get(VmDetailConstants.VMWARE_VCENTER_PASSWORD));
    }

    // -----------------------------------------------------------------------
    // createParamsForTemplateFromVmwareVmMigration
    // -----------------------------------------------------------------------

    @Test
    public void testCreateParamsForTemplateFromVmwareVmMigrationBuildsMap() {
        Map<String, String> params = service.createParamsForTemplateFromVmwareVmMigration(
                "vc.example.com", "DC1", "admin", "secret", "cluster1", "esxi-host", "vm-src");
        Assert.assertEquals("vc.example.com", params.get(VmDetailConstants.VMWARE_VCENTER_HOST));
        Assert.assertEquals("DC1", params.get(VmDetailConstants.VMWARE_DATACENTER_NAME));
        Assert.assertEquals("cluster1", params.get(VmDetailConstants.VMWARE_CLUSTER_NAME));
        Assert.assertEquals("esxi-host", params.get(VmDetailConstants.VMWARE_HOST_NAME));
        Assert.assertEquals("vm-src", params.get(VmDetailConstants.VMWARE_VM_NAME));
    }

    // -----------------------------------------------------------------------
    // removeClonedInstance / removeTemplate
    // -----------------------------------------------------------------------

    @Test
    public void testRemoveClonedInstanceSuccessLogs() {
        HypervisorGuru vmwareGuru = mock(HypervisorGuru.class);
        when(hypervisorGuruManager.getGuru(Hypervisor.HypervisorType.VMware)).thenReturn(vmwareGuru);
        when(vmwareGuru.removeClonedHypervisorVMOutOfBand(anyString(), anyString(), anyMap())).thenReturn(true);
        service.removeClonedInstance("vc", "DC", "u", "p", "host", "cloned-vm", "src-vm");
        verify(vmwareGuru).removeClonedHypervisorVMOutOfBand(anyString(), anyString(), anyMap());
    }

    @Test
    public void testRemoveTemplateSuccessLogs() {
        HypervisorGuru vmwareGuru = mock(HypervisorGuru.class);
        DataStoreTO location = mock(DataStoreTO.class);
        when(hypervisorGuruManager.getGuru(Hypervisor.HypervisorType.VMware)).thenReturn(vmwareGuru);
        when(vmwareGuru.removeVMTemplateOutOfBand(location, "template.ovf")).thenReturn(true);
        service.removeTemplate(location, "template.ovf");
        verify(vmwareGuru).removeVMTemplateOutOfBand(location, "template.ovf");
    }

    // -----------------------------------------------------------------------
    // checkConversionSupportOnHost
    // -----------------------------------------------------------------------

    @Test(expected = CloudRuntimeException.class)
    public void testCheckConversionSupportOnHostAgentUnavailableThrows() throws Exception {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(1L);
        when(agentManager.send(anyLong(), any(CheckConvertInstanceCommand.class)))
                .thenThrow(new AgentUnavailableException("unavailable", 1L));
        service.checkConversionSupportOnHost(host, "vm-name", false, false, Collections.emptyMap());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testCheckConversionSupportOnHostNegativeAnswerThrows() throws Exception {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(1L);
        CheckConvertInstanceAnswer answer = mock(CheckConvertInstanceAnswer.class);
        when(answer.getResult()).thenReturn(false);
        when(answer.getDetails()).thenReturn("not supported");
        when(agentManager.send(anyLong(), any(CheckConvertInstanceCommand.class))).thenReturn(answer);
        service.checkConversionSupportOnHost(host, "vm-name", false, false, Collections.emptyMap());
    }

    @Test
    public void testCheckConversionSupportOnHostPositiveAnswerReturns() throws Exception {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(1L);
        CheckConvertInstanceAnswer answer = mock(CheckConvertInstanceAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(agentManager.send(anyLong(), any(CheckConvertInstanceCommand.class))).thenReturn(answer);
        CheckConvertInstanceAnswer result = service.checkConversionSupportOnHost(host, "vm-name", false, false, Collections.emptyMap());
        Assert.assertSame(answer, result);
    }

    // -----------------------------------------------------------------------
    // convertAndImportToKVM
    // -----------------------------------------------------------------------

    @Test(expected = CloudRuntimeException.class)
    public void testConvertAndImportToKvmConvertAnswerFailureThrows() throws Exception {
        HostVO convertHost = mock(HostVO.class);
        HostVO importHost = mock(HostVO.class);
        ConvertInstanceCommand cmd = mock(ConvertInstanceCommand.class);
        when(convertHost.getId()).thenReturn(1L);
        com.cloud.agent.api.Answer failAnswer = mock(com.cloud.agent.api.Answer.class);
        when(failAnswer.getResult()).thenReturn(false);
        when(failAnswer.getDetails()).thenReturn("convert failed");
        when(agentManager.send(anyLong(), any(ConvertInstanceCommand.class))).thenReturn(failAnswer);
        service.convertAndImportToKVM(cmd, convertHost, importHost, "vm", mock(RemoteInstanceTO.class),
                Collections.emptyList(), mock(DataStoreTO.class), false);
    }

    @Test
    public void testConvertAndImportToKvmSuccessReturnsInstance() throws Exception {
        HostVO convertHost = mock(HostVO.class);
        HostVO importHost = mock(HostVO.class);
        ConvertInstanceCommand cmd = mock(ConvertInstanceCommand.class);
        when(convertHost.getId()).thenReturn(1L);
        when(importHost.getId()).thenReturn(2L);

        ConvertInstanceAnswer convertAnswer = mock(ConvertInstanceAnswer.class);
        when(convertAnswer.getResult()).thenReturn(true);
        when(convertAnswer.getTemporaryConvertUuid()).thenReturn("some-uuid");

        UnmanagedInstanceTO expectedInstance = mock(UnmanagedInstanceTO.class);
        ImportConvertedInstanceAnswer importAnswer = mock(ImportConvertedInstanceAnswer.class);
        when(importAnswer.getResult()).thenReturn(true);
        when(importAnswer.getConvertedInstance()).thenReturn(expectedInstance);

        when(agentManager.send(1L, cmd)).thenReturn(convertAnswer);
        when(agentManager.send(Mockito.eq(2L), any(ImportConvertedInstanceCommand.class))).thenReturn(importAnswer);

        UnmanagedInstanceTO result = service.convertAndImportToKVM(cmd, convertHost, importHost, "vm",
                mock(RemoteInstanceTO.class), Collections.emptyList(), mock(DataStoreTO.class), false);
        Assert.assertSame(expectedInstance, result);
    }

    // -----------------------------------------------------------------------
    // selectInstanceConversionTemporaryLocation (protected helpers)
    // -----------------------------------------------------------------------

    @Test(expected = CloudRuntimeException.class)
    public void testSelectInstanceConversionTemporaryLocationNoPoolAndForceConvertThrows() {
        ClusterVO cluster = buildCluster(1L, 1L);
        service.selectInstanceConversionTemporaryLocation(cluster, null, null, null, true);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testSelectInstanceConversionTemporaryLocationNoPoolNoNfsThrows() {
        ClusterVO cluster = buildCluster(1L, 1L);
        when(imageStoreDao.findOneByZoneAndProtocol(anyLong(), anyString())).thenReturn(null);
        service.selectInstanceConversionTemporaryLocation(cluster, null, null, null, false);
    }

    @Test
    public void testSelectInstanceConversionTemporaryLocationNoPoolUsesNfsSecondary() {
        ClusterVO cluster = buildCluster(1L, 1L);
        ImageStoreVO imageStore = mock(ImageStoreVO.class);
        when(imageStore.getId()).thenReturn(50L);
        when(imageStoreDao.findOneByZoneAndProtocol(1L, "nfs")).thenReturn(imageStore);
        DataStore ds = mock(DataStore.class);
        DataStoreTO expectedTO = mock(DataStoreTO.class);
        when(ds.getTO()).thenReturn(expectedTO);
        when(dataStoreManager.getDataStore(50L, DataStoreRole.Image)).thenReturn(ds);
        DataStoreTO result = service.selectInstanceConversionTemporaryLocation(cluster, null, null, null, false);
        Assert.assertSame(expectedTO, result);
    }

    // -----------------------------------------------------------------------
    // sanitizeConvertedInstance
    // -----------------------------------------------------------------------

    @Test
    public void testSanitizeConvertedInstanceCopiesCpuAndMemory() {
        UnmanagedInstanceTO converted = new UnmanagedInstanceTO();
        converted.setDisks(new ArrayList<>());
        converted.setNics(new ArrayList<>());

        UnmanagedInstanceTO source = new UnmanagedInstanceTO();
        source.setCpuCores(8);
        source.setCpuSpeed(3000);
        source.setCpuCoresPerSocket(4);
        source.setMemory(16384);
        source.setDisks(new ArrayList<>());
        source.setNics(new ArrayList<>());

        service.sanitizeConvertedInstance(converted, source);

        Assert.assertEquals(8, (int) converted.getCpuCores());
        Assert.assertEquals(3000, (int) converted.getCpuSpeed());
        Assert.assertEquals(16384, (int) converted.getMemory());
        Assert.assertEquals(UnmanagedInstanceTO.PowerState.PowerOff, converted.getPowerState());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ClusterVO buildCluster(long clusterId, long zoneId) {
        ClusterVO cluster = mock(ClusterVO.class);
        Mockito.lenient().when(cluster.getId()).thenReturn(clusterId);
        Mockito.lenient().when(cluster.getDataCenterId()).thenReturn(zoneId);
        return cluster;
    }
}
