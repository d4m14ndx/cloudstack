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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.vm.ResetVMPasswordCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMSSHKeyCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMUserDataCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.userdata.UserDataManager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.server.ManagementService;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;

/**
 * Spring component implementing password, SSH-key, and user-data reset for VMs.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 22).
 */
@Component
public class VmPasswordSSHKeyResetServiceImpl implements VmPasswordSSHKeyResetService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private UserVmDao _vmDao;
    @Inject
    private VMTemplateDao _templateDao;
    @Inject
    private NetworkDao _networkDao;
    @Inject
    private SSHKeyPairDao _sshKeyPairDao;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private NetworkOrchestrationService _networkMgr;
    @Inject
    private NetworkModel _networkModel;
    @Inject
    private UserDataManager userDataManager;
    @Inject
    private VmCredentialResetService vmCredentialResetService;
    @Inject
    private VmRebootService vmRebootService;
    @Inject
    private ManagementService _mgr;

    @Override
    public UserVm resetVMPassword(ResetVMPasswordCmd cmd, String password)
            throws ResourceUnavailableException, InsufficientCapacityException {
        Account caller = CallContext.current().getCallingAccount();
        Long vmId = cmd.getId();
        UserVmVO userVm = _vmDao.findById(cmd.getId());

        // Do parameters input validation
        if (userVm == null) {
            throw new InvalidParameterValueException("unable to find an Instance with id " + cmd.getId());
        }

        _vmDao.loadDetails(userVm);

        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(userVm.getTemplateId());
        if (template == null || !template.isEnablePassword()) {
            throw new InvalidParameterValueException("Fail to reset password for the Instance, the Template is not password enabled");
        }

        if (userVm.getState() == State.Error || userVm.getState() == State.Expunging) {
            logger.error("vm is not in the right state: {}", userVm);
            throw new InvalidParameterValueException(String.format("Vm %s is not in the right state", userVm));
        }

        if (userVm.getState() != State.Stopped) {
            logger.error("vm is not in the right state: {}", userVm);
            throw new InvalidParameterValueException(String.format("Vm %s should be stopped to do password reset", userVm));
        }

        _accountMgr.checkAccess(caller, null, true, userVm);

        boolean result = resetVMPasswordInternal(vmId, password);

        if (result) {
            userVm.setPassword(password);
        } else {
            throw new CloudRuntimeException("Failed to reset password for the Instance ");
        }

        return userVm;
    }

    @Override
    public boolean resetVMPasswordInternal(Long vmId, String password)
            throws ResourceUnavailableException, InsufficientCapacityException {
        Long userId = CallContext.current().getCallingUserId();
        VMInstanceVO vmInstance = _vmDao.findById(vmId);

        if (password == null || password.equals("")) {
            return false;
        }

        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());
        if (template.isEnablePassword()) {
            Nic defaultNic = _networkModel.getDefaultNic(vmId);
            if (defaultNic == null) {
                logger.error("Unable to reset password for Instance " + vmInstance + " as the Instance doesn't have default NIC");
                return false;
            }

            Network defaultNetwork = _networkDao.findById(defaultNic.getNetworkId());
            NicProfile defaultNicProfile = new NicProfile(defaultNic, defaultNetwork, null, null, null, _networkModel.isSecurityGroupSupportedInNetwork(defaultNetwork),
                    _networkModel.getNetworkTag(template.getHypervisorType(), defaultNetwork));
            VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(vmInstance);
            vmProfile.setParameter(VirtualMachineProfile.Param.VmPassword, password);

            UserDataServiceProvider element = _networkMgr.getPasswordResetProvider(defaultNetwork);
            if (element == null) {
                throw new CloudRuntimeException("Can't find network element for " + Service.UserData.getName() + " provider needed for password reset");
            }

            boolean result = element.savePassword(defaultNetwork, defaultNicProfile, vmProfile);

            // Need to reboot the virtual machine so that the password gets
            // redownloaded from the DomR, and reset on the VM
            if (!result) {
                logger.debug("Failed to reset password for the Instance; no need to reboot the Instance");
                return false;
            } else {
                final UserVmVO userVm = _vmDao.findById(vmId);
                _vmDao.loadDetails(userVm);
                // update the password in vm_details table too
                // Check if an SSH key pair was selected for the instance and if so
                // use it to encrypt & save the vm password
                vmCredentialResetService.encryptAndStorePassword(userVm, password);

                if (vmInstance.getState() == State.Stopped) {
                    logger.debug("Vm " + vmInstance + " is stopped, not rebooting it as a part of password reset");
                    return true;
                }

                if (vmRebootService.rebootVirtualMachineInternal(userId, vmId, false, false) == null) {
                    logger.warn("Failed to reboot the Instance " + vmInstance);
                    return false;
                } else {
                    logger.debug("Instance " + vmInstance + " is rebooted successfully as a part of password reset");
                    return true;
                }
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Reset password called for a Instance that is not using a password enabled Template");
            }
            return false;
        }
    }

    @Override
    public UserVm resetVMUserData(ResetVMUserDataCmd cmd)
            throws ResourceUnavailableException, InsufficientCapacityException {
        Account caller = CallContext.current().getCallingAccount();
        Long vmId = cmd.getId();
        UserVmVO userVm = _vmDao.findById(cmd.getId());

        if (userVm == null) {
            throw new InvalidParameterValueException("unable to find an Instance by id" + cmd.getId());
        }
        if (UserVmManager.SHAREDFSVM.equals(userVm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }
        if (Hypervisor.HypervisorType.External.equals(userVm.getHypervisorType())) {
            logger.error("Reset VM userdata not supported for {} as it is {} hypervisor instance",
                    userVm, Hypervisor.HypervisorType.External.name());
            throw new InvalidParameterValueException(String.format("Operation not supported for instance: %s",
                    userVm.getName()));
        }
        _accountMgr.checkAccess(caller, null, true, userVm);

        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(userVm.getTemplateId());

        // Do parameters input validation

        if (userVm.getState() != State.Stopped) {
            logger.error("vm ({}) should be stopped to do UserData reset. current state: {}", userVm, userVm.getState());
            throw new InvalidParameterValueException(String.format("VM %s should be stopped to do UserData reset", userVm));
        }

        String userData = cmd.getUserData();
        Long userDataId = cmd.getUserdataId();
        String userDataDetails = null;
        if (MapUtils.isNotEmpty(cmd.getUserdataDetails())) {
            userDataDetails = cmd.getUserdataDetails().toString();
        }
        userData = vmCredentialResetService.finalizeUserData(userData, userDataId, template);
        userData = userDataManager.validateUserData(userData, cmd.getHttpMethod());

        userVm.setUserDataId(userDataId);
        userVm.setUserData(userData);
        userVm.setUserDataDetails(userDataDetails);
        _vmDao.update(userVm.getId(), userVm);

        vmCredentialResetService.updateUserData(userVm);

        UserVmVO vm = _vmDao.findById(vmId);
        _vmDao.loadDetails(vm);
        return vm;
    }

    @Override
    public UserVm resetVMSSHKey(ResetVMSSHKeyCmd cmd)
            throws ResourceUnavailableException, InsufficientCapacityException {
        Account caller = CallContext.current().getCallingAccount();
        Account owner = _accountMgr.finalizeOwner(caller, cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
        UserVmVO userVm = _vmDao.findById(cmd.getId());

        if (userVm == null) {
            throw new InvalidParameterValueException("unable to find an Instance by id" + cmd.getId());
        }
        if (UserVmManager.SHAREDFSVM.equals(userVm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }
        if (Hypervisor.HypervisorType.External.equals(userVm.getHypervisorType())) {
            logger.error("Reset VM SSH key not supported for {} as it is {} hypervisor instance",
                    userVm, Hypervisor.HypervisorType.External.name());
            throw new InvalidParameterValueException(String.format("Operation not supported for instance: %s",
                    userVm.getName()));
        }

        // Do parameters input validation
        if (userVm.getState() == State.Error || userVm.getState() == State.Expunging) {
            logger.error("vm ({}) is not in the right state: {}", userVm, userVm.getState());
            throw new InvalidParameterValueException("Vm with specified id is not in the right state");
        }
        if (userVm.getState() != State.Stopped) {
            logger.error(String.format("vm (%s) is not in the stopped state. current state: %s", userVm, userVm.getState()));
            throw new InvalidParameterValueException("Vm " + userVm + " should be stopped to do SSH Key reset");
        }

        List<String> names = cmd.getNames();
        if (CollectionUtils.isEmpty(names)) {
            throw new InvalidParameterValueException("'keypair' or 'keypairs' must be specified");
        }

        userVm = resetVMSSHKeyInternal(userVm, owner, names);
        return userVm;
    }

    @Override
    public UserVmVO resetVMSSHKeyInternal(UserVmVO userVm, Account owner, List<String> names)
            throws ResourceUnavailableException, InsufficientCapacityException {
        Account caller = CallContext.current().getCallingAccount();

        String keypairnames = "";
        String sshPublicKeys = "";
        List<SSHKeyPairVO> pairs = new ArrayList<>();

        pairs = _sshKeyPairDao.findByNames(owner.getAccountId(), owner.getDomainId(), names);
        if (pairs == null || pairs.size() != names.size()) {
            throw new InvalidParameterValueException("Not all specified keypairs exist");
        }
        sshPublicKeys = pairs.stream().map(p -> p.getPublicKey()).collect(Collectors.joining("\n"));
        keypairnames = String.join(",", names);

        _accountMgr.checkAccess(caller, null, true, userVm);

        boolean result = resetVMSSHKeyInternal(userVm.getId(), sshPublicKeys, keypairnames);

        UserVmVO vm = _vmDao.findById(userVm.getId());
        _vmDao.loadDetails(vm);
        if (!result) {
            throw new CloudRuntimeException("Failed to reset SSH Key for the Instance ");
        }

        vmCredentialResetService.removeEncryptedPasswordFromUserVmVoDetails(userVm.getId());

        _vmDao.loadDetails(userVm);
        return userVm;
    }

    private boolean resetVMSSHKeyInternal(Long vmId, String sshPublicKeys, String keypairnames)
            throws ResourceUnavailableException, InsufficientCapacityException {
        Long userId = CallContext.current().getCallingUserId();
        VMInstanceVO vmInstance = _vmDao.findById(vmId);

        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());
        Nic defaultNic = _networkModel.getDefaultNic(vmId);
        if (defaultNic == null) {
            logger.error("Unable to reset SSH Key for Instance " + vmInstance + " as the Instance doesn't have default NIC");
            return false;
        }

        Network defaultNetwork = _networkDao.findById(defaultNic.getNetworkId());
        NicProfile defaultNicProfile = new NicProfile(defaultNic, defaultNetwork, null, null, null, _networkModel.isSecurityGroupSupportedInNetwork(defaultNetwork),
                _networkModel.getNetworkTag(template.getHypervisorType(), defaultNetwork));

        VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(vmInstance);

        UserDataServiceProvider element = _networkMgr.getSSHKeyResetProvider(defaultNetwork);
        if (element == null) {
            throw new CloudRuntimeException("Can't find network element for " + Service.UserData.getName() + " provider needed for SSH Key reset");
        }
        boolean result = element.saveSSHKey(defaultNetwork, defaultNicProfile, vmProfile, sshPublicKeys);
        // Need to reboot the Instance so that the password gets redownloaded from the DomR, and reset on the VM
        if (!result) {
            logger.debug("Failed to reset SSH Key for the Instance; no need to reboot the Instance");
            return false;
        } else {
            final UserVmVO userVm = _vmDao.findById(vmId);
            _vmDao.loadDetails(userVm);
            userVm.setDetail(VmDetailConstants.SSH_PUBLIC_KEY, sshPublicKeys);
            userVm.setDetail(VmDetailConstants.SSH_KEY_PAIR_NAMES, keypairnames);
            _vmDao.saveDetails(userVm);

            if (vmInstance.getState() == State.Stopped) {
                logger.debug("Vm " + vmInstance + " is stopped, not rebooting it as a part of SSH Key reset");
                return true;
            }
            if (vmRebootService.rebootVirtualMachineInternal(userId, vmId, false, false) == null) {
                logger.warn("Failed to reboot the vm " + vmInstance);
                return false;
            } else {
                logger.debug("Vm " + vmInstance + " is rebooted successfully as a part of SSH Key reset");
                return true;
            }
        }
    }

    @Override
    public String getCurrentVmPasswordOrDefineNewPassword(String newPassword, UserVmVO vm, VMTemplateVO template) {
        String password = "saved_password";

        if (template.isEnablePassword()) {
            if (vm.getDetail("password") != null) {
                logger.debug(String.format("Decrypting VM [%s] current password.", vm));
                password = DBEncryptionUtil.decrypt(vm.getDetail("password"));
            } else if (StringUtils.isNotBlank(newPassword)) {
                logger.debug(String.format("A password for VM [%s] was informed. Setting VM password to value defined by user.", vm));
                password = newPassword;
                vm.setPassword(password);
            } else {
                logger.debug(String.format("Setting VM [%s] password to a randomly generated password.", vm));
                password = _mgr.generateRandomPassword();
                vm.setPassword(password);
            }
        } else if (StringUtils.isNotBlank(newPassword)) {
            logger.debug(String.format("A password was informed; however, the template [%s] is not password enabled. Ignoring the parameter.", template));
        }

        return password;
    }
}
