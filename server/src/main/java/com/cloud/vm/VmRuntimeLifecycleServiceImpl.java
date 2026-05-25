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

import jakarta.inject.Inject;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PvlanSetupCommand;
import com.cloud.agent.api.RestoreVMSnapshotAnswer;
import com.cloud.agent.api.RestoreVMSnapshotCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.configuration.Config;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.GuestOSCategoryVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.TemplateManager;
import com.cloud.user.VmDiskStatisticsVO;
import com.cloud.user.dao.VmDiskStatisticsDao;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.snapshot.VMSnapshotManager;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@Component
public class VmRuntimeLifecycleServiceImpl implements VmRuntimeLifecycleService {

    protected static final Logger logger = LogManager.getLogger(VmRuntimeLifecycleServiceImpl.class);

    @Inject
    private EntityManager entityManager;
    @Inject
    private UserVmDao vmDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private NicDao nicDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private ConfigurationDao configDao;
    @Inject
    private GuestOSDao guestOSDao;
    @Inject
    private GuestOSCategoryDao guestOSCategoryDao;
    @Inject
    private TemplateManager templateMgr;
    @Inject
    private VolumeDao volsDao;
    @Inject
    private VmDiskStatisticsDao vmDiskStatsDao;
    @Inject
    private VMSnapshotDao vmSnapshotDao;
    @Inject
    private VMSnapshotManager vmSnapshotMgr;
    @Inject
    private HostDao hostDao;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private NetworkServiceMapDao ntwkSrvcDao;
    @Inject
    private RulesManager rulesMgr;
    @Inject
    private IPAddressDao ipAddressDao;

    private void addUserVMCmdlineArgs(Long vmId, VirtualMachineProfile profile, DeployDestination dest, StringBuilder buf) {
        UserVmVO vm = vmDao.findById(vmId);
        buf.append(" template=domP");
        buf.append(" name=").append(profile.getHostName());
        buf.append(" type=").append(vm.getUserVmType());
        for (NicProfile nic : profile.getNics()) {
            int deviceId = nic.getDeviceId();
            if (nic.getIPv4Address() == null) {
                buf.append(" eth").append(deviceId).append("ip=").append("0.0.0.0");
                buf.append(" eth").append(deviceId).append("mask=").append("0.0.0.0");
            } else {
                buf.append(" eth").append(deviceId).append("ip=").append(nic.getIPv4Address());
                buf.append(" eth").append(deviceId).append("mask=").append(nic.getIPv4Netmask());
            }

            if (nic.isDefaultNic()) {
                buf.append(" gateway=").append(nic.getIPv4Gateway());
            }

            if (nic.getTrafficType() == TrafficType.Management) {
                String mgmtCidr = configDao.getValue(Config.ManagementNetwork.key());
                if (NetUtils.isValidIp4Cidr(mgmtCidr)) {
                    buf.append(" mgmtcidr=").append(mgmtCidr);
                }
                buf.append(" localgw=").append(dest.getPod().getGateway());
            }
        }
        DataCenterVO dc = dcDao.findById(profile.getVirtualMachine().getDataCenterId());
        buf.append(" internaldns1=").append(dc.getInternalDns1());
        if (dc.getInternalDns2() != null) {
            buf.append(" internaldns2=").append(dc.getInternalDns2());
        }
        buf.append(" dns1=").append(dc.getDns1());
        if (dc.getDns2() != null) {
            buf.append(" dns2=").append(dc.getDns2());
        }
        logger.info("cmdline details: " + buf);
    }

    @Override
    public boolean finalizeVirtualMachineProfile(VirtualMachineProfile profile, DeployDestination dest, ReservationContext context) {
        UserVmVO vm = vmDao.findById(profile.getId());
        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vm.getId());
        vm.setDetails(details);
        StringBuilder buf = profile.getBootArgsBuilder();
        if (UserVmManager.CKS_NODE.equals(vm.getUserVmType()) || UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            addUserVMCmdlineArgs(vm.getId(), profile, dest, buf);
        }
        Nic defaultNic = networkModel.getDefaultNic(vm.getId());
        if (defaultNic != null) {
            Network network = networkModel.getNetwork(defaultNic.getNetworkId());
            if (networkModel.isSharedNetworkWithoutServices(network.getId())) {
                final String serviceOffering = serviceOfferingDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId()).getDisplayText();
                GuestOSVO guestOs = guestOSDao.findById(vm.getGuestOSId());
                GuestOSCategoryVO guestOsCategory = guestOSCategoryDao.findById(guestOs.getCategoryId());
                boolean isWindows = guestOsCategory.getName().equalsIgnoreCase("Windows");
                String destHostname = VirtualMachineManager.getHypervisorHostname(dest.getHost() != null ? dest.getHost().getName() : "");
                List<String[]> vmData = networkModel.generateVmData(vm.getUserData(), vm.getUserDataDetails(), serviceOffering, vm.getDataCenterId(), vm.getInstanceName(), vm.getHostName(), vm.getId(),
                        vm.getUuid(), defaultNic.getIPv4Address(), vm.getDetail(VmDetailConstants.SSH_PUBLIC_KEY), (String) profile.getParameter(VirtualMachineProfile.Param.VmPassword), isWindows, destHostname);
                String vmName = vm.getInstanceName();
                String configDriveIsoRootFolder = "/tmp";
                String isoFile = configDriveIsoRootFolder + "/" + vmName + "/configDrive/" + vmName + ".iso";
                profile.setVmData(vmData);
                profile.setConfigDriveLabel(VirtualMachineManager.VmConfigDriveLabel.value());
                profile.setConfigDriveIsoRootFolder(configDriveIsoRootFolder);
                profile.setConfigDriveIsoFile(isoFile);
            }
        }

        templateMgr.prepareIsoForVmProfile(profile, dest);
        return true;
    }

    @Override
    public boolean setupVmForPvlan(boolean add, Long hostId, NicProfile nic) {
        if (nic == null) {
            logger.warn("Skipping PVLAN setup on host {} because NIC profile is null", hostId);
            return false;
        }

        if (nic.getBroadCastUri() == null) {
            logger.debug("Skipping PVLAN setup on host {} for NIC {} because broadcast URI is null", hostId, nic);
            return false;
        }

        String scheme = nic.getBroadCastUri().getScheme();
        if (!"pvlan".equalsIgnoreCase(scheme)) {
            logger.debug("Skipping PVLAN setup on host {} for NIC {} because broadcast URI scheme is {}", hostId, nic, scheme);
            return false;
        }
        String op = "add";
        if (!add) {
            op = "delete";
        }

        Host host = hostDao.findById(hostId);
        if (host == null) {
            logger.warn("Host with id {} does not exist", hostId);
            return false;
        }

        Network network = networkDao.findById(nic.getNetworkId());
        String networkTag = networkModel.getNetworkTag(host.getHypervisorType(), network);
        PvlanSetupCommand cmd = PvlanSetupCommand.createVmSetup(op, nic.getBroadCastUri(), networkTag, nic.getMacAddress());
        Answer answer;
        try {
            answer = agentMgr.send(hostId, cmd);
        } catch (OperationTimedoutException e) {
            logger.warn("Timed Out", e);
            return false;
        } catch (AgentUnavailableException e) {
            logger.warn("Agent Unavailable ", e);
            return false;
        }

        boolean result = true;
        if (answer == null || !answer.getResult()) {
            result = false;
        }
        return result;
    }

    @Override
    public boolean finalizeDeployment(Commands cmds, VirtualMachineProfile profile, DeployDestination dest, ReservationContext context) {
        UserVmVO userVm = vmDao.findById(profile.getId());
        List<NicVO> nics = nicDao.listByVmId(userVm.getId());
        for (NicVO nic : nics) {
            NetworkVO network = networkDao.findById(nic.getNetworkId());
            if (network.getTrafficType() == TrafficType.Guest || network.getTrafficType() == TrafficType.Public) {
                userVm.setPrivateIpAddress(nic.getIPv4Address());
                userVm.setPrivateMacAddress(nic.getMacAddress());
                vmDao.update(userVm.getId(), userVm);
            }
        }

        List<VolumeVO> volumes = volsDao.findByInstance(userVm.getId());
        VmDiskStatisticsVO diskstats = null;
        for (VolumeVO volume : volumes) {
            diskstats = vmDiskStatsDao.findBy(userVm.getAccountId(), userVm.getDataCenterId(), userVm.getId(), volume.getId());
            if (diskstats == null) {
                diskstats = new VmDiskStatisticsVO(userVm.getAccountId(), userVm.getDataCenterId(), userVm.getId(), volume.getId());
                vmDiskStatsDao.persist(diskstats);
            }
        }

        finalizeCommandsOnStart(cmds, profile);
        return true;
    }

    @Override
    public boolean finalizeCommandsOnStart(Commands cmds, VirtualMachineProfile profile) {
        UserVmVO vm = vmDao.findById(profile.getId());
        List<VMSnapshotVO> vmSnapshots = vmSnapshotDao.findByVm(vm.getId());
        RestoreVMSnapshotCommand command = vmSnapshotMgr.createRestoreCommand(vm, vmSnapshots);
        if (command != null) {
            cmds.addCommand("restoreVMSnapshot", command);
        }
        return true;
    }

    @Override
    public boolean finalizeStart(VirtualMachineProfile profile, long hostId, Commands cmds, ReservationContext context,
            Map<Long, UserVmManagerImpl.VmAndCountDetails> vmIdCountMap) {
        UserVmVO vm = vmDao.findById(profile.getId());

        Answer[] answersToCmds = cmds.getAnswers();
        if (answersToCmds == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Returning from finalizeStart() since there are no answers to read");
            }
            return true;
        }
        Answer startAnswer = cmds.getAnswer(StartAnswer.class);
        String returnedIp = null;
        String originalIp = null;
        String originalVncPassword = profile.getVirtualMachine().getVncPassword();
        String returnedVncPassword = null;
        if (startAnswer != null) {
            StartAnswer startAns = (StartAnswer) startAnswer;
            VirtualMachineTO vmTO = startAns.getVirtualMachine();
            for (NicTO nicTO : vmTO.getNics()) {
                if (nicTO.getType() == TrafficType.Guest) {
                    returnedIp = nicTO.getIp();
                }
            }
            returnedVncPassword = vmTO.getVncPassword();
        }

        List<NicVO> nics = nicDao.listByVmId(vm.getId());
        NicVO guestNic = null;
        NetworkVO guestNetwork = null;
        for (NicVO nic : nics) {
            NetworkVO network = networkDao.findById(nic.getNetworkId());
            long isDefault = nic.isDefaultNic() ? 1 : 0;
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vm.getAccountId(), vm.getDataCenterId(), vm.getId(), Long.toString(nic.getId()),
                    network.getNetworkOfferingId(), null, isDefault, VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplay());
            if (network.getTrafficType() == TrafficType.Guest) {
                originalIp = nic.getIPv4Address();
                guestNic = nic;
                guestNetwork = network;
                if (profile.getHypervisorType() != HypervisorType.VMware && nic.getBroadcastUri().getScheme().equals("pvlan")) {
                    NicProfile nicProfile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), 0, false, "pvlan-nic");
                    if (!setupVmForPvlan(true, hostId, nicProfile)) {
                        return false;
                    }
                }
            }
        }
        boolean ipChanged = false;
        if (originalIp != null && !originalIp.equalsIgnoreCase(returnedIp)) {
            if (returnedIp != null && guestNic != null) {
                guestNic.setIPv4Address(returnedIp);
                ipChanged = true;
            }
        }
        if (returnedIp != null && !returnedIp.equalsIgnoreCase(originalIp)) {
            if (guestNic != null) {
                guestNic.setIPv4Address(returnedIp);
                ipChanged = true;
            }
        }
        if (ipChanged) {
            dcDao.findById(vm.getDataCenterId());
            UserVmVO userVm = vmDao.findById(profile.getId());
            if (ntwkSrvcDao.canProviderSupportServiceInNetwork(guestNetwork.getId(), Service.Dhcp, Provider.ExternalDhcpServer)) {
                nicDao.update(guestNic.getId(), guestNic);
                userVm.setPrivateIpAddress(guestNic.getIPv4Address());
                vmDao.update(userVm.getId(), userVm);

                logger.info("Detected that ip changed in the answer, updated nic in the db with new ip " + returnedIp);
            }
        }

        updateVncPasswordIfItHasChanged(originalVncPassword, returnedVncPassword, profile);

        try {
            rulesMgr.getSystemIpAndEnableStaticNatForVm(profile.getVirtualMachine(), false);
        } catch (Exception ex) {
            logger.warn("Failed to get system ip and enable static nat for the vm " + profile.getVirtualMachine() + " due to exception ", ex);
            return false;
        }

        Answer answer = cmds.getAnswer("restoreVMSnapshot");
        if (answer instanceof RestoreVMSnapshotAnswer) {
            RestoreVMSnapshotAnswer restoreVMSnapshotAnswer = (RestoreVMSnapshotAnswer) answer;
            if (!restoreVMSnapshotAnswer.getResult()) {
                logger.warn("Unable to restore the Instance Snapshot from image file to the Instance: " + restoreVMSnapshotAnswer.getDetails());
            }
        }

        final VirtualMachineProfile vmProfile = profile;
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                final UserVmVO vm = vmDao.findById(vmProfile.getId());
                final List<NicVO> nics = nicDao.listByVmId(vm.getId());
                for (NicVO nic : nics) {
                    Network network = networkModel.getNetwork(nic.getNetworkId());
                    if (GuestType.L2.equals(network.getGuestType()) || networkModel.isSharedNetworkWithoutServices(network.getId())) {
                        vmIdCountMap.put(nic.getId(), new UserVmManagerImpl.VmAndCountDetails(nic.getInstanceId(), UserVmManagerImpl.VmIpFetchTrialMax.value()));
                    }
                }
            }
        });

        return true;
    }

    @Override
    public void updateVncPasswordIfItHasChanged(String originalVncPassword, String returnedVncPassword, VirtualMachineProfile profile) {
        if (returnedVncPassword != null && !originalVncPassword.equals(returnedVncPassword)) {
            UserVmVO userVm = vmDao.findById(profile.getId());
            userVm.setVncPassword(returnedVncPassword);
            vmDao.update(userVm.getId(), userVm);
        }
    }

    @Override
    public void finalizeStop(VirtualMachineProfile profile, Answer answer) {
        VirtualMachine vm = profile.getVirtualMachine();
        IPAddressVO ip = ipAddressDao.findByAssociatedVmId(profile.getId());
        if (ip != null && ip.getSystem()) {
            CallContext ctx = CallContext.current();
            try {
                long networkId = ip.getAssociatedWithNetworkId();
                Network guestNetwork = networkDao.findById(networkId);
                NetworkOffering offering = entityManager.findById(NetworkOffering.class, guestNetwork.getNetworkOfferingId());
                assert offering.isAssociatePublicIP() == true : "User VM should not have system owned public IP associated with it when offering configured not to associate public IP.";
                rulesMgr.disableStaticNat(ip.getId(), ctx.getCallingAccount(), ctx.getCallingUserId(), true);
            } catch (Exception ex) {
                logger.warn("Failed to disable static nat and release system ip " + ip + " as a part of vm " + profile.getVirtualMachine() + " stop due to exception ", ex);
            }
        }

        final List<NicVO> nics = nicDao.listByVmId(vm.getId());
        for (final NicVO nic : nics) {
            final NetworkVO network = networkDao.findById(nic.getNetworkId());
            if (network != null && network.getTrafficType() == TrafficType.Guest) {
                if (nic.getBroadcastUri() != null && nic.getBroadcastUri().getScheme().equals("pvlan")) {
                    NicProfile nicProfile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), 0, false, "pvlan-nic");
                    setupVmForPvlan(false, vm.getHostId(), nicProfile);
                }
            }
        }
    }
}
