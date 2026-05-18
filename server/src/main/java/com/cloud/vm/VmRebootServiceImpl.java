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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.vm.RebootVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.CloudException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.NetworkModel;
import com.cloud.resource.ResourceState;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;

import org.apache.cloudstack.engine.cloud.entity.api.db.dao.VMNetworkMapDao;
import com.cloud.network.router.VpcVirtualNetworkApplianceManager;
import com.cloud.vm.dao.DomainRouterDao;

/**
 * Reboot lifecycle for user VMs — normal reboot, forced reboot, and
 * router pre-start for advanced networks.
 *
 * @see VmRebootService
 */
@Component
public class VmRebootServiceImpl implements VmRebootService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private UserVmDao vmDao;
    @Inject
    private NicDao nicDao;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private DomainRouterDao routerDao;
    @Inject
    private VMNetworkMapDao vmNetworkMapDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private VirtualMachineManager itMgr;
    @Inject
    private VpcVirtualNetworkApplianceManager virtualNetAppliance;
    @Inject
    private VmMigrationValidator vmMigrationValidator;
    @Inject
    private VmStatsCollectionService vmStatsCollectionService;

    /**
     * Callback invoked after a successful reboot to schedule IP re-fetch
     * for NICs on L2 / shared-without-services networks.  The god class
     * provides this via a lambda that writes to its {@code vmIdCountMap}.
     */
    private VmIpFetchScheduler ipFetchScheduler;

    /**
     * Back-reference to the god class used for the volatile-VM restore
     * path (calls {@code restoreVMInternal}) and the force-reboot path
     * (calls {@code stopVirtualMachine} / {@code startVirtualMachine}).
     * Set by the god class after its own construction.
     */
    private UserVmManager userVmManager;

    public void setIpFetchScheduler(VmIpFetchScheduler ipFetchScheduler) {
        this.ipFetchScheduler = ipFetchScheduler;
    }

    public void setUserVmManager(UserVmManager userVmManager) {
        this.userVmManager = userVmManager;
    }

    @Override
    public UserVm rebootVirtualMachine(RebootVMCmd cmd)
            throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException {

        Account caller = CallContext.current().getCallingAccount();
        Long vmId = cmd.getId();

        UserVmVO vmInstance = vmDao.findById(vmId);
        if (vmInstance == null) {
            throw new InvalidParameterValueException("Unable to find a Instance with ID " + vmId);
        }

        if (vmInstance.getState() != State.Running) {
            throw new InvalidParameterValueException(String.format(
                    "The Instance %s (%s) is not running, unable to reboot it",
                    vmInstance.getUuid(), vmInstance.getDisplayNameOrHostName()));
        }

        accountManager.checkAccess(caller, null, true, vmInstance);

        vmMigrationValidator.checkIfHostOfVMIsInPrepareForMaintenanceState(vmInstance, "Reboot");

        // Volatile VMs discard their root disk on reboot — delegate to restoreVMInternal.
        long serviceOfferingId = vmInstance.getServiceOfferingId();
        ServiceOfferingVO offering = serviceOfferingDao.findById(vmInstance.getId(), serviceOfferingId);
        if (offering != null && offering.getRemoved() == null) {
            if (offering.isVolatileVm()) {
                return userVmManager.restoreVMInternal(caller, vmInstance);
            }
        } else {
            throw new InvalidParameterValueException(
                    "Unable to find service offering: " + serviceOfferingId
                    + " corresponding to the Instance");
        }

        Boolean enterSetup = cmd.getBootIntoSetup();
        if (enterSetup != null && enterSetup
                && !Hypervisor.HypervisorType.VMware.equals(vmInstance.getHypervisorType())) {
            throw new InvalidParameterValueException(
                    "Booting into a hardware setup menu is not implemented on "
                    + vmInstance.getHypervisorType());
        }

        UserVm userVm = rebootVirtualMachineInternal(
                CallContext.current().getCallingUserId(),
                vmId,
                enterSetup == null ? false : cmd.getBootIntoSetup(),
                cmd.isForced());

        if (userVm != null) {
            // Update vmIdCountMap via the callback if the VM is on an
            // advanced shared network without services (needs IP re-fetch).
            final List<NicVO> nics = nicDao.listByVmId(vmId);
            for (NicVO nic : nics) {
                Network network = networkModel.getNetwork(nic.getNetworkId());
                if (GuestType.L2.equals(network.getGuestType())
                        || networkModel.isSharedNetworkWithoutServices(network.getId())) {
                    logger.debug("Adding Instance {} NIC ID {} into vmIdCountMap as part of "
                            + "Instance reboot for Instance IP fetch", vmId, nic.getId());
                    if (ipFetchScheduler != null) {
                        ipFetchScheduler.scheduleIpFetch(nic.getId(), nic.getInstanceId());
                    }
                }
            }
            return userVm;
        }
        return null;
    }

    @Override
    public UserVm rebootVirtualMachineInternal(long userId, long vmId, boolean enterSetup, boolean forced)
            throws InsufficientCapacityException, ResourceUnavailableException {

        UserVmVO vm = vmDao.findById(vmId);

        if (logger.isTraceEnabled()) {
            logger.trace("reboot {} with enterSetup set to {}", vm, Boolean.toString(enterSetup));
        }

        if (vm == null || vm.getState() == State.Destroyed
                || vm.getState() == State.Expunging || vm.getRemoved() != null) {
            logger.warn("Vm {} with id={} doesn't exist or is not in correct state", vm, vmId);
            return null;
        }

        if (vm.getState() == State.Running && vm.getHostId() != null) {
            collectVmDiskAndNetworkStatistics(vm, State.Running);

            if (forced) {
                HostVO vmOnHost = hostDao.findById(vm.getHostId());
                if (vmOnHost == null
                        || vmOnHost.getResourceState() != ResourceState.Enabled
                        || vmOnHost.getStatus() != Status.Up) {
                    throw new CloudRuntimeException(
                            "Unable to force reboot the VM as the host: "
                            + vm.getHostId() + " is not in the right state");
                }
                return forceRebootVirtualMachine(vm, vm.getHostId(), enterSetup);
            }

            DataCenterVO dc = dcDao.findById(vm.getDataCenterId());
            try {
                if (dc.getNetworkType() == DataCenter.NetworkType.Advanced) {
                    List<Long> vmNetworks = vmNetworkMapDao.getNetworks(vmId);
                    List<DomainRouterVO> routers = new ArrayList<>();
                    for (long vmNetworkId : vmNetworks) {
                        List<DomainRouterVO> router = routerDao.listStopped(vmNetworkId);
                        routers.addAll(router);
                    }
                    for (DomainRouterVO routerToStart : routers) {
                        logger.warn("Trying to start router {} as part of vm: {} reboot",
                                routerToStart, vm);
                        virtualNetAppliance.startRouter(routerToStart.getId(), true);
                    }
                }
            } catch (ConcurrentOperationException e) {
                throw new CloudRuntimeException("Concurrent operations on starting router. " + e);
            } catch (Exception ex) {
                throw new CloudRuntimeException("Router start failed due to" + ex);
            } finally {
                if (logger.isInfoEnabled()) {
                    logger.info("Rebooting vm {}{}.", vm,
                            enterSetup ? " entering hardware setup menu" : " as is");
                }
                Map<VirtualMachineProfile.Param, Object> params = null;
                if (enterSetup) {
                    params = new HashMap<>();
                    params.put(VirtualMachineProfile.Param.BootIntoSetup, Boolean.TRUE);
                    if (logger.isTraceEnabled()) {
                        logger.trace(String.format("Adding %s to paramlist",
                                VirtualMachineProfile.Param.BootIntoSetup));
                    }
                }
                itMgr.reboot(vm.getUuid(), params);
            }
            return vmDao.findById(vmId);
        } else {
            logger.error("Vm {} is not in Running state, failed to reboot", vm);
            return null;
        }
    }

    @Override
    public UserVm forceRebootVirtualMachine(UserVmVO vm, long hostId, boolean enterSetup) {
        try {
            if (userVmManager.stopVirtualMachine(vm.getId(), false) != null) {
                Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
                if (enterSetup) {
                    params.put(VirtualMachineProfile.Param.BootIntoSetup, Boolean.TRUE);
                }
                return userVmManager.startVirtualMachine(vm.getId(), null, null, hostId, params, null, false).first();
            }
        } catch (CloudException e) {
            throw new CloudRuntimeException(String.format("Unable to reboot the VM: %s", vm), e);
        }
        return null;
    }

    /**
     * Collects disk and network statistics for a VM in the given state.
     * Delegates to {@link VmStatsCollectionService}; skips collection when
     * the VM's actual state differs from {@code expectedState}.
     */
    private void collectVmDiskAndNetworkStatistics(UserVm vm, State expectedState) {
        if (expectedState == null || expectedState == vm.getState()) {
            vmStatsCollectionService.collectVmDiskStatistics(vm);
            vmStatsCollectionService.collectVmNetworkStatistics(vm);
        } else {
            logger.warn("Skip collecting vm {} disk and network statistics as the expected vm state is {} but actual state is {}",
                    vm, expectedState, vm.getState());
        }
    }
}
