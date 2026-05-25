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

import jakarta.inject.Inject;

import org.apache.cloudstack.userdata.UserDataManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.UserData;
import com.cloud.user.dao.UserDataDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.crypt.RSAHelper;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

/**
 * Userdata propagation, userdata finalization, password encryption, and
 * SSH-key detail management — the credential-reset helpers extracted from
 * {@link UserVmManagerImpl}.
 *
 * @see VmCredentialResetService
 */
@Component
public class VmCredentialResetServiceImpl implements VmCredentialResetService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private NicDao nicDao;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private UserDataDao userDataDao;
    @Inject
    private UserDataManager userDataManager;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    // ---- updateUserData / applyUserData ----

    @Override
    public void updateUserData(UserVm vm) throws ResourceUnavailableException, InsufficientCapacityException {
        boolean result = updateUserDataInternal(vm);
        if (result) {
            logger.debug("User data successfully updated for vm id:  {}", vm);
        } else {
            throw new CloudRuntimeException("Failed to reset userdata for the virtual machine ");
        }
    }

    private boolean updateUserDataInternal(UserVm vm) throws ResourceUnavailableException, InsufficientCapacityException {
        VMTemplateVO template = templateDao.findByIdIncludingRemoved(vm.getTemplateId());

        List<? extends Nic> nics = nicDao.listByVmId(vm.getId());
        if (nics == null || nics.isEmpty()) {
            logger.error("unable to find any nics for vm {}", vm);
            return false;
        }

        boolean userDataApplied = false;
        for (Nic nic : nics) {
            userDataApplied |= applyUserData(template.getHypervisorType(), vm, nic);
        }
        return userDataApplied;
    }

    @Override
    public boolean applyUserData(HypervisorType hyperVisorType, UserVm vm, Nic nic)
            throws ResourceUnavailableException, InsufficientCapacityException {
        NetworkVO network = networkDao.findById(nic.getNetworkId());
        NicProfile nicProfile = new NicProfile(nic, network, null, null, null,
                networkModel.isSecurityGroupSupportedInNetwork(network),
                networkModel.getNetworkTag(hyperVisorType, network));
        VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(vm);

        if (networkModel.areServicesSupportedByNetworkOffering(network.getNetworkOfferingId(), Service.UserData)) {
            UserDataServiceProvider element = networkModel.getUserDataUpdateProvider(network);
            if (element == null) {
                throw new CloudRuntimeException("Can't find network element for " + Service.UserData.getName() + " provider needed for UserData update");
            }
            boolean result = element.saveUserData(network, nicProfile, vmProfile);
            if (!result) {
                logger.error("Failed to update userdata for vm " + vm + " and nic " + nic);
            } else {
                return true;
            }
        } else {
            logger.debug("Not applying userdata for nic {} in vm {} because it is not supported in network {}", nic, vmProfile, network);
        }
        return false;
    }

    // ---- finalizeUserData ----

    @Override
    public String finalizeUserData(String userData, Long userDataId, VirtualMachineTemplate template) {
        if (StringUtils.isEmpty(userData) && userDataId == null && (template == null || template.getUserDataId() == null)) {
            return null;
        }

        if (userDataId != null && StringUtils.isNotEmpty(userData)) {
            throw new InvalidParameterValueException("Both userdata and userdata ID inputs are not allowed, please provide only one");
        }
        if (template != null && template.getUserDataId() != null) {
            switch (template.getUserDataOverridePolicy()) {
                case DENYOVERRIDE:
                    if (StringUtils.isNotEmpty(userData) || userDataId != null) {
                        String msg = String.format("UserData input is not allowed here since template %s is configured to deny any userdata", template.getName());
                        throw new CloudRuntimeException(msg);
                    }
                case ALLOWOVERRIDE:
                    if (userDataId != null) {
                        UserData apiUserDataVO = userDataDao.findById(userDataId);
                        return apiUserDataVO.getUserData();
                    } else if (StringUtils.isNotEmpty(userData)) {
                        return userData;
                    } else {
                        UserData templateUserDataVO = userDataDao.findById(template.getUserDataId());
                        if (templateUserDataVO == null) {
                            String msg = String.format("UserData linked to the template %s is not found", template.getName());
                            throw new CloudRuntimeException(msg);
                        }
                        return templateUserDataVO.getUserData();
                    }
                case APPEND:
                    UserData templateUserDataVO = userDataDao.findById(template.getUserDataId());
                    if (templateUserDataVO == null) {
                        String msg = String.format("UserData linked to the template %s is not found", template.getName());
                        throw new CloudRuntimeException(msg);
                    }
                    if (userDataId != null) {
                        UserData apiUserDataVO = userDataDao.findById(userDataId);
                        return userDataManager.concatenateUserData(templateUserDataVO.getUserData(), apiUserDataVO.getUserData(), null);
                    } else if (StringUtils.isNotEmpty(userData)) {
                        return userDataManager.concatenateUserData(templateUserDataVO.getUserData(), userData, null);
                    } else {
                        return templateUserDataVO.getUserData();
                    }
                default:
                    String msg = String.format("This userdataPolicy %s is not supported for use with this feature", template.getUserDataOverridePolicy().toString());
                    throw new CloudRuntimeException(msg);
            }
        } else {
            if (userDataId != null) {
                UserData apiUserDataVO = userDataDao.findById(userDataId);
                return apiUserDataVO.getUserData();
            } else if (StringUtils.isNotEmpty(userData)) {
                return userData;
            }
        }
        return null;
    }

    // ---- password / SSH key helpers ----

    @Override
    public void encryptAndStorePassword(UserVmVO vm, String password) {
        String sshPublicKeys = vm.getDetail(VmDetailConstants.SSH_PUBLIC_KEY);
        if (sshPublicKeys != null && !sshPublicKeys.equals("") && password != null && !password.equals("saved_password")) {
            if (!sshPublicKeys.startsWith("ssh-rsa")) {
                logger.warn("Only RSA public keys can be used to encrypt a vm password.");
                return;
            }
            String encryptedPasswd = RSAHelper.encryptWithSSHPublicKey(sshPublicKeys, password);
            if (encryptedPasswd == null) {
                throw new CloudRuntimeException("Error encrypting password");
            }

            vm.setDetail(VmDetailConstants.ENCRYPTED_PASSWORD, encryptedPasswd);
            userVmDao.saveDetails(vm);
        }
    }

    @Override
    public void removeEncryptedPasswordFromUserVmVoDetails(long vmId) {
        vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.ENCRYPTED_PASSWORD);
    }
}
