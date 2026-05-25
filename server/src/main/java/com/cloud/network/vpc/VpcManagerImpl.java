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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.cloud.event.UsageEventUtils;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.alert.AlertService;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.vpc.CloneVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.CreateVPCCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vpc.CreateVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.UpdateVPCOfferingCmd;
import org.apache.cloudstack.api.command.user.vpc.CreatePrivateGatewayCmd;
import org.apache.cloudstack.api.command.user.vpc.CreateVPCCmd;
import org.apache.cloudstack.api.command.user.vpc.ListPrivateGatewaysCmd;
import org.apache.cloudstack.api.command.user.vpc.ListStaticRoutesCmd;
import org.apache.cloudstack.api.command.user.vpc.ListVPCOfferingsCmd;
import org.apache.cloudstack.api.command.user.vpc.ListVPCsCmd;
import org.apache.cloudstack.api.command.user.vpc.RestartVPCCmd;
import org.apache.cloudstack.api.command.user.vpc.UpdateVPCCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.network.Ipv4GuestSubnetNetworkMap;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.manager.Commands;
import com.cloud.alert.AlertManager;
import com.cloud.api.query.dao.VpcOfferingJoinDao;
import com.cloud.bgp.BGPService;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.ASNumberVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.ASNumberDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.RemoteAccessVpnDao;
import com.cloud.network.dao.RemoteAccessVpnVO;
import com.cloud.network.dao.Site2SiteCustomerGatewayDao;
import com.cloud.network.dao.Site2SiteCustomerGatewayVO;
import com.cloud.network.dao.Site2SiteVpnConnectionDao;
import com.cloud.network.dao.Site2SiteVpnConnectionVO;
import com.cloud.network.element.NetworkACLServiceProvider;
import com.cloud.network.element.VpcProvider;
import com.cloud.network.router.CommandSetupHelper;
import com.cloud.network.router.NetworkHelper;
import com.cloud.network.router.VpcVirtualNetworkApplianceManager;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.vpn.RemoteAccessVpnService;
import com.cloud.network.vpc.VpcOffering.State;
import com.cloud.network.vpc.dao.NetworkACLDao;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.network.vpc.dao.StaticRouteDao;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcGatewayDao;
import com.cloud.network.vpc.dao.VpcOfferingDao;
import com.cloud.network.vpc.dao.VpcOfferingDetailsDao;
import com.cloud.network.vpc.dao.VpcOfferingServiceMapDao;
import com.cloud.network.vpc.dao.VpcServiceMapDao;
import com.cloud.network.vpn.Site2SiteVpnManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingServiceMapVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.org.Grouping;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.utils.DomainHelper;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;


public class VpcManagerImpl extends ManagerBase implements VpcManager, VpcProvisioningService, VpcService, Configurable {

    public static final String SERVICE = "service";
    public static final String CAPABILITYTYPE = "capabilitytype";
    public static final String CAPABILITYVALUE = "capabilityvalue";
    public static final String TRUE_VALUE = "true";
    public static final String FALSE_VALUE = "false";

    @Inject
    EntityManager _entityMgr;
    @Inject
    VpcOfferingDao _vpcOffDao;
    @Inject
    VpcOfferingJoinDao vpcOfferingJoinDao;
    @Inject
    VpcOfferingDetailsDao vpcOfferingDetailsDao;
    @Inject
    VpcOfferingServiceMapDao _vpcOffSvcMapDao;
    @Inject
    VpcDao vpcDao;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    AccountManager _accountMgr;
    @Inject
    NetworkDao _ntwkDao;
    @Inject
    NetworkOfferingDao networkOfferingDao;
    @Inject
    NetworkOrchestrationService _ntwkMgr;
    @Inject
    NetworkModel _ntwkModel;
    @Inject
    NetworkService _ntwkSvc;
    @Inject
    IPAddressDao _ipAddressDao;
    @Inject
    VpcGatewayDao _vpcGatewayDao;
    @Inject
    PrivateIpDao _privateIpDao;
    @Inject
    StaticRouteDao _staticRouteDao;
    @Inject
    NetworkOfferingServiceMapDao _ntwkOffServiceDao;
    @Inject
    VpcOfferingServiceMapDao _vpcOffServiceDao;
    @Inject
    ResourceTagDao _resourceTagDao;
    @Inject
    FirewallRulesDao _firewallDao;
    @Inject
    Site2SiteVpnManager _s2sVpnMgr;
    @Inject
    VlanDao _vlanDao = null;
    @Inject
    ResourceLimitService _resourceLimitMgr;
    @Inject
    ReservationDao reservationDao;
    @Inject
    VpcServiceMapDao _vpcSrvcDao;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    NetworkACLDao _networkAclDao;
    @Inject
    NetworkACLManager _networkAclMgr;
    @Inject
    IpAddressManager _ipAddrMgr;
    @Inject
    VpcVirtualNetworkApplianceManager _routerService;
    @Inject
    DomainRouterDao routerDao;
    @Inject
    DomainDao domainDao;
    @Inject
    DomainHelper domainHelper;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    NicDao nicDao;
    @Inject
    AlertManager alertManager;
    @Inject
    CommandSetupHelper commandSetupHelper;
    @Autowired
    @Qualifier("networkHelper")
    protected NetworkHelper networkHelper;
    @Inject
    private BGPService bgpService;
    @Inject
    private ASNumberDao asNumberDao;
    @Inject
    private VpcPrivateGatewayTransactionCallable vpcTxCallable;
    @Inject
    private NsxProviderDao nsxProviderDao;
    @Inject
    private NetrisProviderDao netrisProviderDao;
    @Inject
    RoutedIpv4Manager routedIpv4Manager;
    @Inject
    DomainRouterDao domainRouterDao;
    @Inject
    RulesManager rulesManager;
    @Inject
    VMInstanceDao vmInstanceDao;
    @Inject
    RemoteAccessVpnDao remoteAccessVpnDao;
    @Inject
    RemoteAccessVpnService remoteAccessVpnMgr;
    @Inject
    Site2SiteVpnConnectionDao site2SiteVpnConnectionDao;
    @Inject
    Site2SiteCustomerGatewayDao site2SiteCustomerGatewayDao;
    @Inject
    StaticRouteService staticRouteService;
    @Inject
    PrivateGatewayService privateGatewayService;
    @Inject
    VpcIpAllocationService vpcIpAllocationService;
    @Inject
    protected VpcOfferingQueryService vpcOfferingQueryService;
    @Inject
    protected VpcOfferingCrudService vpcOfferingCrudService;

    private final ScheduledExecutorService _executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("VpcChecker"));
    private List<VpcProvider> vpcElements = null;
    private final List<Service> nonSupportedServices = Arrays.asList(Service.SecurityGroup, Service.Firewall);
    private final List<Provider> supportedProviders = Arrays.asList(Provider.VPCVirtualRouter, Provider.InternalLbVm, Provider.Netscaler,
            Provider.Ovs, Provider.ConfigDrive, Provider.Nsx, Provider.Netris);

    int _cleanupInterval;
    int _maxNetworks;
    SearchBuilder<IPAddressVO> IpAddressSearch;

    protected final List<HypervisorType> hTypes = new ArrayList<HypervisorType>();

    @PostConstruct
    protected void setupSupportedVpcHypervisorsList() {
        hTypes.add(HypervisorType.XenServer);
        hTypes.add(HypervisorType.VMware);
        hTypes.add(HypervisorType.KVM);
        hTypes.add(HypervisorType.Simulator);
        hTypes.add(HypervisorType.LXC);
        hTypes.add(HypervisorType.Hyperv);
        hTypes.add(HypervisorType.External);
    }

    private void checkVpcDns(VpcOffering vpcOffering, String ip4Dns1, String ip4Dns2, String ip6Dns1, String ip6Dns2) {
        if (ObjectUtils.anyNotNull(ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2) && !areServicesSupportedByVpcOffering(vpcOffering.getId(), Service.Dns)) {
            throw new InvalidParameterValueException("DNS can not be specified for VPCs with offering that do not support DNS service");
        }
        if (!_vpcOffDao.isIpv6Supported(vpcOffering.getId()) && !org.apache.commons.lang3.StringUtils.isAllBlank(ip6Dns1, ip6Dns2)) {
            throw new InvalidParameterValueException("IPv6 DNS can be specified for IPv6 enabled VPC");
        }
        _ntwkModel.verifyIp4DnsPair(ip4Dns1, ip4Dns2);
        _ntwkModel.verifyIp6DnsPair(ip6Dns1, ip6Dns2);
    }

    @Override
    @DB
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        // configure default vpc offering
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {

                if (_vpcOffDao.findByUniqueName(VpcOffering.defaultVPCOfferingName) == null) {
                    logger.debug("Creating default VPC offering " + VpcOffering.defaultVPCOfferingName);

                    final Map<Service, Set<Provider>> svcProviderMap = new HashMap<Service, Set<Provider>>();
                    final Set<Provider> defaultProviders = new HashSet<Provider>();
                    defaultProviders.add(Provider.VPCVirtualRouter);
                    for (final Service svc : getSupportedServices()) {
                        if (svc == Service.Lb) {
                            final Set<Provider> lbProviders = new HashSet<Provider>();
                            lbProviders.add(Provider.VPCVirtualRouter);
                            lbProviders.add(Provider.InternalLbVm);
                            svcProviderMap.put(svc, lbProviders);
                        } else {
                            svcProviderMap.put(svc, defaultProviders);
                        }
                    }
                    vpcOfferingCrudService.createVpcOfferingInternal(VpcOffering.defaultVPCOfferingName, VpcOffering.defaultVPCOfferingName, svcProviderMap,
                            true, State.Enabled, null, false,
                            false, false, null, null, false, false);
                }

                // configure default vpc offering with Netscaler as LB Provider
                if (_vpcOffDao.findByUniqueName(VpcOffering.defaultVPCNSOfferingName) == null) {
                    logger.debug("Creating default VPC offering with Netscaler as LB Provider" + VpcOffering.defaultVPCNSOfferingName);
                    final Map<Service, Set<Provider>> svcProviderMap = new HashMap<Service, Set<Provider>>();
                    final Set<Provider> defaultProviders = new HashSet<Provider>();
                    defaultProviders.add(Provider.VPCVirtualRouter);
                    for (final Service svc : getSupportedServices()) {
                        if (svc == Service.Lb) {
                            final Set<Provider> lbProviders = new HashSet<Provider>();
                            lbProviders.add(Provider.Netscaler);
                            lbProviders.add(Provider.InternalLbVm);
                            svcProviderMap.put(svc, lbProviders);
                        } else {
                            svcProviderMap.put(svc, defaultProviders);
                        }
                    }
                    vpcOfferingCrudService.createVpcOfferingInternal(VpcOffering.defaultVPCNSOfferingName, VpcOffering.defaultVPCNSOfferingName,
                            svcProviderMap, false, State.Enabled, null, false, false, false, null, null, false, false);

                }

                if (_vpcOffDao.findByUniqueName(VpcOffering.redundantVPCOfferingName) == null) {
                    logger.debug("Creating Redundant VPC offering " + VpcOffering.redundantVPCOfferingName);

                    final Map<Service, Set<Provider>> svcProviderMap = new HashMap<Service, Set<Provider>>();
                    final Set<Provider> defaultProviders = new HashSet<Provider>();
                    defaultProviders.add(Provider.VPCVirtualRouter);
                    for (final Service svc : getSupportedServices()) {
                        if (svc == Service.Lb) {
                            final Set<Provider> lbProviders = new HashSet<Provider>();
                            lbProviders.add(Provider.VPCVirtualRouter);
                            lbProviders.add(Provider.InternalLbVm);
                            svcProviderMap.put(svc, lbProviders);
                        } else {
                            svcProviderMap.put(svc, defaultProviders);
                        }
                    }
                    vpcOfferingCrudService.createVpcOfferingInternal(VpcOffering.redundantVPCOfferingName, VpcOffering.redundantVPCOfferingName, svcProviderMap, true, State.Enabled,
                            null, false, false, true, null, null, false, false);
                }

                // configure default vpc offering with NSX as network service provider in NAT mode
                if (_vpcOffDao.findByUniqueName(VpcOffering.DEFAULT_VPC_NAT_NSX_OFFERING_NAME) == null) {
                    logger.debug("Creating default VPC offering with NSX as network service provider" + VpcOffering.DEFAULT_VPC_NAT_NSX_OFFERING_NAME);
                    final Map<Service, Set<Provider>> svcProviderMap = new HashMap<Service, Set<Provider>>();
                    final Set<Provider> defaultProviders = Set.of(Provider.Nsx);
                    for (final Service svc : getSupportedServices()) {
                        if (List.of(Service.UserData, Service.Dhcp, Service.Dns).contains(svc)) {
                            final Set<Provider> userDataProvider = Set.of(Provider.VPCVirtualRouter);
                            svcProviderMap.put(svc, userDataProvider);
                        } else {
                            svcProviderMap.put(svc, defaultProviders);
                        }
                    }
                    vpcOfferingCrudService.createVpcOfferingInternal(VpcOffering.DEFAULT_VPC_NAT_NSX_OFFERING_NAME, VpcOffering.DEFAULT_VPC_NAT_NSX_OFFERING_NAME, svcProviderMap, false,
                            State.Enabled, null, false, false, false, NetworkOffering.NetworkMode.NATTED, null, false, false);

                }

                // configure default vpc offering with NSX as network service provider in Route mode
                if (_vpcOffDao.findByUniqueName(VpcOffering.DEFAULT_VPC_ROUTE_NSX_OFFERING_NAME) == null) {
                    logger.debug("Creating default VPC offering with NSX as network service provider" + VpcOffering.DEFAULT_VPC_ROUTE_NSX_OFFERING_NAME);
                    final Map<Service, Set<Provider>> svcProviderMap = new HashMap<>();
                    final Set<Provider> defaultProviders = Set.of(Provider.Nsx);
                    for (final Service svc : getSupportedServices()) {
                        if (List.of(Service.UserData, Service.Dhcp, Service.Dns).contains(svc)) {
                            final Set<Provider> userDataProvider = Set.of(Provider.VPCVirtualRouter);
                            svcProviderMap.put(svc, userDataProvider);
                        } else if (List.of(Service.SourceNat, Service.NetworkACL).contains(svc)){
                            svcProviderMap.put(svc, defaultProviders);
                        }
                    }
                    vpcOfferingCrudService.createVpcOfferingInternal(VpcOffering.DEFAULT_VPC_ROUTE_NSX_OFFERING_NAME, VpcOffering.DEFAULT_VPC_ROUTE_NSX_OFFERING_NAME, svcProviderMap, false,
                            State.Enabled, null, false, false, false, NetworkOffering.NetworkMode.ROUTED, null, false, false);

                }

                // configure default vpc offering with Netris as network service provider in Route mode
                if (_vpcOffDao.findByUniqueName(VpcOffering.DEFAULT_VPC_ROUTE_NETRIS_OFFERING_NAME) == null) {
                    logger.debug(String.format("Creating default VPC offering for Netris network service provider %s in Routed mode", VpcOffering.DEFAULT_VPC_ROUTE_NETRIS_OFFERING_NAME));
                    final Map<Service, Set<Provider>> svcProviderMap = new HashMap<>();
                    final Set<Provider> defaultProviders = Set.of(Provider.Netris);
                    for (final Service svc : getSupportedServices()) {
                        if (List.of(Service.UserData, Service.Dhcp, Service.Dns).contains(svc)) {
                            final Set<Provider> userDataProvider = Set.of(Provider.VPCVirtualRouter);
                            svcProviderMap.put(svc, userDataProvider);
                        } else if (List.of(Service.SourceNat, Service.NetworkACL).contains(svc)){
                            svcProviderMap.put(svc, defaultProviders);
                        }
                    }
                    vpcOfferingCrudService.createVpcOfferingInternal(VpcOffering.DEFAULT_VPC_ROUTE_NETRIS_OFFERING_NAME, VpcOffering.DEFAULT_VPC_ROUTE_NETRIS_OFFERING_NAME, svcProviderMap, false,
                            State.Enabled, null, false, false, false, NetworkOffering.NetworkMode.ROUTED, null, false, false);

                }

                // configure default vpc offering with Netris as network service provider in NAT mode
                if (_vpcOffDao.findByUniqueName(VpcOffering.DEFAULT_VPC_NAT_NETRIS_OFFERING_NAME) == null) {
                    logger.debug(String.format("Creating default VPC offering for Netris network service provider %s in NAT mode", VpcOffering.DEFAULT_VPC_NAT_NETRIS_OFFERING_NAME));
                    final Map<Service, Set<Provider>> svcProviderMap = new HashMap<>();
                    final Set<Provider> defaultProviders = Set.of(Provider.Netris);
                    for (final Service svc : getSupportedServices()) {
                        if (List.of(Service.UserData, Service.Dhcp, Service.Dns, Service.Vpn).contains(svc)) {
                            final Set<Provider> userDataProvider = Set.of(Provider.VPCVirtualRouter);
                            svcProviderMap.put(svc, userDataProvider);
                        } else {
                            svcProviderMap.put(svc, defaultProviders);
                        }
                    }
                    vpcOfferingCrudService.createVpcOfferingInternal(VpcOffering.DEFAULT_VPC_NAT_NETRIS_OFFERING_NAME, VpcOffering.DEFAULT_VPC_NAT_NETRIS_OFFERING_NAME, svcProviderMap, false,
                            State.Enabled, null, false, false, false, NetworkOffering.NetworkMode.NATTED, null, false, false);

                }
            }
        });

        final Map<String, String> configs = _configDao.getConfiguration(params);
        final String value = configs.get(Config.VpcCleanupInterval.key());
        _cleanupInterval = NumbersUtil.parseInt(value, 60 * 60); // 1 hour

        final String maxNtwks = configs.get(Config.VpcMaxNetworks.key());
        _maxNetworks = NumbersUtil.parseInt(maxNtwks, 3); // max=3 is default

        IpAddressSearch = _ipAddressDao.createSearchBuilder();
        IpAddressSearch.and("accountId", IpAddressSearch.entity().getAllocatedToAccountId(), Op.EQ);
        IpAddressSearch.and("dataCenterId", IpAddressSearch.entity().getDataCenterId(), Op.EQ);
        IpAddressSearch.and("vpcId", IpAddressSearch.entity().getVpcId(), Op.EQ);
        IpAddressSearch.and("associatedWithNetworkId", IpAddressSearch.entity().getAssociatedWithNetworkId(), Op.EQ);
        final SearchBuilder<VlanVO> virtualNetworkVlanSB = _vlanDao.createSearchBuilder();
        virtualNetworkVlanSB.and("vlanType", virtualNetworkVlanSB.entity().getVlanType(), Op.EQ);
        IpAddressSearch
                .join("virtualNetworkVlanSB", virtualNetworkVlanSB, IpAddressSearch.entity().getVlanId(), virtualNetworkVlanSB.entity().getId(), JoinBuilder.JoinType.INNER);
        IpAddressSearch.done();

        return true;
    }

    @Override
    public boolean start() {
        _executor.scheduleAtFixedRate(new VpcCleanupTask(), _cleanupInterval, _cleanupInterval, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public List<? extends Network> getVpcNetworks(final long vpcId) {
        return _ntwkDao.listByVpc(vpcId);
    }

    @Override
    public VpcOffering getVpcOffering(final long vpcOffId) {
        return vpcOfferingQueryService.getVpcOffering(vpcOffId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_CREATE, eventDescription = "creating vpc offering", create = true)
    public VpcOffering createVpcOffering(CreateVPCOfferingCmd cmd) {
        return vpcOfferingCrudService.createVpcOffering(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_CREATE, eventDescription = "creating vpc offering", create = true)
    public VpcOffering createVpcOffering(final String name, final String displayText, final List<String> supportedServices, final Map<String, List<String>> serviceProviders,
                                         final Map serviceCapabilityList, final NetUtils.InternetProtocol internetProtocol, final Long serviceOfferingId,
                                         final String externalProvider, final NetworkOffering.NetworkMode networkMode, List<Long> domainIds, List<Long> zoneIds, State state,
                                         NetworkOffering.RoutingMode routingMode, boolean specifyAsNumber, boolean conserveMode) {
        return vpcOfferingCrudService.createVpcOffering(name, displayText, supportedServices, serviceProviders, serviceCapabilityList,
                internetProtocol, serviceOfferingId, externalProvider, networkMode, domainIds, zoneIds, state, routingMode, specifyAsNumber, conserveMode);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_CLONE, eventDescription = "cloning VPC offering")
    public VpcOffering cloneVPCOffering(CloneVPCOfferingCmd cmd) {
        return vpcOfferingCrudService.cloneVPCOffering(cmd);
    }

    @Override
    public Vpc getActiveVpc(final long vpcId) {
        return vpcDao.getActiveVpcById(vpcId);
    }

    @Override
    public Map<Service, Set<Provider>> getVpcOffSvcProvidersMap(final long vpcOffId) {
        return vpcOfferingQueryService.getVpcOffSvcProvidersMap(vpcOffId);
    }

    @Override
    public Pair<List<? extends VpcOffering>, Integer> listVpcOfferings(ListVPCOfferingsCmd cmd) {
        return vpcOfferingQueryService.listVpcOfferings(cmd);
    }

    protected boolean areServicesSupportedByVpcOffering(final long vpcOffId, final Service... services) {
        return vpcOfferingQueryService.areServicesSupportedByVpcOffering(vpcOffId, services);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_DELETE, eventDescription = "deleting vpc offering")
    public boolean deleteVpcOffering(final long offId) {
        return vpcOfferingCrudService.deleteVpcOffering(offId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_UPDATE, eventDescription = "updating vpc offering")
    public VpcOffering updateVpcOffering(long vpcOffId, String vpcOfferingName, String displayText, String state) {
        return vpcOfferingCrudService.updateVpcOffering(vpcOffId, vpcOfferingName, displayText, state);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_UPDATE, eventDescription = "updating vpc offering")
    public VpcOffering updateVpcOffering(final UpdateVPCOfferingCmd cmd) {
        return vpcOfferingCrudService.updateVpcOffering(cmd);
    }


    @Override
    public List<Long> getVpcOfferingDomains(Long vpcOfferingId) {
        return vpcOfferingQueryService.getVpcOfferingDomains(vpcOfferingId);
    }

    @Override
    public List<Long> getVpcOfferingZones(Long vpcOfferingId) {
        return vpcOfferingQueryService.getVpcOfferingZones(vpcOfferingId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_CREATE, eventDescription = "creating vpc", create = true)
    public Vpc createVpc(final long zoneId, final long vpcOffId, final long vpcOwnerId, final String vpcName, final String displayText, final String cidr, String networkDomain,
                         final String ip4Dns1, final String ip4Dns2, final String ip6Dns1, final String ip6Dns2, final Boolean displayVpc, Integer publicMtu,
                         final Integer cidrSize, final Long asNumber, final List<Long> bgpPeerIds, Boolean useVrIpResolver, boolean keepMacAddressOnPublicNic) throws ResourceAllocationException {
        final Account caller = CallContext.current().getCallingAccount();
        final Account owner = _accountMgr.getAccount(vpcOwnerId);

        // Verify that caller can perform actions in behalf of vpc owner
        _accountMgr.checkAccess(caller, null, false, owner);

        // Validate zone
        final DataCenter zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Can't find zone by id specified");
        }

        // Validate vpc offering
        final VpcOfferingVO vpcOff = _vpcOffDao.findById(vpcOffId);
        _accountMgr.checkAccess(owner, vpcOff, zone);
        if (vpcOff == null || vpcOff.getState() != State.Enabled) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find vpc offering in " + State.Enabled + " state by specified id");
            if (vpcOff == null) {
                ex.addProxyObject(String.valueOf(vpcOffId), "vpcOfferingId");
            } else {
                ex.addProxyObject(vpcOff.getUuid(), "vpcOfferingId");
            }
            throw ex;
        }

        if (NetworkOffering.NetworkMode.ROUTED.equals(vpcOff.getNetworkMode())
                && !routedIpv4Manager.RoutedNetworkVpcEnabled.valueIn(zoneId)) {
            throw new InvalidParameterValueException("Routed VPC is not enabled in this zone");
        }

        if (NetworkOffering.RoutingMode.Dynamic.equals(vpcOff.getRoutingMode()) && vpcOff.isSpecifyAsNumber() && asNumber == null) {
            throw new InvalidParameterValueException("AS number is required for the VPC but not passed.");
        }

        // Validate VPC cidr/cidrsize
        validateVpcCidrSize(caller, owner.getAccountId(), vpcOff, cidr, cidrSize, zoneId);

        // Validate BGP peers
        if (CollectionUtils.isNotEmpty(bgpPeerIds)) {
            if (!routedIpv4Manager.isDynamicRoutedVpc(vpcOff)) {
                throw new InvalidParameterValueException("The VPC offering does not support Dynamic routing");
            }
            routedIpv4Manager.validateBgpPeers(owner, zone.getId(), bgpPeerIds);
        }

        final boolean isRegionLevelVpcOff = vpcOff.isOffersRegionLevelVPC();
        if (isRegionLevelVpcOff && networkDomain == null) {
            throw new InvalidParameterValueException("Network domain must be specified for region level VPC");
        }

        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getId())) {
            // See DataCenterVO.java
            final PermissionDeniedException ex = new PermissionDeniedException("Cannot perform this operation since specified Zone is currently disabled");
            ex.addProxyObject(zone.getUuid(), "zoneId");
            throw ex;
        }

        if (networkDomain == null) {
            // 1) Get networkDomain from the corresponding account
            networkDomain = _ntwkModel.getAccountNetworkDomain(owner.getId(), zoneId);

            // 2) If null, generate networkDomain using domain suffix from the
            // global config variables
            if (networkDomain == null) {
                networkDomain = "cs" + Long.toHexString(owner.getId()) + NetworkOrchestrationService.GuestDomainSuffix.valueIn(zoneId);
            }
        }

        if (publicMtu > NetworkService.VRPublicInterfaceMtu.valueIn(zoneId)) {
            String subject = "Incorrect MTU configured on network for public interfaces of the VPC VR";
            String message = String.format("Configured MTU for network VR's public interfaces exceeds the upper limit " +
                            "enforced by zone level setting: %s. VR's public interfaces can be configured with a maximum MTU of %s", NetworkService.VRPublicInterfaceMtu.key(),
                    NetworkService.VRPublicInterfaceMtu.valueIn(zoneId));
            logger.warn(message);
            alertManager.sendAlert(AlertService.AlertType.ALERT_TYPE_VR_PUBLIC_IFACE_MTU, zoneId, null, subject, message);
            publicMtu = NetworkService.VRPublicInterfaceMtu.valueIn(zoneId);
        } else if (publicMtu < NetworkService.MINIMUM_MTU) {
            String subject = "Incorrect MTU configured on network for public interfaces of the VPC VR";
            String message = String.format("Configured MTU for network VR's public interfaces is lesser than the supported minim MTU of %s", NetworkService.MINIMUM_MTU);
            logger.warn(message);
            alertManager.sendAlert(AlertService.AlertType.ALERT_TYPE_VR_PUBLIC_IFACE_MTU, zoneId, null, subject, message);
            publicMtu = NetworkService.MINIMUM_MTU;
        }

        checkVpcDns(vpcOff, ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2);

        // validate network domain
        if (!NetUtils.verifyDomainName(networkDomain)) {
            throw new InvalidParameterValueException("Invalid network domain. Total length shouldn't exceed 190 chars. Each domain "
                    + "label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', " + "the digits '0' through '9', "
                    + "and the hyphen ('-'); can't start or end with \"-\"");
        }

        final boolean useDistributedRouter = vpcOff.isSupportsDistributedRouter();
        final VpcVO vpc = new VpcVO(zoneId, vpcName, displayText, owner.getId(), owner.getDomainId(), vpcOffId, cidr, networkDomain, useDistributedRouter, isRegionLevelVpcOff,
                vpcOff.isRedundantRouter(), ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2);
        vpc.setPublicMtu(publicMtu);
        vpc.setDisplay(Boolean.TRUE.equals(displayVpc));
        vpc.setUseRouterIpResolver(Boolean.TRUE.equals(useVrIpResolver));
        vpc.setKeepMacAddressOnPublicNic(keepMacAddressOnPublicNic);

        try (CheckedReservation vpcReservation = new CheckedReservation(owner, ResourceType.vpc, null, null, 1L, reservationDao, _resourceLimitMgr)) {
            if (vpc.getCidr() == null && cidrSize != null) {
                // Allocate a CIDR for VPC
                Ipv4GuestSubnetNetworkMap subnet = routedIpv4Manager.getOrCreateIpv4SubnetForVpc(vpc, cidrSize);
                if (subnet != null) {
                    vpc.setCidr(subnet.getSubnet());
                } else {
                    throw new CloudRuntimeException("Failed to allocate a CIDR with requested size for VPC.");
                }
            }

            Vpc newVpc = createVpc(displayVpc, vpc);
            // assign Ipv4 subnet to Routed VPC
            if (routedIpv4Manager.isRoutedVpc(vpc)) {
                routedIpv4Manager.assignIpv4SubnetToVpc(newVpc);
            }
            if (CollectionUtils.isNotEmpty(bgpPeerIds)) {
                routedIpv4Manager.persistBgpPeersForVpc(newVpc.getId(), bgpPeerIds);
            }
            return newVpc;
        }
    }

    private void validateVpcCidrSize(Account caller, long accountId, VpcOffering vpcOffering, String cidr, Integer cidrSize, long zoneId) {
        if (ObjectUtils.allNull(cidr, cidrSize)) {
            throw new InvalidParameterValueException("VPC cidr or cidr size must be specified");
        }
        if (ObjectUtils.allNotNull(cidr, cidrSize)) {
            throw new InvalidParameterValueException("VPC cidr and cidr size are mutually exclusive");
        }
        if (routedIpv4Manager.isValidGateway(vpcOffering)) {
            if (cidr != null) {
                if (!_accountMgr.isRootAdmin(caller.getId())) {
                    throw new InvalidParameterValueException("Only root admin can set the gateway/netmask of VPC with ROUTED mode");
                }
                return;
            }
            // verify VPC cidrsize
            Integer maxCidrSize = routedIpv4Manager.RoutedVpcIPv4MaxCidrSize.valueIn(accountId);
            if (cidrSize > maxCidrSize) {
                throw new InvalidParameterValueException("VPC cidr size cannot be bigger than maximum cidr size " + maxCidrSize);
            }
            Integer minCidrSize = routedIpv4Manager.RoutedVpcIPv4MinCidrSize.valueIn(accountId);
            if (cidrSize < minCidrSize) {
                throw new InvalidParameterValueException("VPC cidr size cannot be smaller than minimum cidr size " + minCidrSize);
            }
        } else {
            if (cidrSize != null) {
                throw new InvalidParameterValueException("VPC cidr size is only applicable on VPC with Routed mode");
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_CREATE, eventDescription = "creating vpc", create = true)
    public Vpc createVpc(CreateVPCCmd cmd) throws ResourceAllocationException {
        List<Long> bgpPeerIds = (cmd instanceof CreateVPCCmdByAdmin) ? ((CreateVPCCmdByAdmin)cmd).getBgpPeerIds() : null;
        Vpc vpc = createVpc(cmd.getZoneId(), cmd.getVpcOffering(), cmd.getEntityOwnerId(), cmd.getVpcName(), cmd.getDisplayText(),
            cmd.getCidr(), cmd.getNetworkDomain(), cmd.getIp4Dns1(), cmd.getIp4Dns2(), cmd.getIp6Dns1(),
            cmd.getIp6Dns2(), cmd.isDisplay(), cmd.getPublicMtu(), cmd.getCidrSize(), cmd.getAsNumber(), bgpPeerIds,
            cmd.getUseVrIpResolver(), cmd.getKeepMacAddressOnPublicNic());

        String sourceNatIP = cmd.getSourceNatIP();
        boolean forNsx = isVpcForProvider(Provider.Nsx, vpc);
        boolean forNetris = isVpcForProvider(Provider.Netris, vpc);
        try {
            if (sourceNatIP != null || forNsx || forNetris) {
                if (forNsx || forNetris) {
                    logger.info("Provided source NAT IP will be ignored in an NSX-enabled or Netris-enabled zone");
                    sourceNatIP = null;
                }
                logger.info(String.format("Trying to allocate the specified IP [%s] as the source NAT of VPC [%s].", sourceNatIP, vpc));
                allocateSourceNatIp(vpc, sourceNatIP);
            }
            if (isVpcOfferingDynamicRouting(vpc)) {
                bgpService.allocateASNumber(vpc.getZoneId(), cmd.getAsNumber(), null, vpc.getId());
            }
        } catch (CloudRuntimeException ex) {
            try {
                deleteVpc(vpc.getId());
            } catch (Exception ex2) {
                logger.error("Got exception when delete a VPC created just now: {}", ex2.getMessage());
            }
            throw ex;
        }
        return vpc;
    }

    private boolean isVpcOfferingDynamicRouting(Vpc vpc) {
        VpcOffering vpcOffering = getVpcOffering(vpc.getVpcOfferingId());
        if (vpcOffering == null) {
            logger.error(String.format("Cannot find VPC offering with ID %s", vpc.getVpcOfferingId()));
            return false;
        }
        return NetworkOffering.RoutingMode.Dynamic == vpcOffering.getRoutingMode();
    }

    private boolean isVpcForProvider(Provider provider, Vpc vpc) {
        if (vpc == null) {
            return false;
        }
        return _vpcOffSvcMapDao.isProviderForVpcOffering(provider, vpc.getVpcOfferingId());
    }

    private void allocateSourceNatIp(Vpc vpc, String sourceNatIP) {
        Account account = _accountMgr.getAccount(vpc.getAccountId());
        DataCenter zone = _dcDao.findById(vpc.getZoneId());
        // reserve this ip and then
        try {
            if (isVpcForProvider(Provider.Nsx, vpc) && org.apache.commons.lang3.StringUtils.isBlank(sourceNatIP)) {
                logger.debug(String.format("Reserving a source NAT IP for NSX VPC %s", vpc.getName()));
                sourceNatIP = reserveSourceNatIpForProviderVpc(account, zone, Provider.Nsx);
            } else if (isVpcForProvider(Provider.Netris, vpc) && org.apache.commons.lang3.StringUtils.isBlank(sourceNatIP)) {
                logger.debug(String.format("Reserving a source NAT IP for Netris VPC %s", vpc.getName()));
                sourceNatIP = reserveSourceNatIpForProviderVpc(account, zone, Provider.Netris);
            }
            IpAddress ip = _ipAddrMgr.allocateIp(account, false, CallContext.current().getCallingAccount(), CallContext.current().getCallingUser(), zone, null, sourceNatIP);
            this.associateIPToVpc(ip.getId(), vpc.getId());
        } catch (ResourceAllocationException | ResourceUnavailableException | InsufficientAddressCapacityException e){
            throw new CloudRuntimeException("new source NAT address cannot be acquired", e);
        }
    }

    private String reserveSourceNatIpForProviderVpc(Account account, DataCenter zone, Provider provider) throws ResourceAllocationException {
        String detailKey = provider == Provider.Nsx ? ApiConstants.NSX_DETAIL_KEY : ApiConstants.NETRIS_DETAIL_KEY;
        IpAddress ipAddress = _ntwkSvc.reserveIpAddressWithVlanDetail(account, zone, true, detailKey);
        return ipAddress.getAddress().addr();
    }

    @DB
    protected Vpc createVpc(final Boolean displayVpc, final VpcVO vpc) {
        final String cidr = vpc.getCidr();
        if (cidr != null) {
            // Validate CIDR
            if (!NetUtils.isValidIp4Cidr(cidr)) {
                throw new InvalidParameterValueException("Invalid CIDR specified " + cidr);
            }

            // cidr has to be RFC 1918 complient
            if (!NetUtils.validateGuestCidr(cidr, !ConfigurationManager.AllowNonRFC1918CompliantIPs.value())) {
                throw new InvalidParameterValueException("Guest Cidr " + cidr + " is not RFC1918 compliant");
            }
        }

        // get or create Ipv4 subnet for ROUTED VPC
        if (routedIpv4Manager.isRoutedVpc(vpc)) {
            routedIpv4Manager.getOrCreateIpv4SubnetForVpc(vpc, cidr);
        }

        VpcVO vpcVO = Transaction.execute(new TransactionCallback<VpcVO>() {
            @Override
            public VpcVO doInTransaction(final TransactionStatus status) {
                final VpcVO persistedVpc = vpcDao.persist(vpc, finalizeServicesAndProvidersForVpc(vpc.getZoneId(), vpc.getVpcOfferingId()));
                _resourceLimitMgr.incrementResourceCount(vpc.getAccountId(), ResourceType.vpc);
                logger.debug("Created VPC " + persistedVpc);
                CallContext.current().putContextParameter(Vpc.class, persistedVpc.getUuid());
                return persistedVpc;
            }
        });
        if (vpcVO != null) {
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VPC_CREATE, vpcVO.getAccountId(), vpcVO.getZoneId(), vpcVO.getId(), vpcVO.getName(), Vpc.class.getName(), vpcVO.getUuid(), vpcVO.isDisplay());
        }
        return vpcVO;
    }

    private Map<String, List<String>> finalizeServicesAndProvidersForVpc(final long zoneId, final long offeringId) {
        final Map<String, List<String>> svcProviders = new HashMap<>();
        final List<VpcOfferingServiceMapVO> servicesMap = _vpcOffSvcMapDao.listByVpcOffId(offeringId);

        for (final VpcOfferingServiceMapVO serviceMap : servicesMap) {
            final String service = serviceMap.getService();
            String provider = serviceMap.getProvider();

            if (provider == null) {
                // Default to VPCVirtualRouter
                provider = Provider.VPCVirtualRouter.getName();
            }

            if (!_ntwkModel.isProviderEnabledInZone(zoneId, provider)) {
                throw new InvalidParameterValueException("Provider " + provider + " should be enabled in at least one physical network of the zone specified");
            }

            List<String> providers = null;
            if (svcProviders.get(service) == null) {
                providers = new ArrayList<String>();
            } else {
                providers = svcProviders.get(service);
            }
            providers.add(provider);
            svcProviders.put(service, providers);
        }

        return svcProviders;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_DELETE, eventDescription = "deleting VPC")
    public boolean deleteVpc(final long vpcId) throws ConcurrentOperationException, ResourceUnavailableException {
        final CallContext ctx = CallContext.current();

        // Verify vpc id
        final Vpc vpc = vpcDao.findById(vpcId);
        if (vpc == null) {
            throw new InvalidParameterValueException("unable to find VPC id=" + vpcId);
        }

        CallContext.current().setEventDetails(" ID: " + vpc.getUuid());

        // verify permissions
        _accountMgr.checkAccess(ctx.getCallingAccount(), null, false, vpc);
        _resourceTagDao.removeByIdAndType(vpcId, ResourceObjectType.Vpc);

        return destroyVpc(vpc, ctx.getCallingAccount(), ctx.getCallingUserId());
    }

    @Override
    @DB
    public boolean destroyVpc(final Vpc vpc, final Account caller, final Long callerUserId) throws ConcurrentOperationException, ResourceUnavailableException {
        logger.debug("Destroying vpc " + vpc);

        // don't allow to delete vpc if it's in use by existing non system
        // networks (system networks are networks of a private gateway of the
        // VPC,
        // and they will get removed as a part of VPC cleanup
        final int networksCount = _ntwkDao.getNonSystemNetworkCountByVpcId(vpc.getId());
        if (networksCount > 0) {
            throw new InvalidParameterValueException("Can't delete VPC " + vpc + " as its used by " + networksCount + " networks");
        }

        // mark VPC as inactive
        if (vpc.getState() != Vpc.State.Inactive) {
            logger.debug("Updating VPC " + vpc + " with state " + Vpc.State.Inactive + " as a part of vpc delete");
            final VpcVO vpcVO = vpcDao.findById(vpc.getId());
            vpcVO.setState(Vpc.State.Inactive);

            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) {
                    vpcDao.update(vpc.getId(), vpcVO);

                    // decrement resource count
                    _resourceLimitMgr.decrementResourceCount(vpc.getAccountId(), ResourceType.vpc);
                }
            });
        }

        // shutdown VPC
        if (!shutdownVpc(vpc.getId())) {
            logger.warn("Failed to shutdown vpc " + vpc + " as a part of vpc destroy process");
            return false;
        }

        // cleanup vpc resources
        if (!cleanupVpcResources(vpc, caller, callerUserId)) {
            logger.warn("Failed to cleanup resources for vpc " + vpc);
            return false;
        }

        // update the instance with removed flag only when the cleanup is
        // executed successfully
        if (vpcDao.remove(vpc.getId())) {
            logger.debug("Vpc " + vpc + " is destroyed successfully");
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VPC_DELETE, vpc.getAccountId(), vpc.getZoneId(), vpc.getId(), vpc.getName(), Vpc.class.getName(), vpc.getUuid(), vpc.isDisplay());
            return true;
        } else {
            logger.warn("Vpc " + vpc + " failed to destroy");
            return false;
        }
    }

    @Override
    public Vpc updateVpc(UpdateVPCCmd cmd) throws ResourceUnavailableException, InsufficientCapacityException {
        return updateVpc(cmd.getId(), cmd.getVpcName(), cmd.getDisplayText(), cmd.getCustomId(),
                cmd.isDisplayVpc(), cmd.getPublicMtu(), cmd.getSourceNatIP(), cmd.getKeepMacAddressOnPublicNic());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_UPDATE, eventDescription = "updating vpc")
    public Vpc updateVpc(final long vpcId, final String vpcName, final String displayText, final String customId,
                         final Boolean displayVpc, Integer mtu, String sourceNatIp, Boolean keepMacAddressOnPublicNic) throws ResourceUnavailableException, InsufficientCapacityException {
        final Account caller = CallContext.current().getCallingAccount();

        // Verify input parameters
        final VpcVO vpcToUpdate = vpcDao.findById(vpcId);
        if (vpcToUpdate == null) {
            throw new InvalidParameterValueException("Unable to find vpc by id " + vpcId);
        }

        CallContext.current().setEventDetails(" ID: " + vpcToUpdate.getUuid());

        _accountMgr.checkAccess(caller, null, false, vpcToUpdate);

        final VpcVO vpc = vpcDao.createForUpdate(vpcId);
        String previousVpcName = vpcToUpdate.getName();

        if (vpcName != null) {
            vpc.setName(vpcName);
        }

        if (displayText != null) {
            vpc.setDisplayText(displayText);
        }

        if (customId != null) {
            vpc.setUuid(customId);
        }

        if (displayVpc != null) {
            vpc.setDisplay(displayVpc);
        }

        if (keepMacAddressOnPublicNic != null) {
            vpc.setKeepMacAddressOnPublicNic(keepMacAddressOnPublicNic);
        }

        mtu = validateMtu(vpcToUpdate, mtu);
        if (mtu != null) {
            updateMtuOfVpcNetwork(vpcToUpdate, vpc, mtu);
        }

        boolean restartRequired = checkAndUpdateRouterSourceNatIp(vpcToUpdate, sourceNatIp);

        if (vpcDao.update(vpcId, vpc) || restartRequired) { // Note that the update may fail because nothing has changed, other than the sourcenat ip
            logger.debug("Updated VPC {}", vpc);
            if (restartRequired) {
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format("restarting vpc %s/%s, due to changing sourcenat in Update VPC call", vpc.getName(), vpc.getUuid()));
                }
                final User callingUser = _accountMgr.getActiveUser(CallContext.current().getCallingUserId());
                restartVpc(vpcId, true, false, false, callingUser);
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("no restart needed.");
                    if (isVpcForProvider(Provider.Netris, vpcToUpdate)) {
                        final String aclProvider = _vpcSrvcDao.getProviderForServiceInVpc(vpc.getId(), Service.NetworkACL);
                        for (final VpcProvider provider : getVpcElements()) {
                            if ((provider instanceof NetworkACLServiceProvider && provider.getName().equalsIgnoreCase(aclProvider))) {
                                vpcToUpdate.setName(vpcName);
                                provider.updateVpc(vpcToUpdate, previousVpcName);
                                break;
                            }
                        }
                    }
                }
            }
            return vpcDao.findById(vpcId);
        } else if (isVpcForProvider(Provider.Nsx, vpcToUpdate)) {
            if (logger.isDebugEnabled()) {
                logger.debug("no restart needed.");
            }
            return vpcDao.findById(vpcId);
        } else {
            logger.error(String.format("failed to update vpc %s/%s",vpc.getName(), vpc.getUuid()));
            return null;
        }
    }

    private boolean checkAndUpdateRouterSourceNatIp(Vpc vpc, String sourceNatIp) {
        IPAddressVO requestedIp = validateSourceNatip(vpc, sourceNatIp);
        if (requestedIp == null) return false; // ip not associated with this network

        List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedVpc(vpc.getId(), true);
        if (! userIps.isEmpty()) {
            try {
                _ipAddrMgr.updateSourceNatIpAddress(requestedIp, userIps);
                if (isVpcForProvider(Provider.Nsx, vpc) || isVpcForProvider(Provider.Netris, vpc)) {
                    boolean isForNsx = _vpcOffSvcMapDao.isProviderForVpcOffering(Provider.Nsx, vpc.getVpcOfferingId());
                    String providerName = isForNsx ? Provider.Nsx.getName() : Provider.Netris.getName();
                    VpcProvider providerElement = (VpcProvider) _ntwkModel.getElementImplementingProvider(providerName);
                    if (Objects.nonNull(providerElement)) {
                        providerElement.updateVpcSourceNatIp(vpc, requestedIp);
                        return false;
                    }
                    return true;
                }
            } catch (Exception e) { // pokemon exception from transaction
                String msg = String.format("Update of source NAT ip to %s for network \"%s\"/%s failed due to %s",
                        requestedIp.getAddress().addr(), vpc.getName(), vpc.getUuid(), e.getLocalizedMessage());
                logger.error(msg);
                throw new CloudRuntimeException(msg, e);
            }
        }
        return true;
    }

    @Nullable
    protected IPAddressVO validateSourceNatip(Vpc vpc, String sourceNatIp) {
        if (sourceNatIp == null) {
            logger.trace(String.format("no source NAT ip given to update vpc %s with.", vpc.getName()));
            return null;
        } else {
            logger.info(String.format("updating VPC %s to have source NAT ip %s", vpc.getName(), sourceNatIp));
        }
        IPAddressVO requestedIp = getIpAddressVO(vpc, sourceNatIp);
        if (requestedIp == null) return null;
        // check if it is the current source NAT address
        if (requestedIp.isSourceNat()) {
            logger.info(String.format("IP address %s is already the source Nat address. Not updating!", sourceNatIp));
            return null;
        }
        if (_firewallDao.countRulesByIpId(requestedIp.getId()) > 0) {
            logger.info(String.format("IP address %s has firewall/portforwarding rules. Not updating!", sourceNatIp));
            return null;
        }
        return requestedIp;
    }

    @Nullable
    private IPAddressVO getIpAddressVO(Vpc vpc, String sourceNatIp) {
        // check if the address is already aqcuired for this network
        IPAddressVO requestedIp = _ipAddressDao.findByIp(sourceNatIp);
        if (requestedIp == null || requestedIp.getVpcId() == null || ! requestedIp.getVpcId().equals(vpc.getId())) {
            logger.warn(String.format("Source NAT IP %s is not associated with network %s/%s. It cannot be used as source NAT IP.",
                    sourceNatIp, vpc.getName(), vpc.getUuid()));
            return null;
        }
        return requestedIp;
    }

    protected Integer validateMtu(VpcVO vpcToUpdate, Integer mtu) {
        Long zoneId = vpcToUpdate.getZoneId();
        if (mtu == null || NetworkService.AllowUsersToSpecifyVRMtu.valueIn(zoneId)) {
            return null;
        }
        if (mtu > NetworkService.VRPublicInterfaceMtu.valueIn(zoneId)) {
            String subject = "Incorrect MTU configured on network for public interfaces of the VPC VR";
            String message = String.format("Configured MTU for network VR's public interfaces exceeds the upper limit " +
                            "enforced by zone level setting: %s. VR's public interfaces can be configured with a maximum MTU of %s", NetworkService.VRPublicInterfaceMtu.key(),
                    NetworkService.VRPublicInterfaceMtu.valueIn(zoneId));
            logger.warn(message);
            alertManager.sendAlert(AlertService.AlertType.ALERT_TYPE_VR_PUBLIC_IFACE_MTU, zoneId, null, subject, message);
            mtu = NetworkService.VRPublicInterfaceMtu.valueIn(zoneId);
        } else if (mtu < NetworkService.MINIMUM_MTU) {
            String subject = "Incorrect MTU configured on network for public interfaces of the VPC VR";
            String message = String.format("Configured MTU for network VR's public interfaces is lesser than the minimum MTU of %s", NetworkService.MINIMUM_MTU );
            logger.warn(message);
            alertManager.sendAlert(AlertService.AlertType.ALERT_TYPE_VR_PUBLIC_IFACE_MTU, zoneId, null, subject, message);
            mtu = NetworkService.MINIMUM_MTU;
        }
        if (Objects.equals(mtu, vpcToUpdate.getPublicMtu())) {
            logger.info(String.format("Desired MTU of %s already configured on the VPC public interfaces", mtu));
            mtu = null;
        }
        return mtu;
    }

    protected void updateMtuOfVpcNetwork(VpcVO vpcToUpdate, VpcVO vpc, Integer mtu) {
        List<IPAddressVO> ipAddresses = _ipAddressDao.listByAssociatedVpc(vpcToUpdate.getId(), null);
        long vpcId = vpcToUpdate.getId();
        Set<IpAddressTO> ips = new HashSet<>(ipAddresses.size());
        for (IPAddressVO ip : ipAddresses) {
            VlanVO vlan = _vlanDao.findById(ip.getVlanId());
            String vlanNetmask = vlan.getVlanNetmask();
            IpAddressTO to = new IpAddressTO(ip.getAddress().addr(), mtu, vlanNetmask);
            ips.add(to);
        }

        if (!ips.isEmpty()) {
            boolean success = updateMtuOnVpcVr(vpcId, ips);
            if (success) {
                updateVpcMtu(ips, mtu);
                vpc.setPublicMtu(mtu);
                List<NetworkVO> vpcTierNetworks = _ntwkDao.listByVpc(vpcId);
                for (NetworkVO network : vpcTierNetworks) {
                    network.setPublicMtu(mtu);
                    _ntwkDao.update(network.getId(), network);
                }
                logger.info("Successfully update MTU of VPC network");
            } else {
                throw new CloudRuntimeException("Failed to update MTU on the network");
            }
        }
    }

    private void updateVpcMtu(Set<IpAddressTO> ips, Integer publicMtu) {
        for (IpAddressTO ipAddress : ips) {
            NicVO nicVO = nicDao.findByIpAddressAndVmType(ipAddress.getPublicIp(), VirtualMachine.Type.DomainRouter);
            if (nicVO != null) {
                nicVO.setMtu(publicMtu);
                nicDao.update(nicVO.getId(), nicVO);
            }
        }
    }

    protected boolean updateMtuOnVpcVr(Long vpcId, Set<IpAddressTO> ips) {
        boolean success = false;
        List<DomainRouterVO> routers = routerDao.listByVpcId(vpcId);
        for (DomainRouterVO router : routers) {
            Commands cmds = new Commands(Command.OnError.Stop);
            commandSetupHelper.setupUpdateNetworkCommands(router, ips, cmds);
            try {
                networkHelper.sendCommandsToRouter(router, cmds);
                final Answer updateNetworkAnswer = cmds.getAnswer("updateNetwork");
                if (!(updateNetworkAnswer != null && updateNetworkAnswer.getResult())) {
                    logger.warn("Unable to update guest network on router " + router);
                    throw new CloudRuntimeException("Failed to update guest network with new MTU");
                }
                success = true;
            } catch (ResourceUnavailableException e) {
                logger.error(String.format("Failed to update network MTU for router %s due to %s", router, e.getMessage()));
            }
        }
        return success;
    }

    @Override
    public Pair<List<? extends Vpc>, Integer> listVpcs(ListVPCsCmd cmd) {
        return listVpcs(cmd.getId(), cmd.getVpcName(), cmd.getDisplayText(), cmd.getSupportedServices(), cmd.getCidr(), cmd.getVpcOffId(),
                cmd.getState(), cmd.getAccountName(), cmd.getDomainId(), cmd.getKeyword(), cmd.getStartIndex(), cmd.getPageSizeVal(),
                cmd.getZoneId(), cmd.isRecursive(), cmd.listAll(), cmd.getRestartRequired(), cmd.getTags(), cmd.getProjectId(),
                cmd.getDisplay());
    }
    @Override
    public Pair<List<? extends Vpc>, Integer> listVpcs(final Long id, final String vpcName, final String displayText, final List<String> supportedServicesStr, final String cidr,
                                                       final Long vpcOffId, final String state, final String accountName, Long domainId, final String keyword, final Long startIndex, final Long pageSizeVal,
                                                       final Long zoneId, Boolean isRecursive, final Boolean listAll, final Boolean restartRequired, final Map<String, String> tags, final Long projectId,
                                                       final Boolean display) {
        final Account caller = CallContext.current().getCallingAccount();
        final List<Long> permittedAccounts = new ArrayList<Long>();
        final Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<Long, Boolean, ListProjectResourcesCriteria>(domainId, isRecursive,
                null);
        _accountMgr.buildACLSearchParameters(caller, id, accountName, projectId, permittedAccounts, domainIdRecursiveListProject, listAll, false);
        domainId = domainIdRecursiveListProject.first();
        isRecursive = domainIdRecursiveListProject.second();
        final ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        final Filter searchFilter = new Filter(VpcVO.class, "created", false, null, null);

        final SearchBuilder<VpcVO> sb = vpcDao.createSearchBuilder();
        _accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("displayText", sb.entity().getDisplayText(), SearchCriteria.Op.LIKE);
        sb.and("vpcOfferingId", sb.entity().getVpcOfferingId(), SearchCriteria.Op.EQ);
        sb.and("zoneId", sb.entity().getZoneId(), SearchCriteria.Op.EQ);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);
        sb.and("restartRequired", sb.entity().isRestartRequired(), SearchCriteria.Op.EQ);
        sb.and("cidr", sb.entity().getCidr(), SearchCriteria.Op.EQ);
        sb.and("display", sb.entity().isDisplay(), SearchCriteria.Op.EQ);

        if (tags != null && !tags.isEmpty()) {
            final SearchBuilder<ResourceTagVO> tagSearch = _resourceTagDao.createSearchBuilder();
            for (int count = 0; count < tags.size(); count++) {
                tagSearch.or().op("key" + String.valueOf(count), tagSearch.entity().getKey(), SearchCriteria.Op.EQ);
                tagSearch.and("value" + String.valueOf(count), tagSearch.entity().getValue(), SearchCriteria.Op.EQ);
                tagSearch.cp();
            }
            tagSearch.and("resourceType", tagSearch.entity().getResourceType(), SearchCriteria.Op.EQ);
            sb.groupBy(sb.entity().getId());
            sb.join("tagSearch", tagSearch, sb.entity().getId(), tagSearch.entity().getResourceId(), JoinBuilder.JoinType.INNER);
        }

        // now set the SC criteria...
        final SearchCriteria<VpcVO> sc = sb.create();
        _accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (keyword != null) {
            final SearchCriteria<VpcVO> ssc = vpcDao.createSearchCriteria();
            ssc.addOr("displayText", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (vpcName != null) {
            sc.addAnd("name", SearchCriteria.Op.LIKE, "%" + vpcName + "%");
        }

        if (displayText != null) {
            sc.addAnd("displayText", SearchCriteria.Op.LIKE, "%" + displayText + "%");
        }

        if (tags != null && !tags.isEmpty()) {
            int count = 0;
            sc.setJoinParameters("tagSearch", "resourceType", ResourceObjectType.Vpc.toString());
            for (final Map.Entry<String, String> entry : tags.entrySet()) {
                sc.setJoinParameters("tagSearch", "key" + String.valueOf(count), entry.getKey());
                sc.setJoinParameters("tagSearch", "value" + String.valueOf(count), entry.getValue());
                count++;
            }
        }

        if (display != null) {
            sc.setParameters("display", display);
        }

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (vpcOffId != null) {
            sc.addAnd("vpcOfferingId", SearchCriteria.Op.EQ, vpcOffId);
        }

        if (zoneId != null) {
            sc.addAnd("zoneId", SearchCriteria.Op.EQ, zoneId);
        }

        if (state != null) {
            sc.addAnd("state", SearchCriteria.Op.EQ, state);
        }

        if (cidr != null) {
            sc.addAnd("cidr", SearchCriteria.Op.EQ, cidr);
        }

        if (restartRequired != null) {
            sc.addAnd("restartRequired", SearchCriteria.Op.EQ, restartRequired);
        }

        final List<VpcVO> vpcs = vpcDao.search(sc, searchFilter);

        // filter by supported services
        final boolean listBySupportedServices = supportedServicesStr != null && !supportedServicesStr.isEmpty() && !vpcs.isEmpty();

        if (listBySupportedServices) {
            final List<Vpc> supportedVpcs = new ArrayList<>();
            Service[] supportedServices = null;

            if (listBySupportedServices) {
                supportedServices = new Service[supportedServicesStr.size()];
                int i = 0;
                for (final String supportedServiceStr : supportedServicesStr) {
                    final Service service = Service.getService(supportedServiceStr);
                    if (service == null) {
                        throw new InvalidParameterValueException("Invalid service specified " + supportedServiceStr);
                    } else {
                        supportedServices[i] = service;
                    }
                    i++;
                }
            }

            for (final VpcVO vpc : vpcs) {
                if (areServicesSupportedByVpcOffering(vpc.getVpcOfferingId(), supportedServices)) {
                    supportedVpcs.add(vpc);
                }
            }

            final List<? extends Vpc> wPagination = StringUtils.applyPagination(supportedVpcs, startIndex, pageSizeVal);
            if (wPagination != null) {
                return new Pair<>(wPagination, supportedVpcs.size());
            }
            return new Pair<>(supportedVpcs, supportedVpcs.size());
        } else {
            final List<? extends Vpc> wPagination = StringUtils.applyPagination(vpcs, startIndex, pageSizeVal);
            if (wPagination != null) {
                return new Pair<>(wPagination, vpcs.size());
            }
            return new Pair<>(vpcs, vpcs.size());
        }
    }

    protected List<Service> getSupportedServices() {
        final List<Service> services = new ArrayList<>();
        services.add(Network.Service.Dhcp);
        services.add(Network.Service.Dns);
        services.add(Network.Service.UserData);
        services.add(Network.Service.NetworkACL);
        services.add(Network.Service.PortForwarding);
        services.add(Network.Service.Lb);
        services.add(Network.Service.SourceNat);
        services.add(Network.Service.StaticNat);
        services.add(Network.Service.Gateway);
        services.add(Network.Service.Vpn);
        return services;
    }

    @Override
    public boolean startVpc(final long vpcId, final boolean destroyOnFailure) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        final CallContext ctx = CallContext.current();
        final Account caller = ctx.getCallingAccount();
        final User callerUser = _accountMgr.getActiveUser(ctx.getCallingUserId());

        // check if vpc exists
        final Vpc vpc = getActiveVpc(vpcId);
        if (vpc == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find Enabled VPC by id specified");
            ex.addProxyObject(String.valueOf(vpcId), "VPC");
            throw ex;
        }

        // permission check
        _accountMgr.checkAccess(caller, null, false, vpc);

        final DataCenter dc = _entityMgr.findById(DataCenter.class, vpc.getZoneId());

        final DeployDestination dest = new DeployDestination(dc, null, null, null);
        final ReservationContext context = new ReservationContextImpl(null, null, callerUser, _accountMgr.getAccount(vpc.getAccountId()));

        boolean result = true;
        try {
            if (!startVpc(vpc, dest, context)) {
                logger.warn("Failed to start vpc " + vpc);
                result = false;
            }
        } catch (final Exception ex) {
            logger.warn("Failed to start vpc " + vpc + " due to ", ex);
            result = false;
        } finally {
            // do cleanup
            if (!result && destroyOnFailure) {
                logger.debug("Destroying vpc " + vpc + " that failed to start");
                if (destroyVpc(vpc, caller, callerUser.getId())) {
                    logger.warn("Successfully destroyed vpc " + vpc + " that failed to start");
                } else {
                    logger.warn("Failed to destroy vpc " + vpc + " that failed to start");
                }
            }
        }
        return result;
    }


    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_CREATE, eventDescription = "creating vpc", async = true)
    public void startVpc(final CreateVPCCmd cmd) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        if (!cmd.isStart()) {
            logger.debug("Not starting VPC as " + ApiConstants.START + "=false was passed to the API");
            return;
        }
        startVpc(cmd.getEntityId(), true);
    }

    protected boolean startVpc(final Vpc vpc, final DeployDestination dest, final ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException,
            InsufficientCapacityException {
        // deploy provider
        boolean success = true;
        final List<Provider> providersToImplement = getVpcProviders(vpc.getId());
        for (final VpcProvider element : getVpcElements()) {
            if (providersToImplement.contains(element.getProvider())) {
                if (element.implementVpc(vpc, dest, context)) {
                    logger.debug("Vpc " + vpc + " has started successfully");
                } else {
                    logger.warn("Vpc " + vpc + " failed to start");
                    success = false;
                }
            }
        }
        return success;
    }

    @Override
    public boolean shutdownVpc(final long vpcId) throws ConcurrentOperationException, ResourceUnavailableException {
        final CallContext ctx = CallContext.current();
        final Account caller = ctx.getCallingAccount();

        // check if vpc exists
        final Vpc vpc = vpcDao.findById(vpcId);
        if (vpc == null) {
            throw new InvalidParameterValueException("Unable to find vpc by id " + vpcId);
        }

        // permission check
        _accountMgr.checkAccess(caller, null, false, vpc);

        // shutdown provider
        logger.debug("Shutting down vpc " + vpc);
        // TODO - shutdown all vpc resources here (ACLs, gateways, etc)

        boolean success = true;
        final List<Provider> providersToImplement = getVpcProviders(vpc.getId());
        final ReservationContext context = new ReservationContextImpl(null, null, _accountMgr.getActiveUser(ctx.getCallingUserId()), caller);
        for (final VpcProvider element : getVpcElements()) {
            if (providersToImplement.contains(element.getProvider())) {
                if (element.shutdownVpc(vpc, context)) {
                    logger.debug("Vpc " + vpc + " has been shutdown successfully");
                } else {
                    logger.warn("Vpc " + vpc + " failed to shutdown");
                    success = false;
                }
            }
        }

        return success;
    }

    @DB
    @Override
    public void validateNtwkOffForNtwkInVpc(final Long networkId, final long newNtwkOffId, final String newCidr, final String newNetworkDomain, final Vpc vpc,
                                            final String gateway, final Account networkOwner, final Long aclId) {

        final NetworkOffering guestNtwkOff = _entityMgr.findById(NetworkOffering.class, newNtwkOffId);

        if (guestNtwkOff == null) {
            throw new InvalidParameterValueException("Can't find network offering by id specified");
        }

        if (networkId == null) {
            // 1) Validate attributes that has to be passed in when create new
            // guest network
            validateNewVpcGuestNetwork(newCidr, gateway, networkOwner, vpc, newNetworkDomain);
        }

        // 2) validate network offering attributes
        final List<Service> svcs = _ntwkModel.listNetworkOfferingServices(guestNtwkOff.getId());
        validateNtwkOffForVpc(guestNtwkOff, svcs);

        // 3) Check services/providers against VPC providers
        final List<NetworkOfferingServiceMapVO> networkProviders = _ntwkOffServiceDao.listByNetworkOfferingId(guestNtwkOff.getId());

        for (final NetworkOfferingServiceMapVO nSvcVO : networkProviders) {
            final String pr = nSvcVO.getProvider();
            final String service = nSvcVO.getService();
            if (_vpcOffServiceDao.findByServiceProviderAndOfferingId(service, pr, vpc.getVpcOfferingId()) == null) {
                throw new InvalidParameterValueException("Service/provider combination " + service + "/" + pr + " is not supported by VPC " + vpc);
            }
        }

        // 4) Only one network in the VPC can support public LB inside the VPC.
        // Internal LB can be supported on multiple VPC tiers
        if (_ntwkModel.areServicesSupportedByNetworkOffering(guestNtwkOff.getId(), Service.Lb) && guestNtwkOff.isPublicLb()) {
            final List<? extends Network> networks = getVpcNetworks(vpc.getId());
            for (final Network network : networks) {
                if (networkId != null && network.getId() == networkId.longValue()) {
                    // skip my own network
                    continue;
                } else {
                    final NetworkOffering otherOff = _entityMgr.findById(NetworkOffering.class, network.getNetworkOfferingId());
                    // throw only if networks have different offerings with
                    // public lb support
                    if (_ntwkModel.areServicesSupportedInNetwork(network.getId(), Service.Lb) && otherOff.isPublicLb() && guestNtwkOff.getId() != otherOff.getId()) {
                        throw new InvalidParameterValueException("Public LB service is already supported " + "by network " + network + " in VPC " + vpc);
                    }
                }
            }
        }

        // 5) When aclId is provided, verify that ACLProvider is supported by
        // network offering
        boolean isForNsx = _ntwkModel.isProviderForNetworkOffering(Provider.Nsx, guestNtwkOff.getId());
        if (aclId != null && !_ntwkModel.areServicesSupportedByNetworkOffering(guestNtwkOff.getId(), Service.NetworkACL) && !isForNsx) {
            throw new InvalidParameterValueException("Cannot apply NetworkACL. Network Offering does not support NetworkACL service");
        }

    }

    @Override
    public void validateNtwkOffForVpc(final NetworkOffering guestNtwkOff, final List<Service> supportedSvcs) {
        // 1) in current release, only vpc provider is supported by Vpc offering
        final List<Provider> providers = _ntwkModel.getNtwkOffDistinctProviders(guestNtwkOff.getId());
        for (final Provider provider : providers) {
            if (!supportedProviders.contains(provider)) {
                throw new InvalidParameterValueException("Provider of type " + provider.getName() + " is not supported for network offerings that can be used in VPC");
            }
        }

        // 2) Only Isolated networks with Source nat service enabled can be
        // added to vpc
        boolean isForNsx = _ntwkModel.isProviderForNetworkOffering(Provider.Nsx, guestNtwkOff.getId());
        boolean isForNNetris = _ntwkModel.isProviderForNetworkOffering(Provider.Netris, guestNtwkOff.getId());
        if (!isForNsx && !isForNNetris
                && !(guestNtwkOff.getGuestType() == GuestType.Isolated && (supportedSvcs.contains(Service.SourceNat) || supportedSvcs.contains(Service.Gateway)))) {

            throw new InvalidParameterValueException("Only network offerings of type " + GuestType.Isolated + " with service " + Service.SourceNat.getName()
                    + " are valid for vpc ");
        }

        // 3) No redundant router support
        /*
         * TODO This should have never been hardcoded like this in the first
         * place if (guestNtwkOff.getRedundantRouter()) { throw new
         * InvalidParameterValueException
         * ("No redundant router support when network belongs to VPC"); }
         */

        // 4) Conserve mode should be off in older versions ( < 4.19.0.0)
        if (guestNtwkOff.isConserveMode()) {
            logger.info("Creating a network with conserve mode in VPC");
        }

        // 5) If Netscaler is LB provider make sure it is in dedicated mode
        if (providers.contains(Provider.Netscaler) && !guestNtwkOff.isDedicatedLB()) {
            throw new InvalidParameterValueException("Netscaler only with Dedicated LB can belong to VPC");
        }
        return;
    }

    @DB
    protected void validateNewVpcGuestNetwork(final String cidr, final String gateway, final Account networkOwner, final Vpc vpc, final String networkDomain) {

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                final Vpc locked = vpcDao.acquireInLockTable(vpc.getId());
                if (locked == null) {
                    throw new CloudRuntimeException("Unable to acquire lock on " + vpc);
                }

                try {
                    // check number of active networks in vpc
                    if (_ntwkDao.countVpcNetworks(vpc.getId()) >= _maxNetworks) {
                        logger.warn(String.format("Failed to create a new VPC Guest Network because the number of networks per VPC has reached its maximum capacity of [%s]. Increase it by modifying global config [%s].", _maxNetworks, Config.VpcMaxNetworks));
                        throw new CloudRuntimeException(String.format("Number of networks per VPC cannot surpass [%s].", _maxNetworks));
                    }

                    // 1) CIDR is required
                    if (cidr == null) {
                        throw new InvalidParameterValueException("Gateway/netmask are required when create network for VPC");
                    }

                    // 2) Network cidr should be within vpcCidr
                    if (!NetUtils.isNetworkAWithinNetworkB(cidr, vpc.getCidr())) {
                        throw new InvalidParameterValueException("Network cidr " + cidr + " is not within vpc " + vpc + " cidr");
                    }

                    // 3) Network cidr shouldn't cross the cidr of other vpc
                    // network cidrs
                    final List<? extends Network> ntwks = _ntwkDao.listByVpc(vpc.getId());
                    for (final Network ntwk : ntwks) {
                        assert cidr != null : "Why the network cidr is null when it belongs to vpc?";

                        if (NetUtils.isNetworkAWithinNetworkB(ntwk.getCidr(), cidr) || NetUtils.isNetworkAWithinNetworkB(cidr, ntwk.getCidr())) {
                            throw new InvalidParameterValueException("Network cidr " + cidr + " crosses other network cidr " + ntwk + " belonging to the same vpc " + vpc);
                        }
                    }

                    // 4) Vpc's account should be able to access network owner's account
                    CheckAccountsAccess(vpc, networkOwner);

                    // 5) network domain should be the same as VPC's
                    if (!networkDomain.equalsIgnoreCase(vpc.getNetworkDomain())) {
                        throw new InvalidParameterValueException("Network domain of the new network should match network" + " domain of vpc " + vpc);
                    }

                    // 6) gateway should never be equal to the cidr subnet
                    if (NetUtils.getCidrSubNet(cidr).equalsIgnoreCase(gateway)) {
                        throw new InvalidParameterValueException("Invalid gateway specified. It should never be equal to the cidr subnet value");
                    }
                } finally {
                    logger.debug("Releasing lock for " + locked);
                    vpcDao.releaseFromLockTable(locked.getId());
                }
            }
        });
    }

    private void CheckAccountsAccess(Vpc vpc, Account networkAccount) {
        Account vpcaccount = _accountMgr.getAccount(vpc.getAccountId());
        try {
            _accountMgr.checkAccess(vpcaccount, null, false, networkAccount);
        }
        catch (PermissionDeniedException e) {
            logger.error(e.getMessage());
            throw new InvalidParameterValueException(String.format("VPC owner does not have access to account [%s].", networkAccount.getAccountName()));
        }
    }

    @Override
    public List<VpcProvider> getVpcElements() {
        if (vpcElements == null) {
            vpcElements = new ArrayList<VpcProvider>();
            vpcElements.add((VpcProvider) _ntwkModel.getElementImplementingProvider(Provider.VPCVirtualRouter.getName()));
        }

        if (vpcElements == null) {
            throw new CloudRuntimeException("Failed to initialize vpc elements");
        }

        return vpcElements;
    }

    @Override
    public List<? extends Vpc> getVpcsForAccount(final long accountId) {
        final List<Vpc> vpcs = new ArrayList<Vpc>();
        vpcs.addAll(vpcDao.listByAccountId(accountId));
        return vpcs;
    }

    public boolean cleanupVpcResources(final Vpc vpc, final Account caller, final long callerUserId) throws ResourceUnavailableException, ConcurrentOperationException {
        logger.debug("Cleaning up resources for vpc {}", vpc);
        boolean success = true;

        // 1) Remove VPN connections and VPN gateway
        logger.debug("Cleaning up existed site to site VPN connections");
        _s2sVpnMgr.cleanupVpnConnectionByVpc(vpc.getId());
        logger.debug("Cleaning up existed site to site VPN gateways");
        _s2sVpnMgr.cleanupVpnGatewayByVpc(vpc.getId());

        List<RemoteAccessVpnVO> vpns = remoteAccessVpnDao.listByVpcId(vpc.getId());
        for (RemoteAccessVpnVO vpn : vpns) {
            logger.debug("Disabling remote access VPN on {}", vpn.getServerAddressId());
            remoteAccessVpnMgr.destroyRemoteAccessVpnForIp(vpn.getServerAddressId(), caller, true);
        }

        // 2) release all ip addresses
        final List<IPAddressVO> ipsToRelease = _ipAddressDao.listByAssociatedVpc(vpc.getId(), null);
        logger.debug("Releasing ips for vpc {} as a part of vpc cleanup", vpc);
        for (final IPAddressVO ipToRelease : ipsToRelease) {
            if (ipToRelease.isPortable()) {
                // portable IP address are associated with owner, until
                // explicitly requested to be disassociated.
                // so as part of VPC clean up just break IP association with VPC
                ipToRelease.setVpcId(null);
                ipToRelease.setAssociatedWithNetworkId(null);
                _ipAddressDao.update(ipToRelease.getId(), ipToRelease);
                logger.debug("Portable IP address " + ipToRelease + " is no longer associated with any VPC");
            } else {
                success = success && _ipAddrMgr.disassociatePublicIpAddress(ipToRelease, callerUserId, caller);
                if (!success) {
                    logger.warn("Failed to cleanup ip {} as a part of vpc {} cleanup", ipToRelease, vpc);
                }
            }
        }

        if (success) {
            logger.debug("Released ip addresses for vpc {} as a part of cleanup vpc process", vpc);
        } else {
            logger.warn("Failed to release ip addresses for vpc {} as a part of cleanup vpc process", vpc);
            // although it failed, proceed to the next cleanup step as it
            // doesn't depend on the public ip release
        }

        // 3) Delete all static route rules
        if (!revokeStaticRoutesForVpc(vpc, caller)) {
            logger.warn("Failed to revoke static routes for vpc {} as a part of cleanup vpc process", vpc);
            return false;
        }

        // 4) Delete private gateways
        final List<PrivateGateway> gateways = getVpcPrivateGateways(vpc.getId());
        if (gateways != null) {
            for (final PrivateGateway gateway : gateways) {
                if (gateway != null) {
                    logger.debug("Deleting private gateway {} as a part of vpc {} resources cleanup", gateway, vpc);
                    if (!deleteVpcPrivateGateway(gateway.getId())) {
                        success = false;
                        logger.debug("Failed to delete private gateway {} as a part of vpc {} resources cleanup", gateway, vpc);
                    } else {
                        logger.debug("Deleted private gateway {} as a part of vpc {} resources cleanup", gateway, vpc);
                    }
                }
            }
        }

        //5) Delete ACLs
        final SearchBuilder<NetworkACLVO> searchBuilder = _networkAclDao.createSearchBuilder();

        searchBuilder.and("vpcId", searchBuilder.entity().getVpcId(), Op.IN);
        final SearchCriteria<NetworkACLVO> searchCriteria = searchBuilder.create();
        searchCriteria.setParameters("vpcId", vpc.getId());

        final Filter filter = new Filter(NetworkACLVO.class, "id", false, null, null);
        final Pair<List<NetworkACLVO>, Integer> aclsCountPair =  _networkAclDao.searchAndCount(searchCriteria, filter);

        final List<NetworkACLVO> acls = aclsCountPair.first();
        for (final NetworkACLVO networkAcl : acls) {
            _networkAclMgr.deleteNetworkACL(networkAcl);
        }

        routedIpv4Manager.releaseBgpPeersForVpc(vpc.getId());
        routedIpv4Manager.releaseIpv4SubnetForVpc(vpc.getId());

        annotationDao.removeByEntityType(AnnotationService.EntityType.VPC.name(), vpc.getUuid());

        ASNumberVO asNumber = asNumberDao.findByZoneAndVpcId(vpc.getZoneId(), vpc.getId());
        if (asNumber != null) {
            logger.debug("Releasing AS number {} from VPC {}", asNumber.getAsNumber(), vpc);
            bgpService.releaseASNumber(vpc.getZoneId(), asNumber.getAsNumber(), true);
        }

        return success;
    }


    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_RESTART, eventDescription = "restarting vpc")
    public boolean restartVpc(final RestartVPCCmd cmd) throws ConcurrentOperationException, ResourceUnavailableException,
            InsufficientCapacityException {
        final long vpcId = cmd.getId();
        final boolean cleanUp = cmd.getCleanup();
        final boolean makeRedundant = cmd.getMakeredundant();
        final boolean livePatch = cmd.getLivePatch();
        final User callerUser = _accountMgr.getActiveUser(CallContext.current().getCallingUserId());
        return restartVpc(vpcId, cleanUp, makeRedundant, livePatch, callerUser);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_RESTART, eventDescription = "restarting vpc")
    public boolean restartVpc(Long vpcId, boolean cleanUp, boolean makeRedundant, boolean livePatch, User user) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        Vpc vpc = getActiveVpc(vpcId);
        if (vpc == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find Enabled VPC by id specified");
            ex.addProxyObject(String.valueOf(vpcId), "VPC");
            throw ex;
        }

        Account callerAccount = _accountMgr.getActiveAccountById(user.getAccountId());
        final ReservationContext context = new ReservationContextImpl(null, null, user, callerAccount);
        _accountMgr.checkAccess(callerAccount, null, false, vpc);

        logger.debug("Restarting VPC " + vpc);
        boolean restartRequired = false;
        try {
            boolean forceCleanup = cleanUp;
            if (!vpc.isRedundant() && makeRedundant) {
                final VpcOfferingVO redundantOffering = _vpcOffDao.findByUniqueName(VpcOffering.redundantVPCOfferingName);

                final VpcVO entity = vpcDao.findById(vpcId);
                entity.setRedundant(true);
                entity.setVpcOfferingId(redundantOffering.getId());

                // Change the VPC in order to get it updated after the end of
                // the restart procedure.
                if (vpcDao.update(vpc.getId(), entity)) {
                    vpc = entity;
                }

                // If the offering and redundant column are changing, force the
                // clean up.
                forceCleanup = true;
            }

            if (forceCleanup) {
                if (!rollingRestartVpc(vpc, context)) {
                    logger.warn("Failed to execute a rolling restart as a part of VPC " + vpc + " restart process");
                    restartRequired = true;
                    return false;
                }
                reconfigStaticNatForVpcVr(vpcId);
                return true;
            }

            if (cleanUp) {
                livePatch = false;
            }

            restartVPCNetworks(vpcId, callerAccount, user, cleanUp, livePatch);

            logger.debug("Starting VPC " + vpc + " as a part of VPC restart process without cleanup");
            if (!startVpc(vpcId, false)) {
                logger.warn("Failed to start vpc as a part of VPC " + vpc + " restart process");
                restartRequired = true;
                return false;
            }
            logger.debug("VPC " + vpc + " was restarted successfully");
            return true;
        } finally {
            logger.debug("Updating VPC " + vpc + " with restartRequired=" + restartRequired);
            final VpcVO vo = vpcDao.findById(vpcId);
            vo.setRestartRequired(restartRequired);
            vpcDao.update(vpc.getId(), vo);
        }
    }

    private void restartVPCNetworks(long vpcId, Account callerAccount, User callerUser, boolean cleanUp, boolean livePatch) throws InsufficientCapacityException, ResourceUnavailableException {
        List<? extends Network> networks = _ntwkModel.listNetworksByVpc(vpcId);
        for (Network network: networks) {
            if (network.isRestartRequired() || livePatch) {
                _ntwkMgr.restartNetwork(network.getId(), callerAccount, callerUser, cleanUp, livePatch);
            }
        }
    }

    @Override
    public List<PrivateGateway> getVpcPrivateGateways(final long vpcId) {
        return privateGatewayService.getVpcPrivateGateways(vpcId);
    }

    @Override
    public PrivateGateway getVpcPrivateGateway(final long id) {
        return privateGatewayService.getVpcPrivateGateway(id);
    }

    @Override
    public PrivateGateway createVpcPrivateGateway(CreatePrivateGatewayCmd command) throws ResourceAllocationException,
            ConcurrentOperationException, InsufficientCapacityException {
        return privateGatewayService.createVpcPrivateGateway(command);
    }

    /**
     * Delegates to {@link PrivateGatewayService#validateVpcPrivateGatewayAclId(long, Long)}.
     * Retained as a wrapper because existing unit tests invoke it via
     * {@code manager.validateVpcPrivateGatewayAclId(...)}.
     */
    protected void validateVpcPrivateGatewayAclId(long vpcId, Long aclId) {
        privateGatewayService.validateVpcPrivateGatewayAclId(vpcId, aclId);
    }

    @Override
    public PrivateGateway applyVpcPrivateGateway(final long gatewayId, final boolean destroyOnFailure) throws ConcurrentOperationException, ResourceUnavailableException {
        return privateGatewayService.applyVpcPrivateGateway(gatewayId, destroyOnFailure);
    }

    @Override
    public boolean deleteVpcPrivateGateway(final long gatewayId) throws ConcurrentOperationException, ResourceUnavailableException {
        return privateGatewayService.deleteVpcPrivateGateway(gatewayId);
    }

    @Override
    public Pair<List<PrivateGateway>, Integer> listPrivateGateway(final ListPrivateGatewaysCmd cmd) {
        return privateGatewayService.listPrivateGateway(cmd);
    }

    @Override
    public StaticRoute getStaticRoute(final long routeId) {
        return staticRouteService.getStaticRoute(routeId);
    }

    @Override
    public boolean applyStaticRoutesForVpc(final long vpcId) throws ResourceUnavailableException {
        final Account caller = CallContext.current().getCallingAccount();
        final List<StaticRouteVO> routes = getVpcStaticRoutes(vpcId);
        return applyStaticRoutes(routes, caller, true);
    }

    @Override
    public boolean applyStaticRouteForVpcVpnIfNeeded(final Long vpcId, boolean updateAllVpn) throws ResourceUnavailableException {
        if (isProviderSupportServiceInVpc(vpcId, Service.Vpn, Network.Provider.VPCVirtualRouter)) {
            boolean isVpcVRSourceNat = isProviderSupportServiceInVpc(vpcId, Service.SourceNat, Network.Provider.VPCVirtualRouter);
            if (isVpcVRSourceNat) {
                logger.debug("Skipping static route configuration as VPC VR is Source NAT");
                return true;
            }
            logger.debug("Configuring static route for VPC VR of VPC " + vpcId);
            final Account caller = CallContext.current().getCallingAccount();
            final List<StaticRouteVO> routes = getVpcStaticRoutes(vpcId, updateAllVpn);
            return applyStaticRoutes(routes, caller, false);
        }
        return true;
    }

    protected boolean isNetworkOnVpc(Network network) {
        return network.getVpcId() != null;
    }

    @Override
    public boolean isNetworkOnVpcEnabledConserveMode(Network newRuleNetwork) {
        if (isNetworkOnVpc(newRuleNetwork)) {
            Vpc vpc = getActiveVpc(newRuleNetwork.getVpcId());
            VpcOfferingVO vpcOffering = vpc != null ? _vpcOffDao.findById(vpc.getVpcOfferingId()) : null;
            return vpcOffering != null && vpcOffering.isConserveMode();
        }
        return false;
    }

    protected boolean applyStaticRoutes(final List<StaticRouteVO> routes, final Account caller, final boolean updateRoutesInDB) throws ResourceUnavailableException {
        return staticRouteService.applyStaticRoutes(routes, caller, updateRoutesInDB);
    }

    protected boolean applyStaticRoutes(final List<StaticRouteProfile> routes) throws ResourceUnavailableException {
        return staticRouteService.applyStaticRoutes(routes);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_STATIC_ROUTE_DELETE, eventDescription = "deleting static route")
    public boolean revokeStaticRoute(final long routeId) throws ResourceUnavailableException {
        final Account caller = CallContext.current().getCallingAccount();

        final StaticRouteVO route = _staticRouteDao.findById(routeId);
        if (route == null) {
            throw new InvalidParameterValueException("Unable to find static route by id");
        }

        _accountMgr.checkAccess(caller, null, false, route);

        markStaticRouteForRevoke(route, caller);

        return applyStaticRoutesForVpc(route.getVpcId());
    }

    @DB
    protected boolean revokeStaticRoutesForVpc(final Vpc vpc, final Account caller) throws ResourceUnavailableException {
        // get all static routes for the vpc
        final List<StaticRouteVO> routes = getVpcStaticRoutes(vpc.getId());
        logger.debug("Found {} to revoke for the vpc {}", routes.size(), vpc);
        if (!routes.isEmpty()) {
            // mark all of them as revoke
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) {
                    for (final StaticRouteVO route : routes) {
                        markStaticRouteForRevoke(route, caller);
                    }
                }
            });
            return applyStaticRoutesForVpc(vpc.getId());
        }

        return true;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_STATIC_ROUTE_CREATE, eventDescription = "creating static route", create = true)
    public StaticRoute createStaticRoute(final Long gatewayId, Long vpcId, final String nextHop, final String cidr) throws NetworkRuleConflictException {
        return staticRouteService.createStaticRoute(gatewayId, vpcId, nextHop, cidr);
    }

    protected boolean isCidrDenylisted(final String cidr, final long zoneId) {
        return staticRouteService.isCidrDenylisted(cidr, zoneId);
    }

    @Override
    public Pair<List<? extends StaticRoute>, Integer> listStaticRoutes(final ListStaticRoutesCmd cmd) {
        return staticRouteService.listStaticRoutes(cmd);
    }

    protected void detectRoutesConflict(final StaticRoute newRoute) throws NetworkRuleConflictException {
        staticRouteService.detectRoutesConflict(newRoute);
    }

    protected void markStaticRouteForRevoke(final StaticRouteVO route, final Account caller) {
        staticRouteService.markStaticRouteForRevoke(route, caller);
    }

    @Override
    public String getConfigComponentName() {
        return VpcManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{
                VpcTierNamePrepend,
                VpcTierNamePrependDelimiter
        };
    }

    protected class VpcCleanupTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            try {
                final GlobalLock lock = GlobalLock.getInternLock("VpcCleanup");
                if (lock == null) {
                    logger.debug("Couldn't get the global lock");
                    return;
                }

                if (!lock.lock(30)) {
                    logger.debug("Couldn't lock the db");
                    return;
                }

                try {
                    // Cleanup inactive VPCs
                    final List<VpcVO> inactiveVpcs = vpcDao.listInactiveVpcs();
                    if (inactiveVpcs != null) {
                        logger.info("Found " + inactiveVpcs.size() + " removed VPCs to cleanup");
                        for (final VpcVO vpc : inactiveVpcs) {
                            logger.debug("Cleaning up " + vpc);
                            destroyVpc(vpc, _accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM), User.UID_SYSTEM);
                        }
                    }
                } catch (final Exception e) {
                    logger.error("Exception ", e);
                } finally {
                    lock.unlock();
                }
            } catch (final Exception e) {
                logger.error("Exception ", e);
            }
        }
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_ASSIGN, eventDescription = "associating Ip", async = true)
    public IpAddress associateIPToVpc(final long ipId, final long vpcId) throws ResourceAllocationException, ResourceUnavailableException, InsufficientAddressCapacityException,
            ConcurrentOperationException {
        return vpcIpAllocationService.associateIPToVpc(ipId, vpcId);
    }

    @Override
    public void unassignIPFromVpcNetwork(final long ipId, final long networkId) {
        vpcIpAllocationService.unassignIPFromVpcNetwork(ipId, networkId);
    }

    @Override
    public void unassignIPFromVpcNetwork(final IPAddressVO ip, final Network network) {
        vpcIpAllocationService.unassignIPFromVpcNetwork(ip, network);
    }

    @Override
    public boolean isIpAllocatedToVpc(final IpAddress ip) {
        return vpcIpAllocationService.isIpAllocatedToVpc(ip);
    }

    @DB
    @Override
    public Network createVpcGuestNetwork(final long ntwkOffId, final String name, final String displayText, final String gateway, final String cidr, final String vlanId,
            String networkDomain, final Account owner, final Long domainId, final PhysicalNetwork pNtwk, final long zoneId, final ACLType aclType, final Boolean subdomainAccess,
            final long vpcId, final Long aclId, final Account caller, final Boolean isDisplayNetworkEnabled, String externalId, String ip6Gateway, String ip6Cidr,
            final String ip4Dns1, final String ip4Dns2, final String ip6Dns1, final String ip6Dns2, Pair<Integer, Integer> vrIfaceMTUs, Integer networkCidrSize)
            throws ConcurrentOperationException, InsufficientCapacityException, ResourceAllocationException {

        final Vpc vpc = getActiveVpc(vpcId);

        if (vpc == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find Enabled VPC ");
            ex.addProxyObject(String.valueOf(vpcId), "VPC");
            throw ex;
        }
        _accountMgr.checkAccess(caller, null, false, vpc);

        if (networkDomain == null) {
            networkDomain = vpc.getNetworkDomain();
        }

        if (!vpc.isRegionLevelVpc() && vpc.getZoneId() != zoneId) {
            throw new InvalidParameterValueException("New network doesn't belong to vpc zone");
        }

        // 1) Validate if network can be created for VPC
        validateNtwkOffForNtwkInVpc(null, ntwkOffId, cidr, networkDomain, vpc, gateway, owner, aclId);

        // 2) Create network
        final Network guestNetwork = _ntwkMgr.createGuestNetwork(ntwkOffId, name, displayText, gateway, cidr, vlanId, false, networkDomain, owner, domainId, pNtwk, zoneId, aclType,
                subdomainAccess, vpcId, ip6Gateway, ip6Cidr, isDisplayNetworkEnabled, null, null, externalId, null, null, ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2, vrIfaceMTUs, networkCidrSize);

        if (guestNetwork != null) {
            guestNetwork.setNetworkACLId(aclId);
            _ntwkDao.update(guestNetwork.getId(), (NetworkVO) guestNetwork);
        }
        return guestNetwork;
    }

    protected IPAddressVO getExistingSourceNatInVpc(final long ownerId, final long vpcId, final boolean forNsx, final boolean forNetris) {
        return vpcIpAllocationService.getExistingSourceNatInVpc(ownerId, vpcId, forNsx, forNetris);
    }

    protected List<IPAddressVO> listPublicIpsAssignedToVpc(final long accountId, final Boolean sourceNat, final long vpcId) {
        return vpcIpAllocationService.listPublicIpsAssignedToVpc(accountId, sourceNat, vpcId);
    }

    @Override
    public PublicIp assignSourceNatIpAddressToVpc(final Account owner, final Vpc vpc, final Long podId) throws InsufficientAddressCapacityException, ConcurrentOperationException {
        return vpcIpAllocationService.assignSourceNatIpAddressToVpc(owner, vpc, podId);
    }

    @Override
    public List<HypervisorType> getSupportedVpcHypervisors() {
        return Collections.unmodifiableList(hTypes);
    }

    private List<Provider> getVpcProviders(final long vpcId) {
        final List<String> providerNames = _vpcSrvcDao.getDistinctProviders(vpcId);
        final Map<String, Provider> providers = new HashMap<String, Provider>();
        for (final String providerName : providerNames) {
            if (!providers.containsKey(providerName)) {
                providers.put(providerName, Network.Provider.getProvider(providerName));
            }
        }

        return new ArrayList<Provider>(providers.values());
    }

    @Inject
    public void setVpcElements(final List<VpcProvider> vpcElements) {
        this.vpcElements = vpcElements;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_STATIC_ROUTE_CREATE, eventDescription = "Applying static route", async = true)
    public boolean applyStaticRoute(final long routeId) throws ResourceUnavailableException {
        final StaticRoute route = _staticRouteDao.findById(routeId);
        return applyStaticRoutesForVpc(route.getVpcId());
    }

    @Override
    public boolean isSrcNatIpRequired(long vpcOfferingId) {
        final Map<Network.Service, Set<Network.Provider>> vpcOffSvcProvidersMap = getVpcOffSvcProvidersMap(vpcOfferingId);
        return (Objects.nonNull(vpcOffSvcProvidersMap.get(Network.Service.SourceNat))
                && (vpcOffSvcProvidersMap.get(Network.Service.SourceNat).contains(Network.Provider.VPCVirtualRouter)
                || vpcOffSvcProvidersMap.get(Service.SourceNat).contains(Provider.Nsx)
                || vpcOffSvcProvidersMap.get(Service.SourceNat).contains(Provider.Netris)))
                || (Objects.nonNull(vpcOffSvcProvidersMap.get(Network.Service.Gateway))
                    && (vpcOffSvcProvidersMap.get(Service.Gateway).contains(Network.Provider.VPCVirtualRouter)
                    || vpcOffSvcProvidersMap.get(Service.Gateway).contains(Provider.Nsx)
                    || vpcOffSvcProvidersMap.get(Service.Gateway).contains(Network.Provider.Netris)));
    }

     @Override
     public boolean isSrcNatIpRequiredForVpcVr(long vpcOfferingId) {
         final Map<Network.Service, Set<Network.Provider>> vpcOffSvcProvidersMap = getVpcOffSvcProvidersMap(vpcOfferingId);
         return (Objects.nonNull(vpcOffSvcProvidersMap.get(Network.Service.SourceNat))
                 && vpcOffSvcProvidersMap.get(Network.Service.SourceNat).contains(Network.Provider.VPCVirtualRouter))
                 || (Objects.nonNull(vpcOffSvcProvidersMap.get(Network.Service.Gateway))
                 && vpcOffSvcProvidersMap.get(Service.Gateway).contains(Network.Provider.VPCVirtualRouter));
     }

    /**
     * rollingRestartVpc performs restart of routers of a VPC by first
     * deploying a new VR and then destroying old VRs in rolling fashion. For
     * non-redundant VPC, it will re-program the new router as final step
     * otherwise deploys a backup router for the VPC.
     * @param vpc vpc to be restarted
     * @param context reservation context
     * @return returns true when the rolling restart succeeds
     * @throws ResourceUnavailableException
     * @throws ConcurrentOperationException
     * @throws InsufficientCapacityException
     */
    private boolean rollingRestartVpc(final Vpc vpc, final ReservationContext context) throws ResourceUnavailableException, ConcurrentOperationException, InsufficientCapacityException {
        if (!NetworkOrchestrationService.RollingRestartEnabled.value()) {
            if (shutdownVpc(vpc.getId())) {
                return startVpc(vpc.getId(), false);
            }
            logger.warn("Failed to shutdown vpc as a part of VPC " + vpc + " restart process");
            return false;
        }
        logger.debug("Performing rolling restart of routers of VPC " + vpc);
        _ntwkMgr.destroyExpendableRouters(routerDao.listByVpcId(vpc.getId()), context);

        final DeployDestination dest = new DeployDestination(_dcDao.findById(vpc.getZoneId()), null, null, null);
        final List<DomainRouterVO> oldRouters = routerDao.listByVpcId(vpc.getId());

        // Create a new router
        if (oldRouters.size() > 0) {
            vpc.setRollingRestart(true);
        }
        startVpc(vpc, dest, context);
        if (oldRouters.size() > 0) {
            vpc.setRollingRestart(false);
        }

        // For redundant vpc wait for 3*advert_int+skew_seconds for VRRP to kick in
        if (vpc.isRedundant() || (oldRouters.size() == 1 && oldRouters.get(0).getIsRedundantRouter())) {
            try {
                Thread.sleep(NetworkOrchestrationService.RVRHandoverTime);
            } catch (final InterruptedException ignored) {
            }
        }

        // Destroy old routers
        for (final DomainRouterVO oldRouter : oldRouters) {
            _routerService.stopRouter(oldRouter.getId(), true);
            _routerService.destroyRouter(oldRouter.getId(), context.getAccount(), context.getCaller().getId());
        }

        // Re-program VPC VR or add a new backup router for redundant VPC
        if (!startVpc(vpc, dest, context)) {
            logger.debug("Failed to re-program VPC router or deploy a new backup router for VPC{}", vpc);
            return false;
        }

        return _ntwkMgr.areRoutersRunning(routerDao.listByVpcId(vpc.getId()));
    }

    protected boolean isGlobalAcl(Long aclVpcId) {
        return aclVpcId != null && aclVpcId == 0;
    }

    protected boolean isDefaultAcl(long aclId) {
        return aclId == NetworkACL.DEFAULT_ALLOW || aclId == NetworkACL.DEFAULT_DENY;
    }

    @Override
    public List<StaticRouteProfile> getVpcStaticRoutes(final List<? extends StaticRoute> routes) {
        return staticRouteService.getVpcStaticRoutes(routes);
    }

    @Override
    public List<StaticRouteVO> getVpcStaticRoutes(Long vpcId) {
        return getVpcStaticRoutes(vpcId, false);
    }

    public List<StaticRouteVO> getVpcStaticRoutes(Long vpcId, boolean updateAllVpn) {
        final List<StaticRouteVO> routes = _staticRouteDao.listByVpcId(vpcId);

        if (isProviderSupportServiceInVpc(vpcId, Service.Vpn, Network.Provider.VPCVirtualRouter)
                && !isProviderSupportServiceInVpc(vpcId, Service.SourceNat, Network.Provider.VPCVirtualRouter)) {

            Vpc vpc = vpcDao.findById(vpcId);
            IPAddressVO ipAddressForVpcVR = getIpAddressForVpcVr(vpc, null, false);
            String nextHop = getFirstGuestIpAddressForVpcVr(vpc.getId());

            if (ipAddressForVpcVR != null && (updateAllVpn || nextHop != null)) {
                // Add Static Routes for Remote Access VPN
                List<StaticRouteVO> staticRoutesForRemoteAccessVpn = new ArrayList<>();
                RemoteAccessVpnVO remoteAccessVpn = remoteAccessVpnDao.findByPublicIpAddress(ipAddressForVpcVR.getId());
                if (remoteAccessVpn != null) {
                    String ipRange = remoteAccessVpn.getIpRange();
                    String startIp = ipRange.split("-")[0];
                    String endIp = ipRange.split("-")[1];
                    int cidrSize = NetUtils.getBigCidrSizeOfIpRange(NetUtils.ip2Long(startIp), NetUtils.ip2Long(endIp));
                    String cidr = NetUtils.transformCidr(startIp + "/" + cidrSize);
                    if (nextHop == null || RemoteAccessVpn.State.Removed.equals(remoteAccessVpn.getState())) {
                        StaticRouteVO newRoute = new StaticRouteVO(cidr, vpc.getId(), vpc.getAccountId(), vpc.getDomainId(), null,
                                StaticRoute.State.Revoke, true);
                        staticRoutesForRemoteAccessVpn.add(newRoute);
                    } else {
                        StaticRoute.State state = updateAllVpn ? StaticRoute.State.Update : StaticRoute.State.Add;
                        StaticRouteVO newRoute = new StaticRouteVO(cidr, vpc.getId(), vpc.getAccountId(), vpc.getDomainId(), nextHop,
                                state, true);
                        staticRoutesForRemoteAccessVpn.add(newRoute);
                    }
                }
                logger.debug("Adding {} static routes for Remote Access VPN", staticRoutesForRemoteAccessVpn.size());
                routes.addAll(staticRoutesForRemoteAccessVpn);

                // Add Static Routes for Site-to-Site VPN connections
                List<StaticRouteVO> staticRoutesForSite2SiteVpn = new ArrayList<>();
                List<Site2SiteVpnConnectionVO> vpnConnections = site2SiteVpnConnectionDao.listByVpcId(vpcId);
                for (Site2SiteVpnConnectionVO vpnConnection : vpnConnections) {
                    Site2SiteCustomerGatewayVO customerGateway = site2SiteCustomerGatewayDao.findById(vpnConnection.getCustomerGatewayId());
                    if (nextHop == null || Site2SiteVpnConnection.State.Removed.equals(vpnConnection.getState())) {
                        for (String cidr: customerGateway.getGuestCidrList().split(",")) {
                            StaticRouteVO newRoute = new StaticRouteVO(cidr, vpc.getId(), vpc.getAccountId(), vpc.getDomainId(), null,
                                    StaticRoute.State.Revoke, true);
                            staticRoutesForSite2SiteVpn.add(newRoute);
                        }
                    } else {
                        StaticRoute.State state = updateAllVpn ? StaticRoute.State.Update : StaticRoute.State.Add;
                        for (String cidr : customerGateway.getGuestCidrList().split(",")) {
                            StaticRouteVO newRoute = new StaticRouteVO(cidr, vpc.getId(), vpc.getAccountId(), vpc.getDomainId(), nextHop,
                                    state, true);
                            staticRoutesForSite2SiteVpn.add(newRoute);
                        }
                    }
                }
                logger.debug("Adding {} static routes for {} Site-to-Site VPN connections",
                        staticRoutesForSite2SiteVpn.size(), vpnConnections.size());
                routes.addAll(staticRoutesForSite2SiteVpn);
            }
        }

        logger.debug("Found {} static routes for VPC {}", routes.size(), vpcId);
        return routes;
    }

    @Override
    public boolean isProviderSupportServiceInVpc(long vpcId, Service service, Provider provider) {
        return _vpcSrvcDao.canProviderSupportServiceInVpc(vpcId, service, provider);
    }

    @Override
    public IPAddressVO getIpAddressForVpcVr(Vpc vpc, IPAddressVO ipAddress, boolean allocateIpIfNeeded) {
        // Validate if the IP address is associated to a VPC VR
        final List<IPAddressVO> ips = _ipAddressDao.listByAssociatedVpc(vpc.getId(), null);
        IPAddressVO ipAddressForVR = ips.stream().filter(ip -> ip.isForRouter()).findFirst().orElse(null);
        if (ipAddressForVR != null) {
            if (ipAddress != null && ipAddressForVR.getId() != ipAddress.getId()) {
                throw new InvalidParameterValueException(String.format("Cannot assign Public IP %s to VPC VR as %s has been associated to the VPC VR.",
                        ipAddress.getAddress().addr(), ipAddressForVR.getAddress().addr()));
            }
            return ipAddressForVR;
        } else if (ipAddress != null) {
            if (ipAddress.isSourceNat()) {
                throw new InvalidParameterValueException("Vpn service can not be configured on the Source NAT IP of VPC id=" + ipAddress.getVpcId());
            }
            return ipAddress;
        }

        if (allocateIpIfNeeded) {
            Account account = _accountMgr.getAccount(vpc.getAccountId());
            DataCenter zone = _dcDao.findById(vpc.getZoneId());
            try {
                IpAddress ip = _ipAddrMgr.allocateIp(account, false, CallContext.current().getCallingAccount(),
                        CallContext.current().getCallingUser(), zone, null, null);
                this.associateIPToVpc(ip.getId(), vpc.getId());
                return _ipAddressDao.findById(ip.getId());
            } catch (InsufficientAddressCapacityException | ResourceAllocationException |
                     ResourceUnavailableException ex) {
                throw new InvalidParameterValueException("Cannot assign Public IP to VPC VR: " + ex.getMessage());
            }
        } else {
            return null;
        }
    }

    /* This method configures the Static Nat for VPC VR if it is used for VPN but not Source NAT.
     * (1) Update forRouter to true and one-to-one to true
     * (2) Get current network and router ID/IP
     * (3) Get new network and router ID/IP
     * (4) If network or IP is changed (in case VPC tier is removed or shutdown), disable/apply Static NAT with new VM ID and VM IP
     * (5) otherwise, If VPC VR ID does not exist or is changed, update the VM ID.
     * (6) otherwise, do nothing
     *
     * This is used in the following processes
     * (1) create remote access VPN
     * (2) create S2S VPN
     * (3) destroy Router
     * (4) restart Vpc with cleanup
     * (5) add VPC tier
     * (6) delete VPC tier
     * (7) remove VPC
     */

    @Override
    public boolean configStaticNatForVpcVr(Vpc vpc, IPAddressVO ipAddress) {
        logger.debug("Configuring static nat for VPC VR of VPC " + vpc.getId());
        // (1) Update forRouter to true and one-to-one to true
        if (!ipAddress.isForRouter()) {
            ipAddress.setForRouter(true);
            ipAddress.setOneToOneNat(true);
            _ipAddressDao.update(ipAddress.getId(), ipAddress);
        }

        // (2) Get current network and router ID/IP
        Long currentNetworkId = ipAddress.getAssociatedWithNetworkId();
        Long currentRouterId = ipAddress.getAssociatedWithVmId();
        String currentRouterIp = ipAddress.getVmIp();

        // (3) Get new network and router ID/IP
        Long newNetworkId = null;
        Long newRouterId = null;
        String newRouterIp = null;
        List<NetworkVO> networks = _ntwkDao.listByVpc(vpc.getId());
        for (NetworkVO network : networks) {
            NicVO newNic = nicDao.findNonPlaceHolderByNetworkIdAndType(network.getId(), VirtualMachine.Type.DomainRouter);
            if (newNic != null) {
                logger.debug("Got VPC VR NIC for network {}: {}", network.getId(), newNic);
                newNetworkId = network.getId();
                newRouterId = newNic.getInstanceId();
                newRouterIp = newNic.getIPv4Address();
                break;
            }
        }

        // Do nothing if the current and new network and router are Null
        if (ObjectUtils.allNull(currentNetworkId, currentRouterId, newNetworkId, newRouterId)) {
            logger.debug("The current and new network and router are Null, do nothing");
            return true;
        }

        if (currentNetworkId == null || !currentNetworkId.equals(newNetworkId)) {
            // (4) If network or IP is changed (in case VPC tier is removed or shutdown), disable/apply Static NAT with new VM ID and VM IP
            if (currentNetworkId != null) {
                // Disable Static NAT for current VPC VR
                logger.debug("Disabling static nat for VPC VR (network: {}, router: {})", currentNetworkId, currentRouterId);
                CallContext ctx = CallContext.current();
                if (!rulesManager.applyStaticNatForIp(ipAddress.getId(), false, ctx.getCallingAccount(),true)) {
                    throw new CloudRuntimeException("Failed to disable static nat for VPC VR");
                }
                ipAddress.setAssociatedWithNetworkId(null);
                ipAddress.setAssociatedWithVmId(null);
                ipAddress.setVmIp(null);
                _ipAddressDao.update(ipAddress.getId(), ipAddress);
            }
            if (newNetworkId != null) {
                // Enable static nat for the new VPC VR
                logger.debug("Enabling static nat for VPC VR  (network: {}, router: {})", newNetworkId, newRouterId);
                ipAddress.setAssociatedWithNetworkId(newNetworkId);
                ipAddress.setAssociatedWithVmId(newRouterId);
                ipAddress.setVmIp(newRouterIp);
                _ipAddressDao.update(ipAddress.getId(), ipAddress);
                CallContext ctx = CallContext.current();
                if (!rulesManager.applyStaticNatForIp(ipAddress.getId(), false, ctx.getCallingAccount(),false)) {
                    throw new CloudRuntimeException("Failed to enable static nat for VPC VR");
                }
            }
        } else if (currentRouterId == null || !currentRouterId.equals(newRouterId)) {
            // (5) otherwise, If VPC VR ID does not exist or is changed, update the VM ID.
            ipAddress.setAssociatedWithVmId(newRouterId);
            ipAddress.setVmIp(newRouterIp);
            _ipAddressDao.update(ipAddress.getId(), ipAddress);
        }
        return true;
    }

    @Override
    public void reconfigStaticNatForVpcVr(Long vpcId) {
        Vpc vpc = vpcDao.findById(vpcId);
        IPAddressVO ipAddressForVpcVR = getIpAddressForVpcVr(vpc, null, false);
        if (ipAddressForVpcVR != null && !configStaticNatForVpcVr(vpc, ipAddressForVpcVR)) {
            throw new CloudRuntimeException("Failed to reconfig static nat for VPC VR as part of the process");
        }
    }

    private String getFirstGuestIpAddressForVpcVr(Long vpcId) {
        String nextHop = null;
        List<NetworkVO> networks = _ntwkDao.listByVpc(vpcId);
        for (NetworkVO network : networks) {
            NicVO nic = nicDao.findNonPlaceHolderByNetworkIdAndType(network.getId(), VirtualMachine.Type.DomainRouter);
            if (nic != null) {
                nextHop = nic.getIPv4Address();
                break;
            }
        }
        return nextHop;
    }
}
