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
package com.cloud.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.systemvm.ListSystemVMsCmd;
import org.apache.cloudstack.api.command.admin.systemvm.PatchSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.ScaleSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.UpgradeSystemVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.CloudStackVersion;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.PatchSystemVmAnswer;
import com.cloud.agent.api.PatchSystemVmCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.manager.Commands;
import com.cloud.cpu.CPU;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Networks;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Handles system VM patch, upgrade and search operations.
 * Extracted from {@link ManagementServerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 12).
 */
@Component
public class SystemVmOperationsServiceImpl implements SystemVmOperationsService {

    protected Logger logger = LogManager.getLogger(getClass());

    private static final VirtualMachine.Type[] systemVmTypes = {
        VirtualMachine.Type.SecondaryStorageVm, VirtualMachine.Type.ConsoleProxy
    };
    private static final int patchCommandTimeout = 600000;

    @Inject protected VMInstanceDao vmInstanceDao;
    @Inject protected UserVmManager userVmMgr;
    @Inject protected VirtualMachineManager itMgr;
    @Inject protected ServiceOfferingDao offeringDao;
    @Inject protected AccountManager accountMgr;
    @Inject protected AgentManager agentMgr;
    @Inject protected NicDao nicDao;
    @Inject protected NetworkDao networkDao;
    @Inject protected DomainRouterDao routerDao;
    @Inject protected PrimaryDataStoreDao primaryDataStoreDao;
    @Inject protected VolumeDao volumeDao;
    @Inject protected VMTemplateDao templateDao;
    @Inject protected CapabilitiesService capabilitiesService;

    @Override
    public VirtualMachine upgradeSystemVM(final ScaleSystemVMCmd cmd)
            throws ResourceUnavailableException, ManagementServerException,
                   VirtualMachineMigrationException, ConcurrentOperationException {
        final VMInstanceVO vmInstance = vmInstanceDao.findById(cmd.getId());
        if (vmInstance.getHypervisorType() == HypervisorType.XenServer && vmInstance.getState().equals(State.Running)) {
            throw new InvalidParameterValueException("Dynamic Scaling operation is not permitted for this hypervisor on system vm");
        }
        final boolean result = userVmMgr.upgradeVirtualMachine(cmd.getId(), cmd.getServiceOfferingId(), cmd.getDetails());
        if (result) {
            return vmInstanceDao.findById(cmd.getId());
        } else {
            throw new CloudRuntimeException("Failed to upgrade System VM");
        }
    }

    @Override
    public VirtualMachine upgradeSystemVM(final UpgradeSystemVMCmd cmd) {
        final Long systemVmId = cmd.getId();
        final Long serviceOfferingId = cmd.getServiceOfferingId();
        return upgradeStoppedSystemVm(systemVmId, serviceOfferingId, cmd.getDetails());
    }

    protected VirtualMachine upgradeStoppedSystemVm(final Long systemVmId, final Long serviceOfferingId, final Map<String, String> customparameters) {
        final Account caller = CallContext.current().getCallingAccount();

        final VMInstanceVO systemVm = vmInstanceDao.findByIdTypes(systemVmId, VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);
        if (systemVm == null) {
            throw new InvalidParameterValueException("Unable to find SystemVm with id " + systemVmId);
        }

        accountMgr.checkAccess(caller, null, true, systemVm);

        // Check that the specified service offering ID is valid
        ServiceOfferingVO newServiceOffering = offeringDao.findById(serviceOfferingId);
        final ServiceOfferingVO currentServiceOffering = offeringDao.findById(systemVmId, systemVm.getServiceOfferingId());
        if (newServiceOffering.isDynamic()) {
            newServiceOffering.setDynamicFlag(true);
            userVmMgr.validateCustomParameters(newServiceOffering, customparameters);
            newServiceOffering = offeringDao.getComputeOffering(newServiceOffering, customparameters);
        }
        itMgr.checkIfCanUpgrade(systemVm, newServiceOffering);

        final boolean result = itMgr.upgradeVmDb(systemVmId, newServiceOffering, currentServiceOffering);

        if (result) {
            return vmInstanceDao.findById(systemVmId);
        } else {
            throw new CloudRuntimeException("Unable to upgrade system vm " + systemVm);
        }
    }

    @Override
    public Pair<Boolean, String> patchSystemVM(PatchSystemVMCmd cmd) {
        Long systemVmId = cmd.getId();
        boolean forced = cmd.isForced();

        if (systemVmId == null) {
            throw new InvalidParameterValueException("Please provide a valid ID of a system VM to be patched");
        }

        final VMInstanceVO systemVm = vmInstanceDao.findByIdTypes(systemVmId, systemVmTypes);
        if (systemVm == null) {
            throw new InvalidParameterValueException(String.format(
                "Unable to find SystemVm with id %s. patchSystemVm API can be used to patch CPVM / SSVM only.", systemVmId));
        }

        return updateSystemVM(systemVm, forced);
    }

    @Override
    public Pair<Boolean, String> updateSystemVM(VMInstanceVO systemVM, boolean forced) {
        String msg = String.format("Unable to patch SystemVM: %s as it is not in Running state. Please destroy and recreate the SystemVM.", systemVM);
        if (systemVM.getState() != State.Running) {
            logger.error(msg);
            return new Pair<>(false, msg);
        }
        return patchSystemVm(systemVM, forced);
    }

    protected String getControlIp(final long systemVmId) {
        String controlIpAddress = null;
        final List<NicVO> nics = nicDao.listByVmId(systemVmId);
        for (final NicVO n : nics) {
            final NetworkVO nc = networkDao.findById(n.getNetworkId());
            if (nc != null && nc.getTrafficType() == Networks.TrafficType.Control) {
                controlIpAddress = n.getIPv4Address();
                // router will have only one control IP
                break;
            }
        }

        if (controlIpAddress == null) {
            logger.warn(String.format("Unable to find systemVm's control ip in its attached NICs!. systemVmId: %s", systemVmId));
            VMInstanceVO systemVM = vmInstanceDao.findById(systemVmId);
            return systemVM.getPrivateIpAddress();
        }

        return controlIpAddress;
    }

    protected boolean updateRouterDetails(Long routerId, String scriptVersion, String templateVersion) {
        DomainRouterVO router = routerDao.findById(routerId);
        if (router == null) {
            throw new CloudRuntimeException(String.format("Failed to find router with id: %s", routerId));
        }

        router.setTemplateVersion(templateVersion);
        router.setScriptsVersion(scriptVersion);
        String codeVersion = capabilitiesService.getVersion();
        if (StringUtils.isNotEmpty(codeVersion)) {
            codeVersion = CloudStackVersion.parse(codeVersion).toString();
        }
        router.setSoftwareVersion(codeVersion);
        return routerDao.update(routerId, router);
    }

    protected Pair<Boolean, String> patchSystemVm(VMInstanceVO systemVM, boolean forced) {
        PatchSystemVmAnswer answer;
        final PatchSystemVmCommand command = new PatchSystemVmCommand();
        command.setAccessDetail(NetworkElementCommand.ROUTER_IP, getControlIp(systemVM.getId()));
        command.setAccessDetail(NetworkElementCommand.ROUTER_NAME, systemVM.getInstanceName());
        command.setForced(forced);
        try {
            Commands cmds = new Commands(Command.OnError.Stop);
            cmds.addCommand(command);
            Answer[] answers = agentMgr.send(systemVM.getHostId(), cmds, patchCommandTimeout);
            answer = (PatchSystemVmAnswer) answers[0];
            if (!answer.getResult()) {
                String errMsg = String.format("Failed to patch systemVM %s due to %s", systemVM.getInstanceName(), answer.getDetails());
                logger.error(errMsg);
                return new Pair<>(false, errMsg);
            }
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            String errMsg = "SystemVM live patch failed";
            logger.error(errMsg, e);
            return new Pair<>(false, String.format("%s due to: %s", errMsg, e.getMessage()));
        }
        logger.info(String.format("Successfully patched system VM %s", systemVM.getInstanceName()));
        List<VirtualMachine.Type> routerTypes = new ArrayList<>();
        routerTypes.add(VirtualMachine.Type.DomainRouter);
        routerTypes.add(VirtualMachine.Type.InternalLoadBalancerVm);
        if (routerTypes.contains(systemVM.getType())) {
            boolean updated = updateRouterDetails(systemVM.getId(), answer.getScriptsVersion(), answer.getTemplateVersion());
            if (!updated) {
                logger.warn("Failed to update router's script and template version details");
            }
        }
        return new Pair<>(true, answer.getDetails());
    }

    @Override
    public Pair<List<? extends VirtualMachine>, Integer> searchForSystemVm(final ListSystemVMsCmd cmd) {
        final String type = cmd.getSystemVmType();
        final Long zoneId = accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), cmd.getZoneId());
        final Long id = cmd.getId();
        final String name = cmd.getSystemVmName();
        final String state = cmd.getState();
        final String keyword = cmd.getKeyword();
        final Long podId = cmd.getPodId();
        final Long hostId = cmd.getHostId();
        final Long storageId = cmd.getStorageId();
        final CPU.CPUArch arch = cmd.getArch();

        final Filter searchFilter = new Filter(VMInstanceVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchBuilder<VMInstanceVO> sb = vmInstanceDao.createSearchBuilder();

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("hostName", sb.entity().getHostName(), SearchCriteria.Op.LIKE);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("podId", sb.entity().getPodIdToDeployIn(), SearchCriteria.Op.EQ);
        sb.and("hostId", sb.entity().getHostId(), SearchCriteria.Op.EQ);
        sb.and("type", sb.entity().getType(), SearchCriteria.Op.EQ);
        sb.and("nulltype", sb.entity().getType(), SearchCriteria.Op.IN);

        if (storageId != null) {
            StoragePoolVO storagePool = primaryDataStoreDao.findById(storageId);
            if (storagePool.getPoolType() == Storage.StoragePoolType.DatastoreCluster) {
                final SearchBuilder<VolumeVO> volumeSearch = volumeDao.createSearchBuilder();
                volumeSearch.and("poolId", volumeSearch.entity().getPoolId(), SearchCriteria.Op.IN);
                sb.join("volumeSearch", volumeSearch, sb.entity().getId(), volumeSearch.entity().getInstanceId(), JoinBuilder.JoinType.INNER);
            } else {
                final SearchBuilder<VolumeVO> volumeSearch = volumeDao.createSearchBuilder();
                volumeSearch.and("poolId", volumeSearch.entity().getPoolId(), SearchCriteria.Op.EQ);
                sb.join("volumeSearch", volumeSearch, sb.entity().getId(), volumeSearch.entity().getInstanceId(), JoinBuilder.JoinType.INNER);
            }
        }

        boolean templateJoinNeeded = arch != null;
        if (templateJoinNeeded) {
            SearchBuilder<VMTemplateVO> templateSearch = templateDao.createSearchBuilder();
            templateSearch.and("templateArch", templateSearch.entity().getArch(), SearchCriteria.Op.EQ);
            sb.join("vmTemplate", templateSearch, templateSearch.entity().getId(), sb.entity().getTemplateId(), JoinBuilder.JoinType.INNER);
        }

        final SearchCriteria<VMInstanceVO> sc = sb.create();

        if (keyword != null) {
            final SearchCriteria<VMInstanceVO> ssc = vmInstanceDao.createSearchCriteria();
            ssc.addOr("hostName", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("state", SearchCriteria.Op.LIKE, "%" + keyword + "%");

            sc.addAnd("hostName", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("hostName", name);
        }
        if (state != null) {
            sc.setParameters("state", state);
        }
        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }
        if (podId != null) {
            sc.setParameters("podId", podId);
        }
        if (hostId != null) {
            sc.setParameters("hostId", hostId);
        }

        if (type != null) {
            sc.setParameters("type", type);
        } else {
            sc.setParameters("nulltype", VirtualMachine.Type.SecondaryStorageVm, VirtualMachine.Type.ConsoleProxy);
        }

        if (storageId != null) {
            StoragePoolVO storagePool = primaryDataStoreDao.findById(storageId);
            if (storagePool.getPoolType() == Storage.StoragePoolType.DatastoreCluster) {
                List<StoragePoolVO> childDataStores = primaryDataStoreDao.listChildStoragePoolsInDatastoreCluster(storageId);
                List<Long> childDatastoreIds = childDataStores.stream().map(mo -> mo.getId()).collect(Collectors.toList());
                sc.setJoinParameters("volumeSearch", "poolId", childDatastoreIds.toArray());
            } else {
                sc.setJoinParameters("volumeSearch", "poolId", storageId);
            }
        }

        if (arch != null) {
            sc.setJoinParameters("vmTemplate", "templateArch", arch);
        }

        final Pair<List<VMInstanceVO>, Integer> result = vmInstanceDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }
}
