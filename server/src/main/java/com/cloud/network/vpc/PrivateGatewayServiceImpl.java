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
package com.cloud.network.vpc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.vpc.CreatePrivateGatewayByAdminCmd;
import org.apache.cloudstack.api.command.user.vpc.CreatePrivateGatewayCmd;
import org.apache.cloudstack.api.command.user.vpc.ListPrivateGatewaysCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.VpcProvider;
import com.cloud.network.vpc.dao.NetworkACLDao;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.network.vpc.dao.StaticRouteDao;
import com.cloud.network.vpc.dao.VpcGatewayDao;
import com.cloud.network.vpc.dao.VpcServiceMapDao;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExceptionUtil;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;

/**
 * VPC private-gateway CRUD, validation and provider application —
 * extracted from {@link VpcManagerImpl}.
 *
 * @see PrivateGatewayService
 */
@Component
public class PrivateGatewayServiceImpl implements PrivateGatewayService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private AccountManager accountMgr;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    private EntityManager entityMgr;
    @Inject
    private NetworkACLDao networkAclDao;
    @Inject
    private NetworkOfferingDao networkOfferingDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private NetworkOrchestrationService networkMgr;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private NetworkService networkService;
    @Inject
    private PrivateIpDao privateIpDao;
    @Inject
    private StaticRouteDao staticRouteDao;
    @Inject
    private VpcGatewayDao vpcGatewayDao;
    @Inject
    private VpcServiceMapDao vpcSrvcMapDao;
    @Inject
    private VpcPrivateGatewayTransactionCallable vpcTxCallable;
    @Inject
    private VpcManager vpcManager;

    @Override
    public List<PrivateGateway> getVpcPrivateGateways(final long vpcId) {
        final List<VpcGatewayVO> gateways = vpcGatewayDao.listByVpcIdAndType(vpcId, VpcGateway.Type.Private);

        if (gateways != null) {
            final List<PrivateGateway> pvtGateway = new ArrayList<PrivateGateway>();
            for (final VpcGatewayVO gateway : gateways) {
                pvtGateway.add(getPrivateGatewayProfile(gateway));
            }
            return pvtGateway;
        } else {
            return null;
        }
    }

    @Override
    public PrivateGateway getVpcPrivateGateway(final long id) {
        final VpcGateway gateway = vpcGatewayDao.findById(id);

        if (gateway == null || gateway.getType() != VpcGateway.Type.Private) {
            return null;
        }
        return getPrivateGatewayProfile(gateway);
    }

    protected PrivateGateway getPrivateGatewayProfile(final VpcGateway gateway) {
        final Network network = networkModel.getNetwork(gateway.getNetworkId());
        return new PrivateGatewayProfile(gateway, network.getPhysicalNetworkId());
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_PRIVATE_GATEWAY_CREATE, eventDescription = "creating VPC private gateway", create = true)
    public PrivateGateway createVpcPrivateGateway(CreatePrivateGatewayCmd command) throws ResourceAllocationException,
            ConcurrentOperationException, InsufficientCapacityException {
        long vpcId = command.getVpcId();
        String ipAddress = command.getIpAddress();
        String gateway = command.getGateway();
        String netmask = command.getNetmask();
        long gatewayOwnerId = command.getEntityOwnerId();
        Long networkOfferingId = command.getNetworkOfferingId();
        Boolean isSourceNat = command.getIsSourceNat();
        Long aclId = command.getAclId();
        Long associatedNetworkId = command.getAssociatedNetworkId();

        if (command instanceof CreatePrivateGatewayByAdminCmd) {
            Long physicalNetworkId = ((CreatePrivateGatewayByAdminCmd)command).getPhysicalNetworkId();
            String broadcastUri = ((CreatePrivateGatewayByAdminCmd)command).getBroadcastUri();
            Boolean bypassVlanOverlapCheck = ((CreatePrivateGatewayByAdminCmd)command).getBypassVlanOverlapCheck();
            return createVpcPrivateGatewayInternal(vpcId, physicalNetworkId, broadcastUri, ipAddress, gateway, netmask, gatewayOwnerId, networkOfferingId, isSourceNat, aclId, bypassVlanOverlapCheck, associatedNetworkId);
        }
        return createVpcPrivateGatewayInternal(vpcId, null, null, ipAddress, gateway, netmask, gatewayOwnerId, networkOfferingId, isSourceNat, aclId, false, associatedNetworkId);
    }

    private PrivateGateway createVpcPrivateGatewayInternal(final long vpcId, Long physicalNetworkId, final String broadcastUri, final String ipAddress, final String gateway,
                                                           final String netmask, final long gatewayOwnerId, final Long networkOfferingIdPassed, final Boolean isSourceNat, final Long aclId, final Boolean bypassVlanOverlapCheck, final Long associatedNetworkId) throws ResourceAllocationException,
            ConcurrentOperationException, InsufficientCapacityException {

        // Validate parameters
        final Vpc vpc = vpcManager.getActiveVpc(vpcId);
        if (vpc == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find Enabled VPC by id specified");
            ex.addProxyObject(String.valueOf(vpcId), "VPC");
            throw ex;
        }

        NetworkOfferingVO ntwkOff = getVpcPrivateGatewayNetworkOffering(networkOfferingIdPassed, broadcastUri);
        final Long networkOfferingId = ntwkOff.getId();

        validateVpcPrivateGatewayAssociateNetworkId(ntwkOff, broadcastUri, associatedNetworkId, bypassVlanOverlapCheck);

        final Long dcId = vpc.getZoneId();
        physicalNetworkId = validateVpcPrivateGatewayPhysicalNetworkId(dcId, physicalNetworkId, associatedNetworkId, ntwkOff);
        PhysicalNetwork physNet = entityMgr.findById(PhysicalNetwork.class, physicalNetworkId);

        final Long physicalNetworkIdFinal = physicalNetworkId;
        final PhysicalNetwork physNetFinal = physNet;
        VpcGatewayVO gatewayVO = null;
        try {
            validateVpcPrivateGatewayAclId(vpcId, aclId);

            logger.debug("Creating Private gateway for VPC " + vpc);
            // 1) create private network unless it is existing and
            // lswitch'd
            Network privateNtwk = null;
            if (broadcastUri != null
                    && BroadcastDomainType.getSchemeValue(BroadcastDomainType.fromString(broadcastUri)) == BroadcastDomainType.Lswitch) {
                final String cidr = NetUtils.ipAndNetMaskToCidr(gateway, netmask);
                privateNtwk = networkDao.getPrivateNetwork(broadcastUri, cidr, gatewayOwnerId, dcId, networkOfferingId, vpcId);
                // if the dcid is different we get no network so next we
                // try to create it
            }
            if (privateNtwk == null) {
                logger.info("creating new network for vpc {} using broadcast uri: {} and associated network: {}", vpc, broadcastUri, networkDao.findById(associatedNetworkId));
                final String networkName = "vpc-" + vpc.getName() + "-privateNetwork";
                privateNtwk = networkService.createPrivateNetwork(networkName, networkName, physicalNetworkIdFinal, broadcastUri, ipAddress, null, gateway, netmask,
                        gatewayOwnerId, vpcId, isSourceNat, networkOfferingId, bypassVlanOverlapCheck, associatedNetworkId);
            } else { // create the nic/ip as createPrivateNetwork
                // doesn''t do that work for us now
                logger.info("found and using existing network for vpc " + vpc + ": " + broadcastUri);
                final DataCenterVO dc = dcDao.lockRow(physNetFinal.getDataCenterId(), true);

                // add entry to private_ip_address table
                PrivateIpVO privateIp = privateIpDao.findByIpAndSourceNetworkId(privateNtwk.getId(), ipAddress);
                if (privateIp != null) {
                    throw new InvalidParameterValueException("Private ip address " + ipAddress + " already used for private gateway" + " in zone "
                            + entityMgr.findById(DataCenter.class, dcId).getName());
                }

                final Long mac = dc.getMacAddress();
                final Long nextMac = mac + 1;
                dc.setMacAddress(nextMac);

                logger.info("creating private ip address for vpc ({}, {}, {}, {}, {})", ipAddress, privateNtwk, nextMac, vpcId, isSourceNat);
                privateIp = new PrivateIpVO(ipAddress, privateNtwk.getId(), nextMac, vpcId, isSourceNat);
                privateIpDao.persist(privateIp);

                dcDao.update(dc.getId(), dc);
            }

            Long networkAclId = ObjectUtils.defaultIfNull(aclId, NetworkACL.DEFAULT_DENY);

            { // experimental block, this is a hack
                // set vpc id in network to null
                // might be needed for all types of broadcast domains
                // the ugly hack is that vpc gateway nets are created as
                // guest network
                // while they are not.
                // A more permanent solution would be to define a type of
                // 'gatewaynetwork'
                // so that handling code is not mixed between the two
                final NetworkVO gatewaynet = networkDao.findById(privateNtwk.getId());
                gatewaynet.setVpcId(null);
                networkDao.persist(gatewaynet);
            }

            // 2) create gateway entry
            gatewayVO = new VpcGatewayVO(ipAddress, VpcGateway.Type.Private, vpcId, privateNtwk.getDataCenterId(), privateNtwk.getId(), privateNtwk.getBroadcastUri().toString(),
                    gateway, netmask, vpc.getAccountId(), vpc.getDomainId(), isSourceNat, networkAclId);
            vpcGatewayDao.persist(gatewayVO);

            logger.debug("Created vpc gateway entry " + gatewayVO);
        } catch (final Exception e) {
            ExceptionUtil.rethrowRuntime(e);
            ExceptionUtil.rethrow(e, InsufficientCapacityException.class);
            ExceptionUtil.rethrow(e, ResourceAllocationException.class);
            throw new IllegalStateException(e);
        }

        CallContext.current().setEventDetails("Private Gateway ID: " + gatewayVO.getUuid());
        return getVpcPrivateGateway(gatewayVO.getId());
    }

    /**
     * This method checks if the ACL that is being used to create the private gateway is valid. First, the aclId is used to search for a {@link NetworkACLVO} object
     * by calling the {@link NetworkACLDao#findById(java.io.Serializable)} method. If the object is null, an {@link InvalidParameterValueException} exception is thrown.
     * Secondly, we check if the ACL and the private gateway are in the same VPC and an {@link InvalidParameterValueException} is thrown if they are not.
     *
     * @param vpcId Private gateway VPC ID.
     * @param aclId Private gateway ACL ID.
     * @throws InvalidParameterValueException
     */
    @Override
    public void validateVpcPrivateGatewayAclId(long vpcId, Long aclId) {
        if (aclId == null) {
            return;
        }

        final NetworkACLVO aclVO = networkAclDao.findById(aclId);
        if (aclVO == null) {
            throw new InvalidParameterValueException("Invalid network acl id passed.");
        }
        if (aclVO.getVpcId() != vpcId && !(aclId == NetworkACL.DEFAULT_DENY || aclId == NetworkACL.DEFAULT_ALLOW)) {
            throw new InvalidParameterValueException("Private gateway and network acl are not in the same vpc.");
        }
    }

    private void validateVpcPrivateGatewayAssociateNetworkId(NetworkOfferingVO ntwkOff, String broadcastUri, Long associatedNetworkId, Boolean bypassVlanOverlapCheck) {
        // Validate vlanId and associatedNetworkId
        if (broadcastUri == null && associatedNetworkId == null) {
            throw new InvalidParameterValueException("One of vlanId and associatedNetworkId must be specified");
        }
        if (broadcastUri != null && associatedNetworkId != null) {
            throw new InvalidParameterValueException("vlanId and associatedNetworkId are mutually exclusive");
        }
        Account caller = CallContext.current().getCallingAccount();
        if (!accountMgr.isRootAdmin(caller.getId()) && (ntwkOff.isSpecifyVlan() || broadcastUri != null || bypassVlanOverlapCheck)) {
            throw new InvalidParameterValueException("Only ROOT admin is allowed to specify vlanId or bypass vlan overlap check");
        }
        if (ntwkOff.isSpecifyVlan() && broadcastUri == null) {
            throw new InvalidParameterValueException("vlanId must be specified for this network offering");
        }
        if (! ntwkOff.isSpecifyVlan() && associatedNetworkId == null) {
            throw new InvalidParameterValueException("associatedNetworkId must be specified for this network offering");
        }
    }

    @Override
    public NetworkOfferingVO getVpcPrivateGatewayNetworkOffering(Long networkOfferingIdPassed, String broadcastUri) {
        // Validate network offering
        NetworkOfferingVO ntwkOff = null;
        if (networkOfferingIdPassed != null) {
            ntwkOff = networkOfferingDao.findById(networkOfferingIdPassed);
            if (ntwkOff == null) {
                throw new InvalidParameterValueException("Unable to find network offering by id specified");
            }
            if (! TrafficType.Guest.equals(ntwkOff.getTrafficType())) {
                throw new InvalidParameterValueException("The network offering cannot be used to create Guest network");
            }
            if (! GuestType.Isolated.equals(ntwkOff.getGuestType())) {
                throw new InvalidParameterValueException("The network offering cannot be used to create Isolated network");
            }
        } else if (broadcastUri != null) {
            ntwkOff = networkOfferingDao.findByUniqueName(com.cloud.offering.NetworkOffering.SystemPrivateGatewayNetworkOffering);
        } else {
            ntwkOff = networkOfferingDao.findByUniqueName(com.cloud.offering.NetworkOffering.SystemPrivateGatewayNetworkOfferingWithoutVlan);
        }
        return ntwkOff;
    }

    @Override
    public Long validateVpcPrivateGatewayPhysicalNetworkId(Long dcId, Long physicalNetworkId, Long associatedNetworkId, NetworkOfferingVO ntwkOff) {
        // Validate physical network
        if (associatedNetworkId != null) {
            Network associatedNetwork = entityMgr.findById(Network.class, associatedNetworkId);
            if (associatedNetwork == null) {
                throw new InvalidParameterValueException("Unable to find network by ID " + associatedNetworkId);
            }
            if (physicalNetworkId != null && !physicalNetworkId.equals(associatedNetwork.getPhysicalNetworkId())) {
                throw new InvalidParameterValueException("The network can only be created on the same physical network as the associated network");
            } else if (physicalNetworkId == null) {
                physicalNetworkId = associatedNetwork.getPhysicalNetworkId();
            }
        }
        if (physicalNetworkId == null) {
            // Determine the physical network by network offering tags
            physicalNetworkId = networkService.findPhysicalNetworkId(dcId, ntwkOff.getTags(), ntwkOff.getTrafficType());
        }
        if (physicalNetworkId == null) {
            final List<? extends PhysicalNetwork> pNtwks = networkModel.getPhysicalNtwksSupportingTrafficType(dcId, TrafficType.Guest);
            if (pNtwks.isEmpty() || pNtwks.size() != 1) {
                throw new InvalidParameterValueException("Physical network can't be determined; pass physical network id");
            }
            physicalNetworkId = pNtwks.get(0).getId();
        }
        return physicalNetworkId;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PRIVATE_GATEWAY_CREATE, eventDescription = "Applying VPC private gateway", async = true)
    public PrivateGateway applyVpcPrivateGateway(final long gatewayId, final boolean destroyOnFailure) throws ConcurrentOperationException, ResourceUnavailableException {
        final VpcGatewayVO vo = vpcGatewayDao.findById(gatewayId);

        boolean success = true;
        try {
            final List<Provider> providersToImplement = getVpcProvidersForVpc(vo.getVpcId());

            final PrivateGateway gateway = getVpcPrivateGateway(gatewayId);
            for (final VpcProvider provider : vpcManager.getVpcElements()) {
                if (providersToImplement.contains(provider.getProvider())) {
                    if (!provider.createPrivateGateway(gateway)) {
                        success = false;
                    }
                }
            }
            if (success) {
                logger.debug("Private gateway " + gateway + " was applied successfully on the backend");
                if (vo.getState() != VpcGateway.State.Ready) {
                    vo.setState(VpcGateway.State.Ready);
                    vpcGatewayDao.update(vo.getId(), vo);
                    logger.debug("Marke gateway " + gateway + " with state " + VpcGateway.State.Ready);
                }
                CallContext.current().setEventDetails("Private Gateway ID: " + gateway.getUuid());
                return getVpcPrivateGateway(gatewayId);
            } else {
                logger.warn("Private gateway " + gateway + " failed to apply on the backend");
                return null;
            }
        } finally {
            // do cleanup
            if (!success) {
                if (destroyOnFailure) {
                    logger.debug("Destroying private gateway " + vo + " that failed to start");
                    // calling deleting from db because on createprivategateway
                    // fail, destroyPrivateGateway is already called
                    if (deletePrivateGatewayFromTheDB(getVpcPrivateGateway(gatewayId))) {
                        logger.warn("Successfully destroyed vpc " + vo + " that failed to start");
                    } else {
                        logger.warn("Failed to destroy vpc " + vo + " that failed to start");
                    }
                }
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PRIVATE_GATEWAY_DELETE, eventDescription = "deleting private gateway")
    @DB
    public boolean deleteVpcPrivateGateway(final long gatewayId) throws ConcurrentOperationException, ResourceUnavailableException {
        final VpcGatewayVO gatewayToBeDeleted = vpcGatewayDao.findById(gatewayId);
        if (gatewayToBeDeleted == null) {
            logger.debug("VPC gateway is already deleted for id=" + gatewayId);
            return true;
        }

        final VpcGatewayVO gatewayVO = vpcGatewayDao.acquireInLockTable(gatewayId);
        if (gatewayVO == null || gatewayVO.getType() != VpcGateway.Type.Private) {
            throw new ConcurrentOperationException(String.format("Unable to lock gateway %s", gatewayToBeDeleted));
        }

        final Account caller = CallContext.current().getCallingAccount();
        if (!accountMgr.isRootAdmin(caller.getId())) {
            accountMgr.checkAccess(caller, null, false, gatewayVO);
            final NetworkVO networkVO = networkDao.findById(gatewayVO.getNetworkId());
            if (networkVO != null) {
                accountMgr.checkAccess(caller, null, false, networkVO);
                if (networkOfferingDao.findById(networkVO.getNetworkOfferingId()).isSpecifyVlan()) {
                    throw new InvalidParameterValueException("Unable to delete private gateway with specified vlan by non-ROOT accounts");
                }
            }
        }
        try {
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) {
                    // don't allow to remove gateway when there are static
                    // routes associated with it
                    final long routeCount = staticRouteDao.countRoutesByGateway(gatewayVO.getId());
                    if (routeCount > 0) {
                        throw new CloudRuntimeException("Can't delete private gateway " + gatewayVO + " as it has " + routeCount
                                + " static routes applied. Remove the routes first");
                    }

                    gatewayVO.setState(VpcGateway.State.Deleting);
                    vpcGatewayDao.update(gatewayVO.getId(), gatewayVO);
                    logger.debug("Marked gateway " + gatewayVO + " with state " + VpcGateway.State.Deleting);
                }
            });

            // 1) delete the gateway on the backend
            final List<Provider> providersToImplement = getVpcProvidersForVpc(gatewayVO.getVpcId());
            final PrivateGateway gateway = getVpcPrivateGateway(gatewayId);
            for (final VpcProvider provider : vpcManager.getVpcElements()) {
                if (providersToImplement.contains(provider.getProvider())) {
                    if (provider.deletePrivateGateway(gateway)) {
                        logger.debug("Private gateway " + gateway + " was applied successfully on the backend");
                    } else {
                        logger.warn("Private gateway " + gateway + " failed to apply on the backend");
                        gatewayVO.setState(VpcGateway.State.Ready);
                        vpcGatewayDao.update(gatewayVO.getId(), gatewayVO);
                        logger.debug("Marked gateway " + gatewayVO + " with state " + VpcGateway.State.Ready);

                        return false;
                    }
                }
            }

            // 2) Clean up any remaining routes
            cleanUpRoutesByGatewayId(gatewayId);

            // 3) Delete private gateway from the DB
            return deletePrivateGatewayFromTheDB(gateway);

        } finally {
            if (gatewayVO != null) {
                vpcGatewayDao.releaseFromLockTable(gatewayId);
            }
        }
    }

    private void cleanUpRoutesByGatewayId(long gatewayId) {
        List<StaticRouteVO> routes = staticRouteDao.listByGatewayId(gatewayId);
        for (StaticRouteVO route : routes) {
            staticRouteDao.remove(route.getId());
        }
    }

    @DB
    protected boolean deletePrivateGatewayFromTheDB(final PrivateGateway gateway) {
        // check if there are ips allocted in the network
        final long networkId = gateway.getNetworkId();
        NetworkVO network = networkDao.findById(networkId);

        vpcTxCallable.setGateway(gateway);

        final ExecutorService txExecutor = Executors.newSingleThreadExecutor();
        final Future<Boolean> futureResult = txExecutor.submit(vpcTxCallable);

        boolean deleteNetworkFinal;
        try {
            deleteNetworkFinal = futureResult.get();
            if (deleteNetworkFinal) {
                final User callerUser = accountMgr.getActiveUser(CallContext.current().getCallingUserId());
                final Account owner = accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM);
                final ReservationContext context = new ReservationContextImpl(null, null, callerUser, owner);
                networkMgr.destroyNetwork(networkId, context, false);
                logger.debug("Deleted private network {}", network);
            }
        } catch (final InterruptedException | ExecutionException e) {
            logger.error("deletePrivateGatewayFromTheDB failed to delete network {} due to => ", network, e);
        }

        return true;
    }

    @Override
    public Pair<List<PrivateGateway>, Integer> listPrivateGateway(final ListPrivateGatewaysCmd cmd) {
        final String ipAddress = cmd.getIpAddress();
        final String vlan = cmd.getVlan();
        final Long vpcId = cmd.getVpcId();
        final Long id = cmd.getId();
        Boolean isRecursive = cmd.isRecursive();
        final Boolean listAll = cmd.listAll();
        Long domainId = cmd.getDomainId();
        final String accountName = cmd.getAccountName();
        final Account caller = CallContext.current().getCallingAccount();
        final List<Long> permittedAccounts = new ArrayList<Long>();
        final String state = cmd.getState();
        final Long projectId = cmd.getProjectId();

        final Filter searchFilter = new Filter(VpcGatewayVO.class, "id", false, cmd.getStartIndex(), cmd.getPageSizeVal());
        final Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<Long, Boolean, ListProjectResourcesCriteria>(domainId, isRecursive,
                null);
        accountMgr.buildACLSearchParameters(caller, id, accountName, projectId, permittedAccounts, domainIdRecursiveListProject, listAll, false);
        domainId = domainIdRecursiveListProject.first();
        isRecursive = domainIdRecursiveListProject.second();
        final ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        final SearchBuilder<VpcGatewayVO> sb = vpcGatewayDao.createSearchBuilder();
        accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        if (vlan != null) {
            final SearchBuilder<NetworkVO> ntwkSearch = networkDao.createSearchBuilder();
            ntwkSearch.and("vlan", ntwkSearch.entity().getBroadcastUri(), SearchCriteria.Op.EQ);
            sb.join("networkSearch", ntwkSearch, sb.entity().getNetworkId(), ntwkSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        final SearchCriteria<VpcGatewayVO> sc = sb.create();
        accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        if (id != null) {
            sc.addAnd("id", Op.EQ, id);
        }

        if (ipAddress != null) {
            sc.addAnd("ip4Address", Op.EQ, ipAddress);
        }

        if (state != null) {
            sc.addAnd("state", Op.EQ, state);
        }

        if (vpcId != null) {
            sc.addAnd("vpcId", Op.EQ, vpcId);
        }

        if (vlan != null) {
            sc.setJoinParameters("networkSearch", "vlan", BroadcastDomainType.Vlan.toUri(vlan));
        }

        final Pair<List<VpcGatewayVO>, Integer> vos = vpcGatewayDao.searchAndCount(sc, searchFilter);
        final List<PrivateGateway> privateGtws = new ArrayList<PrivateGateway>(vos.first().size());
        for (final VpcGateway vo : vos.first()) {
            privateGtws.add(getPrivateGatewayProfile(vo));
        }

        return new Pair<List<PrivateGateway>, Integer>(privateGtws, vos.second());
    }

    private List<Provider> getVpcProvidersForVpc(final long vpcId) {
        final List<String> providerNames = vpcSrvcMapDao.getDistinctProviders(vpcId);
        final Map<String, Provider> providers = new HashMap<String, Provider>();
        for (final String providerName : providerNames) {
            if (!providers.containsKey(providerName)) {
                providers.put(providerName, Network.Provider.getProvider(providerName));
            }
        }

        return new ArrayList<Provider>(providers.values());
    }
}
