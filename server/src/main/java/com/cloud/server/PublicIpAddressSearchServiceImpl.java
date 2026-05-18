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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.address.ListPublicIpAddressesCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanDetailsVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.dc.dao.VlanDetailsDao;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.IpAddressManagerImpl;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkAccountDao;
import com.cloud.network.dao.NetworkAccountVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.network.dao.NetworkDomainVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.EnumUtils;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

/**
 * @see PublicIpAddressSearchService
 */
@Component
public class PublicIpAddressSearchServiceImpl implements PublicIpAddressSearchService {

    private static final String FOR_SYSTEMVMS = "forsystemvms";

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected AccountManager _accountMgr;
    @Inject
    protected IPAddressDao _publicIpAddressDao;
    @Inject
    protected VlanDao _vlanDao;
    @Inject
    protected VlanDetailsDao vlanDetailsDao;
    @Inject
    protected DomainDao _domainDao;
    @Inject
    protected AccountDao _accountDao;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected LoadBalancerDao _loadbalancerDao;
    @Inject
    protected ResourceTagDao _resourceTagDao;
    @Inject
    protected IpAddressManager _ipAddressMgr;
    @Inject
    protected NetworkAccountDao _networkAccountDao;
    @Inject
    protected NetworkDomainDao _networkDomainDao;
    @Inject
    protected NetworkModel _networkMgr;
    @Inject
    protected VpcDao _vpcDao;

    @Override
    public List<IpAddress.State> getStatesForIpAddressSearch(final ListPublicIpAddressesCmd cmd) {
        final String statesStr = cmd.getState();
        final List<IpAddress.State> states = new ArrayList<>();
        if (StringUtils.isBlank(statesStr)) {
            return states;
        }
        for (String s : StringUtils.split(statesStr, ",")) {
            IpAddress.State state = EnumUtils.getEnumIgnoreCase(IpAddress.State.class, s.trim());
            if (state == null) {
                throw new InvalidParameterValueException("Invalid state: " + s);
            }
            states.add(state);
        }
        return states;
    }

    @Override
    public Pair<List<? extends IpAddress>, Integer> searchForIPAddresses(final ListPublicIpAddressesCmd cmd) {
        final Long associatedNetworkId = cmd.getAssociatedNetworkId();
        final Long zone = cmd.getZoneId();
        final Long vlan = cmd.getVlanId();
        final Boolean forVirtualNetwork = cmd.isForVirtualNetwork();
        final Long ipId = cmd.getId();
        final Long networkId = cmd.getNetworkId();
        final Long vpcId = cmd.getVpcId();

        final List<IpAddress.State> states = getStatesForIpAddressSearch(cmd);
        Boolean isAllocated = cmd.isAllocatedOnly();
        if (isAllocated == null) {
            if (states.contains(IpAddress.State.Free) || states.contains(IpAddress.State.Reserved)) {
                isAllocated = Boolean.FALSE;
            } else {
                isAllocated = Boolean.TRUE;
            }
        } else {
            if (states.contains(IpAddress.State.Free) || states.contains(IpAddress.State.Reserved)) {
                if (isAllocated) {
                    throw new InvalidParameterValueException("Conflict: allocatedonly is true but state is Free");
                }
            } else if (states.contains(IpAddress.State.Allocated)) {
                isAllocated = Boolean.TRUE;
            }
        }
        boolean isAllocatedTemp = isAllocated;

        VlanType vlanType;
        if (forVirtualNetwork != null) {
            vlanType = forVirtualNetwork ? VlanType.VirtualNetwork : VlanType.DirectAttached;
        } else {
            vlanType = VlanType.VirtualNetwork;
        }

        final Account caller = CallContext.current().getCallingAccount();
        List<IPAddressVO> addrs = new ArrayList<>();
        NetworkVO network = null;

        if (vlanType == VlanType.DirectAttached && networkId == null && ipId == null) {
            if (caller.getType() != Account.Type.ADMIN) {
                isAllocated = true;
            }
        } else if (vlanType == VlanType.DirectAttached) {
            if (networkId == null) {
                IPAddressVO ip = _publicIpAddressDao.findById(ipId);
                if (ip == null) {
                    throw new InvalidParameterValueException("Please specify a valid ipaddress id");
                }
                network = networkDao.findById(ip.getSourceNetworkId());
            } else {
                network = networkDao.findById(networkId);
            }
            if (network == null || network.getGuestType() != Network.GuestType.Shared) {
                throw new InvalidParameterValueException("Please specify a valid network id");
            }
            if (network.getAclType() == ControlledEntity.ACLType.Account) {
                NetworkAccountVO networkMap = _networkAccountDao.getAccountNetworkMapByNetworkId(network.getId());
                if (networkMap == null) {
                    return new Pair<>(addrs, 0);
                }
                try {
                    _accountMgr.checkAccess(caller, null, false, _accountDao.findById(networkMap.getAccountId()));
                } catch (PermissionDeniedException ex) {
                    logger.info("Account " + caller + " do not have permission to access account of network " + network);
                    _accountMgr.checkAccess(caller, SecurityChecker.AccessType.UseEntry, false, network);
                    isAllocated = Boolean.TRUE;
                }
            } else {
                NetworkDomainVO networkMap = _networkDomainDao.getDomainNetworkMapByNetworkId(network.getId());
                if (networkMap == null) {
                    return new Pair<>(addrs, 0);
                }
                if (caller.getType() == Account.Type.NORMAL || caller.getType() == Account.Type.PROJECT) {
                    if (_networkMgr.isNetworkAvailableInDomain(network.getId(), caller.getDomainId())) {
                        isAllocated = Boolean.TRUE;
                    } else {
                        return new Pair<>(addrs, 0);
                    }
                } else if (caller.getType() == Account.Type.DOMAIN_ADMIN || caller.getType() == Account.Type.RESOURCE_DOMAIN_ADMIN) {
                    if (caller.getDomainId() == networkMap.getDomainId() || _domainDao.isChildDomain(caller.getDomainId(), networkMap.getDomainId())) {
                        logger.debug("Caller " + caller.getUuid() + " has permission to access the network : " + network.getUuid());
                    } else {
                        if (_networkMgr.isNetworkAvailableInDomain(network.getId(), caller.getDomainId())) {
                            isAllocated = Boolean.TRUE;
                        } else {
                            return new Pair<>(addrs, 0);
                        }
                    }
                }
            }
        }

        final Filter searchFilter = new Filter(IPAddressVO.class, "address", false, null, null);
        final SearchBuilder<IPAddressVO> sb = _publicIpAddressDao.createSearchBuilder();
        Long domainId = null;
        Boolean isRecursive = cmd.isRecursive();
        final List<Long> permittedAccounts = new ArrayList<>();
        ListProjectResourcesCriteria listProjectResourcesCriteria = null;
        boolean isAllocatedOrReserved = isAllocated ||
                (states.size() == 1 && IpAddress.State.Reserved.equals(states.get(0)));
        if (isAllocatedOrReserved || (vlanType == VlanType.VirtualNetwork && (caller.getType() != Account.Type.ADMIN || cmd.getDomainId() != null))) {
            final Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(),
                    null);
            _accountMgr.buildACLSearchParameters(caller, cmd.getId(), cmd.getAccountName(), cmd.getProjectId(), permittedAccounts,
                    domainIdRecursiveListProject, cmd.listAll(), false);
            domainId = domainIdRecursiveListProject.first();
            isRecursive = domainIdRecursiveListProject.second();
            listProjectResourcesCriteria = domainIdRecursiveListProject.third();
            _accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        }

        buildParameters(sb, cmd, vlanType == VlanType.VirtualNetwork ? true : isAllocated);

        SearchCriteria<IPAddressVO> sc = sb.create();
        setParameters(sc, cmd, vlanType, isAllocated, states);

        if (isAllocatedOrReserved || (vlanType == VlanType.VirtualNetwork && (caller.getType() != Account.Type.ADMIN || cmd.getDomainId() != null))) {
            _accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        }

        if (associatedNetworkId != null) {
            NetworkVO associatedNetwork = networkDao.findById(associatedNetworkId);
            if (associatedNetwork != null) {
                _accountMgr.checkAccess(caller, null, false, associatedNetwork);
                sc.setParameters("associatedNetworkIdEq", associatedNetworkId);
            }
        }

        if (vpcId != null) {
            VpcVO vpc = _vpcDao.findById(vpcId);
            if (vpc != null) {
                _accountMgr.checkAccess(caller, null, false, vpc);
                sc.setParameters("vpcId", vpcId);
            }
        }

        addrs = _publicIpAddressDao.search(sc, searchFilter);

        List<Long> freeAddrIds = new ArrayList<>();
        if (!(isAllocatedOrReserved || vlanType == VlanType.DirectAttached)) {
            Long zoneId = zone;
            Account owner;
            if (cmd.getProjectId() != null && cmd.getProjectId() != -1) {
                owner = _accountMgr.finalizeOwner(CallContext.current().getCallingAccount(), cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
            } else {
                owner = _accountMgr.finalizeOwner(CallContext.current().getCallingAccount(), cmd.getAccountName(), cmd.getDomainId(), null);
            }
            if (associatedNetworkId != null) {
                NetworkVO guestNetwork = networkDao.findById(associatedNetworkId);
                if (guestNetwork != null) {
                    if (zoneId == null) {
                        zoneId = guestNetwork.getDataCenterId();
                    } else if (zoneId != guestNetwork.getDataCenterId()) {
                        throw new InvalidParameterValueException("Please specify a valid associated network id in the specified zone.");
                    }
                    owner = _accountDao.findById(guestNetwork.getAccountId());
                }
            }
            List<DataCenterVO> dcList = new ArrayList<>();
            if (zoneId == null) {
                dcList = ApiDBUtils.listZones();
            } else {
                dcList.add(ApiDBUtils.findZoneById(zoneId));
            }
            List<Long> vlanDbIds = null;
            if (vlan != null) {
                vlanDbIds = new ArrayList<>();
                vlanDbIds.add(vlan);
            }
            List<IPAddressVO> freeAddrs = new ArrayList<>();
            for (DataCenterVO dc : dcList) {
                long dcId = dc.getId();
                try {
                    freeAddrs.addAll(_ipAddressMgr.listAvailablePublicIps(dcId, null, vlanDbIds, owner, VlanType.VirtualNetwork, associatedNetworkId,
                            false, false, false, null, null, false, cmd.getVpcId(), cmd.getDisplay(), false, false));
                } catch (InsufficientAddressCapacityException e) {
                    logger.warn("no free address is found in zone {}", dc);
                }
            }
            for (IPAddressVO addr : freeAddrs) {
                freeAddrIds.add(addr.getId());
            }
        } else if (vlanType == VlanType.DirectAttached && network != null && !isAllocatedTemp && isAllocated) {
            if (caller.getType() != Account.Type.ADMIN && !IpAddressManager.AllowUserListAvailableIpsOnSharedNetwork.value()) {
                logger.debug("Non-admin users are not allowed to list available IPs on shared networks");
            } else {
                final SearchBuilder<IPAddressVO> searchBuilder = _publicIpAddressDao.createSearchBuilder();
                buildParameters(searchBuilder, cmd, false);

                SearchCriteria<IPAddressVO> searchCriteria = searchBuilder.create();
                setParameters(searchCriteria, cmd, vlanType, false, states);
                searchCriteria.setParameters("state", IpAddress.State.Free.name());
                addrs.addAll(_publicIpAddressDao.search(searchCriteria, searchFilter));
            }
        }

        if (!freeAddrIds.isEmpty()) {
            final SearchBuilder<IPAddressVO> sb2 = _publicIpAddressDao.createSearchBuilder();
            buildParameters(sb2, cmd, false);
            sb2.and("ids", sb2.entity().getId(), SearchCriteria.Op.IN);
            sb2.and("quarantinedPublicIpsIdsNIN", sb2.entity().getId(), SearchCriteria.Op.NIN);

            SearchCriteria<IPAddressVO> sc2 = sb2.create();
            setParameters(sc2, cmd, vlanType, isAllocated, states);
            sc2.setParameters("ids", freeAddrIds.toArray());
            _publicIpAddressDao.buildQuarantineSearchCriteria(sc2);
            addrs.addAll(_publicIpAddressDao.search(sc2, searchFilter));
        }
        Collections.sort(addrs, Comparator.comparing(IPAddressVO::getAddress));
        List<? extends IpAddress> wPagination = com.cloud.utils.StringUtils.applyPagination(addrs, cmd.getStartIndex(), cmd.getPageSizeVal());
        if (wPagination != null) {
            return new Pair<>(wPagination, addrs.size());
        }
        return new Pair<>(addrs, addrs.size());
    }

    private void buildParameters(final SearchBuilder<IPAddressVO> sb, final ListPublicIpAddressesCmd cmd, final Boolean isAllocated) {
        final Object keyword = cmd.getKeyword();
        final String address = cmd.getIpAddress();
        final Boolean forLoadBalancing = cmd.isForLoadBalancing();
        final Map<String, String> tags = cmd.getTags();
        boolean forProvider = cmd.isForProvider();

        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("address", sb.entity().getAddress(), SearchCriteria.Op.EQ);
        sb.and("vlanDbId", sb.entity().getVlanId(), SearchCriteria.Op.EQ);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("physicalNetworkId", sb.entity().getPhysicalNetworkId(), SearchCriteria.Op.EQ);
        sb.and("associatedNetworkIdEq", sb.entity().getAssociatedWithNetworkId(), SearchCriteria.Op.EQ);
        sb.and("sourceNetworkId", sb.entity().getSourceNetworkId(), SearchCriteria.Op.EQ);
        sb.and("isSourceNat", sb.entity().isSourceNat(), SearchCriteria.Op.EQ);
        sb.and("isStaticNat", sb.entity().isOneToOneNat(), SearchCriteria.Op.EQ);
        sb.and("vpcId", sb.entity().getVpcId(), SearchCriteria.Op.EQ);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.IN);
        sb.and("display", sb.entity().isDisplay(), SearchCriteria.Op.EQ);
        sb.and(FOR_SYSTEMVMS, sb.entity().isForSystemVms(), SearchCriteria.Op.EQ);

        if (forLoadBalancing != null && forLoadBalancing) {
            final SearchBuilder<LoadBalancerVO> lbSearch = _loadbalancerDao.createSearchBuilder();
            sb.join("lbSearch", lbSearch, sb.entity().getId(), lbSearch.entity().getSourceIpAddressId(), JoinType.INNER);
            sb.groupBy(sb.entity().getId());
        }

        if (keyword != null && address == null) {
            sb.and("addressLIKE", sb.entity().getAddress(), SearchCriteria.Op.LIKE);
        }

        if (tags != null && !tags.isEmpty()) {
            final SearchBuilder<ResourceTagVO> tagSearch = _resourceTagDao.createSearchBuilder();
            for (int count = 0; count < tags.size(); count++) {
                tagSearch.or().op("key" + count, tagSearch.entity().getKey(), SearchCriteria.Op.EQ);
                tagSearch.and("value" + count, tagSearch.entity().getValue(), SearchCriteria.Op.EQ);
                tagSearch.cp();
            }
            tagSearch.and("resourceType", tagSearch.entity().getResourceType(), SearchCriteria.Op.EQ);
            sb.groupBy(sb.entity().getId());
            sb.join("tagSearch", tagSearch, sb.entity().getId(), tagSearch.entity().getResourceId(), JoinBuilder.JoinType.INNER);
        }

        final SearchBuilder<VlanVO> vlanSearch = _vlanDao.createSearchBuilder();
        vlanSearch.and("vlanType", vlanSearch.entity().getVlanType(), SearchCriteria.Op.EQ);
        vlanSearch.and("removed", vlanSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        sb.join("vlanSearch", vlanSearch, sb.entity().getVlanId(), vlanSearch.entity().getId(), JoinBuilder.JoinType.INNER);

        if (isAllocated != null && isAllocated) {
            sb.and("allocated", sb.entity().getAllocatedTime(), SearchCriteria.Op.NNULL);
        }

        if (forProvider) {
            SearchBuilder<VlanDetailsVO> vlanDetailsSearch = vlanDetailsDao.createSearchBuilder();
            vlanDetailsSearch.and("name", vlanDetailsSearch.entity().getName(), SearchCriteria.Op.IN);
            vlanDetailsSearch.and("value", vlanDetailsSearch.entity().getValue(), SearchCriteria.Op.EQ);
            sb.join("vlanDetailSearch", vlanDetailsSearch, sb.entity().getVlanId(), vlanDetailsSearch.entity().getResourceId(), JoinType.LEFT);
        }
    }

    @Override
    public void setParameters(SearchCriteria<IPAddressVO> sc, final ListPublicIpAddressesCmd cmd, VlanType vlanType,
            Boolean isAllocated, List<IpAddress.State> states) {
        final Object keyword = cmd.getKeyword();
        final Long physicalNetworkId = cmd.getPhysicalNetworkId();
        final Long sourceNetworkId = cmd.getNetworkId();
        final Long zone = cmd.getZoneId();
        final String address = cmd.getIpAddress();
        final Long vlan = cmd.getVlanId();
        final Long ipId = cmd.getId();
        final Boolean sourceNat = cmd.isSourceNat();
        final Boolean staticNat = cmd.isStaticNat();
        final Boolean forDisplay = cmd.getDisplay();
        final Boolean forSystemVms = cmd.getForSystemVMs();
        final boolean forProvider = cmd.isForProvider();
        final Map<String, String> tags = cmd.getTags();

        sc.setJoinParameters("vlanSearch", "vlanType", vlanType);

        if (tags != null && !tags.isEmpty()) {
            int count = 0;
            sc.setJoinParameters("tagSearch", "resourceType", ResourceObjectType.PublicIpAddress.toString());
            for (final String key : tags.keySet()) {
                sc.setJoinParameters("tagSearch", "key" + count, key);
                sc.setJoinParameters("tagSearch", "value" + count, tags.get(key));
                count++;
            }
        }

        if (zone != null) {
            sc.setParameters("dataCenterId", zone);
        }
        if (ipId != null) {
            sc.setParameters("id", ipId);
        }
        if (sourceNat != null) {
            sc.setParameters("isSourceNat", sourceNat);
        }
        if (staticNat != null) {
            sc.setParameters("isStaticNat", staticNat);
        }
        if (address == null && keyword != null) {
            sc.setParameters("addressLIKE", "%" + keyword + "%");
        }
        if (address != null) {
            sc.setParameters("address", address);
        }
        if (vlan != null) {
            sc.setParameters("vlanDbId", vlan);
        }
        if (physicalNetworkId != null) {
            sc.setParameters("physicalNetworkId", physicalNetworkId);
        }
        if (sourceNetworkId != null) {
            sc.setParameters("sourceNetworkId", sourceNetworkId);
        }
        if (forDisplay != null) {
            sc.setParameters("display", forDisplay);
        }
        if (CollectionUtils.isNotEmpty(states)) {
            sc.setParameters("state", states.toArray());
        } else if (isAllocated != null && isAllocated) {
            sc.setParameters("state", IpAddress.State.Allocated);
        }

        if (IpAddressManagerImpl.getSystemvmpublicipreservationmodestrictness().value() && states.contains(IpAddress.State.Free)) {
            sc.setParameters(FOR_SYSTEMVMS, false);
        } else {
            sc.setParameters(FOR_SYSTEMVMS, forSystemVms);
        }

        if (forProvider) {
            sc.setJoinParameters("vlanDetailSearch", "name", ApiConstants.NETRIS_DETAIL_KEY, ApiConstants.NSX_DETAIL_KEY);
            sc.setJoinParameters("vlanDetailSearch", "value", "true");
        }
    }
}
