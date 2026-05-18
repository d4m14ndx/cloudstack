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
package com.cloud.network.router;

import static com.cloud.utils.NumbersUtil.toHumanReadableSize;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.NetworkUsageAnswer;
import com.cloud.agent.api.NetworkUsageCommand;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.UserStatisticsVO;
import com.cloud.user.UserStatsLogVO;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.user.dao.UserStatsLogDao;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.net.MacAddress;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.Nic;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;

/**
 * Virtual-router network-stats collector and aggregator — extracted from
 * {@link VirtualNetworkApplianceManagerImpl} (Phase 4 slice 4).
 *
 * @see RouterNetworkStatsService
 */
@Component
public class RouterNetworkStatsServiceImpl implements RouterNetworkStatsService {

    protected Logger logger = LogManager.getLogger(getClass());

    private static final int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION = 5; // seconds

    @Inject
    protected DomainRouterDao routerDao;
    @Inject
    protected UserStatisticsDao userStatsDao;
    @Inject
    protected UserStatsLogDao userStatsLogDao;
    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected NicDao nicDao;
    @Inject
    protected AccountManager accountMgr;
    @Inject
    protected DataCenterDao dcDao;
    @Inject
    protected ManagementServerHostDao msHostDao;
    @Inject
    protected RoutedIpv4Manager routedIpv4Manager;

    protected final long mgmtSrvrId = MacAddress.getMacAddress().toLong();

    private volatile boolean dailyOrHourly = false;

    @Override
    public void setDailyOrHourly(final boolean dailyOrHourly) {
        this.dailyOrHourly = dailyOrHourly;
    }

    @Override
    public <T extends VirtualRouter> void collectNetworkStatistics(final T router, final Nic nic) {
        if (router == null) {
            return;
        }

        final String privateIP = router.getPrivateIpAddress();

        if (privateIP != null) {
            final boolean forVpc = router.getVpcId() != null;
            List<Nic> routerNics = new ArrayList<>();
            if (nic != null) {
                routerNics.add(nic);
            } else {
                routerNics.addAll(nicDao.listByVmId(router.getId()));
            }
            for (final Nic routerNic : routerNics) {
                final Network network = networkModel.getNetwork(routerNic.getNetworkId());
                // Send network usage command for public nic in VPC VR
                // Send network usage command for isolated guest nic of non VPC
                // VR

                //[TODO] Avoiding the NPE now, but I have to find out what is going on with the network. - Wilder Rodrigues
                if (network == null) {
                    logger.error("Could not find a network with ID => " + routerNic.getNetworkId() + ". It might be a problem!");
                    continue;
                }
                if (routedIpv4Manager.isRoutedNetwork(network)) {
                    continue;
                }
                if (forVpc && network.getTrafficType() == TrafficType.Public || !forVpc && network.getTrafficType() == TrafficType.Guest
                        && network.getGuestType() == Network.GuestType.Isolated) {
                    final NetworkUsageCommand usageCmd = new NetworkUsageCommand(privateIP, router.getHostName(), forVpc, routerNic.getIPv4Address());
                    final String routerType = router.getType().toString();
                    final UserStatisticsVO previousStats = userStatsDao.findBy(router.getAccountId(), router.getDataCenterId(), network.getId(),
                            forVpc ? routerNic.getIPv4Address() : null, router.getId(), routerType);
                    NetworkUsageAnswer answer;
                    try {
                        answer = (NetworkUsageAnswer) agentMgr.easySend(router.getHostId(), usageCmd);
                    } catch (final Exception e) {
                        logger.warn("Error while collecting network stats from router: {} from host: {}", router, router.getHostId(), e);
                        continue;
                    }

                    if (answer != null) {
                        if (!answer.getResult()) {
                            logger.warn("Error while collecting network stats from router: {} from host: {}; details: {}", router, router.getHostId(), answer.getDetails());
                            continue;
                        }
                        try {
                            if (answer.getBytesReceived() == 0 && answer.getBytesSent() == 0) {
                                logger.debug("Recieved and Sent bytes are both 0. Not updating user_statistics");
                                continue;
                            }

                            final NetworkUsageAnswer answerFinal = answer;
                            Transaction.execute(new TransactionCallbackNoReturn() {
                                @Override
                                public void doInTransactionWithoutResult(final TransactionStatus status) {
                                    final UserStatisticsVO stats = userStatsDao.lock(router.getAccountId(), router.getDataCenterId(), network.getId(),
                                            forVpc ? routerNic.getIPv4Address() : null, router.getId(), routerType);
                                    if (stats == null) {
                                        logger.warn("unable to find stats for account: {}", () -> accountMgr.getAccount(router.getAccountId()));
                                        return;
                                    }

                                    if (previousStats != null
                                            && (previousStats.getCurrentBytesReceived() != stats.getCurrentBytesReceived() || previousStats.getCurrentBytesSent() != stats
                                            .getCurrentBytesSent())) {
                                        logger.debug("Router stats changed from the time NetworkUsageCommand was sent. " + "Ignoring current answer. Router: "
                                                + answerFinal.getRouterName() + " Rcvd: " + answerFinal.getBytesReceived() + "Sent: " + answerFinal.getBytesSent());
                                        return;
                                    }

                                    if (stats.getCurrentBytesReceived() > answerFinal.getBytesReceived()) {
                                        logger.debug("Received # of bytes that's less than the last one. Assuming something went wrong and persisting it. Router: {} Reported: {} Stored: {}"
                                                    , answerFinal.getRouterName()
                                                    , toHumanReadableSize(answerFinal.getBytesReceived())
                                                    , toHumanReadableSize(stats.getCurrentBytesReceived()));
                                        stats.setNetBytesReceived(stats.getNetBytesReceived() + stats.getCurrentBytesReceived());
                                    }
                                    stats.setCurrentBytesReceived(answerFinal.getBytesReceived());
                                    if (stats.getCurrentBytesSent() > answerFinal.getBytesSent()) {
                                        logger.debug("Received # of bytes that's less than the last one. Assuming something went wrong and persisting it. Router: {} Reported: {} Stored: {}"
                                                , answerFinal.getRouterName()
                                                , toHumanReadableSize(answerFinal.getBytesReceived())
                                                , toHumanReadableSize(stats.getCurrentBytesReceived()));
                                        stats.setNetBytesSent(stats.getNetBytesSent() + stats.getCurrentBytesSent());
                                    }
                                    stats.setCurrentBytesSent(answerFinal.getBytesSent());
                                    if (!dailyOrHourly) {
                                        // update agg bytes
                                        stats.setAggBytesSent(stats.getNetBytesSent() + stats.getCurrentBytesSent());
                                        stats.setAggBytesReceived(stats.getNetBytesReceived() + stats.getCurrentBytesReceived());
                                    }
                                    userStatsDao.update(stats.getId(), stats);
                                }
                            });
                        } catch (final Exception e) {
                            logger.warn("Unable to update user statistics for account: {} Rx: {}; Tx: {}",
                                    accountMgr.getAccount(router.getAccountId()), toHumanReadableSize(answer.getBytesReceived()), toHumanReadableSize(answer.getBytesSent()));
                        }
                    }
                }
            }
        }
    }

    @Override
    public void processStopOrRebootAnswer(final DomainRouterVO router, final Answer ignoredAnswer) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                // FIXME!!! - UserStats command should grab bytesSent/Received
                // for all guest interfaces of the VR
                final List<Long> routerGuestNtwkIds = routerDao.getRouterNetworks(router.getId());
                for (final Long guestNtwkId : routerGuestNtwkIds) {
                    final UserStatisticsVO userStats = userStatsDao.lock(router.getAccountId(), router.getDataCenterId(), guestNtwkId, null, router.getId(), router.getType()
                            .toString());
                    if (userStats != null) {
                        final long currentBytesRcvd = userStats.getCurrentBytesReceived();
                        userStats.setCurrentBytesReceived(0);
                        userStats.setNetBytesReceived(userStats.getNetBytesReceived() + currentBytesRcvd);

                        final long currentBytesSent = userStats.getCurrentBytesSent();
                        userStats.setCurrentBytesSent(0);
                        userStats.setNetBytesSent(userStats.getNetBytesSent() + currentBytesSent);
                        userStatsDao.update(userStats.getId(), userStats);
                        logger.debug("Successfully updated user statistics as a part of domR " + router + " reboot/stop");
                    } else {
                        DataCenterVO zone = dcDao.findById(router.getDataCenterId());
                        Account account = accountMgr.getAccount(router.getAccountId());
                        logger.warn("User stats for router {} were not created for account {} and dc {}", router, account, zone);
                    }
                }
            }
        });
    }

    @Override
    public void runNetworkUsageCollection() {
        try {
            final List<DomainRouterVO> routers = routerDao.listByStateAndNetworkType(VirtualMachine.State.Running, Network.GuestType.Isolated, mgmtSrvrId);
            logger.debug("Found {} running routers. ", routers.size());

            for (final DomainRouterVO router : routers) {
                collectNetworkStatistics(router, null);
            }
        } catch (final Exception e) {
            logger.warn("Error while collecting network stats", e);
        }
    }

    @Override
    public void runNetworkStatsUpdate() {
        final GlobalLock scanLock = GlobalLock.getInternLock("network.stats");
        try {
            if (scanLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                // Check for ownership
                // msHost in UP state with min id should run the job
                final ManagementServerHostVO msHost = msHostDao.findOneInUpState(new Filter(ManagementServerHostVO.class, "id", false, 0L, 1L));
                if (msHost == null || msHost.getMsid() != mgmtSrvrId) {
                    logger.debug("Skipping aggregate network stats update");
                    scanLock.unlock();
                    return;
                }
                try {
                    Transaction.execute(new TransactionCallbackNoReturn() {
                        @Override
                        public void doInTransactionWithoutResult(final TransactionStatus status) {
                            // get all stats with delta > 0
                            final List<UserStatisticsVO> updatedStats = userStatsDao.listUpdatedStats();
                            final Date updatedTime = new Date();
                            for (final UserStatisticsVO stat : updatedStats) {
                                // update agg bytes
                                stat.setAggBytesReceived(stat.getCurrentBytesReceived() + stat.getNetBytesReceived());
                                stat.setAggBytesSent(stat.getCurrentBytesSent() + stat.getNetBytesSent());
                                userStatsDao.update(stat.getId(), stat);
                                // insert into op_user_stats_log
                                final UserStatsLogVO statsLog = new UserStatsLogVO(stat.getId(), stat.getNetBytesReceived(), stat.getNetBytesSent(), stat
                                        .getCurrentBytesReceived(), stat.getCurrentBytesSent(), stat.getAggBytesReceived(), stat.getAggBytesSent(), updatedTime);
                                userStatsLogDao.persist(statsLog);
                            }
                            logger.debug("Successfully updated aggregate network stats");
                        }
                    });
                } catch (final Exception e) {
                    logger.debug("Failed to update aggregate network stats", e);
                } finally {
                    scanLock.unlock();
                }
            }
        } catch (final Exception e) {
            logger.debug("Exception while trying to acquire network stats lock", e);
        } finally {
            scanLock.releaseRef();
        }
    }
}
