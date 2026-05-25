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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.affinity.AffinityGroupService;
import org.apache.cloudstack.affinity.AffinityGroupVO;
import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.security.SecurityGroupVO;
import com.cloud.network.security.dao.SecurityGroupDao;
import com.cloud.offering.DiskOffering;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Storage.TemplateType;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.dao.SSHKeyPairDao;

@RunWith(MockitoJUnitRunner.class)
public class VmAllocationValidationServiceTest {

    private static final long ZONE_ID = 11L;
    private static final long OWNER_ACCOUNT_ID = 22L;
    private static final long OWNER_DOMAIN_ID = 33L;

    @Mock
    private AccountManager accountManager;
    @Mock
    private SecurityGroupDao securityGroupDao;
    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private AffinityGroupDao affinityGroupDao;
    @Mock
    private AffinityGroupService affinityGroupService;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private SnapshotDataFactory snapshotDataFactory;
    @Mock
    private VMTemplateZoneDao templateZoneDao;
    @Mock
    private SSHKeyPairDao sshKeyPairDao;
    @Mock
    private DataCenter zone;
    @Mock
    private Account owner;
    @Mock
    private Account caller;

    private VmAllocationValidationService service;
    private VMTemplateVO template;

    @Before
    public void setUp() {
        service = new VmAllocationValidationService();
        ReflectionTestUtils.setField(service, "accountManager", accountManager);
        ReflectionTestUtils.setField(service, "securityGroupDao", securityGroupDao);
        ReflectionTestUtils.setField(service, "templateDao", templateDao);
        ReflectionTestUtils.setField(service, "affinityGroupDao", affinityGroupDao);
        ReflectionTestUtils.setField(service, "affinityGroupService", affinityGroupService);
        ReflectionTestUtils.setField(service, "storagePoolDao", storagePoolDao);
        ReflectionTestUtils.setField(service, "snapshotDataFactory", snapshotDataFactory);
        ReflectionTestUtils.setField(service, "templateZoneDao", templateZoneDao);
        ReflectionTestUtils.setField(service, "sshKeyPairDao", sshKeyPairDao);

        when(zone.getId()).thenReturn(ZONE_ID);
        when(owner.getAccountId()).thenReturn(OWNER_ACCOUNT_ID);
        when(owner.getDomainId()).thenReturn(OWNER_DOMAIN_ID);

        template = new VMTemplateVO(101L, "template-101", "template", null, true, false, TemplateType.USER, null, null, false, 64,
                OWNER_ACCOUNT_ID, null, "template", false, 1L, true, HypervisorType.KVM);
        when(storagePoolDao.countPoolsByStatus(StoragePoolStatus.Up)).thenReturn(1L);
        when(templateZoneDao.listByZoneTemplate(ZONE_ID, template.getId())).thenReturn(Collections.singletonList(new VMTemplateZoneVO(ZONE_ID, template.getId(), null)));
    }

    @Test
    public void missingSecurityGroupPreservesMessage() {
        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.validatePrePersistenceInputs(zone, owner, caller, Collections.singletonList(7L), null, null, template,
                        HypervisorType.KVM, "User", false, null, null));

        assertEquals("Unable to find security group by id 7", exception.getMessage());
    }

    @Test
    public void securityGroupPermissionCheckUsesCallerOwnerAndGroup() throws Exception {
        SecurityGroupVO securityGroup = mock(SecurityGroupVO.class);
        when(securityGroupDao.findById(7L)).thenReturn(securityGroup);

        service.validatePrePersistenceInputs(zone, owner, caller, Collections.singletonList(7L), null, null, template,
                HypervisorType.KVM, "User", false, null, null);

        verify(accountManager).checkAccess(caller, null, true, owner, securityGroup);
    }

    @Test
    public void datadiskTemplateMustBelongToTemplate() {
        VMTemplateVO datadiskTemplate = datadiskTemplate(202L, 999L, 1L);
        when(templateDao.findById(202L)).thenReturn(datadiskTemplate);
        DiskOffering diskOffering = mock(DiskOffering.class);
        Map<Long, DiskOffering> datadiskMap = new HashMap<>();
        datadiskMap.put(202L, diskOffering);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.validatePrePersistenceInputs(zone, owner, caller, null, null, datadiskMap, template,
                        HypervisorType.KVM, "User", false, null, null));

        assertEquals(String.format("Invalid Datadisk Template. Specified Datadisk Template %s doesn't belong to Template %s", datadiskTemplate, template), exception.getMessage());
    }

    @Test
    public void rootAdminCannotUseAccountAffinityGroupFromDifferentOwner() {
        AffinityGroupVO affinityGroup = mock(AffinityGroupVO.class);
        when(affinityGroupDao.findById(8L)).thenReturn(affinityGroup);
        when(affinityGroup.getType()).thenReturn("host anti-affinity");
        when(affinityGroup.getAclType()).thenReturn(ACLType.Account);
        when(affinityGroup.getAccountId()).thenReturn(444L);
        when(caller.getId()).thenReturn(Account.ACCOUNT_ID_SYSTEM);
        when(affinityGroupService.isAffinityGroupProcessorAvailable("host anti-affinity")).thenReturn(true);

        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.validatePrePersistenceInputs(zone, owner, caller, null, Collections.singletonList(8L), null, template,
                        HypervisorType.KVM, "User", false, null, null));

        assertEquals("Affinity Group " + affinityGroup + " does not belong to the VM's account", exception.getMessage());
    }

    @Test
    public void volumeMustBelongToZone() {
        Volume volume = mock(Volume.class);
        when(volume.getDataCenterId()).thenReturn(44L);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.validatePrePersistenceInputs(zone, owner, caller, null, null, null, template,
                        HypervisorType.KVM, "User", false, volume, null));

        assertEquals("The volume's zone [44] is not the same as the provided zone [11]", exception.getMessage());
    }

    @Test
    public void snapshotMustExistOnZone() {
        Snapshot snapshot = mock(Snapshot.class);
        when(snapshot.getId()).thenReturn(55L);
        when(snapshotDataFactory.getSnapshots(55L, ZONE_ID)).thenReturn(Collections.emptyList());

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.validatePrePersistenceInputs(zone, owner, caller, null, null, null, template,
                        HypervisorType.KVM, "User", false, null, snapshot));

        assertEquals("The snapshot does not exist on zone 11", exception.getMessage());
    }

    @Test
    public void missingTemplateZonePreservesMessage() {
        when(templateZoneDao.listByZoneTemplate(ZONE_ID, template.getId())).thenReturn(Collections.emptyList());

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.validatePrePersistenceInputs(zone, owner, caller, null, null, null, template,
                        HypervisorType.KVM, "User", false, null, null));

        assertEquals("The template 101 is not available for use", exception.getMessage());
    }

    @Test
    public void nonBootableIsoIsRejected() {
        template.setBootable(false);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.validatePrePersistenceInputs(zone, owner, caller, null, null, null, template,
                        HypervisorType.KVM, "User", true, null, null));

        assertEquals(String.format("Installing from ISO requires an ISO that is bootable: %s", template), exception.getMessage());
    }

    @Test
    public void sshKeyPairsResolvePublicKeysAndNames() throws Exception {
        SSHKeyPairVO firstPair = keyPair("first", "ssh-rsa first");
        SSHKeyPairVO secondPair = keyPair("second", "ssh-rsa second");
        List<String> requestedKeys = Arrays.asList("first", "second");
        when(sshKeyPairDao.findByNames(OWNER_ACCOUNT_ID, OWNER_DOMAIN_ID, requestedKeys)).thenReturn(Arrays.asList(firstPair, secondPair));

        VmAllocationValidationService.ResolvedSshKeyPairs resolved = service.resolveSshKeyPairs(owner, requestedKeys);

        assertEquals("ssh-rsa first\nssh-rsa second", resolved.getPublicKeys());
        assertEquals("first,second", resolved.getNames());
    }

    @Test
    public void missingSshKeyPairPreservesMessage() {
        List<String> requestedKeys = Arrays.asList("first", "second");
        when(sshKeyPairDao.findByNames(OWNER_ACCOUNT_ID, OWNER_DOMAIN_ID, requestedKeys)).thenReturn(Collections.singletonList(keyPair("first", "ssh-rsa first")));

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.resolveSshKeyPairs(owner, requestedKeys));

        assertEquals("Not all specified keypairs exist", exception.getMessage());
    }

    @Test
    public void snapshotPresenceAllowsValidation() throws Exception {
        Snapshot snapshot = mock(Snapshot.class);
        SnapshotInfo snapshotInfo = mock(SnapshotInfo.class);
        when(snapshot.getId()).thenReturn(55L);
        when(snapshotDataFactory.getSnapshots(55L, ZONE_ID)).thenReturn(Collections.singletonList(snapshotInfo));

        service.validatePrePersistenceInputs(zone, owner, caller, null, null, null, template,
                HypervisorType.KVM, "User", false, null, snapshot);
    }

    private VMTemplateVO datadiskTemplate(long id, long parentTemplateId, long size) {
        VMTemplateVO datadiskTemplate = new VMTemplateVO(id, "datadisk-" + id, "datadisk", null, true, false, TemplateType.DATADISK, null, null,
                false, 64, OWNER_ACCOUNT_ID, null, "datadisk", false, 1L, true, HypervisorType.KVM);
        datadiskTemplate.setParentTemplateId(parentTemplateId);
        datadiskTemplate.setSize(size);
        return datadiskTemplate;
    }

    private SSHKeyPairVO keyPair(String name, String publicKey) {
        SSHKeyPairVO keyPair = new SSHKeyPairVO();
        keyPair.setName(name);
        keyPair.setPublicKey(publicKey);
        return keyPair;
    }
}
