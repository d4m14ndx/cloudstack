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

import java.net.URI;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmNetworkAttachmentOrchestrationServiceImpl implements VmNetworkAttachmentOrchestrationService {

    private static final Logger logger = LogManager.getLogger(VmNetworkAttachmentOrchestrationServiceImpl.class);

    @Inject
    protected UserVmManager userVmMgr;
    @Inject
    protected NetworkOrchestrationService networkMgr;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected NicDao nicsDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected HypervisorGuruManager hvGuruMgr;
    @Inject
    protected EntityManager entityMgr;

    @Override
    public NicProfile addVmToNetwork(final VirtualMachine vm, final Network network, final NicProfile requested,
            BackendNicOperations backendNicOperations) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        final CallContext cctx = CallContext.current();

        checkIfNetworkExistsForUserVM(vm, network);
        logger.debug("Adding Instance {} to Network {}; requested NIC profile {}", vm, network, requested);
        final VMInstanceVO vmVO = vmDao.findById(vm.getId());
        final ReservationContext context = new ReservationContextImpl(null, null, cctx.getCallingUser(), cctx.getCallingAccount());

        final VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmVO, null, null, null, null);

        final DataCenter dc = entityMgr.findById(DataCenter.class, network.getDataCenterId());
        final Host host = hostDao.findById(vm.getHostId());
        final DeployDestination dest = new DeployDestination(dc, null, null, host);

        if (vm.getState() == State.Running) {
            final NicProfile nic = networkMgr.createNicForVm(network, requested, context, vmProfile, true);

            final HypervisorGuru hvGuru = hvGuruMgr.getGuru(vmProfile.getVirtualMachine().getHypervisorType());
            final VirtualMachineTO vmTO = hvGuru.implement(vmProfile);

            final NicTO nicTO = toNicTO(nic, vmProfile.getVirtualMachine().getHypervisorType());

            logger.debug("Plugging NIC for Instance {} in Network {}", vm, network);

            boolean result = false;
            try {
                result = backendNicOperations.plugNic(network, nicTO, vmTO, context, dest);
                if (result) {
                    userVmMgr.setupVmForPvlan(true, vm.getHostId(), nic);
                    logger.debug("Nic is plugged successfully for vm {} in network {}. VM is a part of network now.", vm, network);
                    final long isDefault = nic.isDefaultNic() ? 1 : 0;

                    if (VirtualMachine.Type.User.equals(vmVO.getType())) {
                        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vmVO.getAccountId(), vmVO.getDataCenterId(), vmVO.getId(),
                                Long.toString(nic.getId()), network.getNetworkOfferingId(), null, isDefault, VirtualMachine.class.getName(), vmVO.getUuid(), vm.isDisplay());
                    }
                    return nic;
                } else {
                    logger.warn("Failed to plug NIC to the Instance {} in Network {}", vm, network);
                    return null;
                }
            } finally {
                if (!result) {
                    logger.debug("Removing NIC {} from Instance {} as NIC plug failed on the backend.", nic, vmProfile.getVirtualMachine());
                    networkMgr.removeNic(vmProfile, nicsDao.findById(nic.getId()));
                }
            }
        } else if (vm.getState() == State.Stopped) {
            return networkMgr.createNicForVm(network, requested, context, vmProfile, false);
        } else {
            logger.warn("Unable to add vm {} to network {}", vm, network);
            throw new ResourceUnavailableException("Unable to add vm " + vm + " to network, is not in the right state", DataCenter.class, vm.getDataCenterId());
        }
    }

    /**
     * duplicated in {@see UserVmManagerImpl} for a {@see UserVmVO}
     */
    @Override
    public void checkIfNetworkExistsForUserVM(VirtualMachine virtualMachine, Network network) {
        if (virtualMachine.getType() != VirtualMachine.Type.User) {
            return; // others may have multiple nics in the same network
        }
        List<NicVO> allNics = nicsDao.listByVmId(virtualMachine.getId());
        for (NicVO nic : allNics) {
            if (nic.getNetworkId() == network.getId()) {
                throw new CloudRuntimeException("A NIC already exists for VM:" + virtualMachine.getInstanceName() + " in network: " + network.getUuid());
            }
        }
    }

    @Override
    public NicTO toNicTO(final NicProfile nic, final HypervisorType hypervisorType) {
        final HypervisorGuru hvGuru = hvGuruMgr.getGuru(hypervisorType);
        return hvGuru.toNicTO(nic);
    }

    @Override
    public boolean removeNicFromVm(final VirtualMachine vm, final Nic nic, BackendNicOperations backendNicOperations)
            throws ConcurrentOperationException, ResourceUnavailableException {
        final CallContext cctx = CallContext.current();
        final VMInstanceVO vmVO = vmDao.findById(vm.getId());
        final NetworkVO network = networkDao.findById(nic.getNetworkId());
        final ReservationContext context = new ReservationContextImpl(null, null, cctx.getCallingUser(), cctx.getCallingAccount());

        final VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmVO, null, null, null, null);

        final DataCenter dc = entityMgr.findById(DataCenter.class, network.getDataCenterId());
        final Host host = hostDao.findById(vm.getHostId());
        final DeployDestination dest = new DeployDestination(dc, null, null, host);
        final HypervisorGuru hvGuru = hvGuruMgr.getGuru(vmProfile.getVirtualMachine().getHypervisorType());
        final VirtualMachineTO vmTO = hvGuru.implement(vmProfile);

        final NicProfile nicProfile =
                new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkModel.getNetworkRate(network.getId(), vm.getId()),
                        networkModel.isSecurityGroupSupportedInNetwork(network), networkModel.getNetworkTag(vmProfile.getVirtualMachine().getHypervisorType(), network));

        if (vm.getState() == State.Running) {
            final NicTO nicTO = toNicTO(nicProfile, vmProfile.getVirtualMachine().getHypervisorType());
            logger.debug("Un-plugging NIC {} for Instance {} from Network {}.", nic, vm, network);
            final boolean result = backendNicOperations.unplugNic(network, nicTO, vmTO, context, dest);
            if (result) {
                userVmMgr.setupVmForPvlan(false, vm.getHostId(), nicProfile);
                logger.debug("NIC is unplugged successfully for Instance {} in Network {}.", vm, network);
                final long isDefault = nic.isDefaultNic() ? 1 : 0;
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_REMOVE, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                        Long.toString(nic.getId()), network.getNetworkOfferingId(), null, isDefault, VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplay());
            } else {
                logger.warn("Failed to unplug NIC for the Instance {} from Network {}.", vm, network);
                return false;
            }
        } else if (vm.getState() != State.Stopped) {
            logger.warn("Unable to remove Instance {} from Network {}", vm, network);
            throw new ResourceUnavailableException("Unable to remove Instance " + vm + " from Network, is not in the right state", DataCenter.class, vm.getDataCenterId());
        }

        networkMgr.releaseNic(vmProfile, nic);
        logger.debug("Successfully released NIC {} for Instance {}", nic, vm);

        networkMgr.removeNic(vmProfile, nic);
        nicsDao.remove(nic.getId());
        return true;
    }

    @Override
    @DB
    public boolean removeVmFromNetwork(final VirtualMachine vm, final Network network, final URI broadcastUri, BackendNicOperations backendNicOperations)
            throws ConcurrentOperationException, ResourceUnavailableException {
        final CallContext cctx = CallContext.current();
        final VMInstanceVO vmVO = vmDao.findById(vm.getId());
        final ReservationContext context = new ReservationContextImpl(null, null, cctx.getCallingUser(), cctx.getCallingAccount());

        final VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmVO, null, null, null, null);

        final DataCenter dc = entityMgr.findById(DataCenter.class, network.getDataCenterId());
        final Host host = hostDao.findById(vm.getHostId());
        final DeployDestination dest = new DeployDestination(dc, null, null, host);
        final HypervisorGuru hvGuru = hvGuruMgr.getGuru(vmProfile.getVirtualMachine().getHypervisorType());
        final VirtualMachineTO vmTO = hvGuru.implement(vmProfile);

        Nic nic = null;
        if (broadcastUri != null) {
            nic = nicsDao.findByNetworkIdInstanceIdAndBroadcastUri(network.getId(), vm.getId(), broadcastUri.toString());
        } else {
            nic = networkModel.getNicInNetwork(vm.getId(), network.getId());
        }

        if (nic == null) {
            logger.warn("Could not get a NIC with {}", network);
            return false;
        }

        if (nic.isDefaultNic() && vm.getType() == VirtualMachine.Type.User) {
            logger.warn("Failed to remove NIC from {} in {}, NIC is default.", vm, network);
            throw new CloudRuntimeException("Failed to remove NIC from " + vm + " in " + network + ", NIC is default.");
        }

        final Nic lock = nicsDao.acquireInLockTable(nic.getId());
        if (lock == null) {
            if (nicsDao.findById(nic.getId()) == null) {
                logger.debug("Not need to remove the vm {} from network {} as the vm doesn't have nic in this network.", vm, network);
                return true;
            }
            throw new ConcurrentOperationException(String.format("Unable to lock nic %s", nic));
        }

        logger.debug("Lock is acquired for nic {} as a part of remove vm {} from network {}", lock, vm, network);

        try {
            final NicProfile nicProfile =
                    new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkModel.getNetworkRate(network.getId(), vm.getId()),
                            networkModel.isSecurityGroupSupportedInNetwork(network), networkModel.getNetworkTag(vmProfile.getVirtualMachine().getHypervisorType(), network));

            if (vm.getState() == State.Running) {
                final NicTO nicTO = toNicTO(nicProfile, vmProfile.getVirtualMachine().getHypervisorType());
                logger.debug("Un-plugging nic for vm {} from network {}", vm, network);
                final boolean result = backendNicOperations.unplugNic(network, nicTO, vmTO, context, dest);
                if (result) {
                    logger.debug("Nic is unplugged successfully for vm {} in network {}", vm, network);
                } else {
                    logger.warn("Failed to unplug nic for the vm {} from network {}", vm, network);
                    return false;
                }
            } else if (vm.getState() != State.Stopped) {
                logger.warn("Unable to remove vm {} from network {}", vm, network);
                throw new ResourceUnavailableException("Unable to remove vm " + vm + " from network, is not in the right state", DataCenter.class, vm.getDataCenterId());
            }

            networkMgr.releaseNic(vmProfile, nic);
            logger.debug("Successfully released nic {} for vm {}", nic, vm);

            networkMgr.removeNic(vmProfile, nic);
            return true;
        } finally {
            nicsDao.releaseFromLockTable(lock.getId());
            logger.debug("Lock is released for nic {} as a part of remove vm {} from network {}", lock, vm, network);
        }
    }
}
