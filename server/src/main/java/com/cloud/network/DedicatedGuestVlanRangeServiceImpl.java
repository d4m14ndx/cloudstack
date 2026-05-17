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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.network.DedicateGuestVlanRangeCmd;
import org.apache.cloudstack.api.command.admin.network.ListDedicatedGuestVlanRangesCmd;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.dc.DataCenterVnetVO;
import com.cloud.dc.dao.DataCenterVnetDao;
import com.cloud.domain.DomainVO;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.dao.AccountGuestVlanMapDao;
import com.cloud.network.dao.AccountGuestVlanMapVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

/**
 * Dedicated guest VLAN range lifecycle operations — extracted from
 * {@link NetworkServiceImpl}.
 *
 * @see DedicatedGuestVlanRangeService
 */
@Component
public class DedicatedGuestVlanRangeServiceImpl implements DedicatedGuestVlanRangeService {
    protected static final Logger LOG = LogManager.getLogger(DedicatedGuestVlanRangeServiceImpl.class);

    @Inject
    AccountManager accountManager;
    @Inject
    AccountDao accountDao;
    @Inject
    ProjectManager projectManager;
    @Inject
    PhysicalNetworkDao physicalNetworkDao;
    @Inject
    DataCenterVnetDao dcVnetDao;
    @Inject
    AccountGuestVlanMapDao accountGuestVlanMapDao;

    @Override
    public GuestVlanRange dedicateGuestVlanRange(DedicateGuestVlanRangeCmd cmd) {
        String vlan = cmd.getVlan();
        String accountName = cmd.getAccountName();
        Long domainId = cmd.getDomainId();
        Long physicalNetworkId = cmd.getPhysicalNetworkId();
        Long projectId = cmd.getProjectId();

        int startVlan, endVlan;
        String updatedVlanRange = null;
        long guestVlanMapId = 0;
        long guestVlanMapAccountId = 0;
        long vlanOwnerId = 0;

        // Verify account is valid
        Account vlanOwner = null;
        if (projectId != null) {
            if (accountName != null) {
                throw new InvalidParameterValueException("accountName and projectId are mutually exclusive");
            }
            Project project = projectManager.getProject(projectId);
            if (project == null) {
                throw new InvalidParameterValueException("Unable to find project by id " + projectId);
            }
            vlanOwner = accountManager.getAccount(project.getProjectAccountId());
        }

        if ((accountName != null) && (domainId != null)) {
            vlanOwner = accountDao.findActiveAccount(accountName, domainId);
        }
        if (vlanOwner == null) {
            throw new InvalidParameterValueException("Unable to find account by name " + accountName);
        }
        vlanOwnerId = vlanOwner.getAccountId();

        // Verify physical network isolation methods contain VLAN or VXLAN
        PhysicalNetworkVO physicalNetwork = physicalNetworkDao.findById(physicalNetworkId);
        if (physicalNetwork == null) {
            throw new InvalidParameterValueException("Unable to find physical network by id " + physicalNetworkId);
        } else if (!physicalNetwork.getIsolationMethods().isEmpty() &&
                !physicalNetwork.getIsolationMethods().contains("VLAN") &&
                !physicalNetwork.getIsolationMethods().contains("VXLAN")) {
            throw new InvalidParameterValueException(String.format("Cannot dedicate guest VLAN range. Physical isolation type of Network %s is not VLAN nor VXLAN", physicalNetwork));
        }

        // Get the start and end vlan
        String[] vlanRange = vlan.split("-");
        if (vlanRange.length != 2) {
            throw new InvalidParameterValueException("Invalid format for parameter value vlan " + vlan + " .VLAN should be specified as 'startvlan-endvlan'");
        }

        try {
            startVlan = Integer.parseInt(vlanRange[0]);
            endVlan = Integer.parseInt(vlanRange[1]);
        } catch (NumberFormatException e) {
            LOG.warn("Unable to parse guest vlan range:", e);
            throw new InvalidParameterValueException("Please provide valid guest vlan range");
        }

        // Verify guest vlan range exists in the system
        List<Pair<Integer, Integer>> existingRanges = physicalNetwork.getVnet();
        Boolean exists = false;
        if (!existingRanges.isEmpty()) {
            for (int i = 0; i < existingRanges.size(); i++) {
                int existingStartVlan = existingRanges.get(i).first();
                int existingEndVlan = existingRanges.get(i).second();
                if (startVlan <= endVlan && startVlan >= existingStartVlan && endVlan <= existingEndVlan) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                throw new InvalidParameterValueException("Unable to find guest vlan by range " + vlan);
            }
        }

        verifyDedicatedGuestVlansWithExistingDatacenterVlans(physicalNetwork, vlanOwner, startVlan, endVlan);

        List<AccountGuestVlanMapVO> guestVlanMaps = accountGuestVlanMapDao.listAccountGuestVlanMapsByPhysicalNetwork(physicalNetworkId);
        // Verify if vlan range is already dedicated
        for (AccountGuestVlanMapVO guestVlanMap : guestVlanMaps) {
            List<Integer> vlanTokens = getVlanFromRange(guestVlanMap.getGuestVlanRange());
            int dedicatedStartVlan = vlanTokens.get(0).intValue();
            int dedicatedEndVlan = vlanTokens.get(1).intValue();
            if ((startVlan < dedicatedStartVlan & endVlan >= dedicatedStartVlan) || (startVlan >= dedicatedStartVlan & startVlan <= dedicatedEndVlan)) {
                throw new InvalidParameterValueException("Vlan range is already dedicated. Cannot" + " dedicate guest vlan range " + vlan);
            }
        }

        // Sort the existing dedicated vlan ranges
        Collections.sort(guestVlanMaps, new Comparator<AccountGuestVlanMapVO>() {
            @Override
            public int compare(AccountGuestVlanMapVO obj1, AccountGuestVlanMapVO obj2) {
                List<Integer> vlanTokens1 = getVlanFromRange(obj1.getGuestVlanRange());
                List<Integer> vlanTokens2 = getVlanFromRange(obj2.getGuestVlanRange());
                return vlanTokens1.get(0).compareTo(vlanTokens2.get(0));
            }
        });

        // Verify if vlan range extends an already dedicated range
        for (int i = 0; i < guestVlanMaps.size(); i++) {
            guestVlanMapId = guestVlanMaps.get(i).getId();
            guestVlanMapAccountId = guestVlanMaps.get(i).getAccountId();
            List<Integer> vlanTokens1 = getVlanFromRange(guestVlanMaps.get(i).getGuestVlanRange());
            // Range extends a dedicated vlan range to the left
            if (endVlan == (vlanTokens1.get(0).intValue() - 1)) {
                if (guestVlanMapAccountId == vlanOwnerId) {
                    updatedVlanRange = startVlan + "-" + vlanTokens1.get(1).intValue();
                }
                break;
            }
            // Range extends a dedicated vlan range to the right
            if (startVlan == (vlanTokens1.get(1).intValue() + 1) & guestVlanMapAccountId == vlanOwnerId) {
                if (i != (guestVlanMaps.size() - 1)) {
                    List<Integer> vlanTokens2 = getVlanFromRange(guestVlanMaps.get(i + 1).getGuestVlanRange());
                    // Range extends 2 vlan ranges, both to the right and left
                    if (endVlan == (vlanTokens2.get(0).intValue() - 1) && guestVlanMaps.get(i + 1).getAccountId() == vlanOwnerId) {
                        dcVnetDao.releaseDedicatedGuestVlans(guestVlanMaps.get(i + 1).getId());
                        accountGuestVlanMapDao.remove(guestVlanMaps.get(i + 1).getId());
                        updatedVlanRange = vlanTokens1.get(0).intValue() + "-" + vlanTokens2.get(1).intValue();
                        break;
                    }
                }
                updatedVlanRange = vlanTokens1.get(0).intValue() + "-" + endVlan;
                break;
            }
        }
        // Dedicate vlan range
        AccountGuestVlanMapVO accountGuestVlanMapVO;
        if (updatedVlanRange != null) {
            accountGuestVlanMapVO = accountGuestVlanMapDao.findById(guestVlanMapId);
            accountGuestVlanMapVO.setGuestVlanRange(updatedVlanRange);
            accountGuestVlanMapDao.update(guestVlanMapId, accountGuestVlanMapVO);
        } else {
            accountGuestVlanMapVO = new AccountGuestVlanMapVO(vlanOwner.getAccountId(), physicalNetworkId);
            accountGuestVlanMapVO.setGuestVlanRange(startVlan + "-" + endVlan);
            accountGuestVlanMapDao.persist(accountGuestVlanMapVO);
        }
        // For every guest vlan set the corresponding account guest vlan map id
        List<Integer> finaVlanTokens = getVlanFromRange(accountGuestVlanMapVO.getGuestVlanRange());
        for (int i = finaVlanTokens.get(0).intValue(); i <= finaVlanTokens.get(1).intValue(); i++) {
            List<DataCenterVnetVO> dataCenterVnet = dcVnetDao.findVnet(physicalNetwork.getDataCenterId(), physicalNetworkId, Integer.toString(i));
            dataCenterVnet.get(0).setAccountGuestVlanMapId(accountGuestVlanMapVO.getId());
            dcVnetDao.update(dataCenterVnet.get(0).getId(), dataCenterVnet.get(0));
        }
        return accountGuestVlanMapVO;
    }

    @Override
    public Pair<List<? extends GuestVlanRange>, Integer> listDedicatedGuestVlanRanges(ListDedicatedGuestVlanRangesCmd cmd) {
        Long id = cmd.getId();
        String accountName = cmd.getAccountName();
        Long domainId = cmd.getDomainId();
        Long projectId = cmd.getProjectId();
        String guestVlanRange = cmd.getGuestVlanRange();
        Long physicalNetworkId = cmd.getPhysicalNetworkId();
        Long zoneId = cmd.getZoneId();

        Long accountId = null;
        if (accountName != null && domainId != null) {
            if (projectId != null) {
                throw new InvalidParameterValueException("Account and projectId can't be specified together");
            }
            Account account = accountDao.findActiveAccount(accountName, domainId);
            if (account == null) {
                InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find account " + accountName);
                DomainVO domain = ApiDBUtils.findDomainById(domainId);
                String domainUuid = domainId.toString();
                if (domain != null) {
                    domainUuid = domain.getUuid();
                }
                ex.addProxyObject(domainUuid, "domainId");
                throw ex;
            } else {
                accountId = account.getId();
            }
        }

        // set project information
        if (projectId != null) {
            Project project = projectManager.getProject(projectId);
            if (project == null) {
                InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find project by id " + projectId);
                ex.addProxyObject(projectId.toString(), "projectId");
                throw ex;
            }
            accountId = project.getProjectAccountId();
        }

        SearchBuilder<AccountGuestVlanMapVO> sb = accountGuestVlanMapDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("accountId", sb.entity().getAccountId(), SearchCriteria.Op.EQ);
        sb.and("guestVlanRange", sb.entity().getGuestVlanRange(), SearchCriteria.Op.EQ);
        sb.and("physicalNetworkId", sb.entity().getPhysicalNetworkId(), SearchCriteria.Op.EQ);

        if (zoneId != null) {
            SearchBuilder<PhysicalNetworkVO> physicalnetworkSearch = physicalNetworkDao.createSearchBuilder();
            physicalnetworkSearch.and("zoneId", physicalnetworkSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
            sb.join("physicalnetworkSearch", physicalnetworkSearch, sb.entity().getPhysicalNetworkId(), physicalnetworkSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        SearchCriteria<AccountGuestVlanMapVO> sc = sb.create();
        if (id != null) {
            sc.setParameters("id", id);
        }

        if (accountId != null) {
            sc.setParameters("accountId", accountId);
        }

        if (guestVlanRange != null) {
            sc.setParameters("guestVlanRange", guestVlanRange);
        }

        if (physicalNetworkId != null) {
            sc.setParameters("physicalNetworkId", physicalNetworkId);
        }

        if (zoneId != null) {
            sc.setJoinParameters("physicalnetworkSearch", "zoneId", zoneId);
        }

        Filter searchFilter = new Filter(AccountGuestVlanMapVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        Pair<List<AccountGuestVlanMapVO>, Integer> result = accountGuestVlanMapDao.searchAndCount(sc, searchFilter);
        return new Pair<List<? extends GuestVlanRange>, Integer>(result.first(), result.second());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_DEDICATED_GUEST_VLAN_RANGE_RELEASE, eventDescription = "releasing" + " dedicated guest vlan range", async = true)
    @DB
    public boolean releaseDedicatedGuestVlanRange(Long dedicatedGuestVlanRangeId) {
        // Verify dedicated range exists
        AccountGuestVlanMapVO dedicatedGuestVlan = accountGuestVlanMapDao.findById(dedicatedGuestVlanRangeId);
        if (dedicatedGuestVlan == null) {
            throw new InvalidParameterValueException("Dedicated guest vlan with specified" + " id doesn't exist in the system");
        }

        // Remove dedication for the guest vlan
        dcVnetDao.releaseDedicatedGuestVlans(dedicatedGuestVlan.getId());
        if (accountGuestVlanMapDao.remove(dedicatedGuestVlanRangeId)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Verify that every VLAN in the requested range exists on the
     * physical network and that no VLAN is already allocated to a
     * different account. Throws {@link InvalidParameterValueException}
     * if either invariant fails.
     */
    protected void verifyDedicatedGuestVlansWithExistingDatacenterVlans(PhysicalNetwork physicalNetwork, Account vlanOwner, int startVlan, int endVlan) {
        for (int i = startVlan; i <= endVlan; i++) {
            List<DataCenterVnetVO> dataCenterVnet = dcVnetDao.findVnet(physicalNetwork.getDataCenterId(), physicalNetwork.getId(), Integer.toString(i));
            if (CollectionUtils.isEmpty(dataCenterVnet)) {
                throw new InvalidParameterValueException(String.format("Guest VLAN %d from this range %d-%d is not present in the system for physical network ID: %s", i, startVlan, endVlan, physicalNetwork.getUuid()));
            }
            // Verify guest vlans in the range don't belong to a network of a different account
            if (dataCenterVnet.get(0).getAccountId() != null && dataCenterVnet.get(0).getAccountId() != vlanOwner.getAccountId()) {
                throw new InvalidParameterValueException("Guest VLAN from this range " + dataCenterVnet.get(0).getVnet() + " is allocated to a different Account."
                        + " Can only dedicate a range which has no allocated VLANs or has VLANs allocated to the same Account ");
            }
        }
    }

    /**
     * Parse a {@code start-end} guest VLAN range string into a list of
     * two integers. Throws {@link InvalidParameterValueException} if
     * either token does not parse.
     */
    protected List<Integer> getVlanFromRange(String vlanRange) {
        // Get the start and end vlan
        String[] vlanTokens = vlanRange.split("-");
        List<Integer> tokens = new ArrayList<Integer>();
        try {
            int startVlan = Integer.parseInt(vlanTokens[0]);
            int endVlan = Integer.parseInt(vlanTokens[1]);
            tokens.add(startVlan);
            tokens.add(endVlan);
        } catch (NumberFormatException e) {
            LOG.warn("Unable to parse guest vlan range:", e);
            throw new InvalidParameterValueException("Please provide valid guest vlan range");
        }
        return tokens;
    }
}
