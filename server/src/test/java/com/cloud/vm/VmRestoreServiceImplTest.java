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
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.offering.DiskOffering;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@RunWith(MockitoJUnitRunner.class)
public class VmRestoreServiceImplTest {

    private static final long GIB_TO_BYTES = 1024L * 1024L * 1024L;
    private static final long USER_ID = 11L;
    private static final long VM_ID = 12L;
    private static final long ACCOUNT_ID = 13L;
    private static final long TEMPLATE_ID = 14L;
    private static final long ISO_ID = 15L;
    private static final long ZONE_ID = 16L;

    @InjectMocks
    private VmRestoreServiceImpl service;

    @Mock
    private UserDao _userDao;
    @Mock
    private UserVmDao _vmDao;
    @Mock
    private AccountDao _accountDao;
    @Mock
    private AccountManager _accountMgr;
    @Mock
    private VMTemplateDao _templateDao;
    @Mock
    private TemplateDataStoreDao _templateStoreDao;
    @Mock
    private VolumeDao _volsDao;
    @Mock
    private VMSnapshotDao _vmSnapshotDao;
    @Mock
    private DiskOfferingDao _diskOfferingDao;
    @Mock
    private ResourceLimitService _resourceLimitMgr;
    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @Mock
    private Account caller;
    @Mock
    private AccountVO owner;
    @Mock
    private UserVmVO vm;
    @Mock
    private VMTemplateVO currentTemplate;
    @Mock
    private VMTemplateVO template;
    @Mock
    private VolumeVO rootVolume;
    @Mock
    private VolumeVO secondRootVolume;
    @Mock
    private VMSnapshotVO vmSnapshot;
    @Mock
    private DiskOffering diskOffering;
    @Mock
    private VMInstanceDetailVO vmRootDiskSizeDetail;

    @Test
    public void restoreVirtualMachineRejectsMissingOwner() {
        stubLookupForVm();
        when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(_accountDao.findById(ACCOUNT_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.restoreVirtualMachine(caller, VM_ID, TEMPLATE_ID, null, false, null));
    }

    @Test
    public void restoreVirtualMachineRejectsDisabledOwner() {
        stubLookupForVm();
        when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(_accountDao.findById(ACCOUNT_ID)).thenReturn(owner);
        when(owner.getState()).thenReturn(Account.State.DISABLED);

        assertThrows(PermissionDeniedException.class,
                () -> service.restoreVirtualMachine(caller, VM_ID, TEMPLATE_ID, null, false, null));
    }

    @Test
    public void restoreVirtualMachineRejectsVmOutsideRunningOrStopped() {
        stubLookupForVm();
        when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(_accountDao.findById(ACCOUNT_ID)).thenReturn(owner);
        when(owner.getState()).thenReturn(Account.State.ENABLED);
        when(vm.getState()).thenReturn(VirtualMachine.State.Starting);
        when(vm.getUuid()).thenReturn("vm-uuid");

        assertThrows(CloudRuntimeException.class,
                () -> service.restoreVirtualMachine(caller, VM_ID, TEMPLATE_ID, null, false, null));
    }

    @Test
    public void restoreVirtualMachineRejectsMissingRootVolume() {
        stubLookupForVm();
        when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(_accountDao.findById(ACCOUNT_ID)).thenReturn(owner);
        when(owner.getState()).thenReturn(Account.State.ENABLED);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(_templateDao.findById(TEMPLATE_ID)).thenReturn(currentTemplate);
        when(_volsDao.findByInstanceAndType(VM_ID, Volume.Type.ROOT)).thenReturn(Collections.emptyList());

        assertThrows(InvalidParameterValueException.class,
                () -> service.restoreVirtualMachine(caller, VM_ID, TEMPLATE_ID, null, false, null));
    }

    @Test
    public void restoreVirtualMachineRejectsMultipleRootVolumesForNonDeployAsIsTemplate() {
        stubLookupForVm();
        when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(_accountDao.findById(ACCOUNT_ID)).thenReturn(owner);
        when(owner.getState()).thenReturn(Account.State.ENABLED);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(currentTemplate.isDeployAsIs()).thenReturn(false);
        when(_templateDao.findById(TEMPLATE_ID)).thenReturn(currentTemplate);
        when(_volsDao.findByInstanceAndType(VM_ID, Volume.Type.ROOT)).thenReturn(List.of(rootVolume, secondRootVolume));

        assertThrows(InvalidParameterValueException.class,
                () -> service.restoreVirtualMachine(caller, VM_ID, TEMPLATE_ID, null, false, null));
    }

    @Test
    public void restoreVirtualMachineRejectsExistingVmSnapshots() {
        stubLookupForVm();
        when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(_accountDao.findById(ACCOUNT_ID)).thenReturn(owner);
        when(owner.getState()).thenReturn(Account.State.ENABLED);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findById(TEMPLATE_ID)).thenReturn(currentTemplate);
        when(_volsDao.findByInstanceAndType(VM_ID, Volume.Type.ROOT)).thenReturn(List.of(rootVolume));
        when(_vmSnapshotDao.findByVm(VM_ID)).thenReturn(List.of(vmSnapshot));

        assertThrows(InvalidParameterValueException.class,
                () -> service.restoreVirtualMachine(caller, VM_ID, TEMPLATE_ID, null, false, null));
    }

    @Test
    public void restoreVirtualMachineRejectsReinstallingIsoBackedVmWithTemplate() {
        stubCommonRestoreValidationState();
        when(rootVolume.getTemplateId()).thenReturn(null);
        when(vm.getIsoId()).thenReturn(ISO_ID);
        when(_templateDao.findById(TEMPLATE_ID)).thenReturn(currentTemplate, template);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        doNothing().when(_accountMgr).checkAccess(caller, null, true, template);

        assertThrows(InvalidParameterValueException.class,
                () -> service.restoreVirtualMachine(caller, VM_ID, TEMPLATE_ID, null, false, null));
    }

    @Test
    public void restoreVirtualMachineRejectsReinstallingTemplateBackedVmWithIso() {
        stubCommonRestoreValidationState();
        when(rootVolume.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findById(TEMPLATE_ID)).thenReturn(currentTemplate, template);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.ISO);
        doNothing().when(_accountMgr).checkAccess(caller, null, true, template);

        assertThrows(InvalidParameterValueException.class,
                () -> service.restoreVirtualMachine(caller, VM_ID, TEMPLATE_ID, null, false, null));
    }

    @Test
    public void restoreVirtualMachineRejectsIsoBackedVmWithoutAttachedIso() {
        stubCommonRestoreValidationState();
        when(_templateDao.findById(TEMPLATE_ID)).thenReturn(currentTemplate);
        when(rootVolume.getTemplateId()).thenReturn(null);
        when(vm.getIsoId()).thenReturn(null);

        assertThrows(CloudRuntimeException.class,
                () -> service.restoreVirtualMachine(caller, VM_ID, null, null, false, null));
    }

    @Test
    public void restoreVirtualMachineRejectsTemplateMissingFromZone() {
        stubCommonRestoreValidationState();
        when(rootVolume.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(vm.getDataCenterId()).thenReturn(ZONE_ID);
        when(_templateDao.findById(TEMPLATE_ID)).thenReturn(currentTemplate, template);
        when(template.isDirectDownload()).thenReturn(false);
        when(template.getId()).thenReturn(TEMPLATE_ID);
        when(template.getUuid()).thenReturn("template-uuid");
        when(_templateStoreDao.findByTemplateZoneReady(TEMPLATE_ID, ZONE_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.restoreVirtualMachine(caller, VM_ID, null, null, false, null));
    }

    @Test
    public void getRootVolumeSizeForVmRestorePrefersDetailsRootSize() {
        when(template.getSize()).thenReturn(10L * GIB_TO_BYTES);
        when(vm.getId()).thenReturn(VM_ID);
        when(diskOffering.isCustomized()).thenReturn(false);
        when(diskOffering.getDiskSize()).thenReturn(8L * GIB_TO_BYTES);
        when(vmRootDiskSizeDetail.getValue()).thenReturn("20");
        when(vmInstanceDetailsDao.findDetail(VM_ID, VmDetailConstants.ROOT_DISK_SIZE)).thenReturn(vmRootDiskSizeDetail);
        Map<String, String> details = new HashMap<>();
        details.put(VmDetailConstants.ROOT_DISK_SIZE, "16");

        Long actual = service.getRootVolumeSizeForVmRestore(null, template, vm, diskOffering, details, false);

        assertEquals(16L * GIB_TO_BYTES, actual.longValue());
    }

    @Test
    public void getRootVolumeSizeForVmRestoreUsesPersistedRootSizeWhenDiskOfferingMissing() {
        when(template.getSize()).thenReturn(10L * GIB_TO_BYTES);
        when(vm.getId()).thenReturn(VM_ID);
        when(vmRootDiskSizeDetail.getValue()).thenReturn("20");
        when(vmInstanceDetailsDao.findDetail(VM_ID, VmDetailConstants.ROOT_DISK_SIZE)).thenReturn(vmRootDiskSizeDetail);

        Long actual = service.getRootVolumeSizeForVmRestore(null, template, vm, null, Collections.emptyMap(), false);

        assertEquals(20L * GIB_TO_BYTES, actual.longValue());
    }

    private void stubLookupForVm() {
        when(caller.getId()).thenReturn(USER_ID);
        when(_vmDao.findById(VM_ID)).thenReturn(vm);
    }

    private void stubCommonRestoreValidationState() {
        stubLookupForVm();
        when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(_accountDao.findById(ACCOUNT_ID)).thenReturn(owner);
        when(owner.getState()).thenReturn(Account.State.ENABLED);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_volsDao.findByInstanceAndType(VM_ID, Volume.Type.ROOT)).thenReturn(List.of(rootVolume));
        when(_vmSnapshotDao.findByVm(VM_ID)).thenReturn(Collections.emptyList());
    }
}
