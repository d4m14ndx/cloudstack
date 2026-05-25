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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.vm.CreateVMFromBackupCmd;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.backup.BackupVO;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.to.deployasis.OVFNetworkTO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deployasis.dao.TemplateDeployAsIsDetailsDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class VmBackupInstanceLifecycleServiceImplTest {

    private static final long ACCOUNT_ID = 2L;
    private static final long BACKUP_ID = 4L;
    private static final long SERVICE_OFFERING_ID = 5L;
    private static final long TEMPLATE_ID = 6L;
    private static final long VM_ID = 7L;
    private static final long ZONE_ID = 8L;

    @InjectMocks
    private VmBackupInstanceLifecycleServiceImpl service;

    @Mock
    private BackupDao backupDao;
    @Mock
    private BackupManager backupManager;
    @Mock
    private DataCenterDao _dcDao;
    @Mock
    private UserVmDao _vmDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private VMTemplateDao _templateDao;
    @Mock
    private DiskOfferingDao _diskOfferingDao;
    @Mock
    private AccountService _accountService;
    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock
    private TemplateDeployAsIsDetailsDao templateDeployAsIsDetailsDao;
    @Mock
    private NetworkModel _networkModel;
    @Mock
    private VmBackupInstanceLifecycleService.ManagerOperations managerOperations;

    @Test
    public void testAllocateVMFromBackupUsingCmdValues()
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        BackupVO backup = mock(BackupVO.class);
        DataCenterVO zone = mock(DataCenterVO.class);
        Account owner = mock(Account.class);
        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        VMTemplateVO template = mock(VMTemplateVO.class);
        DiskOfferingVO rootDiskOffering = mock(DiskOfferingVO.class);
        List<VmDiskInfo> dataDiskInfoList = List.of(new VmDiskInfo(rootDiskOffering, 5L, 1000L, 5000L, 1L));
        List<Long> networkIds = List.of(11L);
        UserVmVO createdVm = mock(UserVmVO.class);

        stubAllocateUsingCommandValues(cmd, backup, zone, owner, serviceOffering, template, rootDiskOffering, dataDiskInfoList, networkIds, createdVm);

        UserVm result = service.allocateVMFromBackup(cmd, managerOperations);

        assertNotNull(result);
        assertEquals(createdVm, result);
        verify(backupDao).findById(BACKUP_ID);
        verify(managerOperations).createVirtualMachine(eq(cmd), eq(zone), eq(owner), eq(serviceOffering), eq(template),
                nullable(HypervisorType.class), isNull(), isNull(), eq(33L), eq(dataDiskInfoList), eq(networkIds),
                isNull(), nullable(Volume.class), nullable(Snapshot.class));
    }

    @Test
    public void testAllocateVMFromBackupUsingBackupValues()
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        BackupVO backup = mock(BackupVO.class);
        DataCenterVO zone = mock(DataCenterVO.class);
        Account owner = mock(Account.class);
        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        VMTemplateVO template = mock(VMTemplateVO.class);
        DiskOfferingVO rootDiskOffering = mock(DiskOfferingVO.class);
        List<VmDiskInfo> dataDiskInfoList = List.of(new VmDiskInfo(rootDiskOffering, 5L, 1000L, 5000L));
        UserVmVO createdVm = mock(UserVmVO.class);

        stubAllocateBasics(cmd, backup, zone, owner, rootDiskOffering, createdVm);
        when(cmd.getServiceOfferingId()).thenReturn(null);
        when(backup.getDetail(ApiConstants.SERVICE_OFFERING_ID)).thenReturn("service-offering-uuid");
        when(serviceOfferingDao.findByUuid("service-offering-uuid")).thenReturn(serviceOffering);
        when(cmd.getTemplateId()).thenReturn(null);
        when(backup.getDetail(ApiConstants.TEMPLATE_ID)).thenReturn("template-uuid");
        when(_templateDao.findByUuid("template-uuid")).thenReturn(template);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(serviceOffering.getDiskOfferingId()).thenReturn(33L);
        when(cmd.getDataDiskInfoList()).thenReturn(null);
        when(backupManager.getDataDiskInfoListFromBackup(backup)).thenReturn(dataDiskInfoList);
        when(cmd.getNetworkIds()).thenReturn(null);
        when(cmd.getIpToNetworkMap()).thenReturn(null);
        when(backupManager.getIpToNetworkMapFromBackup(eq(backup), eq(false), anyList())).thenReturn(new HashMap<>());

        UserVm result = service.allocateVMFromBackup(cmd, managerOperations);

        assertNotNull(result);
        verify(serviceOfferingDao).findByUuid("service-offering-uuid");
        verify(_templateDao).findByUuid("template-uuid");
        verify(backupManager).getDataDiskInfoListFromBackup(backup);
        verify(managerOperations).createVirtualMachine(eq(cmd), eq(zone), eq(owner), eq(serviceOffering), eq(template),
                nullable(HypervisorType.class), isNull(), isNull(), eq(33L), eq(dataDiskInfoList), anyList(),
                anyMap(), nullable(Volume.class), nullable(Snapshot.class));
    }

    @Test
    public void testAllocateVMFromBackupUsingCmdValuesWithISO()
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        BackupVO backup = mock(BackupVO.class);
        DataCenterVO zone = mock(DataCenterVO.class);
        Account owner = mock(Account.class);
        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        VMTemplateVO template = mock(VMTemplateVO.class);
        DiskOfferingVO rootDiskOffering = mock(DiskOfferingVO.class);
        List<VmDiskInfo> dataDiskInfoList = List.of(new VmDiskInfo(rootDiskOffering, 5L, 1000L, 5000L));
        List<Long> networkIds = List.of(11L);
        UserVmVO createdVm = mock(UserVmVO.class);

        stubAllocateUsingCommandValues(cmd, backup, zone, owner, serviceOffering, template, rootDiskOffering, dataDiskInfoList, networkIds, createdVm);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.ISO);
        when(cmd.getDiskOfferingId()).thenReturn(44L);
        when(cmd.getSize()).thenReturn(10L);
        when(_diskOfferingDao.findById(44L)).thenReturn(rootDiskOffering);
        when(rootDiskOffering.getDiskSize()).thenReturn(10L * 1024L * 1024L * 1024L);

        UserVm result = service.allocateVMFromBackup(cmd, managerOperations);

        assertNotNull(result);
        verify(_diskOfferingDao, times(2)).findById(44L);
        verify(managerOperations).createVirtualMachine(eq(cmd), eq(zone), eq(owner), eq(serviceOffering), eq(template),
                nullable(HypervisorType.class), eq(44L), eq(10L), isNull(), eq(dataDiskInfoList), eq(networkIds),
                isNull(), nullable(Volume.class), nullable(Snapshot.class));
    }

    @Test
    public void testAllocateVMFromBackupUsingBackupValuesWithISO()
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        BackupVO backup = mock(BackupVO.class);
        DataCenterVO zone = mock(DataCenterVO.class);
        Account owner = mock(Account.class);
        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        VMTemplateVO template = mock(VMTemplateVO.class);
        DiskOfferingVO rootDiskOffering = mock(DiskOfferingVO.class);
        List<VmDiskInfo> dataDiskInfoList = List.of(new VmDiskInfo(rootDiskOffering, 5L, 1000L, 5000L));
        UserVmVO createdVm = mock(UserVmVO.class);

        stubAllocateBasics(cmd, backup, zone, owner, rootDiskOffering, createdVm);
        when(cmd.getServiceOfferingId()).thenReturn(null);
        when(backup.getDetail(ApiConstants.SERVICE_OFFERING_ID)).thenReturn("service-offering-uuid");
        when(serviceOfferingDao.findByUuid("service-offering-uuid")).thenReturn(serviceOffering);
        when(cmd.getTemplateId()).thenReturn(null);
        when(backup.getDetail(ApiConstants.TEMPLATE_ID)).thenReturn("iso-uuid");
        when(_templateDao.findByUuid("iso-uuid")).thenReturn(template);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.ISO);
        when(rootDiskOffering.getId()).thenReturn(44L);
        when(cmd.getDataDiskInfoList()).thenReturn(null);
        when(backupManager.getDataDiskInfoListFromBackup(backup)).thenReturn(dataDiskInfoList);
        when(cmd.getNetworkIds()).thenReturn(null);
        when(cmd.getIpToNetworkMap()).thenReturn(null);
        when(backupManager.getIpToNetworkMapFromBackup(eq(backup), eq(false), anyList())).thenReturn(new HashMap<>());

        UserVm result = service.allocateVMFromBackup(cmd, managerOperations);

        assertNotNull(result);
        verify(managerOperations).createVirtualMachine(eq(cmd), eq(zone), eq(owner), eq(serviceOffering), eq(template),
                nullable(HypervisorType.class), eq(44L), eq(10L), isNull(), eq(dataDiskInfoList), anyList(),
                anyMap(), nullable(Volume.class), nullable(Snapshot.class));
    }

    @Test
    public void testRestoreVMFromBackup()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        UserVmVO vm = mock(UserVmVO.class);
        Account owner = mock(Account.class);
        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
        List<String> sshKeyPairNames = List.of("key-one");

        when(cmd.getEntityId()).thenReturn(VM_ID);
        when(cmd.getBackupId()).thenReturn(BACKUP_ID);
        when(cmd.getEntityOwnerId()).thenReturn(ACCOUNT_ID);
        when(cmd.getSSHKeyPairNames()).thenReturn(sshKeyPairNames);
        when(cmd.getStartVm()).thenReturn(true);
        when(cmd.getHostId()).thenReturn(null);
        when(cmd.getDeploymentPlanner()).thenReturn(null);
        when(cmd.getDataDiskTemplateToDiskOfferingMap()).thenReturn(new HashMap<>());
        when(managerOperations.startVirtualMachine(eq(VM_ID), isNull(), isNull(), isNull(), anyMap(), isNull()))
                .thenReturn(new Pair<>(vm, params));
        when(_accountService.getActiveAccountById(ACCOUNT_ID)).thenReturn(owner);
        when(_vmDao.findById(VM_ID)).thenReturn(vm);
        when(managerOperations.resetVMSSHKeyInternal(vm, owner, sshKeyPairNames)).thenReturn(vm);
        when(managerOperations.startVirtualMachine(eq(VM_ID), isNull(), isNull(), isNull(), anyMap(), anyMap(), isNull()))
                .thenReturn(vm);

        UserVm result = service.restoreVMFromBackup(cmd, managerOperations);

        assertEquals(vm, result);
        verify(backupManager).restoreBackupToVM(BACKUP_ID, VM_ID);
        verify(managerOperations).resetVMSSHKeyInternal(vm, owner, sshKeyPairNames);
    }

    @Test
    public void testAllocateVMFromBackupWithVmSettingsRestoration()
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        BackupVO backup = mock(BackupVO.class);
        DataCenterVO zone = mock(DataCenterVO.class);
        Account owner = mock(Account.class);
        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        VMTemplateVO template = mock(VMTemplateVO.class);
        DiskOfferingVO rootDiskOffering = mock(DiskOfferingVO.class);
        List<VmDiskInfo> dataDiskInfoList = List.of(new VmDiskInfo(rootDiskOffering, 5L, 1000L, 5000L));
        List<Long> networkIds = List.of(11L);
        UserVmVO createdVm = mock(UserVmVO.class);
        UserVmVO savedVm = mock(UserVmVO.class);
        Map<String, String> existingDetails = new HashMap<>();
        existingDetails.put("existingKey", "existingValue");

        stubAllocateUsingCommandValues(cmd, backup, zone, owner, serviceOffering, template, rootDiskOffering, dataDiskInfoList, networkIds, createdVm);
        when(createdVm.getId()).thenReturn(22L);
        when(backup.getDetail(ApiConstants.VM_SETTINGS)).thenReturn("{\"key1\":\"value1\",\"key2\":\"value2\",\"existingKey\":\"backupValue\"}");
        when(_vmDao.findById(22L)).thenReturn(savedVm);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(22L)).thenReturn(existingDetails);

        UserVm result = service.allocateVMFromBackup(cmd, managerOperations);

        assertNotNull(result);
        verify(vmInstanceDetailsDao).listDetailsKeyPairs(22L);
        verify(savedVm).setDetail("key1", "value1");
        verify(savedVm).setDetail("key2", "value2");
        verify(_vmDao).saveDetails(savedVm);
    }

    @Test
    public void testAllocateVMFromBackupWithOverrideDiskOfferingComputeOnly()
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        BackupVO backup = mock(BackupVO.class);
        DataCenterVO zone = mock(DataCenterVO.class);
        Account owner = mock(Account.class);
        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        VMTemplateVO template = mock(VMTemplateVO.class);
        DiskOfferingVO rootDiskOffering = mock(DiskOfferingVO.class);
        DiskOfferingVO overrideDiskOffering = mock(DiskOfferingVO.class);
        List<VmDiskInfo> dataDiskInfoList = List.of(new VmDiskInfo(rootDiskOffering, 5L, 1000L, 5000L));
        List<Long> networkIds = List.of(11L);
        UserVmVO createdVm = mock(UserVmVO.class);

        stubAllocateUsingCommandValues(cmd, backup, zone, owner, serviceOffering, template, rootDiskOffering, dataDiskInfoList, networkIds, createdVm);
        when(cmd.getOverrideDiskOfferingId()).thenReturn(55L);
        when(_diskOfferingDao.findById(55L)).thenReturn(overrideDiskOffering);
        when(overrideDiskOffering.isComputeOnly()).thenReturn(true);

        UserVm result = service.allocateVMFromBackup(cmd, managerOperations);

        assertNotNull(result);
        verify(_diskOfferingDao).findById(55L);
        verify(overrideDiskOffering).isComputeOnly();
    }

    @Test
    public void allocateVMFromBackupRejectsMissingBackup() {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        when(cmd.getBackupId()).thenReturn(99L);
        when(backupDao.findById(99L)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.allocateVMFromBackup(cmd, managerOperations));
    }

    @Test
    public void allocateVMFromBackupRejectsUnsupportedProvider() {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        BackupVO backup = mock(BackupVO.class);
        when(cmd.getBackupId()).thenReturn(BACKUP_ID);
        when(backupDao.findById(BACKUP_ID)).thenReturn(backup);
        when(backup.getZoneId()).thenReturn(ZONE_ID);
        when(backupManager.canCreateInstanceFromBackup(BACKUP_ID)).thenReturn(false);

        assertThrows(CloudRuntimeException.class,
                () -> service.allocateVMFromBackup(cmd, managerOperations));
    }

    @Test
    public void restoreVMFromBackupExpungesAllocatedVmWhenRestoreFails()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        UserVmVO vm = mock(UserVmVO.class);
        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
        when(cmd.getEntityId()).thenReturn(VM_ID);
        when(cmd.getBackupId()).thenReturn(BACKUP_ID);
        when(managerOperations.startVirtualMachine(eq(VM_ID), isNull(), isNull(), isNull(), anyMap(), isNull()))
                .thenReturn(new Pair<>(vm, params));
        when(_vmDao.findById(VM_ID)).thenReturn(vm);
        doThrow(new CloudRuntimeException("restore failed")).when(backupManager).restoreBackupToVM(BACKUP_ID, VM_ID);

        assertThrows(CloudRuntimeException.class,
                () -> service.restoreVMFromBackup(cmd, managerOperations));
        verify(managerOperations).expunge(vm);
    }

    @Test
    public void allocateVMFromBackupUsesDefaultNetworkForUnmappedOvfNetworks()
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        BackupVO backup = mock(BackupVO.class);
        DataCenterVO zone = mock(DataCenterVO.class);
        Account owner = mock(Account.class);
        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        VMTemplateVO template = mock(VMTemplateVO.class);
        DiskOfferingVO rootDiskOffering = mock(DiskOfferingVO.class);
        List<VmDiskInfo> dataDiskInfoList = List.of(new VmDiskInfo(rootDiskOffering, 5L, 1000L, 5000L));
        UserVmVO createdVm = mock(UserVmVO.class);
        NetworkVO defaultNetwork = mock(NetworkVO.class);
        OVFNetworkTO firstNetwork = ovfNetwork(1);
        OVFNetworkTO secondNetwork = ovfNetwork(2);
        ArgumentCaptor<List<Long>> networkIdsCaptor = ArgumentCaptor.forClass(List.class);

        stubAllocateUsingCommandValues(cmd, backup, zone, owner, serviceOffering, template, rootDiskOffering, dataDiskInfoList, null, createdVm);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.OVA);
        when(template.getId()).thenReturn(TEMPLATE_ID);
        when(cmd.getVmNetworkMap()).thenReturn(new HashMap<>());
        when(templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(TEMPLATE_ID)).thenReturn(List.of(firstNetwork, secondNetwork));
        when(zone.isSecurityGroupEnabled()).thenReturn(false);
        when(zone.getId()).thenReturn(ZONE_ID);
        when(_networkModel.isSecurityGroupSupportedForZone(ZONE_ID)).thenReturn(false);
        when(managerOperations.getDefaultNetwork(zone, owner, true)).thenReturn(defaultNetwork);
        when(defaultNetwork.getId()).thenReturn(77L);

        service.allocateVMFromBackup(cmd, managerOperations);

        verify(managerOperations).createVirtualMachine(eq(cmd), eq(zone), eq(owner), eq(serviceOffering), eq(template),
                nullable(HypervisorType.class), isNull(), isNull(), eq(33L), eq(dataDiskInfoList), networkIdsCaptor.capture(),
                isNull(), nullable(Volume.class), nullable(Snapshot.class));
        assertEquals(List.of(77L, 77L), networkIdsCaptor.getValue());
    }

    private void stubAllocateUsingCommandValues(CreateVMFromBackupCmd cmd, BackupVO backup, DataCenterVO zone, Account owner,
            ServiceOfferingVO serviceOffering, VMTemplateVO template, DiskOfferingVO rootDiskOffering,
            List<VmDiskInfo> dataDiskInfoList, List<Long> networkIds, UserVm createdVm)
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        stubAllocateBasics(cmd, backup, zone, owner, rootDiskOffering, createdVm);
        when(cmd.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(serviceOffering);
        when(cmd.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findById(TEMPLATE_ID)).thenReturn(template);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        lenient().when(serviceOffering.getDiskOfferingId()).thenReturn(33L);
        when(cmd.getDataDiskInfoList()).thenReturn(dataDiskInfoList);
        when(cmd.getNetworkIds()).thenReturn(networkIds);
        when(cmd.getIpToNetworkMap()).thenReturn(null);
    }

    private void stubAllocateBasics(CreateVMFromBackupCmd cmd, BackupVO backup, DataCenterVO zone, Account owner,
            DiskOfferingVO rootDiskOffering, UserVm createdVm)
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        UserVmVO backupVm = new UserVmVO();
        Map<String, String> details = new HashMap<>();

        when(cmd.getBackupId()).thenReturn(BACKUP_ID);
        when(backupDao.findById(BACKUP_ID)).thenReturn(backup);
        when(backup.getZoneId()).thenReturn(ZONE_ID);
        when(backup.getVmId()).thenReturn(VM_ID);
        when(backupManager.canCreateInstanceFromBackup(BACKUP_ID)).thenReturn(true);
        when(cmd.getZoneId()).thenReturn(ZONE_ID);
        when(_dcDao.findById(ZONE_ID)).thenReturn(zone);
        when(cmd.getDetails()).thenReturn(details);
        when(_vmDao.findByIdIncludingRemoved(VM_ID)).thenReturn(backupVm);
        when(cmd.getDiskOfferingId()).thenReturn(null);
        when(cmd.getOverrideDiskOfferingId()).thenReturn(null);
        when(cmd.getSize()).thenReturn(null);
        when(backupManager.getRootDiskInfoFromBackup(backup)).thenReturn(new VmDiskInfo(rootDiskOffering, 10L, 1000L, 2000L));
        when(cmd.getEntityOwnerId()).thenReturn(ACCOUNT_ID);
        when(_accountService.getActiveAccountById(ACCOUNT_ID)).thenReturn(owner);
        when(cmd.getVmNetworkMap()).thenReturn(new HashMap<>());
        lenient().when(cmd.getPreserveIp()).thenReturn(false);
        when(managerOperations.createVirtualMachine(any(), any(), any(), any(), any(), nullable(HypervisorType.class),
                nullable(Long.class), nullable(Long.class), nullable(Long.class), nullable(List.class), nullable(List.class),
                nullable(Map.class), nullable(Volume.class), nullable(Snapshot.class))).thenReturn(createdVm);
    }

    private OVFNetworkTO ovfNetwork(int instanceId) {
        OVFNetworkTO network = new OVFNetworkTO();
        network.setInstanceID(instanceId);
        return network;
    }
}
