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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.utils.cache.SingleCache;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cloud.alert.AlertManager;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.DateUtil;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VirtualMachine.PowerState;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Handles out-of-band VM power-state reports and scanning of stalled
 * transitional VMs. Extracted from {@link VirtualMachineManagerImpl} (Phase 4, slice 6).
 */
@Component
public class VmPowerStateSyncManagerImpl implements VmPowerStateSyncManager {

    private static final Logger logger = LogManager.getLogger(VmPowerStateSyncManagerImpl.class);

    private static final String VM_SYNC_ALERT_SUBJECT = "VM state sync alert";

    @Inject
    protected VMInstanceDao vmInstanceDao;

    @Inject
    protected VmWorkJobDao workJobDao;

    @Inject
    protected HighAvailabilityManager haMgr;

    @Inject
    protected HostDao hostDao;

    @Inject
    protected AlertManager alertMgr;

    @Inject
    @Lazy
    protected VmStateMachineActions vmStateMachineActions;

    protected SingleCache<List<Long>> vmIdsInProgressCache;

    protected boolean syncTransitioningVmPowerState;

    @PostConstruct
    public void init() {
        vmIdsInProgressCache = new SingleCache<>(10, workJobDao::listVmIdsWithPendingJob);
        syncTransitioningVmPowerState = Boolean.TRUE.equals(VirtualMachineManager.VmSyncPowerStateTransitioning.value());
    }

    @Override
    public void handlePowerStateReport(final Long vmId) {
        assert vmId != null;

        final List<VmWorkJobVO> pendingWorkJobs = workJobDao.listPendingWorkJobs(
                VirtualMachine.Type.Instance, vmId);
        if (CollectionUtils.isEmpty(pendingWorkJobs) && !haMgr.hasPendingHaWork(vmId)) {
            final VMInstanceVO vm = vmInstanceDao.findById(vmId);
            if (vm != null) {
                switch (vm.getPowerState()) {
                case PowerOn:
                    handlePowerOnReportWithNoPendingJobsOnVM(vm);
                    break;

                case PowerOff:
                case PowerReportMissing:
                    handlePowerOffReportWithNoPendingJobsOnVM(vm);
                    break;
                case PowerUnknown:
                default:
                    assert false;
                    break;
                }
            } else {
                logger.warn("VM {} no longer exists when processing VM state report.", vmId);
            }
        } else {
            logger.info("There is pending job or HA tasks working on the VM. vm: {}, postpone power-change report by resetting power-change counters.", () -> vmInstanceDao.findById(vmId));
            vmInstanceDao.resetVmPowerStateTracking(vmId);
        }
    }

    protected ApiCommandResourceType getApiCommandResourceTypeForVm(VirtualMachine vm) {
        switch (vm.getType()) {
            case DomainRouter:
                return ApiCommandResourceType.DomainRouter;
            case ConsoleProxy:
                return ApiCommandResourceType.ConsoleProxy;
            case SecondaryStorageVm:
                return ApiCommandResourceType.SystemVm;
        }
        return ApiCommandResourceType.VirtualMachine;
    }

    protected void handlePowerOnReportWithNoPendingJobsOnVM(final VMInstanceVO vm) {
        switch (vm.getState()) {
        case Starting:
            logger.info("VM {} is at {} and we received a power-on report while there is no pending jobs on it.", vm.getInstanceName(), vm.getState());

            try {
                vmStateMachineActions.stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (final NoTransitionException e) {
                logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }

            logger.info("VM {} is sync-ed to at Running state according to power-on report from hypervisor.", vm.getInstanceName());

            alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, "VM " + vm.getHostName() + "(" + vm.getInstanceName()
                    + ") state is sync-ed (Starting -> Running) from out-of-context transition. VM network environment may need to be reset");
            break;

        case Running:
            try {
                if (vm.getHostId() != null && !vm.getHostId().equals(vm.getPowerHostId())) {
                    logger.info("Detected out of band VM migration from host {} to host {}", () -> hostDao.findById(vm.getHostId()), () -> hostDao.findById(vm.getPowerHostId()));
                }
                vmStateMachineActions.stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (final NoTransitionException e) {
                logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }

            break;

        case Stopping:
        case Stopped:
            logger.info("VM {} is at {} and we received a power-on report while there is no pending jobs on it.", vm.getInstanceName(), vm.getState());

            try {
                vmStateMachineActions.stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (final NoTransitionException e) {
                logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }
            alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, "VM " + vm.getHostName() + "(" + vm.getInstanceName() + ") state is sync-ed (" + vm.getState()
                    + " -> Running) from out-of-context transition. VM network environment may need to be reset");

            ActionEventUtils.onActionEvent(User.UID_SYSTEM, Account.ACCOUNT_ID_SYSTEM, vm.getDomainId(),
                EventTypes.EVENT_VM_START, "Out of band VM power on", vm.getId(), getApiCommandResourceTypeForVm(vm).toString());
            logger.info("VM {} is sync-ed to at Running state according to power-on report from hypervisor.", vm.getInstanceName());
            break;

        case Destroyed:
        case Expunging:
            logger.info("Receive power on report when Instance is in destroyed or expunging state. Instance: {}, state: {}.", vm, vm.getState());
            break;

        case Migrating:
            logger.info("Instance {} is at {} and we received a power-on report while there is no pending jobs on it.", vm, vm.getState());
            try {
                vmStateMachineActions.stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (final NoTransitionException e) {
                logger.warn("Unexpected Instance state transition exception, race-condition?", e);
            }
            logger.info("Instance {} is sync-ed to at Running state according to power-on report from hypervisor.", vm);
            break;

        case Error:
        default:
            logger.info("Receive power on report when Instance is in error or unexpected state. Instance: {}, state: {}.", vm, vm.getState());
            break;
        }
    }

    protected void handlePowerOffReportWithNoPendingJobsOnVM(final VMInstanceVO vm) {
        switch (vm.getState()) {
        case Starting:
        case Stopping:
        case Running:
        case Stopped:
            ActionEventUtils.onActionEvent(User.UID_SYSTEM, Account.ACCOUNT_ID_SYSTEM, vm.getDomainId(),
                    EventTypes.EVENT_VM_STOP, "Out of band VM power off", vm.getId(), getApiCommandResourceTypeForVm(vm).toString());
        case Migrating:
            logger.info("VM {} is at {} and we received a {} report while there is no pending jobs on it"
                            , vm, vm.getState(), vm.getPowerState());
            if ((HighAvailabilityManager.ForceHA.value() || vm.isHaEnabled()) && vm.getState() == State.Running
                    && VirtualMachineManagerImpl.HaVmRestartHostUp.value()
                    && vm.getHypervisorType() != HypervisorType.VMware
                    && vm.getHypervisorType() != HypervisorType.Hyperv) {
                logger.info("Detected out-of-band stop of a HA enabled VM {}, will schedule restart.", vm);
                if (!haMgr.hasPendingHaWork(vm.getId())) {
                    haMgr.scheduleRestart(vm, true);
                } else {
                    logger.info("VM {} already has a pending HA task working on it.", vm);
                }
                return;
            }

            if (PowerState.PowerOff.equals(vm.getPowerState())) {
                final VirtualMachineGuru vmGuru = vmStateMachineActions.getVmGuru(vm);
                final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
                if (!vmStateMachineActions.sendStop(vmGuru, profile, true, true)) {
                    return;
                } else {
                    // Release resources on StopCommand success
                    vmStateMachineActions.releaseVmResources(profile, true);
                }
            } else if (PowerState.PowerReportMissing.equals(vm.getPowerState())) {
                final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
                // VM will be sync-ed to Stopped state, release the resources
                vmStateMachineActions.releaseVmResources(profile, true);
            }

            try {
                vmStateMachineActions.stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOffReport, null);
            } catch (final NoTransitionException e) {
                logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }

            alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, String.format("VM %s(%s) state is sync-ed (%s -> Stopped) from out-of-context transition.",
                            vm.getHostName(), vm, vm.getState()));

            logger.info("VM {} is sync-ed to at Stopped state according to power-off report from hypervisor.", vm);

            break;

        case Destroyed:
        case Expunging:
            break;

        case Error:
        default:
            break;
        }
    }

    @Override
    public void scanStalledVMInTransitionStateOnUpHost(final long hostId) {
        if (!syncTransitioningVmPowerState) {
            return;
        }
        if (!hostDao.isHostUp(hostId)) {
            return;
        }
        final long stallThresholdInMs = VirtualMachineManagerImpl.VmJobStateReportInterval.value() * 2;
        final long cutTime = new Date(DateUtil.currentGMTTime().getTime() - stallThresholdInMs).getTime();
        final List<VMInstanceVO> hostTransitionVms = vmInstanceDao.listByHostAndState(hostId, State.Starting, State.Stopping, State.Migrating);

        final List<VMInstanceVO> mostLikelyStoppedVMs = listStalledVMInTransitionStateOnUpHost(hostTransitionVms, cutTime);
        for (final VMInstanceVO vm : mostLikelyStoppedVMs) {
            handlePowerOffReportWithNoPendingJobsOnVM(vm);
        }

        final List<VMInstanceVO> vmsWithRecentReport = listVMInTransitionStateWithRecentReportOnUpHost(hostTransitionVms, cutTime);
        for (final VMInstanceVO vm : vmsWithRecentReport) {
            if (vm.getPowerState() == PowerState.PowerOn) {
                handlePowerOnReportWithNoPendingJobsOnVM(vm);
            } else {
                handlePowerOffReportWithNoPendingJobsOnVM(vm);
            }
        }
    }

    @Override
    public void scanStalledVMInTransitionStateOnDisconnectedHosts() {
        final Date cutTime = new Date(DateUtil.currentGMTTime().getTime() - VirtualMachineManagerImpl.VmOpWaitInterval.value() * 1000);
        final List<Long> stuckAndUncontrollableVMs = listStalledVMInTransitionStateOnDisconnectedHosts(cutTime);
        for (final Long vmId : stuckAndUncontrollableVMs) {
            final VMInstanceVO vm = vmInstanceDao.findById(vmId);

            alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, String.format("VM %s(%s) is stuck in %s state and its host is unreachable for too long",
                            vm.getHostName(), vm, vm.getState()));
        }
    }

    protected List<VMInstanceVO> listStalledVMInTransitionStateOnUpHost(
            final List<VMInstanceVO> transitioningVms, final long cutTime) {
        if (CollectionUtils.isEmpty(transitioningVms)) {
            return transitioningVms;
        }
        List<Long> vmIdsInProgress = vmIdsInProgressCache.get();
        return transitioningVms.stream()
                .filter(v -> v.getPowerStateUpdateTime().getTime() < cutTime && !vmIdsInProgress.contains(v.getId()))
                .collect(Collectors.toList());
    }

    protected List<VMInstanceVO> listVMInTransitionStateWithRecentReportOnUpHost(
            final List<VMInstanceVO> transitioningVms, final long cutTime) {
        if (CollectionUtils.isEmpty(transitioningVms)) {
            return transitioningVms;
        }
        List<Long> vmIdsInProgress = vmIdsInProgressCache.get();
        return transitioningVms.stream()
                .filter(v -> v.getPowerStateUpdateTime().getTime() > cutTime && !vmIdsInProgress.contains(v.getId()))
                .collect(Collectors.toList());
    }

    protected List<Long> listStalledVMInTransitionStateOnDisconnectedHosts(final Date cutTime) {
        final String sql = "SELECT i.* " +
                "FROM vm_instance AS i " +
                "INNER JOIN host AS h ON i.host_id = h.id " +
                "WHERE h.status != 'UP' " +
                "  AND i.power_state_update_time < ? " +
                "  AND i.state IN ('Starting', 'Stopping', 'Migrating') " +
                "  AND i.id NOT IN (SELECT vm_instance_id FROM vm_work_job AS w " +
                "                    INNER JOIN async_job AS j ON w.id = j.id " +
                "                    WHERE j.job_status = ?) " +
                "  AND i.removed IS NULL";

        final List<Long> l = new ArrayList<>();
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        String cutTimeStr = DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), cutTime);
        int jobStatusInProgress = JobInfo.Status.IN_PROGRESS.ordinal();

        try {
            PreparedStatement pstmt = txn.prepareAutoCloseStatement(sql);

            pstmt.setString(1, cutTimeStr);
            pstmt.setInt(2, jobStatusInProgress);
            final ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                l.add(rs.getLong(1));
            }
        } catch (final SQLException e) {
            logger.error("Unable to execute SQL [{}] with params {\"i.power_state_update_time\": \"{}\", \"j.job_status\": {}} due to [{}].", sql, cutTimeStr, jobStatusInProgress, e.getMessage(), e);
        }
        return l;
    }
}
