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
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVmIpAddressCommand;
import com.cloud.domain.Domain;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.uservm.UserVm;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * External DHCP IP-fetch workflow extracted from {@link UserVmManagerImpl}.
 */
@Component
public class VmExternalDhcpIpFetchServiceImpl implements VmExternalDhcpIpFetchService {

    private static final Logger logger = LoggerFactory.getLogger(VmExternalDhcpIpFetchServiceImpl.class);

    static final int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION = 3;

    @Inject
    NetworkDao networkDao;
    @Inject
    NicDao nicDao;
    @Inject
    VMInstanceDao vmInstanceDao;
    @Inject
    UserVmDao vmDao;
    @Inject
    AgentManager agentMgr;
    @Inject
    GuestOSDao guestOSDao;
    @Inject
    GuestOSCategoryDao guestOSCategoryDao;
    @Inject
    NetworkModel networkModel;

    private final Map<Long, VmAndCountDetails> vmIdCountMap = new ConcurrentHashMap<>();
    private ExecutorService vmIpFetchThreadExecutor;

    static class VmAndCountDetails {
        long vmId;
        int retrievalCount = VmIpFetchTrialMax.value();

        VmAndCountDetails() {
        }

        VmAndCountDetails(long vmId, int retrievalCount) {
            this.vmId = vmId;
            this.retrievalCount = retrievalCount;
        }

        int getRetrievalCount() {
            return retrievalCount;
        }

        void setRetrievalCount(int retrievalCount) {
            this.retrievalCount = retrievalCount;
        }

        long getVmId() {
            return vmId;
        }

        void setVmId(long vmId) {
            this.vmId = vmId;
        }

        void decrementCount() {
            retrievalCount--;
        }
    }

    @Override
    public void configure(Map<String, String> configs) {
        vmIpFetchThreadExecutor = Executors.newFixedThreadPool(VmIpFetchThreadPoolMax.value(), new NamedThreadFactory("vmIpFetchThread"));
    }

    @Override
    public Runnable getVmIpFetchTask() {
        return new VmIpFetchTask();
    }

    @Override
    public void scheduleIpFetch(long nicId, long vmId) {
        trackNicForIpFetch(nicId, vmId, VmIpFetchTrialMax.value());
    }

    void trackNicForIpFetch(long nicId, long vmId, int retrievalCount) {
        vmIdCountMap.put(nicId, new VmAndCountDetails(vmId, retrievalCount));
    }

    int getTrackedNicCount() {
        return vmIdCountMap.size();
    }

    int getTrackedRetrievalCount(long nicId) {
        return vmIdCountMap.get(nicId).getRetrievalCount();
    }

    @Override
    public void loadVmDetailsInMapForExternalDhcpIp() {
        List<NetworkVO> networks = new ArrayList<>(networkDao.listByGuestType(Network.GuestType.Shared));
        networks.addAll(networkDao.listByGuestType(Network.GuestType.L2));

        for (NetworkVO network : networks) {
            if (GuestType.L2.equals(network.getGuestType()) || networkModel.isSharedNetworkWithoutServices(network.getId())) {
                List<NicVO> nics = nicDao.listByNetworkId(network.getId());

                for (NicVO nic : nics) {
                    if (nic.getIPv4Address() == null) {
                        long nicId = nic.getId();
                        long vmId = nic.getInstanceId();
                        VMInstanceVO vmInstance = vmInstanceDao.findById(vmId);

                        if (vmInstance != null && vmInstance.getState() == State.Running) {
                            trackNicForIpFetch(nicId, vmId, VmIpFetchTrialMax.value());
                        }
                    }
                }
            }
        }
    }

    @Override
    public void stop() {
        if (vmIpFetchThreadExecutor != null) {
            vmIpFetchThreadExecutor.shutdown();
        }
    }

    private class VmIpAddrFetchThread extends ManagedContextRunnable {
        long nicId;
        long vmId;
        String vmName;
        String vmUuid;
        boolean isWindows;
        Long hostId;
        String networkCidr;
        String macAddress;

        VmIpAddrFetchThread(long vmId, String vmUuid, long nicId, String instanceName, boolean windows, Long hostId, String networkCidr, String macAddress) {
            this.vmId = vmId;
            this.vmUuid = vmUuid;
            this.nicId = nicId;
            this.vmName = instanceName;
            this.isWindows = windows;
            this.hostId = hostId;
            this.networkCidr = networkCidr;
            this.macAddress = macAddress;
        }

        @Override
        protected void runInContext() {
            GetVmIpAddressCommand cmd = new GetVmIpAddressCommand(vmName, networkCidr, isWindows, macAddress);
            boolean decrementCount = true;

            NicVO nic = nicDao.findById(nicId);
            try {
                logger.debug("Trying IP retrieval for Instance [ID: {}, UUID: {}, name: {}], NIC {}", vmId, vmUuid, vmName, nic);
                Answer answer = agentMgr.send(hostId, cmd);
                if (answer.getResult()) {
                    String vmIp = answer.getDetails();
                    if (vmIp == null) {
                        if (nic.getIPv4Address() != null) {
                            nic.setIPv4Address(null);
                            nicDao.update(nicId, nic);
                        }
                    } else if (NetUtils.isValidIp4(vmIp)) {
                        if (nic != null) {
                            nic.setIPv4Address(vmIp);
                            nicDao.update(nicId, nic);
                            logger.debug("Instance [ID: {}, UUID: {}, name: {}] - IP {} retrieved successfully", vmId, vmUuid, vmName, vmIp);
                            vmIdCountMap.remove(nicId);
                            decrementCount = false;
                            ActionEventUtils.onActionEvent(User.UID_SYSTEM, Account.ACCOUNT_ID_SYSTEM,
                                    Domain.ROOT_DOMAIN, EventTypes.EVENT_NETWORK_EXTERNAL_DHCP_VM_IPFETCH,
                                    String.format("Instance [ID: %d, UUID: %s, name: %s], NIC %s, IP address %s got fetched successfully",
                                            vmId, vmUuid, vmName, nic, vmIp), vmId, ApiCommandResourceType.VirtualMachine.toString());
                        }
                    }
                } else {
                    decrementCount = false;
                    if (answer.getDetails() != null) {
                        logger.debug("Failed to get Instance IP for Instance [ID: {}, UUID: {}, name: {}], details: {}",
                                vmId, vmUuid, vmName, answer.getDetails());
                    }
                }
            } catch (OperationTimedoutException e) {
                logger.warn("Timed Out", e);
            } catch (AgentUnavailableException e) {
                logger.warn("Agent Unavailable ", e);
            } finally {
                if (decrementCount) {
                    VmAndCountDetails vmAndCount = vmIdCountMap.get(nicId);
                    vmAndCount.decrementCount();
                    logger.debug("IP is not retrieved for Instance [ID: {}, UUID: {}, name: {}] NIC {} ... decremented count to {}",
                            vmId, vmUuid, vmName, nic, vmAndCount.getRetrievalCount());
                    vmIdCountMap.put(nicId, vmAndCount);
                }
            }
        }
    }

    private class VmIpFetchTask extends ManagedContextRunnable {

        @Override
        protected void runInContext() {
            GlobalLock scanLock = GlobalLock.getInternLock("vmIpFetch");
            try {
                if (scanLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                    try {
                        for (Entry<Long, VmAndCountDetails> entry : vmIdCountMap.entrySet()) {
                            long nicId = entry.getKey();
                            VmAndCountDetails vmIdAndCount = entry.getValue();
                            long vmId = vmIdAndCount.getVmId();

                            if (vmIdAndCount.getRetrievalCount() <= 0) {
                                vmIdCountMap.remove(nicId);
                                logger.debug("Instance {} NIC {} count is zero .. removing Instance NIC from map ", vmId, nicId);

                                ActionEventUtils.onActionEvent(User.UID_SYSTEM, Account.ACCOUNT_ID_SYSTEM,
                                        Domain.ROOT_DOMAIN, EventTypes.EVENT_NETWORK_EXTERNAL_DHCP_VM_IPFETCH,
                                        "Instance " + vmId + " NIC id " + nicId + " IP addr fetch failed ", vmId, ApiCommandResourceType.VirtualMachine.toString());

                                continue;
                            }

                            UserVm userVm = vmDao.findById(vmId);
                            VMInstanceVO vmInstance = vmInstanceDao.findById(vmId);
                            NicVO nicVo = nicDao.findById(nicId);
                            if (userVm == null || vmInstance == null || nicVo == null) {
                                logger.warn("Couldn't fetch ip addr, Vm {} or nic {} doesn't exists", vmId, nicId);
                                continue;
                            }

                            NetworkVO network = networkDao.findById(nicVo.getNetworkId());
                            VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(userVm);
                            VirtualMachine vm = vmProfile.getVirtualMachine();
                            boolean isWindows = guestOSCategoryDao.findById(guestOSDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");

                            vmIpFetchThreadExecutor.execute(new VmIpAddrFetchThread(vmId, vmInstance.getUuid(), nicId, vmInstance.getInstanceName(),
                                    isWindows, vm.getHostId(), network.getCidr(), nicVo.getMacAddress()));
                        }
                    } catch (Exception e) {
                        logger.error("Caught the Exception in VmIpFetchTask", e);
                    } finally {
                        scanLock.unlock();
                    }
                }
            } finally {
                scanLock.releaseRef();
            }
        }
    }
}
