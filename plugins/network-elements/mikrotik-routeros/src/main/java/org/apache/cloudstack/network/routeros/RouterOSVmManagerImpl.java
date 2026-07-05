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
package org.apache.cloudstack.network.routeros;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.network.routeros.api.ApacheRouterOSHttpTransport;
import org.apache.cloudstack.network.routeros.api.RouterOSApiClient;
import org.apache.cloudstack.network.routeros.api.RouterOSApiException;
import org.apache.cloudstack.network.routeros.rules.RouterOSRule;
import org.apache.cloudstack.network.routeros.rules.RouterOSRuleTranslator;

import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManagerImpl;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.IsolationType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.VirtualRouterProvider;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderVO;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.dao.RouterOSDeviceDao;
import com.cloud.network.dao.VirtualRouterProviderDao;
import com.cloud.network.element.RouterOSDeviceVO;
import com.cloud.network.element.VirtualRouterProviderVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.StaticRoute;
import com.cloud.network.vpc.StaticRouteProfile;
import com.cloud.network.vpc.Vpc;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.PasswordGenerator;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.agent.api.Answer;
import com.cloud.agent.manager.Commands;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineGuru;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineName;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;

/**
 * Deploys a Mikrotik RouterOS CHR appliance per isolated network (or per VPC)
 * from an admin-registered template and programs it over the RouterOS v7 REST
 * API.
 *
 * The appliance runs as a system VM: a {@link DomainRouterVO} of
 * {@link VirtualMachine.Type#RouterOSVm} with {@link VirtualRouter.Role#ROUTEROS_VM},
 * owned by the system account and registered with its own VirtualMachineGuru --
 * the same pattern the NetScaler VPX and internal load balancer appliances use.
 * It is therefore invisible to user VM listings, exempt from account resource
 * limits, and protected from user-initiated lifecycle operations.
 */
public class RouterOSVmManagerImpl extends ManagerBase implements RouterOSVmManager, VirtualMachineGuru, Configurable {

    protected static final String VM_NAME_PREFIX = "ros";
    protected static final String DEFAULT_OFFERING_UNIQUE_NAME = "Cloud.Com-RouterOS-CHR";
    protected static final int DEFAULT_CHR_RAM_MB = 512;
    protected static final int DEFAULT_CHR_CPU_MHZ = 1000;
    protected static final String MGMT_ACCESS_COMMENT = "cs-mgmt-access";
    protected static final long PROVISION_POLL_INTERVAL_MS = 5000L;

    private String _instance;
    private String _mgmtCidr;

    @Inject
    protected RouterOSDeviceDao _routerOSDeviceDao;
    @Inject
    protected DomainRouterDao _routerDao;
    @Inject
    protected VirtualRouterProviderDao _vrProviderDao;
    @Inject
    protected PhysicalNetworkServiceProviderDao _pNSPDao;
    @Inject
    protected PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    protected VirtualMachineManager _itMgr;
    @Inject
    protected VMTemplateDao _templateDao;
    @Inject
    protected ServiceOfferingDao _serviceOfferingDao;
    @Inject
    protected AccountManager _accountMgr;
    @Inject
    protected NetworkModel _networkModel;
    @Inject
    protected NetworkDao _networkDao;
    @Inject
    protected NetworkOfferingDao _networkOfferingDao;
    @Inject
    protected NetworkOrchestrationService _networkMgr;
    @Inject
    protected IpAddressManager _ipAddrMgr;
    @Inject
    protected IPAddressDao _ipAddressDao;
    @Inject
    protected NicDao _nicDao;
    @Inject
    protected DataCenterDao _dcDao;
    @Inject
    protected VlanDao _vlanDao;
    @Inject
    protected ConfigurationDao _configDao;

    protected RouterOSRuleTranslator _translator = new RouterOSRuleTranslator();

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        final Map<String, String> configs = _configDao.getConfiguration("AgentManager", params);
        _instance = configs.get("instance.name");
        if (_instance == null) {
            _instance = "DEFAULT";
        }
        _mgmtCidr = _configDao.getValue(Config.ManagementNetwork.key());

        if (RouterOSServiceOfferingUuid.value() == null) {
            final List<ServiceOfferingVO> offerings = _serviceOfferingDao.createSystemServiceOfferings("System Offering For RouterOS CHR",
                    DEFAULT_OFFERING_UNIQUE_NAME, 1, DEFAULT_CHR_RAM_MB, DEFAULT_CHR_CPU_MHZ, null, null, false, null,
                    Storage.ProvisioningType.THIN, true, null, true, VirtualMachine.Type.RouterOSVm, true);
            if (offerings == null || offerings.size() < 2) {
                throw new ConfigurationException("Unable to create the default service offering for RouterOS CHR appliances");
            }
        }

        _itMgr.registerGuru(VirtualMachine.Type.RouterOSVm, this);

        logger.info("{} has been configured", getName());
        return true;
    }

    @Override
    public String getConfigComponentName() {
        return RouterOSVmManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {RouterOSTemplateName, RouterOSServiceOfferingUuid, RouterOSApiPort, RouterOSApiUser, RouterOSTemplatePassword,
                RouterOSApiTimeout, RouterOSProvisionWait};
    }

    // ------------------------------------------------------------------
    // Deployment
    // ------------------------------------------------------------------

    @Override
    @DB
    public RouterOSDeviceVO deployForNetwork(final Network network, final DeployDestination dest)
            throws InsufficientCapacityException, ResourceUnavailableException, ConcurrentOperationException {
        final Network lock = _networkDao.acquireInLockTable(network.getId(), NetworkOrchestrationService.NetworkLockTimeout.value());
        if (lock == null) {
            throw new ConcurrentOperationException(String.format("Unable to lock network %s to deploy a RouterOS appliance", network));
        }
        try {
            RouterOSDeviceVO device = _routerOSDeviceDao.findByNetworkId(network.getId());
            // If the backing VM was expunged out from under us (e.g. an admin deleted
            // the instance), the stale device row would otherwise brick the network:
            // startAppliance would throw forever. Drop it and re-allocate.
            if (device != null && !applianceVmExists(device)) {
                logger.warn("RouterOS appliance {} has no backing instance (it was likely expunged); re-deploying", device);
                _routerOSDeviceDao.remove(device.getId());
                device = null;
            }
            if (device == null) {
                final Account networkOwner = _accountMgr.getAccount(network.getAccountId());
                final PublicIp sourceNatIp = _ipAddrMgr.assignSourceNatIpAddressToGuestNetwork(networkOwner, network);
                final DeploymentPlan plan = createPlan(network.getDataCenterId(), dest);
                final LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<>();
                networks.putAll(createPublicNicNetwork(sourceNatIp, plan));
                networks.putAll(createGuestNicNetwork(network));
                device = allocateAppliance(network.getId(), null, networks, plan, sourceNatIp.getAddress().addr());
            }
            startAppliance(device);
            provisionDevice(device, true);
            return device;
        } finally {
            _networkDao.releaseFromLockTable(lock.getId());
        }
    }

    @Override
    @DB
    public RouterOSDeviceVO deployForVpc(final Vpc vpc, final DeployDestination dest)
            throws InsufficientCapacityException, ResourceUnavailableException, ConcurrentOperationException {
        RouterOSDeviceVO device = _routerOSDeviceDao.findByVpcId(vpc.getId());
        if (device != null && !applianceVmExists(device)) {
            logger.warn("RouterOS appliance {} has no backing instance (it was likely expunged); re-deploying", device);
            _routerOSDeviceDao.remove(device.getId());
            device = null;
        }
        if (device == null) {
            final IPAddressVO sourceNatIp = findVpcSourceNatIp(vpc);
            final PublicIp publicIp = PublicIp.createFromAddrAndVlan(sourceNatIp, _vlanDao.findById(sourceNatIp.getVlanId()));
            final DeploymentPlan plan = createPlan(vpc.getZoneId(), dest);
            final LinkedHashMap<Network, List<? extends NicProfile>> networks = createPublicNicNetwork(publicIp, plan);
            device = allocateAppliance(null, vpc.getId(), networks, plan, publicIp.getAddress().addr());
        }
        startAppliance(device);
        provisionDevice(device, true);
        return device;
    }

    /**
     * @return true when the instance backing the device still exists (has not
     * been expunged out from under the plugin).
     */
    protected boolean applianceVmExists(final RouterOSDeviceVO device) {
        if (device.getVmInstanceId() == null) {
            return false;
        }
        final DomainRouterVO vm = _routerDao.findById(device.getVmInstanceId());
        return vm != null && vm.getState() != VirtualMachine.State.Expunging;
    }

    @Override
    public boolean addVpcTier(final Network network) throws InsufficientCapacityException, ResourceUnavailableException, ConcurrentOperationException {
        final RouterOSDeviceVO device = getDeviceForNetwork(network);
        final DomainRouterVO vm = getApplianceVm(device, network);
        NicVO tierNic = _nicDao.findByNtwkIdAndInstanceId(network.getId(), vm.getId());
        if (tierNic == null) {
            final NicProfile gatewayNic = new NicProfile();
            gatewayNic.setIPv4Address(network.getGateway());
            gatewayNic.setIPv4Gateway(network.getGateway());
            gatewayNic.setIPv4Netmask(NetUtils.getCidrNetmask(network.getCidr()));
            gatewayNic.setBroadcastUri(network.getBroadcastUri());
            gatewayNic.setBroadcastType(network.getBroadcastDomainType());
            gatewayNic.setIsolationUri(network.getBroadcastUri());
            gatewayNic.setMode(network.getMode());
            _itMgr.addVmToNetwork(vm, network, gatewayNic);
            tierNic = _nicDao.findByNtwkIdAndInstanceId(network.getId(), vm.getId());
        }
        final RouterOSApiClient client = getActiveClient(device, network);
        programGuestNetwork(client, device, network);
        return true;
    }

    @Override
    public boolean removeVpcTier(final Network network) throws ResourceUnavailableException {
        final RouterOSDeviceVO device = _routerOSDeviceDao.findByVpcId(network.getVpcId());
        if (device == null) {
            return true;
        }
        try {
            final RouterOSApiClient client = getActiveClient(device, network);
            final String networkUuid = network.getUuid();
            client.removeDhcpServerByComment(RouterOSRuleTranslator.networkComment(networkUuid, "dhcp"));
            client.removeByCommentPrefix(RouterOSApiClient.PATH_FIREWALL_FILTER, "cs-acl-" + networkUuid + "-");
            client.removeByCommentPrefix(RouterOSApiClient.PATH_FIREWALL_FILTER, "cs-net-" + networkUuid + "-");
            client.removeByCommentPrefix(RouterOSApiClient.PATH_FIREWALL_NAT, "cs-net-" + networkUuid + "-");
            client.removeByCommentPrefix(RouterOSApiClient.PATH_IP_ADDRESS, "cs-net-" + networkUuid + "-");
        } catch (final ResourceUnavailableException | RouterOSApiException e) {
            logger.warn("Failed to clean RouterOS configuration of VPC tier {}; the appliance may retain stale objects: {}", network, e.getMessage());
        }
        final DomainRouterVO vm = _routerDao.findById(device.getVmInstanceId());
        if (vm != null) {
            final NicVO tierNic = _nicDao.findByNtwkIdAndInstanceId(network.getId(), vm.getId());
            if (tierNic != null) {
                try {
                    _itMgr.removeNicFromVm(vm, tierNic);
                } catch (final ConcurrentOperationException e) {
                    throw new CloudRuntimeException(String.format("Failed to remove the RouterOS appliance NIC from tier network %s", network), e);
                }
            }
        }
        return true;
    }

    @Override
    public boolean destroyForNetwork(final Network network) throws ResourceUnavailableException, ConcurrentOperationException {
        final RouterOSDeviceVO device = _routerOSDeviceDao.findByNetworkId(network.getId());
        return destroyDevice(device);
    }

    @Override
    public boolean destroyForVpc(final Vpc vpc) throws ResourceUnavailableException, ConcurrentOperationException {
        final RouterOSDeviceVO device = _routerOSDeviceDao.findByVpcId(vpc.getId());
        return destroyDevice(device);
    }

    @Override
    public boolean stopForNetwork(final Network network) throws ResourceUnavailableException, ConcurrentOperationException {
        final RouterOSDeviceVO device = _routerOSDeviceDao.findByNetworkId(network.getId());
        return stopDevice(device);
    }

    protected boolean destroyDevice(final RouterOSDeviceVO device) throws ResourceUnavailableException, ConcurrentOperationException {
        if (device == null) {
            return true;
        }
        final DomainRouterVO vm = device.getVmInstanceId() == null ? null : _routerDao.findById(device.getVmInstanceId());
        if (vm != null) {
            logger.debug("Expunging RouterOS appliance {} backing device {}", vm.getInstanceName(), device);
            _itMgr.expunge(vm.getUuid());
            _routerDao.remove(vm.getId());
        }
        _routerOSDeviceDao.remove(device.getId());
        return true;
    }

    /**
     * Stop the backing appliance VM without destroying it, mirroring the
     * VirtualRouter contract for a non-cleanup shutdown (the device row and its
     * programmed configuration are preserved for a later re-implement).
     */
    protected boolean stopDevice(final RouterOSDeviceVO device) throws ResourceUnavailableException {
        if (device == null) {
            return true;
        }
        final DomainRouterVO vm = device.getVmInstanceId() == null ? null : _routerDao.findById(device.getVmInstanceId());
        if (vm != null && vm.getState() == VirtualMachine.State.Running) {
            logger.debug("Stopping RouterOS appliance {} backing device {}", vm.getInstanceName(), device);
            _itMgr.stop(vm.getUuid());
        }
        return true;
    }

    protected DeploymentPlan createPlan(final long dataCenterId, final DeployDestination dest) {
        if (dest != null && dest.getDataCenter() != null) {
            return new DataCenterDeployment(dest.getDataCenter().getId());
        }
        return new DataCenterDeployment(dataCenterId);
    }

    protected LinkedHashMap<Network, List<? extends NicProfile>> createPublicNicNetwork(final PublicIp sourceNatIp, final DeploymentPlan plan)
            throws InsufficientCapacityException {
        final NicProfile publicNic = new NicProfile();
        publicNic.setDefaultNic(true);
        publicNic.setIPv4Address(sourceNatIp.getAddress().addr());
        publicNic.setIPv4Gateway(sourceNatIp.getGateway());
        publicNic.setIPv4Netmask(sourceNatIp.getNetmask());
        publicNic.setMacAddress(sourceNatIp.getMacAddress());
        final Network publicNetworkOfIp = _networkDao.findById(sourceNatIp.getNetworkId());
        if (publicNetworkOfIp.getBroadcastDomainType() == BroadcastDomainType.Vxlan) {
            publicNic.setBroadcastType(BroadcastDomainType.Vxlan);
            publicNic.setBroadcastUri(BroadcastDomainType.Vxlan.toUri(sourceNatIp.getVlanTag()));
            publicNic.setIsolationUri(BroadcastDomainType.Vxlan.toUri(sourceNatIp.getVlanTag()));
        } else {
            publicNic.setBroadcastType(BroadcastDomainType.Vlan);
            publicNic.setBroadcastUri(sourceNatIp.getVlanTag() != null ? BroadcastDomainType.Vlan.toUri(sourceNatIp.getVlanTag()) : null);
            publicNic.setIsolationUri(sourceNatIp.getVlanTag() != null ? IsolationType.Vlan.toUri(sourceNatIp.getVlanTag()) : null);
        }
        final NetworkOffering publicOffering = _networkModel.getSystemAccountNetworkOfferings(NetworkOffering.SystemPublicNetwork).get(0);
        final List<? extends Network> publicNetworks = _networkMgr.setupNetwork(_accountMgr.getSystemAccount(), publicOffering, plan, null, null, false);
        final LinkedHashMap<Network, List<? extends NicProfile>> result = new LinkedHashMap<>();
        result.put(publicNetworks.get(0), new ArrayList<>(Arrays.asList(publicNic)));
        return result;
    }

    protected LinkedHashMap<Network, List<? extends NicProfile>> createGuestNicNetwork(final Network guestNetwork) {
        final NicProfile gatewayNic = new NicProfile();
        gatewayNic.setIPv4Address(guestNetwork.getGateway());
        gatewayNic.setBroadcastUri(guestNetwork.getBroadcastUri());
        gatewayNic.setBroadcastType(guestNetwork.getBroadcastDomainType());
        gatewayNic.setIsolationUri(guestNetwork.getBroadcastUri());
        gatewayNic.setMode(guestNetwork.getMode());
        gatewayNic.setIPv4Netmask(NetUtils.getCidrNetmask(guestNetwork.getCidr()));
        final LinkedHashMap<Network, List<? extends NicProfile>> result = new LinkedHashMap<>();
        result.put(guestNetwork, new ArrayList<>(Arrays.asList(gatewayNic)));
        return result;
    }

    protected RouterOSDeviceVO allocateAppliance(final Long networkId, final Long vpcId, final LinkedHashMap<Network, List<? extends NicProfile>> networks,
            final DeploymentPlan plan, final String publicIpAddress) throws InsufficientCapacityException {
        final VMTemplateVO template = _templateDao.findValidByTemplateName(RouterOSTemplateName.value());
        if (template == null) {
            throw new CloudRuntimeException(String.format("No usable template named '%s' found. Register a Mikrotik RouterOS CHR template with that name " +
                    "(or point the global setting '%s' at an existing template) before using the RouterOS provider.", RouterOSTemplateName.value(),
                    RouterOSTemplateName.key()));
        }
        final ServiceOfferingVO offering = findServiceOffering(plan.getDataCenterId());
        final Account systemAccount = _accountMgr.getSystemAccount();

        final long id = _routerDao.getNextInSequence(Long.class, "id");
        final String instanceName = VirtualMachineName.getSystemVmName(id, _instance, VM_NAME_PREFIX);
        final long elementId = findVirtualRouterProviderId(networkId, vpcId, plan.getDataCenterId());
        DomainRouterVO vm = new DomainRouterVO(id, offering.getId(), elementId, instanceName, template.getId(), template.getHypervisorType(),
                template.getGuestOSId(), systemAccount.getDomainId(), systemAccount.getId(), User.UID_SYSTEM, false,
                VirtualRouter.RedundantState.UNKNOWN, false, false, VirtualMachine.Type.RouterOSVm, vpcId);
        vm.setRole(VirtualRouter.Role.ROUTEROS_VM);
        vm.setDynamicallyScalable(template.isDynamicallyScalable());
        vm = _routerDao.persist(vm);
        _itMgr.allocate(instanceName, template, offering, networks, plan, template.getHypervisorType(), null, null);
        vm = _routerDao.findById(vm.getId());

        final String apiUrl = String.format("https://%s:%d/rest", publicIpAddress, RouterOSApiPort.value());
        final String password = PasswordGenerator.generateRandomPassword(16);
        RouterOSDeviceVO device = new RouterOSDeviceVO(networkId, vpcId, vm.getId(), apiUrl, RouterOSApiUser.value(), password);
        device = _routerOSDeviceDao.persist(device);
        logger.info("Allocated RouterOS appliance {} ({}) reachable at {}", instanceName, device, apiUrl);
        return device;
    }

    protected ServiceOfferingVO findServiceOffering(final long zoneId) {
        final String uuid = RouterOSServiceOfferingUuid.value();
        if (uuid != null && !uuid.isEmpty()) {
            final ServiceOfferingVO offering = _serviceOfferingDao.findByUuid(uuid);
            if (offering == null) {
                throw new CloudRuntimeException(String.format("Global setting '%s' points at service offering '%s' which does not exist",
                        RouterOSServiceOfferingUuid.key(), uuid));
            }
            return offering;
        }
        // Honor the zone-scoped system.vm.use.local.storage setting (mirrors InternalLoadBalancerVMManagerImpl)
        // rather than hardcoding shared storage.
        final Boolean useLocalStorage = ConfigurationManagerImpl.SystemVMUseLocalStorage.valueIn(zoneId);
        final ServiceOfferingVO offering = _serviceOfferingDao.findDefaultSystemOffering(DEFAULT_OFFERING_UNIQUE_NAME, useLocalStorage);
        if (offering == null) {
            throw new CloudRuntimeException("The default RouterOS CHR service offering is missing; set '" + RouterOSServiceOfferingUuid.key() + "'");
        }
        return offering;
    }

    /**
     * The {@code virtual_router_providers} element row backing the appliance's
     * {@link DomainRouterVO#getElementId()}, created lazily per physical-network
     * service provider (the NetScaler VPX pattern).
     */
    protected long findVirtualRouterProviderId(final Long networkId, final Long vpcId, final long zoneId) {
        final String providerName = (vpcId != null ? Network.Provider.VpcRouterOS : Network.Provider.RouterOS).getName();
        PhysicalNetworkServiceProviderVO nsp = null;
        if (networkId != null) {
            final Network network = _networkDao.findById(networkId);
            nsp = _pNSPDao.findByServiceProvider(network.getPhysicalNetworkId(), providerName);
        } else {
            for (final PhysicalNetworkVO physicalNetwork : _physicalNetworkDao.listByZone(zoneId)) {
                nsp = _pNSPDao.findByServiceProvider(physicalNetwork.getId(), providerName);
                if (nsp != null) {
                    break;
                }
            }
        }
        if (nsp == null) {
            throw new CloudRuntimeException(String.format("No %s network service provider found in zone %d to own a RouterOS appliance", providerName, zoneId));
        }
        VirtualRouterProviderVO element = _vrProviderDao.findByNspIdAndType(nsp.getId(), VirtualRouterProvider.Type.RouterOSVm);
        if (element == null) {
            element = _vrProviderDao.persist(new VirtualRouterProviderVO(nsp.getId(), VirtualRouterProvider.Type.RouterOSVm));
        }
        return element.getId();
    }

    protected void startAppliance(final RouterOSDeviceVO device) {
        final DomainRouterVO vm = _routerDao.findById(device.getVmInstanceId());
        if (vm == null) {
            throw new CloudRuntimeException("The instance backing RouterOS appliance " + device + " no longer exists");
        }
        if (vm.getState() != VirtualMachine.State.Running) {
            logger.debug("Starting RouterOS appliance {}", vm.getInstanceName());
            _itMgr.start(vm.getUuid(), null);
        }
    }

    // ------------------------------------------------------------------
    // Provisioning
    // ------------------------------------------------------------------

    /**
     * Try to reach the appliance REST API and push base configuration. When
     * the API cannot be reached within {@link #RouterOSProvisionWait} the
     * device is left in {@code RequiresBootstrap} state and the bootstrap
     * script the operator must apply (once, on the appliance console) is
     * logged; every subsequent plugin operation retries provisioning first.
     */
    protected boolean provisionDevice(final RouterOSDeviceVO device, final boolean wait) {
        if (device.getState() == RouterOSDeviceVO.State.Active) {
            return true;
        }
        RouterOSApiClient client = connect(device, wait);
        if (client == null) {
            if (device.getState() != RouterOSDeviceVO.State.RequiresBootstrap) {
                device.setState(RouterOSDeviceVO.State.RequiresBootstrap);
                _routerOSDeviceDao.update(device.getId(), device);
            }
            logger.warn("RouterOS appliance {} is not reachable at {}. Apply the following bootstrap configuration on its console, then retry the operation:\n{}",
                    device, device.getApiUrl(), buildBootstrapScript(device));
            return false;
        }
        device.setState(RouterOSDeviceVO.State.Provisioning);
        _routerOSDeviceDao.update(device.getId(), device);

        client.setIdentity("cs-" + device.getUuid());
        programManagementAccess(client);
        programBaseConfig(client, device);

        device.setState(RouterOSDeviceVO.State.Active);
        _routerOSDeviceDao.update(device.getId(), device);
        logger.info("RouterOS appliance {} provisioned and active", device);
        return true;
    }

    /**
     * Connect with the per-device credentials; fall back to the template
     * credentials and rotate the password to the generated per-device secret.
     */
    protected RouterOSApiClient connect(final RouterOSDeviceVO device, final boolean wait) {
        final long deadline = System.currentTimeMillis() + (wait ? RouterOSProvisionWait.value() * 1000L : 0L);
        do {
            final RouterOSApiClient deviceClient = createApiClient(device.getApiUrl(), device.getUsername(), device.getPassword());
            if (deviceClient.isReachable()) {
                return deviceClient;
            }
            final RouterOSApiClient templateClient = createApiClient(device.getApiUrl(), RouterOSApiUser.value(), RouterOSTemplatePassword.value());
            if (templateClient.isReachable()) {
                logger.info("Reached RouterOS appliance {} with template credentials; rotating the '{}' password", device, device.getUsername());
                templateClient.setUserPassword(device.getUsername(), device.getPassword());
                final RouterOSApiClient rotated = createApiClient(device.getApiUrl(), device.getUsername(), device.getPassword());
                if (rotated.isReachable()) {
                    return rotated;
                }
            }
            if (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(PROVISION_POLL_INTERVAL_MS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    protected RouterOSApiClient createApiClient(final String apiUrl, final String username, final String password) {
        return new RouterOSApiClient(apiUrl, new ApacheRouterOSHttpTransport(username, password, RouterOSApiTimeout.value()));
    }

    /**
     * Restrict the REST API port to the management server CIDR.
     */
    protected void programManagementAccess(final RouterOSApiClient client) {
        final List<RouterOSRule> rules = new ArrayList<>();
        final String apiPort = String.valueOf(RouterOSApiPort.value());
        if (_mgmtCidr != null && !_mgmtCidr.isEmpty()) {
            final Map<String, String> accept = new LinkedHashMap<>();
            accept.put("chain", "input");
            accept.put("action", "accept");
            accept.put("protocol", "tcp");
            accept.put("dst-port", apiPort);
            accept.put("src-address", _mgmtCidr);
            accept.put("comment", MGMT_ACCESS_COMMENT);
            rules.add(new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_FILTER, accept));

            final Map<String, String> drop = new LinkedHashMap<>();
            drop.put("chain", "input");
            drop.put("action", "drop");
            drop.put("protocol", "tcp");
            drop.put("dst-port", apiPort);
            drop.put("comment", MGMT_ACCESS_COMMENT);
            rules.add(new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_FILTER, drop));
        } else {
            logger.warn("Global setting '{}' is not set; the RouterOS API port stays reachable from any source", Config.ManagementNetwork.key());
        }
        if (!rules.isEmpty()) {
            client.ensureRules(MGMT_ACCESS_COMMENT, rules);
        }
    }

    protected void programBaseConfig(final RouterOSApiClient client, final RouterOSDeviceVO device) {
        final DomainRouterVO vm = _routerDao.findById(device.getVmInstanceId());
        final NicVO publicNic = getNicByTrafficType(vm.getId(), TrafficType.Public);
        if (publicNic == null) {
            throw new CloudRuntimeException("RouterOS appliance " + device + " has no public NIC");
        }
        final String publicInterface = interfaceName(client, publicNic);

        // public IP + default route
        final IPAddressVO publicIp = _ipAddressDao.findByIpAndDcId(vm.getDataCenterId(), publicNic.getIPv4Address());
        final String ipUuid = publicIp != null ? publicIp.getUuid() : device.getUuid();
        client.ensureRules(RouterOSRuleTranslator.publicIpComment(ipUuid), Collections.singletonList(
                _translator.publicIpAddressRule(publicNic.getIPv4Address(), publicNic.getIPv4Netmask(), publicInterface, ipUuid)));
        client.addStaticRoute(NetUtils.ALL_IP4_CIDRS, publicNic.getIPv4Gateway(), "cs-dev-" + device.getUuid() + "-defroute");

        // base conntrack acceptance
        final Map<String, String> established = new LinkedHashMap<>();
        established.put("chain", "forward");
        established.put("action", "accept");
        established.put("connection-state", "established,related");
        established.put("comment", "cs-dev-" + device.getUuid() + "-est");
        client.ensureRules("cs-dev-" + device.getUuid() + "-est", Collections.singletonList(new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_FILTER, established)));

        // per-network base configuration
        if (device.getNetworkId() != null) {
            final Network network = _networkDao.findById(device.getNetworkId());
            programGuestNetwork(client, device, network);
        } else if (device.getVpcId() != null) {
            for (final Network tier : _networkDao.listByVpc(device.getVpcId())) {
                if (_nicDao.findByNtwkIdAndInstanceId(tier.getId(), vm.getId()) != null) {
                    programGuestNetwork(client, device, tier);
                }
            }
        }
    }

    /**
     * Program the per-guest-network base objects: gateway address, source NAT,
     * firewall base rules or ACL defaults, DHCP server.
     */
    protected void programGuestNetwork(final RouterOSApiClient client, final RouterOSDeviceVO device, final Network network) {
        final DomainRouterVO vm = _routerDao.findById(device.getVmInstanceId());
        final NicVO guestNic = _nicDao.findByNtwkIdAndInstanceId(network.getId(), vm.getId());
        if (guestNic == null) {
            logger.warn("RouterOS appliance {} has no NIC in guest network {}; skipping base programming", device, network);
            return;
        }
        final NicVO publicNic = getNicByTrafficType(vm.getId(), TrafficType.Public);
        final String guestInterface = interfaceName(client, guestNic);
        final String publicInterface = interfaceName(client, publicNic);
        final String networkUuid = network.getUuid();
        final String gatewayCidr = network.getGateway() + "/" + NetUtils.getCidrSize(NetUtils.getCidrNetmask(network.getCidr()));

        // gateway address on the guest interface
        final Map<String, String> gwParams = new LinkedHashMap<>();
        gwParams.put("address", gatewayCidr);
        gwParams.put("interface", guestInterface);
        gwParams.put("comment", RouterOSRuleTranslator.networkComment(networkUuid, "gw"));
        client.ensureRules(RouterOSRuleTranslator.networkComment(networkUuid, "gw"),
                Collections.singletonList(new RouterOSRule(RouterOSApiClient.PATH_IP_ADDRESS, gwParams)));

        // source NAT
        if (isOurs(network, Network.Service.SourceNat)) {
            final String sourceNatIp = publicNic.getIPv4Address();
            client.ensureRules(RouterOSRuleTranslator.networkComment(networkUuid, "srcnat"),
                    Collections.singletonList(_translator.sourceNatRule(network.getCidr(), sourceNatIp, publicInterface, networkUuid)));
        }

        // firewall semantics: accept marked dst-nat connections, drop the rest
        if (isOurs(network, Network.Service.Firewall)) {
            final List<RouterOSRule> baseRules = new ArrayList<>();
            final Map<String, String> allowMarked = new LinkedHashMap<>();
            allowMarked.put("chain", "forward");
            allowMarked.put("action", "accept");
            allowMarked.put("connection-nat-state", "dstnat");
            allowMarked.put("connection-mark", RouterOSRuleTranslator.FIREWALL_ALLOW_MARK);
            allowMarked.put("comment", RouterOSRuleTranslator.networkComment(networkUuid, "fw-allow"));
            baseRules.add(new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_FILTER, allowMarked));
            client.ensureRules(RouterOSRuleTranslator.networkComment(networkUuid, "fw-allow"), baseRules);

            final Map<String, String> dropUnmarked = new LinkedHashMap<>();
            dropUnmarked.put("chain", "forward");
            dropUnmarked.put("action", "drop");
            dropUnmarked.put("connection-nat-state", "dstnat");
            dropUnmarked.put("connection-state", "new");
            dropUnmarked.put("out-interface", guestInterface);
            dropUnmarked.put("comment", RouterOSRuleTranslator.networkComment(networkUuid, "fw-drop"));
            client.ensureRules(RouterOSRuleTranslator.networkComment(networkUuid, "fw-drop"),
                    Collections.singletonList(new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_FILTER, dropUnmarked)));

            // egress default policy
            final NetworkOffering offering = _networkOfferingDao.findById(network.getNetworkOfferingId());
            final Map<String, String> egressDefault = new LinkedHashMap<>();
            egressDefault.put("chain", "forward");
            egressDefault.put("action", offering != null && offering.isEgressDefaultPolicy() ? "accept" : "drop");
            egressDefault.put("in-interface", guestInterface);
            egressDefault.put("out-interface", publicInterface);
            egressDefault.put("connection-state", "new");
            egressDefault.put("comment", RouterOSRuleTranslator.networkComment(networkUuid, "egress-default"));
            client.ensureRules(RouterOSRuleTranslator.networkComment(networkUuid, "egress-default"),
                    Collections.singletonList(new RouterOSRule(RouterOSApiClient.PATH_FIREWALL_FILTER, egressDefault)));
        }

        // ACL default drop rules for VPC tiers
        if (isOurs(network, Network.Service.NetworkACL)) {
            for (final NetworkACLItem.TrafficType direction : NetworkACLItem.TrafficType.values()) {
                final RouterOSRule defaultDrop = _translator.aclDefaultDropRule(networkUuid, guestInterface, direction);
                client.ensureRules(defaultDrop.getComment(), Collections.singletonList(defaultDrop));
            }
        }

        // DHCP (static leases only; CloudStack owns address allocation)
        if (isOurs(network, Network.Service.Dhcp)) {
            configureDhcp(client, network, guestInterface);
        }
    }

    protected boolean isOurs(final Network network, final Network.Service service) {
        final Network.Provider provider = network.getVpcId() != null ? Network.Provider.VpcRouterOS : Network.Provider.RouterOS;
        return _networkModel.isProviderSupportServiceInNetwork(network.getId(), service, provider);
    }

    protected String buildBootstrapScript(final RouterOSDeviceVO device) {
        final DomainRouterVO vm = _routerDao.findById(device.getVmInstanceId());
        final NicVO publicNic = getNicByTrafficType(vm.getId(), TrafficType.Public);
        final StringBuilder script = new StringBuilder();
        if (publicNic != null) {
            script.append(String.format("/ip address add address=%s/%d interface=%s%n", publicNic.getIPv4Address(),
                    NetUtils.getCidrSize(publicNic.getIPv4Netmask()), interfaceName(publicNic)));
            script.append(String.format("/ip route add dst-address=0.0.0.0/0 gateway=%s%n", publicNic.getIPv4Gateway()));
        }
        script.append(String.format("/ip service enable www-ssl%n"));
        script.append("# after applying the above, CloudStack completes provisioning over the REST API");
        return script.toString();
    }

    // ------------------------------------------------------------------
    // Rule application
    // ------------------------------------------------------------------

    @Override
    public boolean applyFirewallRules(final Network network, final List<? extends FirewallRule> rules) throws ResourceUnavailableException {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        final RouterOSDeviceVO device = getDeviceForNetwork(network);
        final RouterOSApiClient client = getActiveClient(device, network);
        final String publicInterface = publicInterfaceName(client, device);
        final NetworkOffering offering = _networkOfferingDao.findById(network.getNetworkOfferingId());
        final boolean egressDefaultPolicyAllow = offering != null && offering.isEgressDefaultPolicy();
        // Anchor for user egress rules: they must precede the per-network egress-default rule
        // (RouterOS is first-match), which programGuestNetwork already programs from the offering.
        final String egressAnchorId = egressDefaultRuleId(client, network);
        for (final FirewallRule rule : rules) {
            // The default egress policy rule (FirewallRuleType.System) is pushed by
            // NetworkOrchestrator on every implement with a fresh UUID; keying it on
            // cs-fw-<uuid> would orphan one rule per restart and its revoke would be a
            // no-op. The per-network egress-default rule is already maintained (with a
            // stable comment) by programGuestNetwork, so skip the System rule here.
            if (rule.getType() == FirewallRule.FirewallRuleType.System) {
                continue;
            }
            final String comment = RouterOSRuleTranslator.firewallRuleComment(rule);
            if (rule.getState() == FirewallRule.State.Revoke) {
                client.removeByComment(comment, RouterOSApiClient.PATH_FIREWALL_FILTER, RouterOSApiClient.PATH_FIREWALL_MANGLE);
            } else {
                final String publicIp = publicIpOf(rule);
                final List<RouterOSRule> translated = new ArrayList<>();
                for (final RouterOSRule fwRule : _translator.translateFirewallRule(rule, publicIp, publicInterface, egressDefaultPolicyAllow)) {
                    // only egress forward-filter rules need to sit ahead of the egress-default rule
                    if (egressAnchorId != null && rule.getTrafficType() == FirewallRule.TrafficType.Egress
                            && RouterOSApiClient.PATH_FIREWALL_FILTER.equals(fwRule.getPath())) {
                        translated.add(fwRule.withPlaceBefore(egressAnchorId));
                    } else {
                        translated.add(fwRule);
                    }
                }
                client.ensureRules(comment, translated);
            }
        }
        return true;
    }

    /**
     * @return the RouterOS .id of the per-network egress-default rule
     * ({@code cs-net-<uuid>-egress-default}) so user egress rules can be
     * inserted ahead of it, or null when there is none.
     */
    protected String egressDefaultRuleId(final RouterOSApiClient client, final Network network) {
        final List<Map<String, String>> anchor = client.listByComment(RouterOSApiClient.PATH_FIREWALL_FILTER,
                RouterOSRuleTranslator.networkComment(network.getUuid(), "egress-default"));
        return anchor.isEmpty() ? null : anchor.get(0).get(RouterOSApiClient.ID_FIELD);
    }

    @Override
    public boolean applyPortForwardingRules(final Network network, final List<PortForwardingRule> rules) throws ResourceUnavailableException {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        final RouterOSDeviceVO device = getDeviceForNetwork(network);
        final RouterOSApiClient client = getActiveClient(device, network);
        for (final PortForwardingRule rule : rules) {
            final String comment = RouterOSRuleTranslator.portForwardingComment(rule);
            if (rule.getState() == FirewallRule.State.Revoke) {
                client.removeNatRulesByComment(comment);
            } else {
                client.ensureRules(comment, _translator.translatePortForwardingRule(rule, publicIpOf(rule)));
            }
        }
        return true;
    }

    @Override
    public boolean applyStaticNats(final Network network, final List<? extends StaticNat> rules) throws ResourceUnavailableException {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        final RouterOSDeviceVO device = getDeviceForNetwork(network);
        final RouterOSApiClient client = getActiveClient(device, network);
        // The per-network masquerade/source-NAT rule (cs-net-<uuid>-srcnat) is a
        // catch-all in the srcnat chain; RouterOS is first-match, so a static
        // NAT's 1:1 src-nat rule must be inserted BEFORE it or it never applies.
        final String srcNatAnchorId = networkSrcNatRuleId(client, network);
        for (final StaticNat rule : rules) {
            final String comment = RouterOSRuleTranslator.staticNatComment(rule);
            if (rule.isForRevoke()) {
                client.removeNatRulesByComment(comment);
            } else {
                final IPAddressVO ip = _ipAddressDao.findById(rule.getSourceIpAddressId());
                final List<RouterOSRule> pair = new ArrayList<>();
                for (final RouterOSRule natRule : _translator.translateStaticNat(rule, ip.getAddress().addr())) {
                    // anchor only the srcnat leg ahead of the network source-NAT rule
                    if (srcNatAnchorId != null && "srcnat".equals(natRule.getParam("chain"))) {
                        pair.add(natRule.withPlaceBefore(srcNatAnchorId));
                    } else {
                        pair.add(natRule);
                    }
                }
                client.ensureRules(comment, pair);
            }
        }
        return true;
    }

    /**
     * @return the RouterOS .id of the per-network source-NAT rule
     * ({@code cs-net-<uuid>-srcnat}) so static-NAT src-nat rules can be inserted
     * ahead of it, or null when the network has no source-NAT rule.
     */
    protected String networkSrcNatRuleId(final RouterOSApiClient client, final Network network) {
        final List<Map<String, String>> anchor = client.listByComment(RouterOSApiClient.PATH_FIREWALL_NAT,
                RouterOSRuleTranslator.networkComment(network.getUuid(), "srcnat"));
        return anchor.isEmpty() ? null : anchor.get(0).get(RouterOSApiClient.ID_FIELD);
    }

    @Override
    public boolean applyIps(final Network network, final List<? extends PublicIpAddress> ips) throws ResourceUnavailableException {
        if (ips == null || ips.isEmpty()) {
            return true;
        }
        final RouterOSDeviceVO device = getDeviceForNetwork(network);
        final RouterOSApiClient client = getActiveClient(device, network);
        final String publicInterface = publicInterfaceName(client, device);
        for (final PublicIpAddress ip : ips) {
            final String comment = RouterOSRuleTranslator.publicIpComment(ip.getUuid());
            if (ip.getState() == IpAddress.State.Releasing) {
                client.removeIpAddressesByComment(comment);
            } else {
                client.ensureRules(comment, Collections.singletonList(
                        _translator.publicIpAddressRule(ip.getAddress().addr(), ip.getNetmask(), publicInterface, ip.getUuid())));
            }
        }
        return true;
    }

    @Override
    public boolean applyNetworkACLs(final Network network, final List<? extends NetworkACLItem> rules) throws ResourceUnavailableException {
        final RouterOSDeviceVO device = getDeviceForNetwork(network);
        final RouterOSApiClient client = getActiveClient(device, network);
        final DomainRouterVO vm = getApplianceVm(device, network);
        final NicVO tierNic = _nicDao.findByNtwkIdAndInstanceId(network.getId(), vm.getId());
        if (tierNic == null) {
            throw new ResourceUnavailableException("The RouterOS appliance has no NIC in the tier network yet", Network.class, network.getId());
        }
        final String tierInterface = interfaceName(client, tierNic);
        final String networkUuid = network.getUuid();

        // ensure the default drop anchors exist and grab their ids for place-before
        final Map<NetworkACLItem.TrafficType, String> anchors = new LinkedHashMap<>();
        for (final NetworkACLItem.TrafficType direction : NetworkACLItem.TrafficType.values()) {
            final RouterOSRule defaultDrop = _translator.aclDefaultDropRule(networkUuid, tierInterface, direction);
            client.ensureRules(defaultDrop.getComment(), Collections.singletonList(defaultDrop));
            final List<Map<String, String>> anchor = client.listByComment(RouterOSApiClient.PATH_FIREWALL_FILTER, defaultDrop.getComment());
            if (!anchor.isEmpty()) {
                anchors.put(direction, anchor.get(0).get(RouterOSApiClient.ID_FIELD));
            }
        }

        // Full resync of the tier ACL. Translate ALL items FIRST (ordered by number,
        // ahead of the anchors) so that a translation failure (e.g. an unexpected
        // protocol) aborts before we have wiped the tier's existing ACL rules.
        final List<NetworkACLItem> sorted = new ArrayList<>(rules);
        sorted.sort((a, b) -> Integer.compare(a.getNumber(), b.getNumber()));
        final List<RouterOSRule> toAdd = new ArrayList<>();
        for (final NetworkACLItem item : sorted) {
            if (item.getState() == NetworkACLItem.State.Revoke) {
                continue;
            }
            final String anchorId = anchors.get(item.getTrafficType());
            for (RouterOSRule rule : _translator.translateAclItem(item, networkUuid, tierInterface, network.getCidr())) {
                if (anchorId != null) {
                    rule = rule.withPlaceBefore(anchorId);
                }
                toAdd.add(rule);
            }
        }
        // Only now that every item translated cleanly do we remove the old set and re-add.
        client.removeByCommentPrefix(RouterOSApiClient.PATH_FIREWALL_FILTER, RouterOSRuleTranslator.aclItemCommentPrefix(networkUuid));
        for (final RouterOSRule rule : toAdd) {
            client.add(rule.getPath(), rule.getParams());
        }
        return true;
    }

    @Override
    public boolean applyStaticRoutes(final Vpc vpc, final List<StaticRouteProfile> routes) throws ResourceUnavailableException {
        if (routes == null || routes.isEmpty()) {
            return true;
        }
        final RouterOSDeviceVO device = _routerOSDeviceDao.findByVpcId(vpc.getId());
        if (device == null) {
            throw new ResourceUnavailableException("No RouterOS appliance deployed for VPC " + vpc.getName(), Vpc.class, vpc.getId());
        }
        final RouterOSApiClient client = getActiveClient(device, null);
        for (final StaticRouteProfile route : routes) {
            final String comment = RouterOSRuleTranslator.staticRouteComment(route);
            if (route.getState() == StaticRoute.State.Revoke) {
                client.removeStaticRoutesByComment(comment);
            } else {
                client.ensureRules(comment, Collections.singletonList(_translator.translateStaticRoute(route)));
            }
        }
        return true;
    }

    @Override
    public boolean updateSourceNatIp(final Vpc vpc, final IpAddress address) {
        final RouterOSDeviceVO device = _routerOSDeviceDao.findByVpcId(vpc.getId());
        if (device == null) {
            logger.warn("No RouterOS appliance for VPC {}; cannot update the source NAT IP", vpc.getName());
            return false;
        }
        try {
            final RouterOSApiClient client = getActiveClient(device, null);
            final String publicInterface = publicInterfaceName(client, device);
            final DomainRouterVO vm = _routerDao.findById(device.getVmInstanceId());
            for (final Network tier : _networkDao.listByVpc(vpc.getId())) {
                if (_nicDao.findByNtwkIdAndInstanceId(tier.getId(), vm.getId()) == null || !isOurs(tier, Network.Service.SourceNat)) {
                    continue;
                }
                client.ensureRules(RouterOSRuleTranslator.networkComment(tier.getUuid(), "srcnat"), Collections.singletonList(
                        _translator.sourceNatRule(tier.getCidr(), address.getAddress().addr(), publicInterface, tier.getUuid())));
            }
            return true;
        } catch (final ResourceUnavailableException | RouterOSApiException e) {
            logger.error("Failed to update the source NAT IP on RouterOS appliance {}: {}", device, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // DHCP
    // ------------------------------------------------------------------

    @Override
    public boolean configureDhcpForNetwork(final Network network) throws ResourceUnavailableException {
        final RouterOSDeviceVO device = getDeviceForNetwork(network);
        final RouterOSApiClient client = getActiveClient(device, network);
        final DomainRouterVO vm = getApplianceVm(device, network);
        final NicVO guestNic = _nicDao.findByNtwkIdAndInstanceId(network.getId(), vm.getId());
        if (guestNic == null) {
            throw new ResourceUnavailableException("The RouterOS appliance has no NIC in the guest network yet", Network.class, network.getId());
        }
        configureDhcp(client, network, interfaceName(client, guestNic));
        return true;
    }

    protected void configureDhcp(final RouterOSApiClient client, final Network network, final String guestInterface) {
        final String comment = RouterOSRuleTranslator.networkComment(network.getUuid(), "dhcp");
        client.setDhcpServer(dhcpServerName(network), guestInterface, network.getCidr(), network.getGateway(), dnsServers(network), comment);
    }

    @Override
    public boolean removeDhcpForNetwork(final Network network) throws ResourceUnavailableException {
        final RouterOSDeviceVO device = deviceForNetworkOrNull(network);
        if (device == null) {
            return true;
        }
        final RouterOSApiClient client = getActiveClient(device, network);
        client.removeDhcpServerByComment(RouterOSRuleTranslator.networkComment(network.getUuid(), "dhcp"));
        return true;
    }

    @Override
    public boolean addDhcpEntry(final Network network, final NicProfile nic) throws ResourceUnavailableException {
        final RouterOSDeviceVO device = getDeviceForNetwork(network);
        final RouterOSApiClient client = getActiveClient(device, network);
        client.addDhcpLease(dhcpServerName(network), nic.getIPv4Address(), nic.getMacAddress(), RouterOSRuleTranslator.nicComment(nic.getUuid()));
        return true;
    }

    @Override
    public boolean removeDhcpEntry(final Network network, final NicProfile nic) throws ResourceUnavailableException {
        final RouterOSDeviceVO device = deviceForNetworkOrNull(network);
        if (device == null) {
            return true;
        }
        final RouterOSApiClient client = getActiveClient(device, network);
        client.removeDhcpLeasesByComment(RouterOSRuleTranslator.nicComment(nic.getUuid()));
        return true;
    }

    protected String dhcpServerName(final Network network) {
        return "cs-" + network.getUuid();
    }

    protected String dnsServers(final Network network) {
        final List<String> servers = new ArrayList<>();
        if (network.getDns1() != null && !network.getDns1().isEmpty()) {
            servers.add(network.getDns1());
            if (network.getDns2() != null && !network.getDns2().isEmpty()) {
                servers.add(network.getDns2());
            }
        } else {
            final DataCenterVO zone = _dcDao.findById(network.getDataCenterId());
            if (zone != null) {
                if (zone.getDns1() != null) {
                    servers.add(zone.getDns1());
                }
                if (zone.getDns2() != null) {
                    servers.add(zone.getDns2());
                }
            }
        }
        return String.join(",", servers);
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    protected RouterOSDeviceVO getDeviceForNetwork(final Network network) throws ResourceUnavailableException {
        final RouterOSDeviceVO device = deviceForNetworkOrNull(network);
        if (device == null) {
            throw new ResourceUnavailableException("No RouterOS appliance deployed for this network", Network.class, network.getId());
        }
        return device;
    }

    protected RouterOSDeviceVO deviceForNetworkOrNull(final Network network) {
        if (network.getVpcId() != null) {
            return _routerOSDeviceDao.findByVpcId(network.getVpcId());
        }
        return _routerOSDeviceDao.findByNetworkId(network.getId());
    }

    /**
     * @return a client for a device in Active state, lazily retrying
     * provisioning when the appliance has not been bootstrapped yet.
     */
    protected RouterOSApiClient getActiveClient(final RouterOSDeviceVO device, final Network network) throws ResourceUnavailableException {
        if (device.getState() != RouterOSDeviceVO.State.Active && !provisionDevice(device, false)) {
            throw new ResourceUnavailableException(String.format("RouterOS appliance %s is not reachable (state %s); bootstrap it and retry",
                    device, device.getState()), Network.class, network != null ? network.getId() : (device.getNetworkId() != null ? device.getNetworkId() : 0L));
        }
        return createApiClient(device.getApiUrl(), device.getUsername(), device.getPassword());
    }

    protected DomainRouterVO getApplianceVm(final RouterOSDeviceVO device, final Network network) throws ResourceUnavailableException {
        final DomainRouterVO vm = device.getVmInstanceId() == null ? null : _routerDao.findById(device.getVmInstanceId());
        if (vm == null) {
            throw new ResourceUnavailableException("The instance backing RouterOS appliance " + device + " no longer exists", Network.class,
                    network != null ? network.getId() : 0L);
        }
        return vm;
    }

    protected NicVO getNicByTrafficType(final long vmId, final TrafficType trafficType) {
        for (final NicVO nic : _nicDao.listByVmId(vmId)) {
            final Network network = _networkModel.getNetwork(nic.getNetworkId());
            if (network != null && network.getTrafficType() == trafficType) {
                return nic;
            }
        }
        return null;
    }

    protected String publicInterfaceName(final RouterOSDeviceVO device) {
        final NicVO publicNic = getNicByTrafficType(device.getVmInstanceId(), TrafficType.Public);
        if (publicNic == null) {
            throw new CloudRuntimeException("RouterOS appliance " + device + " has no public NIC");
        }
        return interfaceName(publicNic);
    }

    protected String publicInterfaceName(final RouterOSApiClient client, final RouterOSDeviceVO device) {
        final NicVO publicNic = getNicByTrafficType(device.getVmInstanceId(), TrafficType.Public);
        if (publicNic == null) {
            throw new CloudRuntimeException("RouterOS appliance " + device + " has no public NIC");
        }
        return interfaceName(client, publicNic);
    }

    /**
     * Positional fallback: RouterOS names interfaces ether1..N in device order;
     * CloudStack NIC device ids start at 0. Used only when the RouterOS
     * interface cannot be resolved by MAC (e.g. before the appliance is
     * reachable).
     */
    protected String interfaceName(final NicVO nic) {
        return "ether" + (nic.getDeviceId() + 1);
    }

    /**
     * Resolve the RouterOS interface name for a NIC by matching its MAC address
     * against {@code /interface}. VPC tier hot-plug/unplug can reorder ether
     * interfaces, so device-id arithmetic ({@link #interfaceName(NicVO)}) drifts;
     * the MAC is stable. Falls back to the positional name when the interface
     * cannot be resolved by MAC.
     */
    protected String interfaceName(final RouterOSApiClient client, final NicVO nic) {
        final String mac = nic.getMacAddress();
        if (client != null && mac != null && !mac.isEmpty()) {
            try {
                for (final Map<String, String> iface : client.listInterfaces()) {
                    final String ifaceMac = iface.get("mac-address");
                    final String ifaceName = iface.get("name");
                    if (ifaceMac != null && ifaceName != null && ifaceMac.equalsIgnoreCase(mac)) {
                        return ifaceName;
                    }
                }
                logger.warn("No RouterOS interface with MAC {} found; falling back to positional name for NIC {}", mac, nic.getUuid());
            } catch (final RuntimeException e) {
                logger.warn("Failed to resolve RouterOS interface by MAC {}; falling back to positional name: {}", mac, e.getMessage());
            }
        }
        return interfaceName(nic);
    }

    protected String publicIpOf(final FirewallRule rule) {
        if (rule.getSourceIpAddressId() == null) {
            return null;
        }
        final IPAddressVO ip = _ipAddressDao.findById(rule.getSourceIpAddressId());
        return ip == null ? null : ip.getAddress().addr();
    }

    protected IPAddressVO findVpcSourceNatIp(final Vpc vpc) {
        final List<IPAddressVO> ips = _ipAddressDao.listByAssociatedVpc(vpc.getId(), true);
        if (ips == null || ips.isEmpty()) {
            throw new CloudRuntimeException(String.format("VPC %s has no source NAT IP; cannot deploy a RouterOS appliance for it", vpc.getName()));
        }
        return ips.get(0);
    }

    protected DataCenter getZone(final long zoneId) {
        return _dcDao.findById(zoneId);
    }

    // ------------------------------------------------------------------
    // VirtualMachineGuru
    // ------------------------------------------------------------------
    // The CHR boots a vendor image that takes no CloudStack boot args and runs
    // no systemvm agent; all configuration happens over the RouterOS REST API
    // after boot (provisionDevice). The guru callbacks are therefore no-ops,
    // matching the NetScaler VPX appliance guru.

    @Override
    public boolean finalizeVirtualMachineProfile(final VirtualMachineProfile profile, final DeployDestination dest, final ReservationContext context) {
        return true;
    }

    @Override
    public boolean finalizeDeployment(final Commands cmds, final VirtualMachineProfile profile, final DeployDestination dest, final ReservationContext context) {
        return true;
    }

    @Override
    public boolean finalizeStart(final VirtualMachineProfile profile, final long hostId, final Commands cmds, final ReservationContext context) {
        return true;
    }

    @Override
    public boolean finalizeCommandsOnStart(final Commands cmds, final VirtualMachineProfile profile) {
        return true;
    }

    @Override
    public void finalizeStop(final VirtualMachineProfile profile, final Answer answer) {
    }

    @Override
    public void finalizeExpunge(final VirtualMachine vm) {
    }

    @Override
    public void prepareStop(final VirtualMachineProfile profile) {
    }

    @Override
    public void finalizeUnmanage(final VirtualMachine vm) {
    }
}
