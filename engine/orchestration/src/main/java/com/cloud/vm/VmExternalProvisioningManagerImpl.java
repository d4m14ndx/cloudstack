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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Inject;

import org.apache.cloudstack.framework.extensions.dao.ExtensionDetailsDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PrepareExternalProvisioningAnswer;
import com.cloud.agent.api.PrepareExternalProvisioningCommand;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.dc.DataCenter;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;

/**
 * External-hypervisor provisioning handshake and command-decoration — extracted
 * from {@link VirtualMachineManagerImpl}.
 *
 * @see VmExternalProvisioningManager
 */
@Component
public class VmExternalProvisioningManagerImpl implements VmExternalProvisioningManager {

    private static final Logger logger = LogManager.getLogger(VmExternalProvisioningManagerImpl.class);

    @Inject
    private AgentManager agentMgr;
    @Inject
    private NicDao nicsDao;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private ExtensionsManager extensionsManager;
    @Inject
    private ExtensionDetailsDao extensionDetailsDao;
    @Inject
    private NetworkService networkService;
    @Inject
    private HostDao hostDao;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private HypervisorGuruManager hvGuruMgr;

    @Override
    public void updateVmMetadataManufacturerAndProduct(VirtualMachineTO vmTO, VMInstanceVO vm) {
        String metadataManufacturer = VirtualMachineManager.VmMetadataManufacturer.valueIn(vm.getDataCenterId());
        if (StringUtils.isBlank(metadataManufacturer)) {
            metadataManufacturer = VirtualMachineManager.VmMetadataManufacturer.defaultValue();
        }
        vmTO.setMetadataManufacturer(metadataManufacturer);
        String metadataProduct = VirtualMachineManager.VmMetadataProductName.valueIn(vm.getDataCenterId());
        if (StringUtils.isBlank(metadataProduct)) {
            metadataProduct = String.format("CloudStack %s Hypervisor", vm.getHypervisorType().toString());
        }
        vmTO.setMetadataProductName(metadataProduct);
    }

    @Override
    public void updateExternalVmDetailsFromPrepareAnswer(VirtualMachineTO vmTO, UserVmVO userVmVO,
            Map<String, String> newDetails) {
        if (newDetails == null || newDetails.equals(vmTO.getDetails())) {
            return;
        }
        vmTO.setDetails(newDetails);
        userVmVO.setDetails(newDetails);
        userVmDao.saveDetails(userVmVO);
    }

    @Override
    public void updateExternalVmDataFromPrepareAnswer(VirtualMachineTO vmTO, VirtualMachineTO updatedTO) {
        final String vncPassword = updatedTO.getVncPassword();
        final Map<String, String> details = updatedTO.getDetails();
        if ((vncPassword == null || vncPassword.equals(vmTO.getVncPassword())) &&
                (details == null || details.equals(vmTO.getDetails()))) {
            return;
        }
        UserVmVO userVmVO = userVmDao.findById(vmTO.getId());
        if (userVmVO == null) {
            return;
        }
        if (vncPassword != null && !vncPassword.equals(userVmVO.getPassword())) {
            userVmVO.setVncPassword(vncPassword);
            vmTO.setVncPassword(vncPassword);
        }
        updateExternalVmDetailsFromPrepareAnswer(vmTO, userVmVO, updatedTO.getDetails());
    }

    @Override
    public void updateExternalVmNicsFromPrepareAnswer(VirtualMachineTO vmTO, VirtualMachineTO updatedTO) {
        if (ObjectUtils.anyNull(vmTO.getNics(), updatedTO.getNics())) {
            return;
        }
        Map<String, NicTO> originalNicsByUuid = new HashMap<>();
        for (NicTO nic : vmTO.getNics()) {
            originalNicsByUuid.put(nic.getNicUuid(), nic);
        }
        for (NicTO updatedNicTO : updatedTO.getNics()) {
            final String nicUuid = updatedNicTO.getNicUuid();
            NicTO originalNicTO = originalNicsByUuid.get(nicUuid);
            if (originalNicTO == null) {
                continue;
            }
            final String mac = updatedNicTO.getMac();
            final String ip4 = updatedNicTO.getIp();
            final String ip6 = updatedNicTO.getIp6Address();
            if (Objects.equals(mac, originalNicTO.getMac()) &&
                    Objects.equals(ip4, originalNicTO.getIp()) &&
                    Objects.equals(ip6, originalNicTO.getIp6Address())) {
                continue;
            }
            NicVO nicVO = nicsDao.findByUuid(nicUuid);
            if (nicVO == null) {
                continue;
            }
            logger.debug("Updating {} during External VM preparation", nicVO);
            if (ip4 != null && !ip4.equals(nicVO.getIPv4Address())) {
                nicVO.setIPv4Address(ip4);
                originalNicTO.setIp(ip4);
            }
            if (ip6 != null && !ip6.equals(nicVO.getIPv6Address())) {
                nicVO.setIPv6Address(ip6);
                originalNicTO.setIp6Address(ip6);
            }
            if (mac != null && !mac.equals(nicVO.getMacAddress())) {
                nicVO.setMacAddress(mac);
                originalNicTO.setMac(mac);
            }
            nicsDao.update(nicVO.getId(), nicVO);
        }
    }

    @Override
    public void updateExternalVmFromPrepareAnswer(VirtualMachineTO vmTO, VirtualMachineTO updatedTO) {
        if (updatedTO == null) {
            return;
        }
        updateExternalVmDataFromPrepareAnswer(vmTO, updatedTO);
        updateExternalVmNicsFromPrepareAnswer(vmTO, updatedTO);
    }

    @Override
    public void processPrepareExternalProvisioning(boolean firstStart, Host host,
            VirtualMachineProfile vmProfile, DataCenter dataCenter, VirtualMachineTO virtualMachineTO)
            throws CloudRuntimeException {
        if (virtualMachineTO.getNics() == null || virtualMachineTO.getNics().length == 0) {
            List<NicVO> nics = nicsDao.listByVmId(vmProfile.getId());
            NicTO[] nicTOs = new NicTO[nics.size()];
            nics.forEach(nicVO -> {
                NicTO nicTO = toNicTO(networkModel.getNicProfile(vmProfile.getVirtualMachine(), nicVO, dataCenter),
                        host.getHypervisorType());
                nicTOs[nicTO.getDeviceId()] = nicTO;
            });
            virtualMachineTO.setNics(nicTOs);
        }
        Map<String, String> vmDetails = virtualMachineTO.getExternalDetails();
        Map<String, Map<String, String>> externalDetails = extensionsManager.getExternalAccessDetails(host,
                vmDetails);
        PrepareExternalProvisioningCommand cmd = new PrepareExternalProvisioningCommand(virtualMachineTO);
        cmd.setExternalDetails(externalDetails);
        Answer answer = null;
        CloudRuntimeException cre = new CloudRuntimeException("Failed to prepare VM");
        try {
            answer = agentMgr.send(host.getId(), cmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.error("Failed PrepareExternalProvisioningCommand due to : {}", e.getMessage(), e);
            throw cre;
        }
        if (answer == null) {
            logger.error("Invalid answer received for PrepareExternalProvisioningCommand");
            throw cre;
        }
        if (!(answer instanceof PrepareExternalProvisioningAnswer)) {
            logger.error("Unexpected answer received for PrepareExternalProvisioningCommand: [result: {}, details: {}]",
                    answer.getResult(), answer.getDetails());
            throw cre;
        }
        PrepareExternalProvisioningAnswer prepareAnswer = (PrepareExternalProvisioningAnswer) answer;
        if (!prepareAnswer.getResult()) {
            logger.error("Unexpected answer received for PrepareExternalProvisioningCommand: [result: {}, details: {}]",
                    answer.getResult(), answer.getDetails());
            throw cre;
        }
        updateExternalVmFromPrepareAnswer(virtualMachineTO, prepareAnswer.getVirtualMachineTO());
    }

    @Override
    public void updateStartCommandWithExternalDetails(Host host, VirtualMachineTO vmTO, StartCommand command) {
        if (!HypervisorType.External.equals(host.getHypervisorType())) {
            return;
        }
        Map<String, String> vmExternalDetails = vmTO.getExternalDetails();
        for (NicTO nic : vmTO.getNics()) {
            if (!nic.isDefaultNic()) {
                continue;
            }
            vmExternalDetails.put(VmDetailConstants.CLOUDSTACK_VLAN, networkService.getNicVlanValueForExternalVm(nic));
        }
        Map<String, Map<String, String>> externalDetails = extensionsManager.getExternalAccessDetails(host, vmExternalDetails);
        command.setExternalDetails(externalDetails);
    }

    @Override
    public void updateStopCommandForExternalHypervisorType(HypervisorType hypervisorType,
            VirtualMachineProfile vmProfile, StopCommand stopCommand, VirtualMachineTO vmTO) {
        if (!HypervisorType.External.equals(hypervisorType) || vmProfile.getHostId() == null) {
            return;
        }
        HostVO host = hostDao.findById(vmProfile.getHostId());
        if (host == null) {
            return;
        }
        if (MapUtils.isEmpty(vmTO.getGuestOsDetails())) {
            vmTO.setGuestOsDetails(null);
        }
        if (MapUtils.isEmpty(vmTO.getExtraConfig())) {
            vmTO.setExtraConfig(null);
        }
        if (MapUtils.isEmpty(vmTO.getNetworkIdToNetworkNameMap())) {
            vmTO.setNetworkIdToNetworkNameMap(null);
        }
        Map<String, Map<String, String>> externalDetails = extensionsManager.getExternalAccessDetails(host, vmTO.getExternalDetails());
        stopCommand.setVirtualMachine(vmTO);
        stopCommand.setExternalDetails(externalDetails);
    }

    @Override
    public void updateRebootCommandWithExternalDetails(Host host, VirtualMachineTO vmTO, RebootCommand rebootCmd) {
        if (!HypervisorType.External.equals(host.getHypervisorType())) {
            return;
        }
        Map<String, Map<String, String>> externalDetails = extensionsManager.getExternalAccessDetails(host, vmTO.getExternalDetails());
        rebootCmd.setExternalDetails(externalDetails);
    }

    /**
     * Build a {@link NicTO} from the given profile for the specified hypervisor,
     * delegating to the hypervisor guru — mirrors
     * {@link VirtualMachineManagerImpl#toNicTO(NicProfile, HypervisorType)}.
     */
    protected NicTO toNicTO(NicProfile nicProfile, HypervisorType hypervisorType) {
        HypervisorGuru hvGuru = hvGuruMgr.getGuru(hypervisorType);
        return hvGuru.toNicTO(nicProfile);
    }
}
