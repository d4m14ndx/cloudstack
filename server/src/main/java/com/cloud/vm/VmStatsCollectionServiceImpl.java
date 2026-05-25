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

import static com.cloud.utils.NumbersUtil.toHumanReadableSize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.GetVmDiskStatsAnswer;
import com.cloud.agent.api.GetVmDiskStatsCommand;
import com.cloud.agent.api.GetVmNetworkStatsAnswer;
import com.cloud.agent.api.GetVmNetworkStatsCommand;
import com.cloud.agent.api.VmDiskStatsEntry;
import com.cloud.agent.api.VmNetworkStatsEntry;
import com.cloud.dc.VlanVO;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.dao.VlanDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.UserStatisticsVO;
import com.cloud.user.VmDiskStatisticsVO;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.user.dao.VmDiskStatisticsDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.NumbersUtil;
import com.cloud.vm.dao.NicDao;

import jakarta.annotation.PostConstruct;

/**
 * Spring-component implementation of {@link VmStatsCollectionService}.
 * Extracted from {@link UserVmManagerImpl} as slice 19 of the Phase 4
 * decomposition.
 *
 * <p>Behaviour is preserved verbatim: same hypervisor gating, same
 * single-NIC / single-volume reconciliation logic per stat entry, same
 * best-effort error handling, same warn-log format strings. The
 * aggregation-range flag previously held as {@code _dailyOrHourly} is
 * resolved once at startup from the {@code usage.stats.job.aggregation.range}
 * config so the bean is self-contained.
 */
@Component
public class VmStatsCollectionServiceImpl implements VmStatsCollectionService {

    private static final Logger logger = LoggerFactory.getLogger(VmStatsCollectionServiceImpl.class);

    private static final int HOURLY_TIME_MINUTES = 60;
    private static final int DAILY_TIME_MINUTES = 60 * 24;
    private static final String USAGE_AGGREGATION_RANGE_KEY = "usage.stats.job.aggregation.range";

    @Inject
    private HostDao hostDao;
    @Inject
    private VolumeDao volsDao;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private AccountManager accountMgr;
    @Inject
    private NicDao nicDao;
    @Inject
    private VmDiskStatisticsDao vmDiskStatsDao;
    @Inject
    private UserStatisticsDao userStatsDao;
    @Inject
    private VlanDao vlanDao;
    @Inject
    private ConfigurationDao configDao;

    /**
     * True when the usage aggregation range is "daily" or "hourly" — when
     * true, agg counters are rolled up out-of-band by the usage job and
     * this code skips its own agg update.
     */
    protected boolean dailyOrHourly = false;

    @PostConstruct
    protected void init() {
        if (configDao == null) {
            return;
        }
        String aggregationRange = configDao.getValue(USAGE_AGGREGATION_RANGE_KEY);
        int range = NumbersUtil.parseInt(aggregationRange, 1440);
        dailyOrHourly = (range == DAILY_TIME_MINUTES) || (range == HOURLY_TIME_MINUTES);
    }

    @Override
    public void collectVmNetworkStatistics(final UserVm userVm) {
        if (!userVm.getHypervisorType().equals(HypervisorType.KVM)) {
            return;
        }
        logger.debug("Collect vm network statistics from host before stopping Vm");
        long hostId = userVm.getHostId();
        List<String> vmNames = new ArrayList<>();
        vmNames.add(userVm.getInstanceName());
        final HostVO host = hostDao.findById(hostId);
        final Account account = accountMgr.getAccount(userVm.getAccountId());

        GetVmNetworkStatsAnswer networkStatsAnswer = null;
        try {
            networkStatsAnswer = (GetVmNetworkStatsAnswer) agentMgr.easySend(hostId, new GetVmNetworkStatsCommand(vmNames, host.getGuid(), host.getName()));
        } catch (Exception e) {
            logger.warn("Error while collecting network stats for vm: {} from host: {}", userVm, host, e);
            return;
        }
        if (networkStatsAnswer == null) {
            return;
        }
        if (!networkStatsAnswer.getResult()) {
            logger.warn("Error while collecting network stats vm: {} from host: {}; details: {}", userVm, host, networkStatsAnswer.getDetails());
            return;
        }
        try {
            final GetVmNetworkStatsAnswer networkStatsAnswerFinal = networkStatsAnswer;
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    updateNetworkStatsInTransaction(userVm, host, account, networkStatsAnswerFinal);
                }
            });
        } catch (Exception e) {
            logger.warn("Unable to update vm network statistics for vm: {} from host: {}", userVm, host, e);
        }
    }

    /**
     * Per-NIC reconciliation of a successful {@link GetVmNetworkStatsAnswer}.
     * Package-private so it can be exercised directly from tests without
     * setting up the agent-manager round trip.
     */
    protected void updateNetworkStatsInTransaction(UserVm userVm, HostVO host, Account account, GetVmNetworkStatsAnswer answer) {
        HashMap<String, List<VmNetworkStatsEntry>> vmNetworkStatsByName = answer.getVmNetworkStatsMap();
        if (vmNetworkStatsByName == null) {
            return;
        }
        List<VmNetworkStatsEntry> vmNetworkStats = vmNetworkStatsByName.get(userVm.getInstanceName());
        if (vmNetworkStats == null) {
            return;
        }

        for (VmNetworkStatsEntry vmNetworkStat : vmNetworkStats) {
            SearchCriteria<NicVO> sc_nic = nicDao.createSearchCriteria();
            sc_nic.addAnd("macAddress", SearchCriteria.Op.EQ, vmNetworkStat.getMacAddress());
            NicVO nic = nicDao.search(sc_nic, null).get(0);
            List<VlanVO> vlan = vlanDao.listVlansByNetworkId(nic.getNetworkId());
            if (vlan == null || vlan.size() == 0 || vlan.get(0).getVlanType() != VlanType.DirectAttached) {
                break; // only get network statistics for DirectAttached network (shared networks in Basic zone and Advanced zone with/without SG)
            }
            UserStatisticsVO previousvmNetworkStats = userStatsDao.findBy(userVm.getAccountId(), userVm.getDataCenterId(), nic.getNetworkId(), nic.getIPv4Address(), userVm.getId(), "UserVm");
            if (previousvmNetworkStats == null) {
                previousvmNetworkStats = new UserStatisticsVO(userVm.getAccountId(), userVm.getDataCenterId(), nic.getIPv4Address(), userVm.getId(), "UserVm", nic.getNetworkId());
                userStatsDao.persist(previousvmNetworkStats);
            }
            UserStatisticsVO vmNetworkStat_lock = userStatsDao.lock(userVm.getAccountId(), userVm.getDataCenterId(), nic.getNetworkId(), nic.getIPv4Address(), userVm.getId(), "UserVm");

            if ((vmNetworkStat.getBytesSent() == 0) && (vmNetworkStat.getBytesReceived() == 0)) {
                logger.debug("bytes sent and received are all 0. Not updating user_statistics");
                continue;
            }

            if (vmNetworkStat_lock == null) {
                logger.warn("unable to find vm network stats from host for account: {} with vm: {} and nic: {}", account, userVm, nic);
                continue;
            }

            if (previousvmNetworkStats != null
                    && ((previousvmNetworkStats.getCurrentBytesSent() != vmNetworkStat_lock.getCurrentBytesSent())
                            || (previousvmNetworkStats.getCurrentBytesReceived() != vmNetworkStat_lock.getCurrentBytesReceived()))) {
                logger.debug("vm network stats changed from the time GetNmNetworkStatsCommand was sent. " +
                        "Ignoring current answer. Host: " + host + " . VM: " + vmNetworkStat.getVmName() +
                        " Sent(Bytes): " + toHumanReadableSize(vmNetworkStat.getBytesSent()) + " Received(Bytes): " + toHumanReadableSize(vmNetworkStat.getBytesReceived()));
                continue;
            }

            if (vmNetworkStat_lock.getCurrentBytesSent() > vmNetworkStat.getBytesSent()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Sent # of bytes that's less than the last one.  Assuming something went wrong and persisting it. Host: {} . VM: {} Reported: {} Stored: {}",
                            host, vmNetworkStat.getVmName(), toHumanReadableSize(vmNetworkStat.getBytesSent()), toHumanReadableSize(vmNetworkStat_lock.getCurrentBytesSent()));
                }
                vmNetworkStat_lock.setNetBytesSent(vmNetworkStat_lock.getNetBytesSent() + vmNetworkStat_lock.getCurrentBytesSent());
            }
            vmNetworkStat_lock.setCurrentBytesSent(vmNetworkStat.getBytesSent());

            if (vmNetworkStat_lock.getCurrentBytesReceived() > vmNetworkStat.getBytesReceived()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Received # of bytes that's less than the last one.  Assuming something went wrong and persisting it. Host: {} . VM: {} Reported: {} Stored: {}",
                            host, vmNetworkStat.getVmName(), toHumanReadableSize(vmNetworkStat.getBytesReceived()), toHumanReadableSize(vmNetworkStat_lock.getCurrentBytesReceived()));
                }
                vmNetworkStat_lock.setNetBytesReceived(vmNetworkStat_lock.getNetBytesReceived() + vmNetworkStat_lock.getCurrentBytesReceived());
            }
            vmNetworkStat_lock.setCurrentBytesReceived(vmNetworkStat.getBytesReceived());

            if (!dailyOrHourly) {
                //update agg bytes
                vmNetworkStat_lock.setAggBytesReceived(vmNetworkStat_lock.getNetBytesReceived() + vmNetworkStat_lock.getCurrentBytesReceived());
                vmNetworkStat_lock.setAggBytesSent(vmNetworkStat_lock.getNetBytesSent() + vmNetworkStat_lock.getCurrentBytesSent());
            }

            userStatsDao.update(vmNetworkStat_lock.getId(), vmNetworkStat_lock);
        }
    }

    @Override
    public void collectVmDiskStatistics(final UserVm userVm) {
        // Only supported for KVM and VMware
        if (!(userVm.getHypervisorType().equals(HypervisorType.KVM) || userVm.getHypervisorType().equals(HypervisorType.VMware))) {
            return;
        }
        logger.debug("Collect vm disk statistics from host before stopping VM");
        if (userVm.getHostId() == null) {
            logger.error("Unable to collect vm disk statistics for VM as the host is null, skipping VM disk statistics collection");
            return;
        }
        long hostId = userVm.getHostId();
        List<String> vmNames = new ArrayList<>();
        vmNames.add(userVm.getInstanceName());
        final HostVO host = hostDao.findById(hostId);
        final Account account = accountMgr.getAccount(userVm.getAccountId());

        GetVmDiskStatsAnswer diskStatsAnswer = null;
        try {
            diskStatsAnswer = (GetVmDiskStatsAnswer) agentMgr.easySend(hostId, new GetVmDiskStatsCommand(vmNames, host.getGuid(), host.getName()));
        } catch (Exception e) {
            logger.warn("Error while collecting disk stats for vm: {} from host: {}", userVm, host, e);
            return;
        }
        if (diskStatsAnswer == null) {
            return;
        }
        if (!diskStatsAnswer.getResult()) {
            logger.warn("Error while collecting disk stats vm: {} from host: {}; details: {}", userVm, host, diskStatsAnswer.getDetails());
            return;
        }
        try {
            final GetVmDiskStatsAnswer diskStatsAnswerFinal = diskStatsAnswer;
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    updateDiskStatsInTransaction(userVm, host, account, diskStatsAnswerFinal);
                }
            });
        } catch (Exception e) {
            logger.warn("Unable to update VM disk statistics for {} from {}", userVm, host, e);
        }
    }

    /**
     * Per-volume reconciliation of a successful {@link GetVmDiskStatsAnswer}.
     * Package-private so it can be exercised directly from tests.
     */
    protected void updateDiskStatsInTransaction(UserVm userVm, HostVO host, Account account, GetVmDiskStatsAnswer answer) {
        HashMap<String, List<VmDiskStatsEntry>> vmDiskStatsByName = answer.getVmDiskStatsMap();
        if (vmDiskStatsByName == null) {
            return;
        }
        List<VmDiskStatsEntry> vmDiskStats = vmDiskStatsByName.get(userVm.getInstanceName());
        if (vmDiskStats == null) {
            return;
        }

        for (VmDiskStatsEntry vmDiskStat : vmDiskStats) {
            SearchCriteria<VolumeVO> sc_volume = volsDao.createSearchCriteria();
            sc_volume.addAnd("path", SearchCriteria.Op.LIKE, vmDiskStat.getPath() + "%");
            List<VolumeVO> volumes = volsDao.search(sc_volume, null);
            if ((volumes == null) || (volumes.size() == 0)) {
                break;
            }
            VolumeVO volume = volumes.get(0);
            VmDiskStatisticsVO previousVmDiskStats = vmDiskStatsDao.findBy(userVm.getAccountId(), userVm.getDataCenterId(), userVm.getId(), volume.getId());
            VmDiskStatisticsVO vmDiskStat_lock = vmDiskStatsDao.lock(userVm.getAccountId(), userVm.getDataCenterId(), userVm.getId(), volume.getId());

            if ((vmDiskStat.getIORead() == 0) && (vmDiskStat.getIOWrite() == 0) && (vmDiskStat.getBytesRead() == 0) && (vmDiskStat.getBytesWrite() == 0)) {
                logger.debug("Read/Write of IO and Bytes are both 0. Not updating vm_disk_statistics");
                continue;
            }

            if (vmDiskStat_lock == null) {
                logger.warn("unable to find vm disk stats from host for account: {} with vm: {} and volume: {}", account, userVm, volume);
                continue;
            }

            if (previousVmDiskStats != null
                    && ((previousVmDiskStats.getCurrentIORead() != vmDiskStat_lock.getCurrentIORead()) || ((previousVmDiskStats.getCurrentIOWrite() != vmDiskStat_lock
                    .getCurrentIOWrite())
                            || (previousVmDiskStats.getCurrentBytesRead() != vmDiskStat_lock.getCurrentBytesRead()) || (previousVmDiskStats
                                    .getCurrentBytesWrite() != vmDiskStat_lock.getCurrentBytesWrite())))) {
                logger.debug("vm disk stats changed from the time" +
                        " GetVmDiskStatsCommand was sent. Ignoring current " +
                        "answer. Host: {} . VM: {} IO Read: {} IO Write: {} " +
                        "Bytes Read: {} Bytes Write: {}",
                        host, vmDiskStat, vmDiskStat.getIORead(), vmDiskStat.getIOWrite(),
                        vmDiskStat.getBytesRead(), vmDiskStat.getBytesWrite());
                continue;
            }

            if (vmDiskStat_lock.getCurrentIORead() > vmDiskStat.getIORead()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Read # of IO that's less than " +
                            "the last one.  Assuming something went wrong and " +
                            "persisting it. Host: {} . VM: {} Reported: {} Stored: {}",
                            host, vmDiskStat, vmDiskStat.getIORead(), vmDiskStat_lock.getCurrentIORead());
                }
                vmDiskStat_lock.setNetIORead(vmDiskStat_lock.getNetIORead() + vmDiskStat_lock.getCurrentIORead());
            }
            vmDiskStat_lock.setCurrentIORead(vmDiskStat.getIORead());
            if (vmDiskStat_lock.getCurrentIOWrite() > vmDiskStat.getIOWrite()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Write # of IO that's less than " +
                            "the last one. Assuming something went wrong and " +
                            "persisting it. Host: {}. VM: {} Reported: {} Stored: {}",
                            host, vmDiskStat, vmDiskStat.getIOWrite(), vmDiskStat_lock.getCurrentIOWrite());
                }
                vmDiskStat_lock.setNetIOWrite(vmDiskStat_lock.getNetIOWrite() + vmDiskStat_lock.getCurrentIOWrite());
            }
            vmDiskStat_lock.setCurrentIOWrite(vmDiskStat.getIOWrite());
            if (vmDiskStat_lock.getCurrentBytesRead() > vmDiskStat.getBytesRead()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Read # of Bytes that's less " +
                            "than the last one. Assuming something went wrong and" +
                            " persisting it. Host: {} . VM: {} Reported: {} Stored: {}",
                            host, vmDiskStat, toHumanReadableSize(vmDiskStat.getBytesRead()),
                            toHumanReadableSize(vmDiskStat_lock.getCurrentBytesRead()));
                }
                vmDiskStat_lock.setNetBytesRead(vmDiskStat_lock.getNetBytesRead() + vmDiskStat_lock.getCurrentBytesRead());
            }
            vmDiskStat_lock.setCurrentBytesRead(vmDiskStat.getBytesRead());
            if (vmDiskStat_lock.getCurrentBytesWrite() > vmDiskStat.getBytesWrite()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Write # of Bytes that's less " +
                            "than the last one.  Assuming something went wrong " +
                            "and persisting it. Host: {} . VM: {} Reported: {} Stored: {}",
                            host, vmDiskStat, toHumanReadableSize(vmDiskStat.getBytesWrite()),
                            toHumanReadableSize(vmDiskStat_lock.getCurrentBytesWrite()));
                }
                vmDiskStat_lock.setNetBytesWrite(vmDiskStat_lock.getNetBytesWrite() + vmDiskStat_lock.getCurrentBytesWrite());
            }
            vmDiskStat_lock.setCurrentBytesWrite(vmDiskStat.getBytesWrite());

            if (!dailyOrHourly) {
                //update agg bytes
                vmDiskStat_lock.setAggIORead(vmDiskStat_lock.getNetIORead() + vmDiskStat_lock.getCurrentIORead());
                vmDiskStat_lock.setAggIOWrite(vmDiskStat_lock.getNetIOWrite() + vmDiskStat_lock.getCurrentIOWrite());
                vmDiskStat_lock.setAggBytesRead(vmDiskStat_lock.getNetBytesRead() + vmDiskStat_lock.getCurrentBytesRead());
                vmDiskStat_lock.setAggBytesWrite(vmDiskStat_lock.getNetBytesWrite() + vmDiskStat_lock.getCurrentBytesWrite());
            }

            vmDiskStatsDao.update(vmDiskStat_lock.getId(), vmDiskStat_lock);
        }
    }
}
