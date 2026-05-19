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
package org.apache.cloudstack.engine.orchestration;

import java.net.URI;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DataCenterVnetVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterVnetDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.dao.AccountGuestVlanMapDao;
import com.cloud.network.dao.AccountGuestVlanMapVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.UuidUtils;

@Component
public class NetworkOfferingVlanValidationServiceImpl implements NetworkOfferingVlanValidationService {

    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected NetworkDao networksDao;
    @Inject
    protected DataCenterVnetDao dataCenterVnetDao;
    @Inject
    protected AccountGuestVlanMapDao accountGuestVlanMapDao;
    @Inject
    protected NetworkOfferingDao networkOfferingDao;
    @Inject
    protected NetworkModel networkModel;

    @Override
    public boolean isSharedNetworkWithoutSpecifyVlan(NetworkOffering offering) {
        if (offering == null || offering.getTrafficType() != TrafficType.Guest || offering.getGuestType() != GuestType.Shared) {
            return false;
        }
        return !offering.isSpecifyVlan();
    }

    @Override
    public boolean isPrivateGatewayWithoutSpecifyVlan(NetworkOffering offering) {
        return offering.getId() == networkOfferingDao.findByUniqueName(NetworkOffering.SystemPrivateGatewayNetworkOfferingWithoutVlan).getId();
    }

    @Override
    public URI encodeVlanIdIntoBroadcastUri(String vlanId, PhysicalNetwork physicalNetwork) {
        if (physicalNetwork == null) {
            throw new InvalidParameterValueException(String.format("Failed to encode VLAN/VXLAN %s into a Broadcast URI. Physical Network cannot be null.", vlanId));
        }

        if (!physicalNetwork.getIsolationMethods().isEmpty() && StringUtils.isNotBlank(physicalNetwork.getIsolationMethods().get(0))) {
            String isolationMethod = physicalNetwork.getIsolationMethods().get(0).toLowerCase();
            String vxlan = BroadcastDomainType.Vxlan.toString().toLowerCase();
            if (isolationMethod.equals(vxlan)) {
                if (StringUtils.isNotBlank(vlanId) && UuidUtils.isUuid(vlanId)) {
                    return BroadcastDomainType.Vxlan.toUri(vlanId);
                }
                return BroadcastDomainType.encodeStringIntoBroadcastUri(vlanId, BroadcastDomainType.Vxlan);
            }
        }
        if (StringUtils.isNotBlank(vlanId) && UuidUtils.isUuid(vlanId)) {
            return BroadcastDomainType.Vlan.toUri(vlanId);
        }
        return BroadcastDomainType.fromString(vlanId);
    }

    @Override
    public void validateGuestNetworkOfferingVlan(String vlanId, String isolatedPvlan, boolean bypassVlanOverlapCheck,
            NetworkOfferingVO offering, PhysicalNetwork physicalNetwork, DataCenterVO zone, long zoneId, Account owner, boolean isPrivateNetwork) {
        final boolean vlanSpecified = vlanId != null;
        if (vlanSpecified != offering.isSpecifyVlan()) {
            if (vlanSpecified) {
                if (!isSharedNetworkWithoutSpecifyVlan(offering) && !isPrivateGatewayWithoutSpecifyVlan(offering)) {
                    throw new InvalidParameterValueException("Can't specify vlan; corresponding offering says specifyVlan=false");
                }
            } else {
                throw new InvalidParameterValueException("Vlan has to be specified; corresponding offering says specifyVlan=true");
            }
        }

        if (!vlanSpecified) {
            return;
        }

        URI uri = encodeVlanIdIntoBroadcastUri(vlanId, physicalNetwork);
        URI secondaryUri = StringUtils.isNotBlank(isolatedPvlan) ? BroadcastDomainType.fromString(isolatedPvlan) : null;
        if (isSharedNetworkWithoutSpecifyVlan(offering) || isPrivateGatewayWithoutSpecifyVlan(offering)) {
            bypassVlanOverlapCheck = true;
        }
        if (!(bypassVlanOverlapCheck && (offering.getGuestType() == GuestType.Shared || isPrivateNetwork))
                && dataCenterDao.findVnet(zoneId, physicalNetwork.getId(), BroadcastDomainType.getValue(uri)).size() > 0) {
            throw new InvalidParameterValueException("The VLAN tag to use for new guest network, " + vlanId
                    + " is already being used for dynamic vlan allocation for the guest network in zone " + zone.getName());
        }
        if (secondaryUri != null && !(bypassVlanOverlapCheck && offering.getGuestType() == GuestType.Shared) &&
                dataCenterDao.findVnet(zoneId, physicalNetwork.getId(), BroadcastDomainType.getValue(secondaryUri)).size() > 0) {
            throw new InvalidParameterValueException(String.format(
                    "The VLAN tag for isolated PVLAN %s is already being used for dynamic vlan allocation for the guest network in zone %s",
                    isolatedPvlan, zone));
        }
        if (!UuidUtils.isUuid(vlanId)) {
            validateVlanOverlapAndDedicatedRanges(vlanId, isolatedPvlan, bypassVlanOverlapCheck, offering, zone, zoneId, owner, isPrivateNetwork, uri, secondaryUri);
        }
    }

    protected void validateVlanOverlapAndDedicatedRanges(String vlanId, String isolatedPvlan, boolean bypassVlanOverlapCheck,
            NetworkOfferingVO offering, DataCenterVO zone, long zoneId, Account owner, boolean isPrivateNetwork, URI uri, URI secondaryUri) {
        if (!hasGuestBypassVlanOverlapCheck(bypassVlanOverlapCheck, offering, isPrivateNetwork)) {
            if (networksDao.listByZoneAndUriAndGuestType(zoneId, uri.toString(), null).size() > 0) {
                throw new InvalidParameterValueException(String.format(
                        "Network with vlan %s already exists or overlaps with other network vlans in zone %s",
                        vlanId, zone));
            } else if (secondaryUri != null && networksDao.listByZoneAndUriAndGuestType(zoneId, secondaryUri.toString(), null).size() > 0) {
                throw new InvalidParameterValueException(String.format(
                        "Network with vlan %s already exists or overlaps with other network vlans in zone %s",
                        isolatedPvlan, zone));
            } else {
                validateDedicatedGuestVlanOwnership(vlanId, zoneId, owner, uri);
            }
        } else {
            if (!bypassVlanOverlapCheck && networksDao.listByZoneAndUriAndGuestType(zoneId, uri.toString(), GuestType.Isolated).size() > 0) {
                throw new InvalidParameterValueException(String.format(
                        "There is an existing isolated/shared network that overlaps with vlan id:%s in zone %s", vlanId, zone));
            }
        }
    }

    protected void validateDedicatedGuestVlanOwnership(String vlanId, long zoneId, Account owner, URI uri) {
        final List<DataCenterVnetVO> dcVnets = dataCenterVnetDao.findVnet(zoneId, BroadcastDomainType.getValue(uri));
        //for the network that is created as part of private gateway,
        //the vnet is not coming from the data center vnet table, so the list can be empty
        if (!dcVnets.isEmpty()) {
            final DataCenterVnetVO dcVnet = dcVnets.get(0);
            // Fail network creation if specified vlan is dedicated to a different account
            if (dcVnet.getAccountGuestVlanMapId() != null) {
                final Long accountGuestVlanMapId = dcVnet.getAccountGuestVlanMapId();
                final AccountGuestVlanMapVO map = accountGuestVlanMapDao.findById(accountGuestVlanMapId);
                if (map.getAccountId() != owner.getAccountId()) {
                    throw new InvalidParameterValueException("Vlan " + vlanId + " is dedicated to a different account");
                }
                // Fail network creation if owner has a dedicated range of vlans but the specified vlan belongs to the system pool
            } else {
                final List<AccountGuestVlanMapVO> maps = accountGuestVlanMapDao.listAccountGuestVlanMapsByAccount(owner.getAccountId());
                if (maps != null && !maps.isEmpty()) {
                    final int vnetsAllocatedToAccount = dataCenterVnetDao.countVnetsAllocatedToAccount(zoneId, owner.getAccountId());
                    final int vnetsDedicatedToAccount = dataCenterVnetDao.countVnetsDedicatedToAccount(zoneId, owner.getAccountId());
                    if (vnetsAllocatedToAccount < vnetsDedicatedToAccount) {
                        throw new InvalidParameterValueException("Specified vlan " + vlanId + " doesn't belong" + " to the vlan range dedicated to the owner "
                                + owner.getAccountName());
                    }
                }
            }
        }
    }

    protected boolean hasGuestBypassVlanOverlapCheck(final boolean bypassVlanOverlapCheck, final NetworkOfferingVO offering, final boolean isPrivateNetwork) {
        return bypassVlanOverlapCheck && (offering.getGuestType() != GuestType.Isolated || isPrivateNetwork);
    }

    @Override
    public void checkL2OfferingServices(NetworkOfferingVO offering) {
        if (offering.getGuestType() == GuestType.L2 && !networkModel.listNetworkOfferingServices(offering.getId()).isEmpty() &&
                (!networkModel.areServicesSupportedByNetworkOffering(offering.getId(), Service.UserData) ||
                        (networkModel.areServicesSupportedByNetworkOffering(offering.getId(), Service.UserData) &&
                                networkModel.listNetworkOfferingServices(offering.getId()).size() > 1))) {
            throw new InvalidParameterValueException("For L2 networks, only UserData service is allowed");
        }
    }
}
