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
package com.cloud.configuration;

import java.util.List;

import org.apache.cloudstack.api.command.admin.network.CreateManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.UpdatePodManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.pod.DeletePodCmd;

import com.cloud.dc.DataCenter;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Pod;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ResourceUnavailableException;

/**
 * Pod CRUD and management-network IP-range operations — create / delete pod,
 * and create / delete / update the per-pod management IP ranges that live in
 * {@link HostPodVO#getDescription()} as a comma-separated
 * {@code <startIp>-<endIp>-<forSystemVms>-<vlan>} list.
 *
 * <p>Extracted from {@link ConfigurationManagerImpl} as part of the Phase 4
 * Spring-component decomposition. {@code ConfigurationManagerImpl} keeps
 * thin delegating wrappers so the {@code ConfigurationService} /
 * {@link ConfigurationManager} interface contracts and existing test spies
 * continue to work. Shared CIDR / IP-range / pod-state validators
 * ({@code getCidrAddress}, {@code checkIpRange}, {@code checkPodCidrSubnets},
 * {@code checkOverlapPublicIpRange}, {@code validPod},
 * {@code podHasAllocatedPrivateIPs}, {@code checkIfPodIsDeletable},
 * {@code getVlanNumberFromUri}) are also used by zone / edit-pod paths that
 * remain on the manager; PodServiceImpl keeps a local copy so the slice has
 * no back-reference into the manager.
 */
public interface PodService {

    /**
     * Create a pod in the given zone. For an Edge zone, IP-range parameters
     * must be {@code null}; for any other zone type, the start IP / end IP /
     * gateway / netmask quadruple must be a valid IPv4 range inside a valid
     * CIDR. Defaults {@code allocationState} to {@code Enabled} when
     * {@code null}.
     */
    Pod createPod(long zoneId, String name, String startIp, String endIp,
                  String gateway, String netmask, String allocationState,
                  List<String> storageAccessGroups);

    /**
     * Inner create-pod overload used by zone bring-up and other internal
     * flows: validates pod attributes, persists the pod row, registers the
     * private IP range (when present) and the link-local IP range, and
     * publishes {@link ConfigurationManager#MESSAGE_CREATE_POD_IP_RANGE_EVENT}.
     */
    HostPodVO createPod(long userId, String podName, DataCenter zone, String gateway,
                        String cidr, String startIp, String endIp, String allocationStateStr,
                        boolean skipGatewayOverlapCheck, List<String> storageAccessGroups);

    /**
     * Delete a pod after verifying it has no allocated private IPs, hosts,
     * volumes, VMs or clusters. Cleans up private and link-local IP rows,
     * pod VLANs, capacity records, dedication records and entity comments,
     * then publishes
     * {@link ConfigurationManager#MESSAGE_DELETE_POD_IP_RANGE_EVENT}.
     */
    boolean deletePod(DeletePodCmd cmd);

    /**
     * Append a new management IP range to a pod's description, after
     * validating the range parameters, the gateway / netmask match against
     * the pod, that the range does not overlap public IPs or any existing
     * pod range, and that the caller is root admin. Publishes
     * {@link ConfigurationManager#MESSAGE_CREATE_POD_IP_RANGE_EVENT}.
     */
    Pod createPodIpRange(CreateManagementNetworkIpRangeCmd cmd);

    /**
     * Remove a management IP range from a pod's description, after
     * verifying no IP inside the range is currently allocated. Releases
     * the private-IP rows for the deleted range and publishes
     * {@link ConfigurationManager#MESSAGE_DELETE_POD_IP_RANGE_EVENT}.
     */
    void deletePodIpRange(DeleteManagementNetworkIpRangeCmd cmd)
            throws ResourceUnavailableException, ConcurrentOperationException;

    /**
     * Resize / shift an existing management IP range, after verifying the
     * new range still contains every currently-allocated IP and does not
     * overlap any sibling pod range. Adds rows for IPs that are new, and
     * deletes rows for IPs that have dropped out of the range.
     */
    void updatePodIpRange(UpdatePodManagementNetworkIpRangeCmd cmd) throws ConcurrentOperationException;
}
