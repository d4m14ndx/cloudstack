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

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.affinity.AffinityGroupService;
import org.apache.cloudstack.affinity.AffinityGroupVO;
import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.security.SecurityGroup;
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
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.dao.SSHKeyPairDao;

@Component
public class VmAllocationValidationService {

    @Inject
    private AccountManager accountManager;
    @Inject
    private SecurityGroupDao securityGroupDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private AffinityGroupDao affinityGroupDao;
    @Inject
    private AffinityGroupService affinityGroupService;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private SnapshotDataFactory snapshotDataFactory;
    @Inject
    private VMTemplateZoneDao templateZoneDao;
    @Inject
    private SSHKeyPairDao sshKeyPairDao;

    public void validatePrePersistenceInputs(DataCenter zone, Account owner, Account caller,
            List<Long> securityGroupIdList, List<Long> affinityGroupIdList,
            Map<Long, DiskOffering> datadiskTemplateToDiskOfferringMap, VMTemplateVO template,
            HypervisorType hypervisorType, String vmType, boolean isIso, Volume volume, Snapshot snapshot) throws ResourceUnavailableException {
        verifySecurityGroups(owner, caller, securityGroupIdList);
        verifyDatadiskTemplates(template, datadiskTemplateToDiskOfferringMap);
        verifyAffinityGroups(owner, caller, affinityGroupIdList);
        verifyAvailableStoragePools(hypervisorType);
        verifyTemplateAndSourceAvailability(zone, template, vmType, isIso, volume, snapshot);
        accountManager.checkAccess(owner, AccessType.UseEntry, false, template);
    }

    private void verifySecurityGroups(Account owner, Account caller, List<Long> securityGroupIdList) {
        if (securityGroupIdList != null) {
            for (Long securityGroupId : securityGroupIdList) {
                SecurityGroup sg = securityGroupDao.findById(securityGroupId);
                if (sg == null) {
                    throw new InvalidParameterValueException("Unable to find security group by id " + securityGroupId);
                } else {
                    accountManager.checkAccess(caller, null, true, owner, sg);
                }
            }
        }
    }

    private void verifyDatadiskTemplates(VMTemplateVO template, Map<Long, DiskOffering> datadiskTemplateToDiskOfferringMap) {
        if (datadiskTemplateToDiskOfferringMap != null && !datadiskTemplateToDiskOfferringMap.isEmpty()) {
            for (Entry<Long, DiskOffering> datadiskTemplateToDiskOffering : datadiskTemplateToDiskOfferringMap.entrySet()) {
                VMTemplateVO dataDiskTemplate = templateDao.findById(datadiskTemplateToDiskOffering.getKey());
                DiskOffering dataDiskOffering = datadiskTemplateToDiskOffering.getValue();

                if (dataDiskTemplate == null
                        || (!dataDiskTemplate.getTemplateType().equals(TemplateType.DATADISK)) && (dataDiskTemplate.getState().equals(VirtualMachineTemplate.State.Active))) {
                    throw new InvalidParameterValueException("Invalid Template ID specified for Datadisk Template" + datadiskTemplateToDiskOffering.getKey());
                }
                if (!dataDiskTemplate.getParentTemplateId().equals(template.getId())) {
                    throw new InvalidParameterValueException(String.format("Invalid Datadisk Template. Specified Datadisk Template %s doesn't belong to Template %s", dataDiskTemplate, template));
                }
                if (dataDiskOffering == null) {
                    throw new InvalidParameterValueException(String.format("Invalid disk offering %s specified for datadisk Template %s", datadiskTemplateToDiskOffering.getValue(), dataDiskTemplate));
                }
                if (dataDiskOffering.isCustomized()) {
                    throw new InvalidParameterValueException(String.format("Invalid disk offering %s specified for datadisk Template %s. Custom Disk offerings are not supported for Datadisk Templates", dataDiskOffering, dataDiskTemplate));
                }
                if (dataDiskOffering.getDiskSize() < dataDiskTemplate.getSize()) {
                    throw new InvalidParameterValueException(String.format("Invalid disk offering %s specified for datadisk Template %s. Disk offering size should be greater than or equal to the Template size", dataDiskOffering, dataDiskTemplate));
                }
                templateDao.loadDetails(dataDiskTemplate);
            }
        }
    }

    private void verifyAffinityGroups(Account owner, Account caller, List<Long> affinityGroupIdList) {
        if (affinityGroupIdList != null) {
            for (Long affinityGroupId : affinityGroupIdList) {
                AffinityGroupVO ag = affinityGroupDao.findById(affinityGroupId);
                if (ag == null) {
                    throw new InvalidParameterValueException("Unable to find affinity group " + ag);
                } else if (!affinityGroupService.isAffinityGroupProcessorAvailable(ag.getType())) {
                    throw new InvalidParameterValueException("Affinity group type is not supported for group: " + ag + " ,type: " + ag.getType()
                            + " , Please try again after removing the affinity group");
                } else {
                    verifyAffinityGroupAccess(owner, caller, ag);
                }
            }
        }
    }

    private void verifyAffinityGroupAccess(Account owner, Account caller, AffinityGroupVO ag) {
        if (ag.getAclType() == ACLType.Domain) {
            accountManager.checkAccess(caller, null, false, owner, ag);
            if (caller.getId() == Account.ACCOUNT_ID_SYSTEM || accountManager.isRootAdmin(caller.getId())) {
                if (!affinityGroupService.isAffinityGroupAvailableInDomain(ag.getId(), owner.getDomainId())) {
                    throw new PermissionDeniedException("Affinity Group " + ag + " does not belong to the VM's domain");
                }
            }
        } else {
            accountManager.checkAccess(caller, null, true, owner, ag);
            if (caller.getId() == Account.ACCOUNT_ID_SYSTEM || accountManager.isRootAdmin(caller.getId())) {
                if (ag.getAccountId() != owner.getAccountId()) {
                    throw new PermissionDeniedException("Affinity Group " + ag + " does not belong to the VM's account");
                }
            }
        }
    }

    private void verifyAvailableStoragePools(HypervisorType hypervisorType) throws StorageUnavailableException {
        if (hypervisorType != HypervisorType.BareMetal && hypervisorType != HypervisorType.External) {
            long availablePools = storagePoolDao.countPoolsByStatus(StoragePoolStatus.Up);
            if (availablePools < 1) {
                throw new StorageUnavailableException("There are no available pools in the UP state for vm deployment", -1);
            }
        }
    }

    private void verifyTemplateAndSourceAvailability(DataCenter zone, VMTemplateVO template, String vmType, boolean isIso, Volume volume, Snapshot snapshot) {
        if (TemplateType.SYSTEM.equals(template.getTemplateType()) && !UserVmManager.CKS_NODE.equals(vmType) && !UserVmManager.SHAREDFSVM.equals(vmType)) {
            throw new InvalidParameterValueException(String.format("Unable to use system template %s to deploy a user vm", template));
        }

        if (volume != null) {
            if (zone.getId() != volume.getDataCenterId()) {
                throw new InvalidParameterValueException(String.format("The volume's zone [%s] is not the same as the provided zone [%s]", volume.getDataCenterId(), zone.getId()));
            }
        } else if (snapshot != null) {
            List<SnapshotInfo> snapshotsOnZone = snapshotDataFactory.getSnapshots(snapshot.getId(), zone.getId());
            if (CollectionUtils.isEmpty(snapshotsOnZone)) {
                throw new InvalidParameterValueException("The snapshot does not exist on zone " + zone.getId());
            }
        } else {
            List<VMTemplateZoneVO> listZoneTemplate = templateZoneDao.listByZoneTemplate(zone.getId(), template.getId());
            if (listZoneTemplate == null || listZoneTemplate.isEmpty()) {
                throw new InvalidParameterValueException("The template " + template.getId() + " is not available for use");
            }
        }

        if (isIso && !template.isBootable()) {
            throw new InvalidParameterValueException(String.format("Installing from ISO requires an ISO that is bootable: %s", template));
        }
    }

    public ResolvedSshKeyPairs resolveSshKeyPairs(Account owner, List<String> sshKeyPairs) {
        String sshPublicKeys = "";
        String keypairnames = "";
        if (!sshKeyPairs.isEmpty()) {
            List<SSHKeyPairVO> pairs = sshKeyPairDao.findByNames(owner.getAccountId(), owner.getDomainId(), sshKeyPairs);
            if (pairs == null || pairs.size() != sshKeyPairs.size()) {
                throw new InvalidParameterValueException("Not all specified keypairs exist");
            }

            sshPublicKeys = pairs.stream().map(p -> p.getPublicKey()).collect(Collectors.joining("\n"));
            keypairnames = String.join(",", sshKeyPairs);
        }
        return new ResolvedSshKeyPairs(sshPublicKeys, keypairnames);
    }

    public static class ResolvedSshKeyPairs {
        private final String publicKeys;
        private final String names;

        public ResolvedSshKeyPairs(String publicKeys, String names) {
            this.publicKeys = publicKeys;
            this.names = names;
        }

        public String getPublicKeys() {
            return publicKeys;
        }

        public String getNames() {
            return names;
        }
    }
}
