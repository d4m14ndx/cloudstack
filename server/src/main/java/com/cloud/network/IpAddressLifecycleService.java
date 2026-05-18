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
package com.cloud.network;

import org.apache.cloudstack.api.command.admin.address.ReleasePodIpCmdByAdmin;
import org.apache.cloudstack.api.response.AcquirePodIpCmdResponse;

import com.cloud.dc.DataCenter;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Encapsulates allocation, reservation, association, update and release of
 * public (and pod) IP addresses. Extracted from {@link NetworkServiceImpl}
 * as part of the Phase 4 god-class decomposition (slice 6).
 */
public interface IpAddressLifecycleService {

    /**
     * Allocates a public IP address from a zone for a given account.
     */
    IpAddress allocateIP(Account ipOwner, long zoneId, Long networkId, Boolean displayIp, String ipaddress)
            throws ResourceAllocationException, InsufficientAddressCapacityException, ConcurrentOperationException;

    /**
     * Allocates a portable IP address for the given account/region.
     */
    IpAddress allocatePortableIP(Account ipOwner, int regionId, Long zoneId, Long networkId, Long vpcId)
            throws ResourceAllocationException, InsufficientAddressCapacityException, ConcurrentOperationException;

    /**
     * Releases a previously allocated portable IP address.
     */
    boolean releasePortableIpAddress(long ipAddressId);

    /**
     * Reserves an allocated IP address for the given account.
     */
    IpAddress reserveIpAddress(Account account, Boolean displayIp, Long ipAddressId) throws ResourceAllocationException;

    /**
     * Reserves an IP address from the VLAN range identified by the given detail key.
     */
    IpAddress reserveIpAddressWithVlanDetail(Account account, DataCenter zone, Boolean displayIp, String vlanDetailKey)
            throws ResourceAllocationException;

    /**
     * Releases a reserved (not allocated/in-use) IP address back to the free pool.
     */
    boolean releaseReservedIpAddress(long ipAddressId) throws InsufficientAddressCapacityException;

    /**
     * Disassociates (releases) an allocated public IP address.
     */
    boolean releaseIpAddress(long ipAddressId) throws InsufficientAddressCapacityException;

    /**
     * Associates a previously allocated public IP to a guest network.
     */
    IpAddress associateIPToNetwork(long ipId, long networkId)
            throws InsufficientAddressCapacityException, ResourceAllocationException,
            ResourceUnavailableException, ConcurrentOperationException;

    /**
     * Returns the IP address object identified by its database id.
     */
    IpAddress getIp(long id);

    /**
     * Returns the IP address object identified by its dotted-decimal string.
     */
    IpAddress getIp(String ipAddress);

    /**
     * Updates metadata (customId / display flag) of a public IP address.
     */
    IpAddress updateIP(Long id, String customId, Boolean displayIp);

    /**
     * Allocates a pod-scoped IP address for the given account/zone/pod.
     */
    AcquirePodIpCmdResponse allocatePodIp(Account ipOwner, String zoneId, String podId)
            throws ResourceAllocationException;

    /**
     * Releases a pod-scoped IP address.
     */
    boolean releasePodIp(ReleasePodIpCmdByAdmin ip) throws CloudRuntimeException;
}
