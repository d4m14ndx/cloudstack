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

import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.ScaleVmCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.alert.AlertManager;
import com.cloud.capacity.CapacityManager;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.event.EventTypes;
import com.cloud.exception.AffinityConflictException;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruBase;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.ItWorkVO.Step;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmScaleReconfigurationServiceImpl implements VmScaleReconfigurationService {

    private static final Logger logger = LogManager.getLogger(VmScaleReconfigurationServiceImpl.class);

    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected ServiceOfferingDao offeringDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected DeploymentPlanningManager dpMgr;
    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected NetworkOrchestrationService networkMgr;
    @Inject
    protected VolumeOrchestrationService volumeMgr;
    @Inject
    protected ItWorkDao workDao;
    @Inject
    protected AlertManager alertMgr;
    @Inject
    protected HypervisorGuruManager hvGuruMgr;
    @Inject
    protected UserVmManager userVmMgr;
    @Inject
    protected CapacityManager capacityMgr;
    @Inject
    protected HighAvailabilityManager haMgr;
    @Inject
    protected VmWorkJobQueueService vmWorkJobQueueService;
    @Inject
    protected VmServiceOfferingUpgradeManager vmServiceOfferingUpgradeManager;
    @Inject
    protected VmScaleReconfigurationActions vmScaleReconfigurationActions;

    @Override
    public void findHostAndMigrate(final String vmUuid, final Long newSvcOfferingId, final Map<String, String> customParameters, final ExcludeList excludes)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {

        final VMInstanceVO vm = vmDao.findByUuid(vmUuid);
        if (vm == null) {
            throw new CloudRuntimeException("Unable to find " + vmUuid);
        }
        ServiceOfferingVO newServiceOffering = offeringDao.findById(newSvcOfferingId);
        if (newServiceOffering.isDynamic()) {
            newServiceOffering.setDynamicFlag(true);
            newServiceOffering = offeringDao.getComputeOffering(newServiceOffering, customParameters);
        }
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, null, newServiceOffering, null, null);

        final Long srcHostId = vm.getHostId();
        final Long oldSvcOfferingId = vm.getServiceOfferingId();
        if (srcHostId == null) {
            throw new CloudRuntimeException("Unable to scale the vm because it doesn't have a host id");
        }
        final Host host = hostDao.findById(srcHostId);
        final DataCenterDeployment plan = new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), null, null, null);
        excludes.addHost(vm.getHostId());
        vm.setServiceOfferingId(newSvcOfferingId);

        DeployDestination dest = null;

        try {
            dest = dpMgr.planDeployment(profile, plan, excludes, null);
        } catch (final AffinityConflictException e2) {
            String message = String.format("Unable to create deployment, affinity rules associated to the %s conflict.", vm.toString());
            logger.warn(message, e2);
            throw new CloudRuntimeException(message);
        }

        if (dest != null) {
            logger.debug("Found {} for scaling the vm to.", dest);
        }

        if (dest == null) {
            throw new InsufficientServerCapacityException("Unable to find a server to scale the vm to.", host.getClusterId());
        }

        excludes.addHost(dest.getHost().getId());
        try {
            migrateForScale(vm.getUuid(), srcHostId, dest, oldSvcOfferingId);
        } catch (ResourceUnavailableException | ConcurrentOperationException e) {
            logger.warn("Unable to migrate {} to {} due to [{}]", vm.toString(), dest.getHost().toString(), e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void migrateForScale(final String vmUuid, final long srcHostId, final DeployDestination dest, final Long oldSvcOfferingId)
            throws ResourceUnavailableException, ConcurrentOperationException {
        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            final VirtualMachine vm = vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = vmWorkJobQueueService.createPlaceHolderWork(vm.getId());
            try {
                orchestrateMigrateForScale(vmUuid, srcHostId, dest, oldSvcOfferingId);
            } finally {
                vmWorkJobQueueService.expungePlaceHolderWork(placeHolder);
            }
        } else {
            final Outcome<VirtualMachine> outcome = vmWorkJobQueueService.migrateVmForScaleThroughJobQueue(vmUuid, srcHostId, dest, oldSvcOfferingId);

            vmWorkJobQueueService.retrieveVmFromJobOutcome(outcome, vmUuid, "migrateVmForScale");

            try {
                vmWorkJobQueueService.retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
            } catch (InsufficientCapacityException ex) {
                throw new RuntimeException("Unexpected exception", ex);
            }
        }
    }

    @Override
    public void orchestrateMigrateForScale(final String vmUuid, final long srcHostId, final DeployDestination dest, final Long oldSvcOfferingId)
            throws ResourceUnavailableException, ConcurrentOperationException {

        VMInstanceVO vm = vmDao.findByUuid(vmUuid);
        logger.info("Migrating {} to {}", vm, dest);

        vm.getServiceOfferingId();
        final long dstHostId = dest.getHost().getId();
        final Host fromHost = hostDao.findById(srcHostId);
        if (fromHost == null) {
            String logMessageUnableToFindHost = String.format("Unable to find host to migrate from %s.", srcHostId);
            logger.info(logMessageUnableToFindHost);
            throw new CloudRuntimeException(logMessageUnableToFindHost);
        }

        Host dstHost = hostDao.findById(dstHostId);
        long destHostClusterId = dest.getCluster().getId();
        long fromHostClusterId = fromHost.getClusterId();
        if (fromHostClusterId != destHostClusterId) {
            String logMessageHostsOnDifferentCluster = String.format("Source and destination host are not in same cluster, unable to migrate to %s", fromHost);
            logger.info(logMessageHostsOnDifferentCluster);
            throw new CloudRuntimeException(logMessageHostsOnDifferentCluster);
        }

        final VirtualMachineGuru vmGuru = vmScaleReconfigurationActions.getVmGuru(vm);

        vm = vmDao.findByUuid(vmUuid);
        if (vm == null) {
            String message = String.format("Unable to find VM {\"uuid\": \"%s\"}.", vmUuid);
            logger.warn(message);
            throw new CloudRuntimeException(message);
        }

        if (vm.getState() != State.Running) {
            String message = String.format("%s is not in \"Running\" state, unable to migrate it. Current state [%s].", vm.toString(), vm.getState());
            logger.warn(message);
            throw new CloudRuntimeException(message);
        }

        AlertManager.AlertType alertType = AlertManager.AlertType.ALERT_TYPE_USERVM_MIGRATE;
        if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER_MIGRATE;
        } else if (VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY_MIGRATE;
        }

        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        networkMgr.prepareNicForMigration(profile, dest);

        volumeMgr.prepareForMigration(profile, dest);

        final VirtualMachineTO to = vmScaleReconfigurationActions.toVmTO(profile);
        final PrepareForMigrationCommand pfmc = new PrepareForMigrationCommand(to);

        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), vmScaleReconfigurationActions.getNodeId(), State.Migrating, vm.getType(), vm.getId());
        work.setStep(Step.Prepare);
        work.setResourceType(ItWorkVO.ResourceType.Host);
        work.setResourceId(dstHostId);
        work = workDao.persist(work);

        Answer pfma = null;
        try {
            pfma = agentMgr.send(dstHostId, pfmc);
            if (pfma == null || !pfma.getResult()) {
                final String details = pfma != null ? pfma.getDetails() : "null answer returned";
                pfma = null;
                throw new AgentUnavailableException(String.format("Unable to prepare for migration to destination host [%s] due to [%s].", dest.getHost(), details), dstHostId);
            }
        } catch (final OperationTimedoutException e1) {
            throw new AgentUnavailableException("Operation timed out", dstHostId);
        } finally {
            if (pfma == null) {
                work.setStep(Step.Done);
                workDao.update(work.getId(), work);
            }
        }

        vm.setLastHostId(srcHostId);
        try {
            if (vm.getHostId() == null || vm.getHostId() != srcHostId || !vmScaleReconfigurationActions.changeState(vm, Event.MigrationRequested, dstHostId, work, Step.Migrating)) {
                String message = String.format("Migration of %s cancelled because state has changed.", vm.toString());
                logger.warn(message);
                throw new ConcurrentOperationException(message);
            }
        } catch (final NoTransitionException e1) {
            String message = String.format("Migration of %s cancelled due to [%s].", vm.toString(), e1.getMessage());
            logger.error(message, e1);
            throw new ConcurrentOperationException(message);
        }

        boolean migrated = false;
        try {
            final MigrateCommand mc = vmScaleReconfigurationActions.buildMigrateCommand(vm, to, dest, pfma, null);

            try {
                final Answer ma = agentMgr.send(vm.getLastHostId(), mc);
                if (ma == null || !ma.getResult()) {
                    String msg = String.format("Unable to migrate %s due to [%s].", vm.toString(), ma != null ? ma.getDetails() : "null answer returned");
                    logger.error(msg);
                    throw new CloudRuntimeException(msg);
                }
            } catch (final OperationTimedoutException e) {
                if (e.isActive()) {
                    logger.warn("Active migration command so scheduling a restart for {}", vm, e);
                    haMgr.scheduleRestart(vm, true);
                }
                throw new AgentUnavailableException("Operation timed out on migrating " + vm, dstHostId, e);
            }

            try {
                final long newServiceOfferingId = vm.getServiceOfferingId();
                vm.setServiceOfferingId(oldSvcOfferingId);
                if (!vmScaleReconfigurationActions.changeState(vm, VirtualMachine.Event.OperationSucceeded, dstHostId, work, Step.Started)) {
                    throw new ConcurrentOperationException("Unable to change the state for " + vm);
                }
                vm.setServiceOfferingId(newServiceOfferingId);
            } catch (final NoTransitionException e1) {
                throw new ConcurrentOperationException("Unable to change state due to " + e1.getMessage());
            }

            try {
                if (!vmScaleReconfigurationActions.checkVmOnHost(vm, dstHostId)) {
                    logger.error("Unable to complete migration for {}", vm);
                    try {
                        agentMgr.send(srcHostId, new Commands(vmScaleReconfigurationActions.cleanup(vm.getInstanceName())), null);
                    } catch (final AgentUnavailableException e) {
                        logger.error("Unable to cleanup source host [{}] due to [{}].", fromHost, e.getMessage(), e);
                    }
                    vmScaleReconfigurationActions.cleanup(vmGuru, new VirtualMachineProfileImpl(vm), work, Event.AgentReportStopped, true);
                    throw new CloudRuntimeException("Unable to complete migration for " + vm);
                }
            } catch (final OperationTimedoutException e) {
                logger.debug("Error while checking the {} on {}", vm, dstHost, e);
            }

            migrated = true;
        } finally {
            if (!migrated) {
                logger.info("Migration was unsuccessful.  Cleaning up: {}", vm);

                String alertSubject = String.format("Unable to migrate %s from %s in Zone [%s] and Pod [%s].",
                        vm.getInstanceName(), fromHost, dest.getDataCenter().getName(), dest.getPod().getName());
                String alertBody = "Migrate Command failed. Please check logs.";
                alertMgr.sendAlert(alertType, fromHost.getDataCenterId(), fromHost.getPodId(), alertSubject, alertBody);
                try {
                    agentMgr.send(dstHostId, new Commands(vmScaleReconfigurationActions.cleanup(vm.getInstanceName())), null);
                } catch (final AgentUnavailableException ae) {
                    logger.info("Looks like the destination Host is unavailable for cleanup");
                }
                networkMgr.setHypervisorHostname(profile, dest, false);
                try {
                    vmScaleReconfigurationActions.stateTransitTo(vm, Event.OperationFailed, srcHostId);
                } catch (final NoTransitionException e) {
                    logger.warn(e.getMessage(), e);
                }
            } else {
                networkMgr.setHypervisorHostname(profile, dest, true);

                vmScaleReconfigurationActions.updateVmPod(vm, dstHostId);
            }

            work.setStep(Step.Done);
            workDao.update(work.getId(), work);
        }
    }

    @Override
    public VMInstanceVO reConfigureVm(final String vmUuid, final ServiceOffering oldServiceOffering, final ServiceOffering newServiceOffering,
            Map<String, String> customParameters, final boolean reconfiguringOnExistingHost)
                    throws ResourceUnavailableException, InsufficientServerCapacityException, ConcurrentOperationException {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            final VirtualMachine vm = vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = vmWorkJobQueueService.createPlaceHolderWork(vm.getId());
            try {
                return orchestrateReConfigureVm(vmUuid, oldServiceOffering, newServiceOffering, reconfiguringOnExistingHost);
            } finally {
                vmWorkJobQueueService.expungePlaceHolderWork(placeHolder);
            }
        } else {
            final Outcome<VirtualMachine> outcome = vmWorkJobQueueService.reconfigureVmThroughJobQueue(vmUuid, oldServiceOffering, newServiceOffering, customParameters, reconfiguringOnExistingHost);

            VirtualMachine vm = vmWorkJobQueueService.retrieveVmFromJobOutcome(outcome, vmUuid, "reconfigureVm");

            Object result = null;
            try {
                result = vmWorkJobQueueService.retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
            } catch (Exception ex) {
                throw new RuntimeException("Unhandled exception", ex);
            }

            if (result != null) {
                throw new RuntimeException(String.format("Unexpected job execution result [%s]", result));
            }

            return (VMInstanceVO)vm;
        }
    }

    @Override
    public VMInstanceVO orchestrateReConfigureVm(final String vmUuid, final ServiceOffering oldServiceOffering, final ServiceOffering newServiceOffering,
            final boolean reconfiguringOnExistingHost) throws ResourceUnavailableException, ConcurrentOperationException {
        final VMInstanceVO vm = vmDao.findByUuid(vmUuid);

        HostVO hostVo = hostDao.findById(vm.getHostId());

        Long clustedId = hostVo.getClusterId();
        Float memoryOvercommitRatio = CapacityManager.MemOverprovisioningFactor.valueIn(clustedId);
        Float cpuOvercommitRatio = CapacityManager.CpuOverprovisioningFactor.valueIn(clustedId);
        boolean divideMemoryByOverprovisioning = HypervisorGuruBase.VmMinMemoryEqualsMemoryDividedByMemOverprovisioningFactor.valueIn(clustedId);
        boolean divideCpuByOverprovisioning = HypervisorGuruBase.VmMinCpuSpeedEqualsCpuSpeedDividedByCpuOverprovisioningFactor.valueIn(clustedId);

        int minMemory = (int)(newServiceOffering.getRamSize() / (divideMemoryByOverprovisioning ? memoryOvercommitRatio : 1));
        int minSpeed = (int)(newServiceOffering.getSpeed() / (divideCpuByOverprovisioning ? cpuOvercommitRatio : 1));

        ScaleVmCommand scaleVmCommand =
                new ScaleVmCommand(vm.getInstanceName(), newServiceOffering.getCpu(), minSpeed,
                        newServiceOffering.getSpeed(), minMemory * 1024L * 1024L, newServiceOffering.getRamSize() * 1024L * 1024L, newServiceOffering.getLimitCpuUse());

        scaleVmCommand.getVirtualMachine().setId(vm.getId());
        scaleVmCommand.getVirtualMachine().setUuid(vm.getUuid());
        scaleVmCommand.getVirtualMachine().setType(vm.getType());

        Long dstHostId = vm.getHostId();

        if (vm.getHypervisorType().equals(HypervisorType.VMware)) {
            HypervisorGuru hvGuru = hvGuruMgr.getGuru(vm.getHypervisorType());
            Map<String, String> details = hvGuru.getClusterSettings(vm.getId());
            scaleVmCommand.getVirtualMachine().setDetails(details);
        }

        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), vmScaleReconfigurationActions.getNodeId(), State.Running, vm.getType(), vm.getId());

        work.setStep(Step.Prepare);
        work.setResourceType(ItWorkVO.ResourceType.Host);
        work.setResourceId(vm.getHostId());
        workDao.persist(work);

        try {
            Answer reconfigureAnswer = agentMgr.send(vm.getHostId(), scaleVmCommand);

            if (reconfigureAnswer == null || !reconfigureAnswer.getResult()) {
                logger.error("Unable to scale vm due to {}", (reconfigureAnswer == null ? "" : reconfigureAnswer.getDetails()));
                throw new CloudRuntimeException("Unable to scale vm due to " + (reconfigureAnswer == null ? "" : reconfigureAnswer.getDetails()));
            }

            vmServiceOfferingUpgradeManager.upgradeVmDb(vm.getId(), newServiceOffering, oldServiceOffering);

            if (vm.getType().equals(VirtualMachine.Type.User)) {
                userVmMgr.generateUsageEvent(vm, vm.isDisplayVm(), EventTypes.EVENT_VM_DYNAMIC_SCALE);
            }

            if (reconfiguringOnExistingHost) {
                vm.setServiceOfferingId(oldServiceOffering.getId());
                capacityMgr.releaseVmCapacity(vm, false, false, vm.getHostId());
                vm.setServiceOfferingId(newServiceOffering.getId());
                capacityMgr.allocateVmCapacity(vm, false);
            }

        } catch (final OperationTimedoutException e) {
            throw new AgentUnavailableException("Operation timed out on reconfiguring " + vm, dstHostId);
        } catch (final AgentUnavailableException e) {
            throw e;
        }

        return vm;
    }

    void removeCustomOfferingDetails(long vmId) {
        vmServiceOfferingUpgradeManager.removeCustomOfferingDetails(vmId);
    }

    void saveCustomOfferingDetails(long vmId, ServiceOffering serviceOffering) {
        vmServiceOfferingUpgradeManager.saveCustomOfferingDetails(vmId, serviceOffering);
    }
}
