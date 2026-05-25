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

import org.apache.cloudstack.framework.config.ConfigKey;

/**
 * Owns external DHCP VM IP-fetch retry bookkeeping and worker execution.
 */
public interface VmExternalDhcpIpFetchService extends VmIpFetchScheduler {

    ConfigKey<Integer> VmIpFetchWaitInterval = new ConfigKey<>("Advanced", Integer.class, "externaldhcp.vmip.retrieval.interval", "180",
            "Wait Interval (in seconds) for shared network vm dhcp ip addr fetch for next iteration ", true);

    ConfigKey<Integer> VmIpFetchTrialMax = new ConfigKey<>("Advanced", Integer.class, "externaldhcp.vmip.max.retry", "10",
            "The max number of retrieval times for shared network vm dhcp ip fetch, in case of failures", true);

    ConfigKey<Integer> VmIpFetchThreadPoolMax = new ConfigKey<>("Advanced", Integer.class, "externaldhcp.vmipFetch.threadPool.max", "10",
            "number of threads for fetching vms ip address", true);

    ConfigKey<Integer> VmIpFetchTaskWorkers = new ConfigKey<>("Advanced", Integer.class, "externaldhcp.vmipfetchtask.workers", "10",
            "number of worker threads for vm ip fetch task ", true);

    void configure(Map<String, String> configs);

    Runnable getVmIpFetchTask();

    void loadVmDetailsInMapForExternalDhcpIp();

    void stop();
}
