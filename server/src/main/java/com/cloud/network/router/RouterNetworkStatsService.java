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

import com.cloud.agent.api.Answer;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.Nic;

/**
 * Helpers for collecting, persisting, and aggregating per-interface network
 * usage statistics for virtual-router VMs.
 *
 * <p>This service is the home for three related concerns previously interleaved
 * inside {@link VirtualNetworkApplianceManagerImpl}:
 *
 * <ul>
 *   <li>{@link #collectNetworkStatistics(com.cloud.network.router.VirtualRouter, Nic)}
 *       — send a {@link com.cloud.agent.api.NetworkUsageCommand} to a router
 *       (per NIC or for all NICs) and persist the returned byte counters into
 *       {@code user_statistics}.</li>
 *   <li>{@link #processStopOrRebootAnswer(DomainRouterVO, Answer)} — flush the
 *       running byte counters into the {@code netBytes*} columns when a
 *       router is stopped or rebooted so the next sample starts from
 *       zero.</li>
 *   <li>The two scheduled runnables: a per-management-server collection task
 *       (every {@code router.stats.interval} seconds) and an aggregation task
 *       that promotes deltas into {@code op_user_stats_log} under a global
 *       lock (aligned with the usage-stats aggregation range).</li>
 * </ul>
 *
 * <p>Extracted from {@link VirtualNetworkApplianceManagerImpl} as part of the
 * Phase 4 Spring-component decomposition (slice 4). The god class keeps thin
 * delegating wrappers for the public methods so existing callers — including
 * the {@link com.cloud.network.VirtualNetworkApplianceService} interface
 * contract and the external
 * {@link com.cloud.network.rules.NicPlugInOutRules} caller — continue to work
 * unchanged.
 *
 * <p>The {@code dailyOrHourly} flag is set by the parent manager during
 * {@code start()} once it has parsed the
 * {@code usage.stats.job.aggregation.range} configuration; it controls whether
 * {@code collectNetworkStatistics} also updates the aggregate-bytes columns on
 * each sample (turned off when the aggregation task is responsible for the
 * roll-up).
 */
public interface RouterNetworkStatsService {

    /**
     * Collect and persist network-usage statistics for one router.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>Returns immediately if {@code router} is {@code null} or has no
     *       private IP.</li>
     *   <li>When {@code nic} is {@code null}, samples every NIC attached to
     *       the router; otherwise samples only the supplied NIC.</li>
     *   <li>Skips NICs that belong to a routed (BGP-managed) network.</li>
     *   <li>Sends a {@link com.cloud.agent.api.NetworkUsageCommand} per
     *       eligible NIC. For a VPC router the eligible NICs are
     *       {@code Public}; for a non-VPC router they are
     *       {@code Guest/Isolated}.</li>
     *   <li>Persists the returned byte counters into the matching
     *       {@link com.cloud.user.UserStatisticsVO} row inside a single
     *       transaction, guarding against stale answers and detecting counter
     *       resets on the router side.</li>
     *   <li>If {@code dailyOrHourly} is {@code false}, also refreshes the
     *       {@code aggBytes*} columns on the same row.</li>
     * </ul>
     *
     * <p>This method swallows per-NIC errors so a single unreachable router
     * does not abort the rest of the sweep.
     *
     * @param router the router to query — typed broadly so test doubles can
     *               supply any {@link VirtualRouter}; null is tolerated
     * @param nic    a specific NIC to sample, or {@code null} to sample all
     * @param <T>    inferred router type
     */
    <T extends VirtualRouter> void collectNetworkStatistics(T router, Nic nic);

    /**
     * Flush the running byte counters for a router into its persistent totals
     * when the router stops or reboots, so subsequent samples do not double
     * count.
     *
     * <p>For every guest network attached to the router this method:
     * <ul>
     *   <li>Locks the matching {@code user_statistics} row.</li>
     *   <li>Adds the current {@code currentBytesReceived} /
     *       {@code currentBytesSent} into {@code netBytesReceived} /
     *       {@code netBytesSent}.</li>
     *   <li>Resets the {@code currentBytes*} columns to zero.</li>
     *   <li>Persists the update.</li>
     * </ul>
     *
     * <p>The whole sequence runs inside one transaction. The {@code answer}
     * parameter is intentionally ignored — the caller already inspected it to
     * decide whether the router really stopped; this method only needs to
     * know that the counters on the router side are about to disappear.
     *
     * @param router the router that just stopped or rebooted
     * @param ignoredAnswer the agent answer (not consulted — see above)
     */
    void processStopOrRebootAnswer(DomainRouterVO router, Answer ignoredAnswer);

    /**
     * Run one network-usage collection sweep across every running isolated
     * router owned by this management server. Invoked from the parent
     * manager's {@code NetworkUsageTask} scheduled runnable.
     */
    void runNetworkUsageCollection();

    /**
     * Run one aggregation pass that promotes per-interface byte deltas into
     * the {@code op_user_stats_log} table under the {@code network.stats}
     * global lock. Invoked from the parent manager's
     * {@code NetworkStatsUpdateTask} scheduled runnable.
     */
    void runNetworkStatsUpdate();

    /**
     * Toggle whether {@link #collectNetworkStatistics} also refreshes the
     * aggregate-bytes columns on each sample.
     *
     * <p>Set by the parent manager during {@code start()} once it has parsed
     * the {@code usage.stats.job.aggregation.range} configuration. When
     * {@code true}, the dedicated {@link #runNetworkStatsUpdate} aggregation
     * task is responsible for the roll-up and per-sample updates skip it; when
     * {@code false}, every sample refreshes the aggregate.
     */
    void setDailyOrHourly(boolean dailyOrHourly);
}
