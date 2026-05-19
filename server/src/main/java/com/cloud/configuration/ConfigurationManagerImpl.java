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

import static com.cloud.configuration.Config.SecStorageAllowedInternalDownloadSites;
import static com.cloud.offering.NetworkOffering.RoutingMode.Dynamic;
import static com.cloud.offering.NetworkOffering.RoutingMode.Static;
import static org.apache.cloudstack.framework.config.ConfigKey.CATEGORY_SYSTEM;

import java.lang.reflect.Field;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;

import java.util.HashMap;

import java.util.List;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.utils.DomainHelper;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.affinity.AffinityGroupService;
import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.agent.lb.IndirectAgentLB;
import org.apache.cloudstack.agent.lb.IndirectAgentLBServiceImpl;

import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.config.ResetCfgCmd;
import org.apache.cloudstack.api.command.admin.config.UpdateCfgCmd;
import org.apache.cloudstack.api.command.admin.network.CloneNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.CreateGuestNetworkIpv6PrefixCmd;
import org.apache.cloudstack.api.command.admin.network.CreateManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteGuestNetworkIpv6PrefixCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.ListGuestNetworkIpv6PrefixesCmd;
import org.apache.cloudstack.api.command.admin.network.NetworkOfferingBaseCmd;
import org.apache.cloudstack.api.command.admin.network.UpdateNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.UpdatePodManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.offering.CloneDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CloneServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CreateDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CreateServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.IsAccountAllowedToCreateOfferingsWithTagsCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.pod.DeletePodCmd;
import org.apache.cloudstack.api.command.admin.pod.UpdatePodCmd;
import org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd;
import org.apache.cloudstack.api.command.admin.region.DeletePortableIpRangeCmd;
import org.apache.cloudstack.api.command.admin.region.ListPortableIpRangesCmd;
import org.apache.cloudstack.api.command.admin.vlan.CreateVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.DedicatePublicIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.DeleteVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.ReleasePublicIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.UpdateVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.zone.CreateZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.DeleteZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.UpdateZoneCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkOfferingsCmd;
import org.apache.cloudstack.config.ApiServiceConfiguration;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.ValidatedConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.dao.ConfigurationGroupDao;
import org.apache.cloudstack.framework.config.dao.ConfigurationSubGroupDao;
import org.apache.cloudstack.framework.config.impl.ConfigurationGroupVO;
import org.apache.cloudstack.framework.config.impl.ConfigurationSubGroupVO;
import org.apache.cloudstack.framework.config.impl.ConfigurationVO;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.MessageSubscriber;
import org.apache.cloudstack.framework.messagebus.PublishScope;

import org.apache.cloudstack.region.PortableIp;
import org.apache.cloudstack.region.PortableIpRange;
import org.apache.cloudstack.region.PortableIpRangeDao;
import org.apache.cloudstack.region.PortableIpRangeVO;
import org.apache.cloudstack.region.dao.RegionDao;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.userdata.UserDataManager;
import org.apache.cloudstack.utils.jsinterpreter.TagAsRuleHelper;
import org.apache.cloudstack.vm.UnmanagedVMsManager;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.alert.AlertManager;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.NetworkOfferingJoinDao;

import com.cloud.capacity.CapacityManager;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterGuestIpv6Prefix;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Pod;
import com.cloud.dc.Vlan;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.AccountVlanMapDao;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterDetailsDao;
import com.cloud.dc.dao.DataCenterIpAddressDao;
import com.cloud.dc.dao.DataCenterLinkLocalIpAddressDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.dao.DomainVlanMapDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.dc.dao.VlanDetailsDao;
import com.cloud.dc.dao.VsphereStoragePolicyDao;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainDetailVO;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.domain.dao.DomainDetailsDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.host.HostTagVO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.ExternalProvisioner;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.kvm.dpdk.DpdkHelper;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;

import com.cloud.network.dao.UserIpv6AddressDao;
import com.cloud.network.netris.NetrisService;

import com.cloud.network.vpc.VpcManager;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offering.NetworkOffering.Detail;
import com.cloud.offering.ServiceOffering;

import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.org.Grouping;
import com.cloud.org.Grouping.AllocationState;
import com.cloud.projects.ProjectManager;
import com.cloud.server.ManagementService;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountDetailVO;
import com.cloud.user.AccountDetailsDao;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountManagerImpl;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;

import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.NicIpAliasDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.common.base.Enums;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

public class ConfigurationManagerImpl extends ManagerBase implements ConfigurationManager, ConfigurationService, Configurable {
    public static final String PERACCOUNT = "peraccount";
    public static final String PERZONE = "perzone";
    public static final String CLUSTER_NODES_DEFAULT_START_SSH_PORT = ConfigurationValueValidator.CLUSTER_NODES_DEFAULT_START_SSH_PORT;

    @Inject
    EntityManager _entityMgr;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    ConfigurationGroupDao _configGroupDao;
    @Inject
    ConfigurationSubGroupDao _configSubGroupDao;
    @Inject
    ConfigDepot _configDepot;
    @Inject
    HostPodDao _podDao;
    @Inject
    HostDao _hostDao;
    @Inject
    VolumeDao _volumeDao;
    @Inject
    VMInstanceDao _vmInstanceDao;
    @Inject
    AccountVlanMapDao _accountVlanMapDao;
    @Inject
    DomainVlanMapDao _domainVlanMapDao;
    @Inject
    PodVlanMapDao podVlanMapDao;
    @Inject
    DataCenterDao _zoneDao;
    @Inject
    DomainDao _domainDao;
    @Inject
    ServiceOfferingDao _serviceOfferingDao;
    @Inject
    ServiceOfferingDetailsDao _serviceOfferingDetailsDao;
    @Inject
    DiskOfferingDao _diskOfferingDao;
    @Inject
    DiskOfferingDetailsDao diskOfferingDetailsDao;
    @Inject
    VgpuProfileDao vgpuProfileDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    NetworkOfferingJoinDao networkOfferingJoinDao;
    @Inject
    NetworkOfferingDetailsDao networkOfferingDetailsDao;
    @Inject
    VlanDao _vlanDao;
    @Inject
    VlanDetailsDao vlanDetailsDao;
    @Inject
    IPAddressDao _publicIpAddressDao;
    @Inject
    DataCenterIpAddressDao _privateIpAddressDao;
    @Inject
    AccountDao _accountDao;
    @Inject
    NetworkDao _networkDao;
    @Inject
    AccountManager _accountMgr;
    @Inject
    NetworkOrchestrationService _networkMgr;
    @Inject
    NetworkService _networkSvc;
    @Inject
    NetworkModel _networkModel;
    @Inject
    ClusterDao _clusterDao;
    @Inject
    AlertManager _alertMgr;
    @Inject
    DomainHelper domainHelper;
    List<SecurityChecker> _secChecker;
    List<ExternalProvisioner> externalProvisioners;

    @Inject
    CapacityDao _capacityDao;
    @Inject
    ResourceLimitService _resourceLimitMgr;
    @Inject
    ReservationDao reservationDao;
    @Inject
    ProjectManager _projectMgr;
    @Inject
    NetworkOfferingServiceMapDao _ntwkOffServiceMapDao;
    @Inject
    PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    PhysicalNetworkTrafficTypeDao _trafficTypeDao;
    @Inject
    FirewallRulesDao _firewallDao;
    @Inject
    VpcManager _vpcMgr;
    @Inject
    UserDao _userDao;
    @Inject
    PortableIpRangeDao _portableIpRangeDao;
    @Inject
    RegionDao _regionDao;
    @Inject
    DataCenterDetailsDao _dcDetailsDao;
    @Inject
    ClusterDetailsDao _clusterDetailsDao;
    @Inject
    StoragePoolDetailsDao _storagePoolDetailsDao;
    @Inject
    AccountDetailsDao _accountDetailsDao;
    @Inject
    DomainDetailsDao _domainDetailsDao;
    @Inject
    PrimaryDataStoreDao _storagePoolDao;
    @Inject
    NicIpAliasDao _nicIpAliasDao;
    @Inject
    public ManagementService _mgr;
    @Inject
    DedicatedResourceDao _dedicatedDao;
    @Inject
    IpAddressManager _ipAddrMgr;
    @Inject
    AffinityGroupDao _affinityGroupDao;
    @Inject
    AffinityGroupService _affinityGroupService;
    @Inject
    StorageManager _storageManager;
    @Inject
    ImageStoreDao _imageStoreDao;
    @Inject
    ImageStoreDetailsDao _imageStoreDetailsDao;
    @Inject
    MessageBus messageBus;
    @Inject
    AgentManager _agentManager;
    @Inject
    IndirectAgentLB _indirectAgentLB;
    @Inject
    VMTemplateZoneDao templateZoneDao;
    @Inject
    VsphereStoragePolicyDao vsphereStoragePolicyDao;
    @Inject
    HostTagsDao hostTagDao;
    @Inject
    StoragePoolTagsDao storagePoolTagDao;
    @Inject
    AnnotationDao annotationDao;
    @Inject
    UserIpv6AddressDao _ipv6Dao;
    @Inject
    NsxProviderDao nsxProviderDao;
    @Inject
    NetrisProviderDao netrisProviderDao;
    @Inject
    private jakarta.inject.Provider<NetrisService> netrisServiceProvider;
    @Inject
    VMLeaseManager vmLeaseManager;

    // FIXME - why don't we have interface for DataCenterLinkLocalIpAddressDao?
    @Inject
    protected DataCenterLinkLocalIpAddressDao _linkLocalIpAllocDao;

    @Inject
    protected PodService podService;

    @Inject
    protected DiskOfferingService diskOfferingService;

    @Inject
    protected PortableIpRangeService portableIpRangeService;

    @Inject
    protected ZoneService zoneService;

    @Inject
    protected ServiceOfferingService serviceOfferingService;

    @Inject
    protected ConfigurationResetService configurationResetService;

    @Inject
    protected VlanService vlanService;

    @Inject
    protected NetworkOfferingService networkOfferingService;

    @Inject
    protected GuestIpv6PrefixService guestIpv6PrefixService;

    @Inject
    protected OfferingCloneParameterService offeringCloneParameterService;

    private long _defaultPageSize = Long.parseLong(Config.DefaultPageSize.getDefaultValue());
    // Validation sets now live in ConfigurationValueValidator as immutable static
    // constants. These instance fields are kept (and back the same data) so any
    // subclass or test that referenced them directly continues to work.
    private final Set<String> configValuesForValidation = ConfigurationValueValidator.POSITIVE_INTEGER_CONFIGS;
    private final Set<String> configKeysAllowedOnlyForDefaultAdmin = ConfigurationValueValidator.CONFIG_KEYS_ALLOWED_ONLY_FOR_DEFAULT_ADMIN;
    private final Set<String> weightBasedParametersForValidation = ConfigurationValueValidator.WEIGHT_BASED_PARAMETERS;
    private final Set<String> overprovisioningFactorsForValidation = ConfigurationValueValidator.OVERPROVISIONING_FACTORS;

    public static final ConfigKey<Boolean> SystemVMUseLocalStorage = new ConfigKey<>(Boolean.class, "system.vm.use.local.storage", "Advanced", "false",
            "Indicates whether to use local storage pools or shared storage pools for system VMs.", false, ConfigKey.Scope.Zone, null);

    public final static ConfigKey<Long> BYTES_MAX_READ_LENGTH= new ConfigKey<>(Long.class, "vm.disk.bytes.maximum.read.length", "Advanced", "0",
            "Maximum Bytes read burst duration (seconds). If '0' (zero) then does not check for maximum burst length.", true, ConfigKey.Scope.Global, null);
    public final static ConfigKey<Long> BYTES_MAX_WRITE_LENGTH = new ConfigKey<>(Long.class, "vm.disk.bytes.maximum.write.length", "Advanced", "0",
            "Maximum Bytes write burst duration (seconds). If '0' (zero) then does not check for maximum burst length.", true, ConfigKey.Scope.Global, null);
    public final static ConfigKey<Long> IOPS_MAX_READ_LENGTH = new ConfigKey<>(Long.class, "vm.disk.iops.maximum.read.length", "Advanced", "0",
            "Maximum IOPS read burst duration (seconds). If '0' (zero) then does not check for maximum burst length.", true, ConfigKey.Scope.Global, null);
    public final static ConfigKey<Long> IOPS_MAX_WRITE_LENGTH = new ConfigKey<>(Long.class, "vm.disk.iops.maximum.write.length", "Advanced", "0",
            "Maximum IOPS write burst duration (seconds). If '0' (zero) then does not check for maximum burst length.", true, ConfigKey.Scope.Global, null);
    public static final ConfigKey<Boolean> ADD_HOST_ON_SERVICE_RESTART_KVM = new ConfigKey<>(Boolean.class, "add.host.on.service.restart.kvm", "Advanced", "true",
            "Indicates whether the host will be added back to cloudstack after restarting agent service on host. If false it won't be added back even after service restart",
            true, ConfigKey.Scope.Global, null);
    public static final ConfigKey<Boolean> SET_HOST_DOWN_TO_MAINTENANCE = new ConfigKey<>(Boolean.class, "set.host.down.to.maintenance", "Advanced", "false",
            "Indicates whether the host in down state can be put into maintenance state so that it's not enabled after it comes back.",
            true, ConfigKey.Scope.Zone, null);
    public static final ConfigKey<Boolean> ENABLE_ACCOUNT_SETTINGS_FOR_DOMAIN = new ConfigKey<>(Boolean.class, "enable.account.settings.for.domain", "Advanced", "false",
            "Indicates whether to add Account settings for domain. If True, Account settings will be added to domain settings, all Accounts in the domain will inherit the domain setting if Account setting is not set.", true, ConfigKey.Scope.Global, null);
    public static final ConfigKey<Boolean> ENABLE_DOMAIN_SETTINGS_FOR_CHILD_DOMAIN = new ConfigKey<>(Boolean.class, "enable.domain.settings.for.child.domain", "Advanced", "false",
            "Indicates whether the settings of parent domain should be applied for child domain. If true, the child domain will get value from parent domain if its not configured in child domain else global value is taken.",
            true, ConfigKey.Scope.Global, null);

    public static ConfigKey<Integer> VM_SERVICE_OFFERING_MAX_CPU_CORES = new ConfigKey<>("Advanced", Integer.class, "vm.serviceoffering.cpu.cores.max", "0", "Maximum CPU cores "
            + "for Instance service offering. If 0 - no limitation", true);

    public static ConfigKey<Integer> VM_SERVICE_OFFERING_MAX_RAM_SIZE = new ConfigKey<>("Advanced", Integer.class, "vm.serviceoffering.ram.size.max", "0", "Maximum RAM size in "
            + "MB for Instance service offering. If 0 - no limitation", true);

    public static final ConfigKey<Boolean> MIGRATE_VM_ACROSS_CLUSTERS = new ConfigKey<>(Boolean.class, "migrate.vm.across.clusters", "Advanced", "false",
            "Indicates whether the Instance can be migrated to different cluster if no host is found in same cluster",true, ConfigKey.Scope.Zone, null);

    public static final ConfigKey<Boolean> ALLOW_DOMAIN_ADMINS_TO_CREATE_TAGGED_OFFERINGS = new ConfigKey<>(Boolean.class, "allow.domain.admins.to.create.tagged.offerings", "Advanced",
            "false", "Allow domain admins to create offerings with tags.", true, ConfigKey.Scope.Account, null);

    public static final ConfigKey<Boolean> EXPOSE_ERRORS_TO_USER = new ConfigKey<>(Boolean.class, "expose.errors.to.user", ConfigKey.CATEGORY_ADVANCED,
            "false", "If set to true, detailed error messages will be returned to all user roles. If false, detailed errors are only shown to admin users", true, ConfigKey.Scope.Global, null);

    public static final ConfigKey<Long> DELETE_QUERY_BATCH_SIZE = new ConfigKey<>("Advanced", Long.class, "delete.query.batch.size", "0",
            "Indicates the limit applied while deleting entries in bulk. With this, the delete query will apply the limit as many times as necessary," +
                    " to delete all the entries. This is advised when retaining several days of records, which can lead to slowness. <= 0 means that no limit will " +
                    "be applied. Default value is 0. For now, this is used for deletion of VM stats, volume stats, and usage records.", true);

    private static final String IOPS_READ_RATE = "IOPS Read";
    private static final String IOPS_WRITE_RATE = "IOPS Write";
    private static final String BYTES_READ_RATE = "Bytes Read";
    private static final String BYTES_WRITE_RATE = "Bytes Write";

    private static final Set<Provider> VPC_ONLY_PROVIDERS = Sets.newHashSet(Provider.VPCVirtualRouter, Provider.InternalLbVm);

    private static final List<String> SUPPORTED_ROUTING_MODE_STRS = Arrays.asList(Static.toString().toLowerCase(), Dynamic.toString().toLowerCase());
    public List<ExternalProvisioner> getExternalProvisioners() {
        return externalProvisioners;
    }

    public void setExternalProvisioners(final List<ExternalProvisioner> externalProvisioners) {
        this.externalProvisioners = externalProvisioners;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        final String defaultPageSizeString = _configDao.getValue(Config.DefaultPageSize.key());
        _defaultPageSize = NumbersUtil.parseLong(defaultPageSizeString, Long.parseLong(Config.DefaultPageSize.getDefaultValue()));

        // Validation sets are now immutable static constants in ConfigurationValueValidator;
        // no per-instance population needed.
        initMessageBusListener();
        return true;
    }

    /**
     * @deprecated The sets these methods used to populate now live as immutable
     *             static constants in {@link ConfigurationValueValidator}. These
     *             methods are kept as no-ops for back-compat with tests/spies.
     */
    @Deprecated
    protected void populateConfigValuesForValidationSet() {
        // No-op: see ConfigurationValueValidator.POSITIVE_INTEGER_CONFIGS
    }

    /** @deprecated see {@link #populateConfigValuesForValidationSet()} */
    @Deprecated
    protected void weightBasedParametersForValidation() {
        // No-op: see ConfigurationValueValidator.WEIGHT_BASED_PARAMETERS
    }

    /** @deprecated see {@link #populateConfigValuesForValidationSet()} */
    @Deprecated
    protected void overProvisioningFactorsForValidation() {
        // No-op: see ConfigurationValueValidator.OVERPROVISIONING_FACTORS
    }

    /** @deprecated see {@link #populateConfigValuesForValidationSet()} */
    @Deprecated
    protected void populateConfigKeysAllowedOnlyForDefaultAdmin() {
        // No-op: see ConfigurationValueValidator.CONFIG_KEYS_ALLOWED_ONLY_FOR_DEFAULT_ADMIN
    }

    private void initMessageBusListener() {
        messageBus.subscribe(EventTypes.EVENT_CONFIGURATION_VALUE_EDIT, new MessageSubscriber() {
            @Override
            public void onPublishMessage(String senderAddress, String subject, Object args) {
                Ternary<String, ConfigKey.Scope, Long> settingUpdated = (Ternary<String, ConfigKey.Scope, Long>) args;
                String settingNameUpdated = settingUpdated.first();
                if (StringUtils.isEmpty(settingNameUpdated)) {
                    return;
                }
                if (settingNameUpdated.equals(ApiServiceConfiguration.ManagementServerAddresses.key()) ||
                        settingNameUpdated.equals(IndirectAgentLBServiceImpl.IndirectAgentLBAlgorithm.key())) {
                    _indirectAgentLB.propagateMSListToAgents(false);
                } else if (settingNameUpdated.equals(Config.RouterAggregationCommandEachTimeout.toString())
                        ||  settingNameUpdated.equals(Config.MigrateWait.toString())) {
                    Map<String, String> params = new HashMap<>();
                    params.put(Config.RouterAggregationCommandEachTimeout.toString(), _configDao.getValue(Config.RouterAggregationCommandEachTimeout.toString()));
                    params.put(Config.MigrateWait.toString(), _configDao.getValue(Config.MigrateWait.toString()));
                    _agentManager.propagateChangeToAgents(params);
                } else if (settingNameUpdated.equals(IndirectAgentLBServiceImpl.IndirectAgentLBCheckInterval.key())) {
                    ConfigKey.Scope scope = settingUpdated.second();
                    if (scope == ConfigKey.Scope.Global) {
                        _indirectAgentLB.propagateMSListToAgents(false);
                    } else if (scope == ConfigKey.Scope.Cluster) {
                        Long clusterId = settingUpdated.third();
                        _indirectAgentLB.propagateMSListToAgentsInCluster(clusterId);
                    }
                } else if (VMLeaseManager.InstanceLeaseEnabled.key().equals(settingNameUpdated)) {
                    vmLeaseManager.onLeaseFeatureToggle();
                }
            }
        });
    }

    protected void validateIpAddressRelatedConfigValues(final String configName, final String value) {
        if (!ConfigurationValueValidator.isIpConfigName(configName)) {
            return;
        }
        if (StringUtils.isEmpty(value)) {
            return;
        }
        // Configuration must be registered and of String type for IP-shape validation to apply.
        final ConfigKey<?> configKey = _configDepot.get(configName);
        if (configKey == null || !String.class.equals(configKey.type())) {
            return;
        }
        String error = ConfigurationValueValidator.validateIpConfigValue(configName, value);
        if (error != null) {
            throw new InvalidParameterValueException(error);
        }
    }

    protected void validateConflictingConfigValue(final String configName, final String value) {
        String errorMessage = ConfigurationValueValidator.validateConflictingConfigValue(configName, value);
        if (errorMessage != null) {
            logger.error(errorMessage);
            throw new InvalidParameterValueException(errorMessage);
        }
    }

    @Override
    public boolean start() {

        // TODO : this may not be a good place to do integrity check here, we
        // put it here as we need _alertMgr to be properly
        // configured
        // before we can use it

        // As it is so common for people to forget about configuring
        // management.network.cidr,
        final String mgtCidr = _configDao.getValue(Config.ManagementNetwork.key());
        if (mgtCidr == null || mgtCidr.trim().isEmpty()) {
            final String[] localCidrs = NetUtils.getLocalCidrs();
            if (localCidrs != null && localCidrs.length > 0) {
                logger.warn("Management network CIDR is not configured originally. Set it default to {}", localCidrs[0]);

        _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_MANAGEMENT_NODE, 0, 0L, "Management network CIDR is not configured originally. Set it default to "
                        + localCidrs[0], "");
                _configDao.update(Config.ManagementNetwork.key(), Config.ManagementNetwork.getCategory(), localCidrs[0]);
            } else {
                logger.warn("Management network CIDR is not properly configured and we are not able to find a default setting");
                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_MANAGEMENT_NODE, 0, 0L,
                        "Management network CIDR is not properly configured and we are not able to find a default setting", "");
            }
        }

        return true;
    }

    @Override
    @DB
    public String updateConfiguration(final long userId, final String name, final String category, String value, ConfigKey.Scope scope, final Long resourceId) {
        if (Boolean.class == getConfigurationTypeWrapperClass(name)) {
            value = value.toLowerCase();
        }

        final String validationMsg = validateConfigurationValue(name, value, scope);
        if (validationMsg != null) {
            logger.error("Invalid value [{}] for configuration [{}] due to [{}].", value, name, validationMsg);
            throw new InvalidParameterValueException(validationMsg);
        }

        ConfigKey<?> configKey = _configDepot.get(name);
        if (configKey instanceof ValidatedConfigKey) {
            ValidatedConfigKey<?> validatedConfigKey = (ValidatedConfigKey<?>) configKey;
            validatedConfigKey.validateValue(value);
        }

        // If scope of the parameter is given then it needs to be updated in the
        // corresponding details table,
        // if scope is mentioned as global or not mentioned then it is normal
        // global parameter updation
        if (scope != null && !ConfigKey.Scope.Global.equals(scope)) {
            boolean valueEncrypted = shouldEncryptValue(category);
            if (valueEncrypted) {
                value = DBEncryptionUtil.encrypt(value);
            }

            ApiCommandResourceType resourceType;
            switch (scope) {
            case Zone:
                final DataCenterVO zone = _zoneDao.findById(resourceId);
                if (zone == null) {
                    throw new InvalidParameterValueException("unable to find zone by id " + resourceId);
                }
                resourceType = ApiCommandResourceType.Zone;
                _dcDetailsDao.addDetail(resourceId, name, value, true);
                break;
            case Cluster:
                final ClusterVO cluster = _clusterDao.findById(resourceId);
                if (cluster == null) {
                    throw new InvalidParameterValueException("unable to find cluster by id " + resourceId);
                }
                resourceType = ApiCommandResourceType.Cluster;
                String newName = name;
                if (name.equalsIgnoreCase("cpu.overprovisioning.factor")) {
                    newName = "cpuOvercommitRatio";
                }
                if (name.equalsIgnoreCase("mem.overprovisioning.factor")) {
                    newName = "memoryOvercommitRatio";
                }
                ClusterDetailsVO clusterDetailsVO = _clusterDetailsDao.findDetail(resourceId, newName);
                if (clusterDetailsVO == null) {
                    clusterDetailsVO = new ClusterDetailsVO(resourceId, newName, value);
                    _clusterDetailsDao.persist(clusterDetailsVO);
                } else {
                    clusterDetailsVO.setValue(value);
                    _clusterDetailsDao.update(clusterDetailsVO.getId(), clusterDetailsVO);
                }
                break;

            case StoragePool:
                final StoragePoolVO pool = _storagePoolDao.findById(resourceId);
                if (pool == null) {
                    throw new InvalidParameterValueException("unable to find storage pool by id " + resourceId);
                }
                resourceType = ApiCommandResourceType.StoragePool;
                if(name.equals(CapacityManager.StorageOverprovisioningFactor.key())) {
                    if(!pool.getPoolType().supportsOverProvisioning() ) {
                        throw new InvalidParameterValueException(String.format("Unable to update storage pool %s. Overprovision not supported for %s", pool, pool.getPoolType()));
                    }
                }

                _storagePoolDetailsDao.addDetail(resourceId, name, value, true);
                if (pool.getPoolType() == Storage.StoragePoolType.DatastoreCluster) {
                    List<StoragePoolVO> childDataStores = _storagePoolDao.listChildStoragePoolsInDatastoreCluster(resourceId);
                    for (StoragePoolVO childDataStore: childDataStores) {
                        _storagePoolDetailsDao.addDetail(childDataStore.getId(), name, value, true);
                    }
                }

                break;

            case Account:
                final AccountVO account = _accountDao.findById(resourceId);
                if (account == null) {
                    throw new InvalidParameterValueException("Unable to find Account by id " + resourceId);
                }
                resourceType = ApiCommandResourceType.Account;
                AccountDetailVO accountDetailVO = _accountDetailsDao.findDetail(resourceId, name);
                if (accountDetailVO == null) {
                    accountDetailVO = new AccountDetailVO(resourceId, name, value);
                    _accountDetailsDao.persist(accountDetailVO);
                } else {
                    accountDetailVO.setValue(value);
                    _accountDetailsDao.update(accountDetailVO.getId(), accountDetailVO);
                }
                break;

            case ImageStore:
                final ImageStoreVO imgStore = _imageStoreDao.findById(resourceId);
                Preconditions.checkState(imgStore != null);
                resourceType = ApiCommandResourceType.ImageStore;
                _imageStoreDetailsDao.addDetail(resourceId, name, value, true);
                break;

            case Domain:
                final DomainVO domain = _domainDao.findById(resourceId);
                if (domain == null) {
                    throw new InvalidParameterValueException("unable to find domain by id " + resourceId);
                }
                resourceType = ApiCommandResourceType.Domain;
                DomainDetailVO domainDetailVO = _domainDetailsDao.findDetail(resourceId, name);
                if (domainDetailVO == null) {
                    domainDetailVO = new DomainDetailVO(resourceId, name, value);
                    _domainDetailsDao.persist(domainDetailVO);
                } else {
                    domainDetailVO.setValue(value);
                    _domainDetailsDao.update(domainDetailVO.getId(), domainDetailVO);
                }
                break;

            default:
                throw new InvalidParameterValueException("Scope provided is invalid");
            }

            CallContext.current().setEventResourceType(resourceType);
            CallContext.current().setEventResourceId(resourceId);
            CallContext.current().setEventDetails(String.format(" Name: %s, New Value: %s, Scope: %s", name, value, scope.name()));

            _configDepot.invalidateConfigCache(name, scope, resourceId);
            messageBus.publish(_name, EventTypes.EVENT_CONFIGURATION_VALUE_EDIT, PublishScope.GLOBAL, new Ternary<>(name, scope, resourceId));
            return valueEncrypted ? DBEncryptionUtil.decrypt(value) : value;
        }

        // Execute all updates in a single transaction
        final TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();

        String previousValue = _configDao.getValue(name);
        if (!_configDao.update(name, category, value)) {
            logger.error("Failed to update configuration option, name: {}, value: {}", name, value);
            throw new CloudRuntimeException("Failed to update configuration value. Please contact Cloud Support.");
        }
        _configDepot.invalidateConfigCache(name, ConfigKey.Scope.Global, null);

        PreparedStatement pstmt;
        if (Config.XenServerGuestNetwork.key().equalsIgnoreCase(name)) {
            final String sql = "update host_details set value=? where name=?";
            try {
                pstmt = txn.prepareAutoCloseStatement(sql);
                pstmt.setString(1, value);
                pstmt.setString(2, "guest.network.device");

                pstmt.executeUpdate();
            } catch (final Throwable e) {
                throw new CloudRuntimeException("Failed to update guest.network.device in host_details due to exception ", e);
            }
        } else if (Config.XenServerPrivateNetwork.key().equalsIgnoreCase(name)) {
            final String sql = "update host_details set value=? where name=?";
            try {
                pstmt = txn.prepareAutoCloseStatement(sql);
                pstmt.setString(1, value);
                pstmt.setString(2, "private.network.device");

                pstmt.executeUpdate();
            } catch (final Throwable e) {
                throw new CloudRuntimeException("Failed to update private.network.device in host_details due to exception ", e);
            }
        } else if (Config.XenServerPublicNetwork.key().equalsIgnoreCase(name)) {
            final String sql = "update host_details set value=? where name=?";
            try {
                pstmt = txn.prepareAutoCloseStatement(sql);
                pstmt.setString(1, value);
                pstmt.setString(2, "public.network.device");

                pstmt.executeUpdate();
            } catch (final Throwable e) {
                throw new CloudRuntimeException("Failed to update public.network.device in host_details due to exception ", e);
            }
        } else if (Config.XenServerStorageNetwork1.key().equalsIgnoreCase(name)) {
            final String sql = "update host_details set value=? where name=?";
            try {
                pstmt = txn.prepareAutoCloseStatement(sql);
                pstmt.setString(1, value);
                pstmt.setString(2, "storage.network.device1");

                pstmt.executeUpdate();
            } catch (final Throwable e) {
                throw new CloudRuntimeException("Failed to update storage.network.device1 in host_details due to exception ", e);
            }
        } else if (Config.XenServerStorageNetwork2.key().equals(name)) {
            final String sql = "update host_details set value=? where name=?";
            try {
                pstmt = txn.prepareAutoCloseStatement(sql);
                pstmt.setString(1, value);
                pstmt.setString(2, "storage.network.device2");

                pstmt.executeUpdate();
            } catch (final Throwable e) {
                throw new CloudRuntimeException("Failed to update storage.network.device2 in host_details due to exception ", e);
            }
        } else if (Config.SecStorageSecureCopyCert.key().equalsIgnoreCase(name)) {
            //FIXME - Ideally there should be a listener model to listen to global config changes and be able to take action gracefully.
            //Expire the download urls
            final String sqlTemplate = "update template_store_ref set download_url_created=?";
            final String sqlVolume = "update volume_store_ref set download_url_created=?";
            try {
                // Change for templates
                pstmt = txn.prepareAutoCloseStatement(sqlTemplate);
                pstmt.setDate(1, new Date(-1L));// Set the time before the epoch time.
                pstmt.executeUpdate();
                // Change for volumes
                pstmt = txn.prepareAutoCloseStatement(sqlVolume);
                pstmt.setDate(1, new Date(-1L));// Set the time before the epoch time.
                pstmt.executeUpdate();
                // Cleanup the download urls
                _storageManager.cleanupDownloadUrls();
            } catch (final Throwable e) {
                throw new CloudRuntimeException("Failed to clean up download URLs in template_store_ref or volume_store_ref due to exception ", e);
            }
        } else if (HypervisorGuru.HypervisorCustomDisplayName.key().equals(name)) {
            updateCustomDisplayNameOnHypervisorsList(previousValue, value);
        }

        txn.commit();
        messageBus.publish(_name, EventTypes.EVENT_CONFIGURATION_VALUE_EDIT, PublishScope.GLOBAL, new Ternary<>(name, ConfigKey.Scope.Global, resourceId));
        return _configDao.getValue(name);
    }

    private boolean shouldEncryptValue(String category) {
        return ConfigurationValueValidator.shouldEncryptValue(category);
    }

    /**
     * Updates the 'hypervisor.list' value to match the new custom hypervisor name set as newValue if the previous value was set
     */
    private void updateCustomDisplayNameOnHypervisorsList(String previousValue, String newValue) {
        String hypervisorListConfigName = Config.HypervisorList.key();
        String hypervisors = _configDao.getValue(hypervisorListConfigName);
        if (Arrays.asList(hypervisors.split(",")).contains(previousValue)) {
            hypervisors = hypervisors.replace(previousValue, newValue);
            logger.info("Updating the hypervisor list configuration '{}' to match the new custom hypervisor display name",
                    hypervisorListConfigName);
            _configDao.update(hypervisorListConfigName, hypervisors);
        }
    }

    protected String getNormalizedEmptyValueForConfig(final String name, final String inputValue,
                          final Long configStorageId) {
        String value = inputValue.trim();
        if (!value.isEmpty() && !value.equals("null")) {
            return value;
        }
        if (configStorageId != null) {
            return "";
        }
        ConfigKey<?> key = _configDepot.get(name);
        return (key != null && key.type() == String.class) ? "" : null;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_CONFIGURATION_VALUE_EDIT, eventDescription = "updating configuration")
    public Configuration updateConfiguration(final UpdateCfgCmd cmd) throws InvalidParameterValueException {
        final long userId = CallContext.current().getCallingUserId();
        final String name = cmd.getCfgName();
        String value = cmd.getValue();
        final Long zoneId = cmd.getZoneId();
        final Long clusterId = cmd.getClusterId();
        final Long storagepoolId = cmd.getStoragepoolId();
        final Long imageStoreId = cmd.getImageStoreId();
        Long accountId = cmd.getAccountId();
        Long domainId = cmd.getDomainId();
        // check if config value exists
        final ConfigurationVO config = _configDao.findByName(name);
        String category = null;
        String eventValue = encryptEventValueIfConfigIsEncrypted(config, value);
        CallContext.current().setEventDetails(String.format(" Name: %s New Value: %s", name, eventValue));

        final Account caller = CallContext.current().getCallingAccount();
        if (_accountMgr.isDomainAdmin(caller.getId())) {
            if (accountId == null && domainId == null) {
                domainId = caller.getDomainId();
            }
        } else if (_accountMgr.isNormalUser(caller.getId())) {
            if (accountId == null) {
                accountId = caller.getAccountId();
            }
        }

        // FIX ME - All configuration parameters are not moved from config.java to configKey
        if (config == null) {
            if (_configDepot.get(name) == null) {
                logger.warn("Probably the component manager where configuration variable {} is defined needs to implement Configurable interface", name);
                throw new InvalidParameterValueException("Config parameter with name " + name + " doesn't exist");
            }
            category = _configDepot.get(name).category();
        } else {
            category = config.getCategory();
        }

        if (value == null) {
            throw new InvalidParameterValueException(String.format("The new value for the [%s] configuration must be given.", name));
        }

        validateIpAddressRelatedConfigValues(name, value);
        validateConflictingConfigValue(name, value);

        if (CATEGORY_SYSTEM.equals(category) && !_accountMgr.isRootAdmin(caller.getId())) {
            logger.warn("Only Root Admin is allowed to edit the configuration {}", name);
            throw new CloudRuntimeException("Only Root Admin is allowed to edit this configuration.");
        }

        ConfigKey.Scope scope = null;
        Long id = null;
        int paramCountCheck = 0;

        if (zoneId != null) {
            scope = ConfigKey.Scope.Zone;
            id = zoneId;
            paramCountCheck++;
        }
        if (clusterId != null) {
            scope = ConfigKey.Scope.Cluster;
            id = clusterId;
            paramCountCheck++;
        }
        if (accountId != null) {
            Account account = _accountMgr.getAccount(accountId);
            _accountMgr.checkAccess(caller, null, false, account);
            scope = ConfigKey.Scope.Account;
            id = accountId;
            paramCountCheck++;
        }
        if (domainId != null) {
            _accountMgr.checkAccess(caller, _domainDao.findById(domainId));
            scope = ConfigKey.Scope.Domain;
            id = domainId;
            paramCountCheck++;
        }
        if (storagepoolId != null) {
            scope = ConfigKey.Scope.StoragePool;
            id = storagepoolId;
            paramCountCheck++;
        }
        if (imageStoreId != null) {
            scope = ConfigKey.Scope.ImageStore;
            id = imageStoreId;
            paramCountCheck++;
        }

        if (paramCountCheck > 1) {
            throw new InvalidParameterValueException("cannot handle multiple IDs, provide only one ID corresponding to the scope");
        }

        value = getNormalizedEmptyValueForConfig(name, value, id);

        String currentValueInScope = getConfigurationValueInScope(config, name, scope, id);
        final String updatedValue = updateConfiguration(userId, name, category, value, scope, id);
        if (value == null && updatedValue == null || updatedValue.equalsIgnoreCase(value)) {
            logger.debug("Config: {} value is updated from: {} to {} for scope: {}", name,
                    encryptEventValueIfConfigIsEncrypted(config, currentValueInScope),
                    encryptEventValueIfConfigIsEncrypted(config, value),
                    scope != null ? scope : ConfigKey.Scope.Global.name());

            return _configDao.findByName(name);
        } else {
            throw new CloudRuntimeException("Unable to update configuration parameter " + name);
        }
    }

    private String encryptEventValueIfConfigIsEncrypted(ConfigurationVO config, String value) {
        return ConfigurationValueValidator.maskEventValueIfEncrypted(config, value);
    }

    private ParamCountPair getParamCount(Map<String, Long> scopeMap) {
        Long id = null;
        int paramCount = 0;
        String scope = ConfigKey.Scope.Global.toString();

        for (var entry : scopeMap.entrySet()) {
            if (entry.getValue() != null) {
                id = entry.getValue();
                scope = entry.getKey();
                paramCount++;
            }
        }

        return new ParamCountPair(id, paramCount, scope);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_CONFIGURATION_VALUE_EDIT, eventDescription = "resetting configuration")
    public Pair<Configuration, String> resetConfiguration(final ResetCfgCmd cmd) throws InvalidParameterValueException {
        return configurationResetService.resetConfiguration(cmd);
    }

    private String getConfigurationValueInScope(ConfigurationVO config, String name, ConfigKey.Scope scope, Long id) {
        String configValue;
        if (scope == null || ConfigKey.Scope.Global.equals(scope)) {
            configValue = config.getValue();
        } else {
            ConfigKey<?> configKey = _configDepot.get(name);
            Object currentValue = configKey.valueInScope(scope, id);
            configValue = currentValue != null ? currentValue.toString() : null;
        }
        return configValue;
    }

    /**
     * Validates whether a value is valid for the specified configuration. This includes type and range validation.
     * @param name name of the configuration.
     * @param value value to validate.
     * @param scope scope of the configuration.
     * @return null if the value is valid; otherwise, returns an error message.
     */
    protected String validateConfigurationValue(String name, String value, ConfigKey.Scope scope) {
        final ConfigurationVO cfg = _configDao.findByName(name);
        if (cfg == null) {
            logger.error("Missing configuration variable {} in configuration table", name);
            return "Invalid configuration variable.";
        }
        validateConfigurationAllowedOnlyForDefaultAdmin(name, value);

        List<ConfigKey.Scope> configScope = cfg.getScopes();
        if (scope != null) {
            if (!configScope.contains(scope) &&
                    !(ENABLE_ACCOUNT_SETTINGS_FOR_DOMAIN.value() && configScope.contains(ConfigKey.Scope.Account) &&
                            ConfigKey.Scope.Domain.equals(scope))) {
                logger.error("Invalid scope id provided for the parameter {}", name);
                return "Invalid scope id provided for the parameter " + name;
            }
        }
        Class<?> type = getConfigurationTypeWrapperClass(name);
        if (type == null) {
            return null;
        }

        validateSpecificConfigurationValues(name, value, type);

        boolean isTypeValid = validateValueType(value, type);
        if (!isTypeValid) {
            return String.format("Value [%s] is not a valid [%s].", value, type);
        }

        return validateValueRange(name, value, type, Config.getConfig(name));
    }

    /**
     * Returns the configuration type's wrapper class.
     * @param name name of the configuration.
     * @return if the configuration exists, returns its type's wrapper class; if not, returns null.
     */
    protected Class<?> getConfigurationTypeWrapperClass(String name) {
        Config configuration = Config.getConfig(name);
        if (configuration != null) {
            return configuration.getType();
        }

        logger.warn("Did not find configuration [{}] in Config.java. Perhaps moved to ConfigDepot.", name);
        ConfigKey<?> configKey = _configDepot.get(name);
        if (configKey == null) {
            logger.warn("Did not find configuration [{}] in ConfigDepot too.", name);
            return null;
        }

        return configKey.type();
    }

    protected void validateConfigurationAllowedOnlyForDefaultAdmin(String configName, String value) {
        if (configKeysAllowedOnlyForDefaultAdmin.contains(configName)) {
            final Long userId = CallContext.current().getCallingUserId();
            if (userId != User.UID_ADMIN) {
                throw new CloudRuntimeException("Only default admin is allowed to change this setting");
            }

            if (AccountManagerImpl.listOfRoleTypesAllowedForOperationsOfSameRoleType.key().equals(configName)) {
                if (value != null && !value.isBlank()) {
                    List<String> validRoleTypes = Arrays.stream(RoleType.values())
                            .map(Enum::name)
                            .collect(Collectors.toList());

                    boolean allValid = Arrays.stream(value.split(","))
                            .map(String::trim)
                            .allMatch(validRoleTypes::contains);

                    if (!allValid) {
                        throw new CloudRuntimeException("Invalid role types provided in value");
                    }
                } else {
                    throw new CloudRuntimeException("Value for role types must not be empty");
                }
            }
        }
    }

    /**
     * Returns whether a value is valid for a configuration of the provided type.
     * Valid configuration values are:
     *
     * <ul>
     *     <li>String: any value, including null;</li>
     *     <li>Character: any value, including null;</li>
     *     <li>Boolean: strings that equal "true" or "false" (case-insensitive);</li>
     *     <li>Integer, Short, Long: strings that contain a valid int/short/long;</li>
     *     <li>Float, Double: strings that contain a valid float/double, except infinity.</li>
     * </ul>
     *
     * If a type isn't listed here, then the value will be considered invalid.
     * @param value value to validate.
     * @param type type of the configuration.
     * @return boolean indicating whether the value is valid.
     */
    protected boolean validateValueType(String value, Class<?> type) {
        return ConfigurationValueValidator.validateValueType(value, type);
    }

    /**
     * If the specified configuration contains a range, validates if the value is in that range. If it doesn't contain
     * a range, any value is considered valid.
     * The value must be previously checked by `validateValueType` so there aren't casting exceptions here.
     * @param name name of the configuration.
     * @param value value to validate.
     * @param type type of the value.
     * @param configuration if the configuration uses Config instead of ConfigKey, the Config object; null otherwise.
     * @return if the value is valid, returns null; if not, returns an error message.
     */
    protected String validateValueRange(String name, String value, Class<?> type, Config configuration) {
        if (type.equals(Float.class)) {
            Float val = Float.parseFloat(value);
            if (overprovisioningFactorsForValidation.contains(name) && val <= 0f) {
                return String.format("Value for configuration [%s] should be greater than 0.", name);
            } else if (weightBasedParametersForValidation.contains(name) && (val < 0f || val > 1f)) {
                return String.format("Please enter a value between 0 and 1 for the configuration parameter: [%s].", name);
            }
        }

        if (type.equals(Integer.class)) {
            int val = Integer.parseInt(value);
            if (NetworkModel.MACIdentifier.key().equalsIgnoreCase(name)) {
                // The value needs to be between 0 to 255 because the MAC generation needs a value of 8 bits
                // 0 is considered as disabled.
                if (val < 0 || val > 255){
                    return String.format("[%s] value should be between 0 and 255. 0 value will disable this feature.", name);
                }
            }
            if (UnmanagedVMsManager.ThreadsOnMSToImportVMwareVMFiles.key().equalsIgnoreCase(name) ||
                    UnmanagedVMsManager.ThreadsOnKVMHostToImportVMwareVMFiles.key().equalsIgnoreCase(name)) {
                if (val < -1 || val > 10) {
                    return String.format("Please enter a value between -1 and 10 for the configuration parameter: [%s]. -1 will disable it.", name);
                }
            } else if (configValuesForValidation.contains(name)) {
                if (val <= 0) {
                    return String.format("Please enter a positive value for the configuration parameter: [%s].", name);
                }
                if ("vm.password.length".equalsIgnoreCase(name) && val < 6) {
                    return String.format("Please enter a value greater than 5 for the configuration parameter: [%s].",  name);
                }
                if ("remote.access.vpn.psk.length".equalsIgnoreCase(name) && (val < 8 || val > 256)) {
                    return String.format("Please enter a value greater than 7 and less than 257 for the configuration parameter: [%s].", name);
                }
                if (UserDataManager.VM_USERDATA_MAX_LENGTH_STRING.equalsIgnoreCase(name) && val > 1048576) {
                    return String.format("Please enter a value less than 1048577 for the configuration parameter: [%s].", name);
                }
            }
        }

        if (type.equals(String.class) && SecStorageAllowedInternalDownloadSites.key().equalsIgnoreCase(name)) {
            String cidrError = ConfigurationValueValidator.validateCidrList(name, value);
            if (cidrError != null) {
                return cidrError;
            }
        }

        validateIpAddressRelatedConfigValues(name, value);

        if (!shouldValidateConfigRange(name, value, configuration)) {
            return null;
        }

        String[] range = configuration.getRange().split(",");
        if (type.equals(Integer.class)) {
            return validateIfIntValueIsInRange(name, value, range[0]);
        }
        return validateIfStringValueIsInRange(name, value, range);
    }

    /**
     * Validates configuration values for the given name, value, and type.
     * <ul>
     *   <li>The value must be a comma-separated list of key-value pairs, where each value must be a positive integer.</li>
     *   <li>Each key-value pair must be in the format "command=value", with the value being a positive integer greater than 0,
     *          otherwise fails with an error message</li>
     *   <li>Throws an {@link InvalidParameterValueException} if validation fails.</li>
     * </ul>
     *
     * @param name  the configuration name
     * @param value the configuration value as a comma-separated string of key-value pairs
     * @param type  the configuration type, expected to be String
     * @throws InvalidParameterValueException if validation fails with a specific error message
     */
    protected void validateSpecificConfigurationValues(String name, String value, Class<?> type) {
        String errMsg = ConfigurationValueValidator.validateSpecificConfigurationValues(name, value, type);
        if (errMsg != null) {
            logger.error(errMsg);
            throw new InvalidParameterValueException(errMsg);
        }
    }

    protected Pair<Boolean, String> validateCommaSeparatedKeyValueConfigWithPositiveIntegerValues(String value) {
        return ConfigurationValueValidator.validateCommaSeparatedKeyValueConfigWithPositiveIntegerValues(value);
    }

    /**
     * Returns a boolean indicating whether a Config's range should be validated. It should not be validated when:</br>
     * <ul>
     *  <li>The value is null;</li>
     *  <li>The configuration uses ConfigKey instead of Config;</li>
     *  <li>The Config does not have a specified range.</li>
     * </ul>
     */
    protected boolean shouldValidateConfigRange(String name, String value, Config configuration) {
        return ConfigurationValueValidator.shouldValidateConfigRange(name, value, configuration);
    }

    /**
     * A valid value should be an integer between min and max (the values from the range).
     */
    protected String validateIfIntValueIsInRange(String name, String value, String range) {
        return ConfigurationValueValidator.validateIfIntValueIsInRange(name, value, range);
    }

    /**
     * Checks if the value for the configuration is valid for any of the ranges selected.
     */
    protected String validateIfStringValueIsInRange(String name, String value, String... range) {
        // Orchestration stays here so that test spies can still mock the
        // protected validateRange* methods on instances.
        List<String> message = new ArrayList<>();
        String errMessage = "";
        for (String rangeOption : range) {
            switch (rangeOption) {
                case "privateip":
                    errMessage = validateRangePrivateIp(name, value);
                    break;
                case "hypervisorList":
                    errMessage = validateRangeHypervisorList(value);
                    break;
                case "instanceName":
                    errMessage = validateRangeInstanceName(value);
                    break;
                case "domainName":
                    errMessage = validateRangeDomainName(value);
                    break;
                default:
                    errMessage = validateRangeOther(name, value, rangeOption);
            }
            if (StringUtils.isEmpty(errMessage)) {
                return null;
            }
            message.add(errMessage);
        }
        if (message.size() == 1) {
            return String.format("The provided value is not %s.", message.get(0));
        }
        return String.format("The provided value is neither %s.", String.join(" NOR ", message));
    }

    /**
     * Checks if the value is a private IP according to {@link NetUtils#isSiteLocalAddress(String)}.
     */
    protected String validateRangePrivateIp(String name, String value) {
        return ConfigurationValueValidator.validateRangePrivateIp(name, value);
    }

    /**
     * Valid values are XenServer, KVM, VMware, Hyperv, VirtualBox, Parralels, BareMetal, Simulator, LXC.
     * Inputting "Any" will return the hypervisor type Any, other inputs will result in the hypervisor type none.
     * Both of these are invalid values and will return an error message.
     */
    protected String validateRangeHypervisorList(String value) {
        return ConfigurationValueValidator.validateRangeHypervisorList(value);
    }

    /**
     * Valid values are instance names, the only restriction is that they may not have hyphens, spaces or plus signs.
     */
    protected String validateRangeInstanceName(String value) {
        return ConfigurationValueValidator.validateRangeInstanceName(value);
    }

    /**
     * Verifies if the value is a valid domain name. If it starts with "*.", these two symbols are ignored and do not count towards the character limit.
     * Max length for FQDN is 253 + 2, code adds xxx-xxx-xxx-xxx to domain name when creating URL.
     */
    protected String validateRangeDomainName(String value) {
        return ConfigurationValueValidator.validateRangeDomainName(value);
    }

    /**
     * In configurations where this type of range is used, a list of possible values is passed as argument in the creation of the configuration,
     * a valid value is any option within this list.
     */
    protected String validateRangeOther(String name, String value, String rangeOption) {
        return ConfigurationValueValidator.validateRangeOther(name, value, rangeOption);
    }


    private boolean podHasAllocatedPrivateIPs(final long podId) {
        final HostPodVO pod = _podDao.findById(podId);
        final int count = _privateIpAddressDao.countIPs(podId, pod.getDataCenterId(), true);
        return count > 0;
    }

    protected void checkIfPodIsDeletable(final long podId) {
        final HostPodVO pod = _podDao.findById(podId);

        final String errorMsg = "The pod cannot be deleted because ";

        // Check if there are allocated private IP addresses in the pod
        if (_privateIpAddressDao.countIPs(podId, pod.getDataCenterId(), true) != 0) {
            throw new CloudRuntimeException(errorMsg + "there are private IP addresses allocated in this pod.");
        }

        // Check if there are any non-removed volumes in the pod.
        if (!_volumeDao.findByPod(podId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are storage volumes in this pod.");
        }

        // Check if there are any non-removed hosts in the pod.
        if (!_hostDao.findByPodId(podId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are servers in this pod.");
        }

        // Check if there are any non-removed vms in the pod.
        if (!_vmInstanceDao.listByPodId(podId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are Instances in this pod.");
        }

        // Check if there are any non-removed clusters in the pod.
        if (!_clusterDao.listByPodId(podId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are clusters in this pod.");
        }
    }

    private void checkPodAttributesForNonEdgeZone(final long podId, final String podName, final DataCenter zone, final String gateway,
          final String cidr, final String startIp, final String endIp, final boolean skipGatewayOverlapCheck) {

        String cidrAddress;
        long cidrSize;
        // Get the individual cidrAddress and cidrSize values, if the CIDR is
        // valid. If it's not valid, return an error.
        if (NetUtils.isValidIp4Cidr(cidr)) {
            cidrAddress = getCidrAddress(cidr);
            cidrSize = getCidrSize(cidr);
        } else {
            throw new InvalidParameterValueException("Please enter a valid CIDR for pod: " + podName);
        }

        // Check if the IP range is valid
        checkIpRange(startIp, endIp, cidrAddress, cidrSize);

        // Check if the IP range overlaps with the public ip
        if (StringUtils.isNotEmpty(startIp)) {
            checkOverlapPublicIpRange(zone.getId(), startIp, endIp);
        }

        // Check if the gateway is a valid IP address
        if (!NetUtils.isValidIp4(gateway)) {
            throw new InvalidParameterValueException("The gateway is not a valid IP address.");
        }

        // Check if the gateway is in the CIDR subnet
        if (!NetUtils.getCidrSubNet(gateway, cidrSize).equalsIgnoreCase(NetUtils.getCidrSubNet(cidrAddress, cidrSize))) {
            throw new InvalidParameterValueException("The gateway is not in the CIDR subnet.");
        }

        // Don't allow gateway to overlap with start/endIp
        if (!skipGatewayOverlapCheck) {
            if (NetUtils.ipRangesOverlap(startIp, endIp, gateway, gateway)) {
                throw new InvalidParameterValueException("The gateway shouldn't overlap start/end IP addresses");
            }
        }

        final String checkPodCIDRs = _configDao.getValue("check.pod.cidrs");
        if (checkPodCIDRs == null || checkPodCIDRs.trim().isEmpty() || Boolean.parseBoolean(checkPodCIDRs)) {
            checkPodCidrSubnets(zone.getId(), podId, cidr);
        }
    }

    private void checkPodAttributes(final long podId, final String podName, final DataCenter zone, final String gateway, final String cidr, final String startIp, final String endIp, final String allocationStateStr,
            final boolean checkForDuplicates, final boolean skipGatewayOverlapCheck) {
        if (checkForDuplicates) {
            // Check if the pod already exists
            if (validPod(podName, zone.getId())) {
                throw new InvalidParameterValueException(String.format("A pod with name: %s already exists in zone %s. Please specify a different pod name. ", podName, zone));
            }
        }

        if (!DataCenter.Type.Edge.equals(zone.getType())) {
            checkPodAttributesForNonEdgeZone(podId, podName, zone, gateway, cidr, startIp, endIp, skipGatewayOverlapCheck);
        }

        if (allocationStateStr != null && !allocationStateStr.isEmpty()) {
            try {
                Grouping.AllocationState.valueOf(allocationStateStr);
            } catch (final IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve Allocation State '" + allocationStateStr + "' to a supported state");
            }
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_POD_DELETE, eventDescription = "deleting pod", async = false)
    public boolean deletePod(final DeletePodCmd cmd) {
        return podService.deletePod(cmd);
    }

    /**
     * Get vlan number from vlan uri
     * @param vlan
     * @return
     */
    protected String getVlanNumberFromUri(String vlan) {
        return BroadcastDomainType.parseVlanNumberFromUri(vlan);
    }

    @Override
    @DB
    public Pod createPodIpRange(final CreateManagementNetworkIpRangeCmd cmd) {
        return podService.createPodIpRange(cmd);
    }

    @Override
    @DB
    public void deletePodIpRange(final DeleteManagementNetworkIpRangeCmd cmd) throws ResourceUnavailableException, ConcurrentOperationException {
        podService.deletePodIpRange(cmd);
    }

    @Override
    @DB
    public void updatePodIpRange(final UpdatePodManagementNetworkIpRangeCmd cmd) throws ConcurrentOperationException {
        podService.updatePodIpRange(cmd);
    }

    @Override
    @DB
    public DataCenterGuestIpv6Prefix createDataCenterGuestIpv6Prefix(final CreateGuestNetworkIpv6PrefixCmd cmd) throws ConcurrentOperationException {
        return guestIpv6PrefixService.createDataCenterGuestIpv6Prefix(cmd);
    }

    @Override
    public List<? extends DataCenterGuestIpv6Prefix> listDataCenterGuestIpv6Prefixes(final ListGuestNetworkIpv6PrefixesCmd cmd) throws ConcurrentOperationException {
        return guestIpv6PrefixService.listDataCenterGuestIpv6Prefixes(cmd);
    }

    @Override
    public boolean deleteDataCenterGuestIpv6Prefix(DeleteGuestNetworkIpv6PrefixCmd cmd) {
        return guestIpv6PrefixService.deleteDataCenterGuestIpv6Prefix(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_POD_EDIT, eventDescription = "updating pod", async = false)
    public Pod editPod(final UpdatePodCmd cmd) {
        return editPod(cmd.getId(), cmd.getPodName(), null, null, cmd.getGateway(), cmd.getNetmask(), cmd.getAllocationState());
    }

    @Override
    @DB
    public Pod editPod(final long id, String name, String startIp, String endIp, String gateway, String netmask, String allocationStateStr) {

        // verify parameters
        final HostPodVO pod = _podDao.findById(id);

        if (pod == null) {
            throw new InvalidParameterValueException("Unable to find pod by id " + id);
        }

        // If the gateway, CIDR, private IP range is being changed, check if the
        // pod has allocated private IP addresses
        if (podHasAllocatedPrivateIPs(id)) {

            if (StringUtils.isNotEmpty(netmask)) {
                final long newCidr = NetUtils.getCidrSize(netmask);
                final long oldCidr = pod.getCidrSize();

                if (newCidr > oldCidr) {
                    throw new CloudRuntimeException("The specified pod has allocated private IP addresses, so its IP address range can be extended only");
                }
            }
        }

        if (gateway == null) {
            gateway = pod.getGateway();
        }

        if (netmask == null) {
            netmask = NetUtils.getCidrNetmask(pod.getCidrSize());
        }

        final String oldPodName = pod.getName();
        if (name == null) {
            name = oldPodName;
        }

        if (allocationStateStr == null) {
            allocationStateStr = pod.getAllocationState().toString();
        }

        // Verify pod's attributes
        final String cidr = NetUtils.ipAndNetMaskToCidr(gateway, netmask);
        final boolean checkForDuplicates = !oldPodName.equals(name);
        final DataCenterVO zone = _zoneDao.findById(pod.getDataCenterId());
        checkPodAttributes(id, name, zone, gateway, cidr, startIp, endIp, allocationStateStr, checkForDuplicates, true);

        // Valid check is already done in checkPodAttributes method.
        final String cidrAddress = getCidrAddress(cidr);
        final long cidrSize = getCidrSize(cidr);

        // Check if start IP and end IP of all the ranges lie in the CIDR subnet.
        final String[] existingPodIpRanges = pod.getDescription().split(",");

        for(String podIpRange: existingPodIpRanges) {
            final String[] existingPodIpRange = podIpRange.split("-");

            if (existingPodIpRange.length > 1) {
                if (!NetUtils.isValidIp4(existingPodIpRange[0]) || !NetUtils.isValidIp4(existingPodIpRange[1])) {
                    continue;
                }

                if (!NetUtils.getCidrSubNet(existingPodIpRange[0], cidrSize).equalsIgnoreCase(NetUtils.getCidrSubNet(cidrAddress, cidrSize))) {
                    throw new InvalidParameterValueException("The start address of the some IP range is not in the CIDR subnet.");
                }

                if (!NetUtils.getCidrSubNet(existingPodIpRange[1], cidrSize).equalsIgnoreCase(NetUtils.getCidrSubNet(cidrAddress, cidrSize))) {
                    throw new InvalidParameterValueException("The end address of the some IP range is not in the CIDR subnet.");
                }

                if (NetUtils.ipRangesOverlap(existingPodIpRange[0], existingPodIpRange[1], gateway, gateway)) {
                    throw new InvalidParameterValueException("The gateway shouldn't overlap some start/end IP addresses");
                }
            }
        }

        try {
            final String allocationStateStrFinal = allocationStateStr;
            final String nameFinal = name;
            final String gatewayFinal = gateway;
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) {
                    final long zoneId = pod.getDataCenterId();

                    pod.setName(nameFinal);
                    pod.setDataCenterId(zoneId);
                    pod.setGateway(gatewayFinal);
                    pod.setCidrAddress(getCidrAddress(cidr));
                    pod.setCidrSize(getCidrSize(cidr));

                    Grouping.AllocationState allocationState = null;
                    if (allocationStateStrFinal != null && !allocationStateStrFinal.isEmpty()) {
                        allocationState = Grouping.AllocationState.valueOf(allocationStateStrFinal);
                        pod.setAllocationState(allocationState);
                    }

                    _podDao.update(id, pod);
                }
            });

            messageBus.publish(_name, MESSAGE_DELETE_POD_IP_RANGE_EVENT, PublishScope.LOCAL, pod);
            messageBus.publish(_name, MESSAGE_CREATE_POD_IP_RANGE_EVENT, PublishScope.LOCAL, pod);
        } catch (final Exception e) {
            logger.error("Unable to edit pod due to {}", e.getMessage(), e);
            throw new CloudRuntimeException("Failed to edit pod. Please contact Cloud Support.");
        }

        return pod;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_POD_CREATE, eventDescription = "creating pod", async = false)
    public Pod createPod(final long zoneId, final String name, final String startIp, final String endIp, final String gateway, final String netmask, String allocationState, List<String> storageAccessGroups) {
        return podService.createPod(zoneId, name, startIp, endIp, gateway, netmask, allocationState, storageAccessGroups);
    }

    @Override
    @DB
    public HostPodVO createPod(final long userId, final String podName, final DataCenter zone, final String gateway, final String cidr, String startIp, String endIp, final String allocationStateStr,
                               final boolean skipGatewayOverlapCheck, List<String> storageAccessGroups) {
        return podService.createPod(userId, podName, zone, gateway, cidr, startIp, endIp, allocationStateStr, skipGatewayOverlapCheck, storageAccessGroups);
    }

    @DB
    protected void checkIfZoneIsDeletable(final long zoneId) {
        final String errorMsg = "The zone cannot be deleted because ";


        // Check if there are any non-removed hosts in the zone.
        if (!_hostDao.listEnabledIdsByDataCenterId(zoneId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are servers in this zone.");
        }

        // Check if there are any non-removed pods in the zone.
        if (!_podDao.listByDataCenterId(zoneId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are pods in this zone.");
        }

        // Check if there are allocated private IP addresses in the zone.
        if (_privateIpAddressDao.countIPs(zoneId, true) != 0) {
            throw new CloudRuntimeException(errorMsg + "there are private IP addresses allocated in this zone.");
        }

        // Check if there are allocated public IP addresses in the zone.
        if (_publicIpAddressDao.countIPs(zoneId, true) != 0) {
            throw new CloudRuntimeException(errorMsg + "there are public IP addresses allocated in this zone.");
        }

        // Check if there are any non-removed vms in the zone.
        if (!_vmInstanceDao.listByZoneId(zoneId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are virtual machines in this zone.");
        }

        // Check if there are any non-removed volumes in the zone.
        if (!_volumeDao.findByDc(zoneId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are storage volumes in this zone.");
        }

        // Check if there are any non-removed physical networks in the zone.
        if (!_physicalNetworkDao.listByZone(zoneId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are physical networks in this zone.");
        }

        //check if there are any secondary stores attached to the zone
        if(!_imageStoreDao.findByZone(new ZoneScope(zoneId), null).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are Secondary storages in this zone");
        }

        // We could check if there are any non-removed VMware datacenters in the zone. EWe don´t care.
        // These can continu to exist as long as the mapping will be gone (see line deleteZone
    }

    private void checkZoneParameters(final String zoneName, final String dns1, final String dns2, final String internalDns1, final String internalDns2, final boolean checkForDuplicates, final Long domainId,
            final String allocationStateStr, final String ip6Dns1, final String ip6Dns2) {
        if (checkForDuplicates) {
            // Check if a zone with the specified name already exists
            if (validZone(zoneName)) {
                throw new InvalidParameterValueException("A zone with that name already exists. Please specify a unique zone name.");
            }
        }

        // check if valid domain
        if (domainId != null) {
            final DomainVO domain = _domainDao.findById(domainId);

            if (domain == null) {
                throw new InvalidParameterValueException("Please specify a valid domain id");
            }
        }

        // Check IP validity for DNS addresses
        // Empty strings is a valid input -- hence the length check
        if (dns1 != null && dns1.length() > 0 && !NetUtils.isValidIp4(dns1)) {
            throw new InvalidParameterValueException("Please enter a valid IP address for DNS1");
        }

        if (dns2 != null && dns2.length() > 0 && !NetUtils.isValidIp4(dns2)) {
            throw new InvalidParameterValueException("Please enter a valid IP address for DNS2");
        }

        if (internalDns1 != null && internalDns1.length() > 0 && !NetUtils.isValidIp4(internalDns1)) {
            throw new InvalidParameterValueException("Please enter a valid IP address for internal DNS1");
        }

        if (internalDns2 != null && internalDns2.length() > 0 && !NetUtils.isValidIp4(internalDns2)) {
            throw new InvalidParameterValueException("Please enter a valid IP address for internal DNS2");
        }

        if (ip6Dns1 != null && ip6Dns1.length() > 0 && !NetUtils.isValidIp6(ip6Dns1)) {
            throw new InvalidParameterValueException("Please enter a valid IPv6 address for IP6 DNS1");
        }

        if (ip6Dns2 != null && ip6Dns2.length() > 0 && !NetUtils.isValidIp6(ip6Dns2)) {
            throw new InvalidParameterValueException("Please enter a valid IPv6 address for IP6 DNS2");
        }

        if (allocationStateStr != null && !allocationStateStr.isEmpty()) {
            try {
                Grouping.AllocationState.valueOf(allocationStateStr);
            } catch (final IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve Allocation State '" + allocationStateStr + "' to a supported state");
            }
        }
    }

    private void checkIpRange(final String startIp, final String endIp, final String cidrAddress, final long cidrSize) {
        //Checking not null for start IP as well. Previously we assumed to be not null always.
        //But the check is required for the change in updatePod API.
        if (StringUtils.isNotEmpty(startIp) && !NetUtils.isValidIp4(startIp)) {
            throw new InvalidParameterValueException("The start address of the IP range is not a valid IP address.");
        }

        if (StringUtils.isNotEmpty(endIp) && !NetUtils.isValidIp4(endIp)) {
            throw new InvalidParameterValueException("The end address of the IP range is not a valid IP address.");
        }

        //Not null check is required for the change in updatePod API.
        if (StringUtils.isNotEmpty(startIp) && !NetUtils.getCidrSubNet(startIp, cidrSize).equalsIgnoreCase(NetUtils.getCidrSubNet(cidrAddress, cidrSize))) {
            throw new InvalidParameterValueException("The start address of the IP range is not in the CIDR subnet.");
        }

        if (StringUtils.isNotEmpty(endIp) && !NetUtils.getCidrSubNet(endIp, cidrSize).equalsIgnoreCase(NetUtils.getCidrSubNet(cidrAddress, cidrSize))) {
            throw new InvalidParameterValueException("The end address of the IP range is not in the CIDR subnet.");
        }

        if (StringUtils.isNotEmpty(endIp) && NetUtils.ip2Long(startIp) > NetUtils.ip2Long(endIp)) {
            throw new InvalidParameterValueException("The start IP address must have a lower value than the end IP address.");
        }

    }

    private void checkOverlapPublicIpRange(final Long zoneId, final String startIp, final String endIp) {
        final long privateStartIp = NetUtils.ip2Long(startIp);
        final long privateEndIp = NetUtils.ip2Long(endIp);

        final List<IPAddressVO> existingPublicIPs = _publicIpAddressDao.listByDcId(zoneId);
        for (final IPAddressVO publicIPVO : existingPublicIPs) {
            final long publicIP = NetUtils.ip2Long(publicIPVO.getAddress().addr());
            if (publicIP >= privateStartIp && publicIP <= privateEndIp) {
                throw new InvalidParameterValueException("The Start IP and endIP address range overlap with Public IP :" + publicIPVO.getAddress().addr());
            }
        }
    }

    private void checkOverlapPrivateIpRange(final Long zoneId, final String startIp, final String endIp) {

        final List<HostPodVO> podsInZone = _podDao.listByDataCenterId(zoneId);
        for (final HostPodVO hostPod : podsInZone) {
            final String[] existingPodIpRanges = hostPod.getDescription().split(",");

            for(String podIpRange: existingPodIpRanges) {
                final String[] existingPodIpRange = podIpRange.split("-");

                if (existingPodIpRange.length > 1) {
                    if (!NetUtils.isValidIp4(existingPodIpRange[0]) || !NetUtils.isValidIp4(existingPodIpRange[1])) {
                        continue;
                    }

                    if (NetUtils.ipRangesOverlap(startIp, endIp, existingPodIpRange[0], existingPodIpRange[1])) {
                        throw new InvalidParameterValueException("The Start IP and EndIP address range overlap with private IP :" + existingPodIpRange[0] + ":" + existingPodIpRange[1]);
                    }
                }
            }
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_ZONE_DELETE, eventDescription = "deleting zone", async = false)
    public boolean deleteZone(final DeleteZoneCmd cmd) {
        return zoneService.deleteZone(cmd);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_ZONE_EDIT, eventDescription = "editing zone", async = false)
    public DataCenter editZone(final UpdateZoneCmd cmd) {
        return zoneService.editZone(cmd);
    }

    @Override
    @DB
    public DataCenterVO createZone(final long userId, final String zoneName, final String dns1, final String dns2, final String internalDns1, final String internalDns2, final String guestCidr, final String domain,
                                   final Long domainId, final NetworkType zoneType, final String allocationStateStr, final String networkDomain, final boolean isSecurityGroupEnabled, final boolean isLocalStorageEnabled,
                                   final String ip6Dns1, final String ip6Dns2, final boolean isEdge, List<String> storageAccessGroups) {
        return zoneService.createZone(userId, zoneName, dns1, dns2, internalDns1, internalDns2, guestCidr, domain, domainId, zoneType, allocationStateStr,
                networkDomain, isSecurityGroupEnabled, isLocalStorageEnabled, ip6Dns1, ip6Dns2, isEdge, storageAccessGroups);
    }

    @Override
    public void createDefaultSystemNetworks(final long zoneId) throws ConcurrentOperationException {
        zoneService.createDefaultSystemNetworks(zoneId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ZONE_CREATE, eventDescription = "creating zone", async = false)
    public DataCenter createZone(final CreateZoneCmd cmd) {
        return zoneService.createZone(cmd);
    }

    @Override
    public ServiceOffering createServiceOffering(final CreateServiceOfferingCmd cmd) {
        return serviceOfferingService.createServiceOffering(cmd);
    }

    private Integer validateVgpuProfileAndGetGpuCount(final Long vgpuProfileId, Integer gpuCount) {
        Integer finalGpuCount = gpuCount;
        if (vgpuProfileId != null) {
            VgpuProfileVO vgpuProfile = vgpuProfileDao.findById(vgpuProfileId);
            if (vgpuProfile == null) {
                throw new InvalidParameterValueException("Please specify a valid vgpu profile.");
            }
            if (gpuCount != null && gpuCount < 1) {
                throw new InvalidParameterValueException("GPU count must be greater than 0.");
            }
            if (gpuCount == null) {
                finalGpuCount = 1;
            }
        }
        return finalGpuCount;
    }

    protected ServiceOfferingVO createServiceOffering(final long userId, final boolean isSystem, final VirtualMachine.Type vmType,
                                                      final String name, final Integer cpu, final Integer ramSize, final Integer speed, final String displayText, final String provisioningType, final boolean localStorageRequired,
                                                      final boolean offerHA, final boolean limitResourceUse, final boolean volatileVm, String tags, final List<Long> domainIds, List<Long> zoneIds, final String hostTag,
                                                      final Integer networkRate, final String deploymentPlanner, final Map<String, String> details, Long rootDiskSizeInGiB, final Boolean isCustomizedIops, Long minIops, Long maxIops,
                                                      Long bytesReadRate, Long bytesReadRateMax, Long bytesReadRateMaxLength,
                                                      Long bytesWriteRate, Long bytesWriteRateMax, Long bytesWriteRateMaxLength,
                                                      Long iopsReadRate, Long iopsReadRateMax, Long iopsReadRateMaxLength,
                                                      Long iopsWriteRate, Long iopsWriteRateMax, Long iopsWriteRateMaxLength,
                                                      final Integer hypervisorSnapshotReserve, String cacheMode, final Long storagePolicyID,
                                                      final boolean dynamicScalingEnabled, final Long diskOfferingId, final boolean diskOfferingStrictness,
                                                      final boolean isCustomized, final boolean encryptRoot, Long vgpuProfileId, Integer gpuCount, Boolean gpuDisplay, final boolean purgeResources, Integer leaseDuration, VMLeaseManager.ExpiryAction leaseExpiryAction) {

        return serviceOfferingService.createServiceOffering(
                userId, isSystem, vmType, name, cpu, ramSize, speed, displayText,
                provisioningType, localStorageRequired, offerHA, limitResourceUse,
                volatileVm, tags, domainIds, zoneIds, hostTag, networkRate,
                deploymentPlanner, details, rootDiskSizeInGiB, isCustomizedIops,
                minIops, maxIops, bytesReadRate, bytesReadRateMax,
                bytesReadRateMaxLength, bytesWriteRate, bytesWriteRateMax,
                bytesWriteRateMaxLength, iopsReadRate, iopsReadRateMax,
                iopsReadRateMaxLength, iopsWriteRate, iopsWriteRateMax,
                iopsWriteRateMaxLength, hypervisorSnapshotReserve, cacheMode,
                storagePolicyID, dynamicScalingEnabled, diskOfferingId,
                diskOfferingStrictness, isCustomized, encryptRoot, vgpuProfileId,
                gpuCount, gpuDisplay, purgeResources, leaseDuration,
                leaseExpiryAction);
    }

    /**
     * This method will return valid and non-empty expiryAction  when
     * "instance.lease.enabled" feature is enabled at global level
     * leaseDuration is positive > 0 and has valid leaseExpiryAction provided
     * @param leaseDuration
     * @param cmdExpiryAction
     * @return leaseExpiryAction
     */
    public static VMLeaseManager.ExpiryAction validateAndGetLeaseExpiryAction(Integer leaseDuration, VMLeaseManager.ExpiryAction cmdExpiryAction) {
        if (!VMLeaseManager.InstanceLeaseEnabled.value() || ObjectUtils.allNull(leaseDuration, cmdExpiryAction)) { // both are null
            return null;
        }

        // one of them is non-null
        if (ObjectUtils.anyNull(leaseDuration, cmdExpiryAction)) {
            throw new InvalidParameterValueException("Provide values for both: leaseduration and leaseexpiryaction");
        }

        if (leaseDuration < 1L || leaseDuration > VMLeaseManager.MAX_LEASE_DURATION_DAYS) {
            throw new InvalidParameterValueException("Invalid leaseduration: must be a natural number (>=1), max supported value is 36500");
        }

        return cmdExpiryAction;
    }

    @Override
    public void validateExtraConfigInServiceOfferingDetail(String detailName) {
        if (!detailName.equals(DpdkHelper.DPDK_NUMA) && !detailName.equals(DpdkHelper.DPDK_HUGE_PAGES)
                && !detailName.startsWith(DpdkHelper.DPDK_INTERFACE_PREFIX)) {
            throw new InvalidParameterValueException("Only extraconfig for DPDK are supported in service offering details");
        }
    }

    private void setIopsRate(DiskOffering offering, Long iopsReadRate, Long iopsReadRateMax, Long iopsReadRateMaxLength, Long iopsWriteRate, Long iopsWriteRateMax, Long iopsWriteRateMaxLength) {
        if (iopsReadRate != null && iopsReadRate > 0) {
            offering.setIopsReadRate(iopsReadRate);
        }
        if (iopsReadRateMax != null && iopsReadRateMax > 0) {
            offering.setIopsReadRateMax(iopsReadRateMax);
        }
        if (iopsReadRateMaxLength != null && iopsReadRateMaxLength > 0) {
            offering.setIopsReadRateMaxLength(iopsReadRateMaxLength);
        }
        if (iopsWriteRate != null && iopsWriteRate > 0) {
            offering.setIopsWriteRate(iopsWriteRate);
        }
        if (iopsWriteRateMax != null && iopsWriteRateMax > 0) {
            offering.setIopsWriteRateMax(iopsWriteRateMax);
        }
        if (iopsWriteRateMaxLength != null && iopsWriteRateMaxLength > 0) {
            offering.setIopsWriteRateMaxLength(iopsWriteRateMaxLength);
        }
    }

    private void setBytesRate(DiskOffering offering, Long bytesReadRate, Long bytesReadRateMax, Long bytesReadRateMaxLength, Long bytesWriteRate, Long bytesWriteRateMax, Long bytesWriteRateMaxLength) {
        if (bytesReadRate != null && bytesReadRate > 0) {
            offering.setBytesReadRate(bytesReadRate);
        }
        if (bytesReadRateMax != null && bytesReadRateMax > 0) {
            offering.setBytesReadRateMax(bytesReadRateMax);
        }
        if (bytesReadRateMaxLength != null && bytesReadRateMaxLength > 0) {
            offering.setBytesReadRateMaxLength(bytesReadRateMaxLength);
        }
        if (bytesWriteRate != null && bytesWriteRate > 0) {
            offering.setBytesWriteRate(bytesWriteRate);
        }
        if (bytesWriteRateMax != null && bytesWriteRateMax > 0) {
            offering.setBytesWriteRateMax(bytesWriteRateMax);
        }
        if (bytesWriteRateMaxLength != null && bytesWriteRateMaxLength > 0) {
            offering.setBytesWriteRateMaxLength(bytesWriteRateMaxLength);
        }
    }

    protected boolean serviceOfferingExternalDetailsNeedUpdate(final Map<String, String> offeringDetails,
               final Map<String, String> externalDetails, final boolean cleanupExternalDetails) {
        if (MapUtils.isEmpty(externalDetails) && !cleanupExternalDetails) {
            return false;
        }

        Map<String, String> existingExternalDetails = offeringDetails.entrySet().stream()
                .filter(detail -> detail.getKey().startsWith(VmDetailConstants.EXTERNAL_DETAIL_PREFIX))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

       if (cleanupExternalDetails) {
           return !MapUtils.isEmpty(existingExternalDetails);
       }

        if (MapUtils.isEmpty(existingExternalDetails) || existingExternalDetails.size() != externalDetails.size()) {
            return true;
        }

        for (Map.Entry<String, String> entry : externalDetails.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!value.equals(existingExternalDetails.get(key))) {
                return true;
            }
        }
        return false;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_OFFERING_CLONE, eventDescription = "cloning service offering")
    public ServiceOffering cloneServiceOffering(final CloneServiceOfferingCmd cmd) {
        final long userId = CallContext.current().getCallingUserId();
        final ServiceOfferingVO sourceOffering = offeringCloneParameterService.getAndValidateSourceOffering(cmd.getSourceOfferingId());
        final DiskOfferingVO sourceDiskOffering = offeringCloneParameterService.getSourceDiskOffering(sourceOffering);
        final Map<String, String> requestParams = cmd.getFullUrlParams();

        final String name = cmd.getServiceOfferingName();
        final String displayText = getOrDefault(cmd.getDisplayText(), sourceOffering.getDisplayText());
        final Integer cpuNumber = getOrDefault(cmd.getCpuNumber(), sourceOffering.getCpu());
        final Integer cpuSpeed = getOrDefault(cmd.getCpuSpeed(), sourceOffering.getSpeed());
        final Integer memory = getOrDefault(cmd.getMemory(), sourceOffering.getRamSize());
        final String provisioningType = offeringCloneParameterService.resolveProvisioningType(cmd, sourceDiskOffering);

        final Boolean offerHa = resolveBooleanParam(requestParams, ApiConstants.OFFER_HA, cmd::isOfferHa, sourceOffering.isOfferHA());
        final Boolean limitCpuUse = resolveBooleanParam(requestParams, ApiConstants.LIMIT_CPU_USE, cmd::isLimitCpuUse, sourceOffering.getLimitCpuUse());
        final Boolean isVolatile = resolveBooleanParam(requestParams, ApiConstants.IS_VOLATILE, cmd::isVolatileVm, sourceOffering.isVolatileVm());
        final Boolean isCustomized = resolveBooleanParam(requestParams, ApiConstants.CUSTOMIZED, cmd::isCustomized, sourceOffering.isCustomized());
        final Boolean dynamicScalingEnabled = resolveBooleanParam(requestParams, ApiConstants.DYNAMIC_SCALING_ENABLED, cmd::getDynamicScalingEnabled, sourceOffering.isDynamicScalingEnabled());
        final Boolean diskOfferingStrictness = resolveBooleanParam(requestParams, ApiConstants.DISK_OFFERING_STRICTNESS, cmd::getDiskOfferingStrictness, sourceOffering.getDiskOfferingStrictness());
        final Boolean encryptRoot = resolveBooleanParam(requestParams, ApiConstants.ENCRYPT_ROOT, cmd::getEncryptRoot, sourceDiskOffering != null && sourceDiskOffering.getEncrypt());
        final Boolean gpuDisplay = resolveBooleanParam(requestParams, ApiConstants.GPU_DISPLAY, cmd::getGpuDisplay, sourceOffering.getGpuDisplay());

        final String storageType = offeringCloneParameterService.resolveStorageType(cmd, sourceDiskOffering);
        final String tags = getOrDefault(cmd.getTags(), sourceDiskOffering != null ? sourceDiskOffering.getTags() : null);
        final List<Long> domainIds = offeringCloneParameterService.resolveDomainIds(cmd, sourceOffering);
        final List<Long> zoneIds = offeringCloneParameterService.resolveZoneIds(cmd, sourceOffering);
        final String hostTag = getOrDefault(cmd.getHostTag(), sourceOffering.getHostTag());
        final Integer networkRate = getOrDefault(cmd.getNetworkRate(), sourceOffering.getRateMbps());
        final String deploymentPlanner = getOrDefault(cmd.getDeploymentPlanner(), sourceOffering.getDeploymentPlanner());

        final OfferingCloneParameterServiceImpl.ClonedDiskOfferingParams diskParams = offeringCloneParameterService.resolveDiskOfferingParams(cmd, sourceDiskOffering);

        final OfferingCloneParameterServiceImpl.CustomOfferingParams customParams = offeringCloneParameterService.resolveCustomOfferingParams(cmd, sourceOffering, isCustomized);

        final Long vgpuProfileId = getOrDefault(cmd.getVgpuProfileId(), sourceOffering.getVgpuProfileId());
        final Integer gpuCount = getOrDefault(cmd.getGpuCount(), sourceOffering.getGpuCount());

        final Boolean purgeResources = offeringCloneParameterService.resolvePurgeResources(cmd, requestParams, sourceOffering);
        final OfferingCloneParameterServiceImpl.LeaseParams leaseParams = offeringCloneParameterService.resolveLeaseParams(cmd, sourceOffering);

        if (cmd.getCacheMode() != null) {
            validateCacheMode(cmd.getCacheMode());
        }
        final Integer finalGpuCount = validateVgpuProfileAndGetGpuCount(vgpuProfileId, gpuCount);

        final Map<String, String> mergedDetails = offeringCloneParameterService.mergeOfferingDetails(cmd, sourceOffering, customParams);

        final boolean localStorageRequired = ServiceOffering.StorageType.local.toString().equalsIgnoreCase(storageType);

        final boolean systemUse = sourceOffering.isSystemUse();
        final VirtualMachine.Type vmType = offeringCloneParameterService.resolveVmType(sourceOffering);

        final Long diskOfferingId = getOrDefault(cmd.getDiskOfferingId(), sourceOffering.getDiskOfferingId());

        return createServiceOffering(userId, systemUse, vmType,
                name, cpuNumber, memory, cpuSpeed, displayText, provisioningType, localStorageRequired,
                offerHa, limitCpuUse, isVolatile, tags, domainIds, zoneIds, hostTag, networkRate,
                deploymentPlanner, mergedDetails, diskParams.rootDiskSize, diskParams.isCustomizedIops,
                diskParams.minIops, diskParams.maxIops,
                diskParams.bytesReadRate, diskParams.bytesReadRateMax, diskParams.bytesReadRateMaxLength,
                diskParams.bytesWriteRate, diskParams.bytesWriteRateMax, diskParams.bytesWriteRateMaxLength,
                diskParams.iopsReadRate, diskParams.iopsReadRateMax, diskParams.iopsReadRateMaxLength,
                diskParams.iopsWriteRate, diskParams.iopsWriteRateMax, diskParams.iopsWriteRateMaxLength,
                diskParams.hypervisorSnapshotReserve, diskParams.cacheMode, customParams.storagePolicy, dynamicScalingEnabled,
                diskOfferingId, diskOfferingStrictness, isCustomized, encryptRoot,
                vgpuProfileId, finalGpuCount, gpuDisplay, purgeResources, leaseParams.leaseDuration, leaseParams.leaseExpiryAction);
    }

    public <T> T getOrDefault(T cmdValue, T defaultValue) {
        return offeringCloneParameterService.getOrDefault(cmdValue, defaultValue);
    }

    public Boolean resolveBooleanParam(Map<String, String> requestParams, String paramKey,
                                       java.util.function.Supplier<Boolean> cmdValueSupplier, Boolean defaultValue) {
        return offeringCloneParameterService.resolveBooleanParam(requestParams, paramKey, cmdValueSupplier, defaultValue);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_DISK_OFFERING_CLONE, eventDescription = "cloning disk offering")
    public DiskOffering cloneDiskOffering(final CloneDiskOfferingCmd cmd) {
        return diskOfferingService.cloneDiskOffering(cmd);
    }

    @Override
    public ServiceOffering updateServiceOffering(final UpdateServiceOfferingCmd cmd) {
        return serviceOfferingService.updateServiceOffering(cmd);
    }

    @Override
    public List<Long> getServiceOfferingDomains(Long serviceOfferingId) {
        return serviceOfferingService.getServiceOfferingDomains(serviceOfferingId);
    }

    @Override
    public List<Long> getServiceOfferingZones(Long serviceOfferingId) {
        return serviceOfferingService.getServiceOfferingZones(serviceOfferingId);
    }

    @Override
    public DiskOffering createDiskOffering(final CreateDiskOfferingCmd cmd) {
        return diskOfferingService.createDiskOffering(cmd);
    }

    @Override
    public DiskOffering updateDiskOffering(final UpdateDiskOfferingCmd cmd) {
        return diskOfferingService.updateDiskOffering(cmd);
    }

    /**
     * Check the host tags parameters to the service offering
     * <ul>
     *     <li>If host tags is null, do nothing and return.</li>
     *     <li>If host tags is not null, will set host tag to the service offering if the hosts with active VMs have the new tags.</li>
     *     <li>If host tags is an blank string, set null on service offering tag.</li>
     * </ul>
     */
    protected void updateServiceOfferingHostTagsIfNotNull(String hostTags, ServiceOfferingVO offering) {
        if (hostTags == null) {
            return;
        }
        if (StringUtils.isNotBlank(hostTags)) {
            hostTags = com.cloud.utils.StringUtils.cleanupTags(hostTags);
            List<HostVO> hosts = _hostDao.listHostsWithActiveVMs(offering.getId());
            if (CollectionUtils.isNotEmpty(hosts)) {
                List<String> listOfHostTags = Arrays.asList(hostTags.split(","));
                for (HostVO host : hosts) {
                    List<HostTagVO> tagsOnHost = hostTagDao.getHostTags(host.getId());
                    List<String> tagsAsString = tagsOnHost.stream().map(HostTagVO::getTag).collect(Collectors.toList());

                    if ((CollectionUtils.isNotEmpty(tagsAsString) && tagsAsString.containsAll(listOfHostTags)) ||
                        (tagsOnHost.size() == 1 && tagsOnHost.get(0).getIsTagARule() &&
                        TagAsRuleHelper.interpretTagAsRule(tagsOnHost.get(0).getTag(), hostTags, HostTagsDao.hostTagRuleExecutionTimeout.value()))) {
                        continue;
                    }

                    throw new InvalidParameterValueException(String.format("There are active VMs using offering [%s], and the hosts [%s] don't have the new tags",
                        offering, hosts));
                }
            }
            offering.setHostTag(hostTags);
        } else {
            offering.setHostTag(null);
        }
    }

    @Override
    public boolean deleteDiskOffering(final DeleteDiskOfferingCmd cmd) {
        return diskOfferingService.deleteDiskOffering(cmd);
    }

    @Override
    public List<Long> getDiskOfferingDomains(Long diskOfferingId) {
        return diskOfferingService.getDiskOfferingDomains(diskOfferingId);
    }

    @Override
    public List<Long> getDiskOfferingZones(Long diskOfferingId) {
        return diskOfferingService.getDiskOfferingZones(diskOfferingId);
    }

    @Override
    public boolean deleteServiceOffering(final DeleteServiceOfferingCmd cmd) {
        return serviceOfferingService.deleteServiceOffering(cmd);
    }

    // -----------------------------------------------------------------
    // VlanService delegating wrappers — Phase 4 slice 7
    // The original VLAN/public-IP-range implementation has been
    // extracted into {@link VlanServiceImpl}. These thin wrappers
    // preserve both the ConfigurationService (API-facing) and
    // ConfigurationManager (engine-facing) contracts, plus the
    // _configMgr-routed callers in AccountManagerImpl and
    // DomainManagerImpl.
    // -----------------------------------------------------------------

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VLAN_IP_RANGE_CREATE, eventDescription = "Creating VLAN IP range", async = false)
    public Vlan createVlanAndPublicIpRange(final CreateVlanIpRangeCmd cmd) throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException,
    ResourceAllocationException {
        return vlanService.createVlanAndPublicIpRange(cmd);
    }

    public NetUtils.SupersetOrSubset checkIfSubsetOrSuperset(String vlanGateway, String vlanNetmask, String newVlanGateway, String newVlanNetmask, final String newStartIP, final String newEndIP) {
        return vlanService.checkIfSubsetOrSuperset(vlanGateway, vlanNetmask, newVlanGateway, newVlanNetmask, newStartIP, newEndIP);
    }

    public Pair<Boolean, Pair<String, String>> validateIpRange(final String startIP, final String endIP, final String newVlanGateway, final String newVlanNetmask, final List<VlanVO> vlans, final boolean ipv4,
            final boolean ipv6, String ip6Gateway, String ip6Cidr, final String startIPv6, final String endIPv6, final Network network) {
        return vlanService.validateIpRange(startIP, endIP, newVlanGateway, newVlanNetmask, vlans, ipv4, ipv6, ip6Gateway, ip6Cidr, startIPv6, endIPv6, network);
    }

    public boolean hasSameSubnet(boolean ipv4, String vlanGateway, String vlanNetmask, String newVlanGateway, String newVlanNetmask, String newStartIp, String newEndIp,
                                  boolean ipv6, String newIp6Gateway, String newIp6Cidr, String newIp6StartIp, String newIp6EndIp, Network network) {
        return vlanService.hasSameSubnet(ipv4, vlanGateway, vlanNetmask, newVlanGateway, newVlanNetmask, newStartIp, newEndIp, ipv6, newIp6Gateway, newIp6Cidr, newIp6StartIp, newIp6EndIp, network);
    }

    @Override
    @DB
    public Vlan createVlanAndPublicIpRange(final long zoneId, final long networkId, final long physicalNetworkId, final boolean forVirtualNetwork, final boolean forSystemVms, final Long podId, final String startIP, final String endIP,
                                           final String vlanGateway, final String vlanNetmask, String vlanId, boolean bypassVlanOverlapCheck, Domain domain, final Account vlanOwner, final String startIPv6, final String endIPv6, final String vlanIp6Gateway, final String vlanIp6Cidr, Provider provider) {
        return vlanService.createVlanAndPublicIpRange(zoneId, networkId, physicalNetworkId, forVirtualNetwork, forSystemVms, podId, startIP, endIP, vlanGateway, vlanNetmask, vlanId, bypassVlanOverlapCheck, domain, vlanOwner, startIPv6, endIPv6, vlanIp6Gateway, vlanIp6Cidr, provider);
    }

    @Override
    public Vlan updateVlanAndPublicIpRange(UpdateVlanIpRangeCmd cmd) throws ConcurrentOperationException,
            ResourceUnavailableException, ResourceAllocationException {
        return vlanService.updateVlanAndPublicIpRange(cmd);
    }

    @Override
    @DB
    public VlanVO deleteVlanAndPublicIpRange(final long userId, final long vlanDbId, final Account caller) {
        return vlanService.deleteVlanAndPublicIpRange(userId, vlanDbId, caller);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VLAN_IP_RANGE_DEDICATE, eventDescription = "dedicating vlan ip range", async = false)
    public Vlan dedicatePublicIpRange(final DedicatePublicIpRangeCmd cmd) throws ResourceAllocationException {
        return vlanService.dedicatePublicIpRange(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VLAN_IP_RANGE_RELEASE, eventDescription = "releasing a public ip range", async = false)
    public boolean releasePublicIpRange(final ReleasePublicIpRangeCmd cmd) {
        return vlanService.releasePublicIpRange(cmd);
    }

    @DB
    public boolean releasePublicIpRange(final long vlanDbId, final User user, final Account caller) {
        return vlanService.releasePublicIpRange(vlanDbId, user, caller);
    }

    @Override
    public void checkPodCidrSubnets(final long dcId, final Long podIdToBeSkipped, final String cidr) {
        vlanService.checkPodCidrSubnets(dcId, podIdToBeSkipped, cidr);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VLAN_IP_RANGE_DELETE, eventDescription = "deleting vlan ip range", async = false)
    public boolean deleteVlanIpRange(final DeleteVlanIpRangeCmd cmd) {
        return vlanService.deleteVlanIpRange(cmd);
    }

    private String getCidrAddress(final String cidr) {
        final String[] cidrPair = cidr.split("\\/");
        return cidrPair[0];
    }

    private int getCidrSize(final String cidr) {
        final String[] cidrPair = cidr.split("\\/");
        return Integer.parseInt(cidrPair[1]);
    }

    private boolean validPod(final long podId) {
        return _podDao.findById(podId) != null;
    }

    private boolean validPod(final String podName, final long zoneId) {
        return _podDao.findByName(podName, zoneId) != null;
    }

    private boolean validZone(final String zoneName) {
        return _zoneDao.findByName(zoneName) != null;
    }

    private boolean validZone(final long zoneId) {
        return _zoneDao.findById(zoneId) != null;
    }



    @Override
    public void checkDiskOfferingAccess(final Account caller, final DiskOffering dof, DataCenter zone) {
        for (final SecurityChecker checker : _secChecker) {
            if (checker.checkAccess(caller, dof, zone)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access granted to {} to disk offering: {} by {}", caller, dof, checker.getName());
                }
                return;
            } else {
                throw new PermissionDeniedException(String.format("Access denied to %s for disk offering: %s, zone: %s by %s", caller, dof, zone, checker.getName()));
            }
        }

        assert false : "How can all of the security checkers pass on checking this caller?";
        throw new PermissionDeniedException(String.format("There's no way to confirm %s has access to disk offering:%s", caller, dof));
    }

    @Override
    public void checkZoneAccess(final Account caller, final DataCenter zone) {
        for (final SecurityChecker checker : _secChecker) {
            if (checker.checkAccess(caller, zone)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access granted to {} to zone:{} by {}", caller, zone, checker.getName());
                }
                return;
            } else {
                throw new PermissionDeniedException(String.format("Access denied to %s by %s for zone %s", caller, checker.getName(), zone));
            }
        }

        assert false : "How can all of the security checkers pass on checking this caller?";
        throw new PermissionDeniedException(String.format("There's no way to confirm %s has access to zone:%s", caller, zone));
    }

    @Override
    public NetworkOffering createNetworkOffering(final NetworkOfferingBaseCmd cmd) {
        return networkOfferingService.createNetworkOffering(cmd);
    }

    public static NetworkOffering.RoutingMode verifyRoutingMode(String routingModeString) {
        return NetworkOfferingServiceImpl.verifyRoutingMode(routingModeString);
    }

    @Override
    public NetworkOfferingVO createNetworkOffering(final String name, final String displayText, final TrafficType trafficType, final String tags, final boolean specifyVlan,
                                                   final Availability availability,
                                                   final Integer networkRate, final Map<Service, Set<Provider>> serviceProviderMap, final boolean isDefault, final GuestType type, final boolean systemOnly,
                                                   final Long serviceOfferingId,
                                                   final boolean conserveMode, final Map<Service, Map<Capability, String>> serviceCapabilityMap, final boolean specifyIpRanges, final boolean isPersistent,
                                                   final Map<Detail, String> details, final boolean egressDefaultPolicy, final Integer maxconn, final boolean enableKeepAlive, final Boolean forVpc,
                                                   final Boolean forTungsten, final boolean forNsx, final boolean forNetris, final NetworkOffering.NetworkMode networkMode, final List<Long> domainIds, final List<Long> zoneIds, final boolean enableOffering, final NetUtils.InternetProtocol internetProtocol,
                                                   final NetworkOffering.RoutingMode routingMode, final boolean specifyAsNumber) {
        return networkOfferingService.createNetworkOffering(name, displayText, trafficType, tags, specifyVlan, availability, networkRate, serviceProviderMap, isDefault, type, systemOnly, serviceOfferingId, conserveMode, serviceCapabilityMap, specifyIpRanges, isPersistent, details, egressDefaultPolicy, maxconn, enableKeepAlive, forVpc, forTungsten, forNsx, forNetris, networkMode, domainIds, zoneIds, enableOffering, internetProtocol, routingMode, specifyAsNumber);
    }

    boolean isRedundantRouter(Set<Provider> providers, Service service, Map<Capability, String> sourceNatServiceCapabilityMap) {
        return ((NetworkOfferingServiceImpl) networkOfferingService).isRedundantRouter(providers, service, sourceNatServiceCapabilityMap);
    }

    boolean isSharedSourceNat(Map<Service, Set<Provider>> serviceProviderMap, Map<Capability, String> sourceNatServiceCapabilityMap) {
        return ((NetworkOfferingServiceImpl) networkOfferingService).isSharedSourceNat(serviceProviderMap, sourceNatServiceCapabilityMap);
    }

    boolean sourceNatCapabilitiesContainValidValues(Map<Capability, String> sourceNatServiceCapabilityMap) {
        return ((NetworkOfferingServiceImpl) networkOfferingService).sourceNatCapabilitiesContainValidValues(sourceNatServiceCapabilityMap);
    }

    protected void validateNtwkOffDetails(final Map<Detail, String> details, final Map<Service, Set<Provider>> serviceProviderMap) {
        ((NetworkOfferingServiceImpl) networkOfferingService).validateNtwkOffDetails(details, serviceProviderMap);
    }

    void validateSourceNatServiceCapablities(final Map<Network.Capability, String> sourceNatServiceCapabilityMap) {
        ((NetworkOfferingServiceImpl) networkOfferingService).validateSourceNatServiceCapablities(sourceNatServiceCapabilityMap);
    }

    void validateStaticNatServiceCapablities(final Map<Network.Capability, String> staticNatServiceCapabilityMap) {
        ((NetworkOfferingServiceImpl) networkOfferingService).validateStaticNatServiceCapablities(staticNatServiceCapabilityMap);
    }

    @Override
    public Pair<List<? extends NetworkOffering>, Integer> searchForNetworkOfferings(final ListNetworkOfferingsCmd cmd) {
        return networkOfferingService.searchForNetworkOfferings(cmd);
    }

    @Override
    public boolean isOfferingForVpc(final NetworkOffering offering) {
        return networkOfferingService.isOfferingForVpc(offering);
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_OFFERING_DELETE, eventDescription = "deleting network offering")
    public boolean deleteNetworkOffering(final DeleteNetworkOfferingCmd cmd) {
        return networkOfferingService.deleteNetworkOffering(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_OFFERING_CLONE, eventDescription = "cloning network offering")
    public NetworkOffering cloneNetworkOffering(final CloneNetworkOfferingCmd cmd) {
        return networkOfferingService.cloneNetworkOffering(cmd);
    }

    public static String getExternalNetworkProvider(String detectedProvider,
                                             Map<Network.Service, Set<Network.Provider>> sourceServiceProviderMap) {
        return NetworkOfferingServiceImpl.getExternalNetworkProvider(detectedProvider, sourceServiceProviderMap);
    }

    public static void setField(Object obj, String fieldName, Object value) throws Exception {
        NetworkOfferingServiceImpl.setField(obj, fieldName, value);
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        return NetworkOfferingServiceImpl.findField(clazz, fieldName);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_OFFERING_EDIT, eventDescription = "updating network offering")
    public NetworkOffering updateNetworkOffering(final UpdateNetworkOfferingCmd cmd) {
        return networkOfferingService.updateNetworkOffering(cmd);
    }

    @Override
    public List<Long> getNetworkOfferingDomains(Long networkOfferingId) {
        return networkOfferingService.getNetworkOfferingDomains(networkOfferingId);
    }

    @Override
    public List<Long> getNetworkOfferingZones(Long networkOfferingId) {
        return networkOfferingService.getNetworkOfferingZones(networkOfferingId);
    }

    @Override
    public Integer getNetworkOfferingNetworkRate(final long networkOfferingId, final Long dataCenterId) {
        return networkOfferingService.getNetworkOfferingNetworkRate(networkOfferingId, dataCenterId);
    }

    @Override
    public List<? extends NetworkOffering> listNetworkOfferings(final TrafficType trafficType, final boolean systemOnly) {
        return networkOfferingService.listNetworkOfferings(trafficType, systemOnly);
    }

    @Override
    @DB
    public boolean releaseDomainSpecificVirtualRanges(final Domain domain) {
        return vlanService.releaseDomainSpecificVirtualRanges(domain);
    }

    @Override
    @DB
    public boolean releaseAccountSpecificVirtualRanges(final Account account) {
        return vlanService.releaseAccountSpecificVirtualRanges(account);
    }

    @Override
    public Account getVlanAccount(long vlanId) {
        return vlanService.getVlanAccount(vlanId);
    }

    @Override
    public Domain getVlanDomain(long vlanId) {
        return vlanService.getVlanDomain(vlanId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACCOUNT_MARK_DEFAULT_ZONE, eventDescription = "Marking account with the " + "default zone", async = true)
    public AccountVO markDefaultZone(final String accountName, final long domainId, final long defaultZoneId) {
        // Check if the account exists
        final Account account = _accountDao.findEnabledAccount(accountName, domainId);
        if (account == null) {
            DomainVO domain = _domainDao.findById(domainId);
            String domainStr = domain == null ? String.valueOf(domainId) : domain.toString();
            logger.error("Unable to find account by name: {} in domain {}", accountName, domainStr);
            throw new InvalidParameterValueException(String.format("Account by name: %s doesn't exist in domain %s", accountName, domainStr));
        }

        // Don't allow modification of system account
        if (account.getId() == Account.ACCOUNT_ID_SYSTEM) {
            throw new InvalidParameterValueException("Can not modify system account");
        }

        final AccountVO acctForUpdate = _accountDao.findById(account.getId());
        acctForUpdate.setDefaultZoneId(defaultZoneId);

        if (_accountDao.update(account.getId(), acctForUpdate)) {
            CallContext.current().setEventDetails("Default zone ID: " + defaultZoneId);
            return _accountDao.findById(account.getId());
        } else {
            return null;
        }
    }

    @Override
    public AllocationState findClusterAllocationState(final ClusterVO cluster) {

        if (cluster.getAllocationState() == AllocationState.Disabled) {
            return AllocationState.Disabled;
        } else if (ApiDBUtils.findPodById(cluster.getPodId()).getAllocationState() == AllocationState.Disabled) {
            return AllocationState.Disabled;
        } else {
            final DataCenterVO zone = ApiDBUtils.findZoneById(cluster.getDataCenterId());
            return zone.getAllocationState();
        }
    }

    @Override
    public AllocationState findPodAllocationState(final HostPodVO pod) {

        if (pod.getAllocationState() == AllocationState.Disabled) {
            return AllocationState.Disabled;
        } else {
            final DataCenterVO zone = ApiDBUtils.findZoneById(pod.getDataCenterId());
            return zone.getAllocationState();
        }
    }

    @Override
    public Long getDefaultPageSize() {
        return _defaultPageSize;
    }

    @Override
    public Integer getServiceOfferingNetworkRate(final long serviceOfferingId, final Long dataCenterId) {

        // validate network offering information
        final ServiceOffering offering = _serviceOfferingDao.findById(serviceOfferingId);
        if (offering == null) {
            throw new InvalidParameterValueException("Unable to find service offering by id=" + serviceOfferingId);
        }

        Integer networkRate;
        if (offering.getRateMbps() != null) {
            networkRate = offering.getRateMbps();
        } else {
            // for domain router service offering, get network rate from
            if (offering.getVmType() != null && offering.getVmType().equalsIgnoreCase(VirtualMachine.Type.DomainRouter.toString())) {
                networkRate = NetworkOrchestrationService.NetworkThrottlingRate.valueIn(dataCenterId);
            } else {
                networkRate = Integer.parseInt(_configDao.getValue(Config.VmNetworkThrottlingRate.key()));
            }
        }

        // networkRate is unsigned int in serviceOffering table, and can't be
        // set to -1
        // so 0 means unlimited; we convert it to -1, so we are consistent with
        // all our other resources where -1 means unlimited
        if (networkRate == 0) {
            networkRate = -1;
        }

        return networkRate;
    }

    @Override
    public PortableIpRange createPortableIpRange(final CreatePortableIpRangeCmd cmd) throws ConcurrentOperationException {
        return portableIpRangeService.createPortableIpRange(cmd);
    }

    @Override
    public boolean deletePortableIpRange(final DeletePortableIpRangeCmd cmd) {
        return portableIpRangeService.deletePortableIpRange(cmd);
    }

    @Override
    public List<? extends PortableIpRange> listPortableIpRanges(final ListPortableIpRangesCmd cmd) {
        return portableIpRangeService.listPortableIpRanges(cmd);
    }

    @Override
    public List<? extends PortableIp> listPortableIps(final long id) {
        return portableIpRangeService.listPortableIps(id);
    }

    private boolean checkOverlapPortableIpRange(final int regionId, final String newStartIpStr, final String newEndIpStr) {
        final long newStartIp = NetUtils.ip2Long(newStartIpStr);
        final long newEndIp = NetUtils.ip2Long(newEndIpStr);

        final List<PortableIpRangeVO> existingPortableIPRanges = _portableIpRangeDao.listByRegionId(regionId);

        if (existingPortableIPRanges == null || existingPortableIPRanges.isEmpty()) {
            return false;
        }

        for (final PortableIpRangeVO portableIpRange : existingPortableIPRanges) {
            final String ipRangeStr = portableIpRange.getIpRange();
            final String[] range = ipRangeStr.split("-");
            final long startip = NetUtils.ip2Long(range[0]);
            final long endIp = NetUtils.ip2Long(range[1]);

            if (newStartIp >= startip && newStartIp <= endIp || newEndIp >= startip && newEndIp <= endIp) {
                return true;
            }

            if (startip >= newStartIp && startip <= newEndIp || endIp >= newStartIp && endIp <= newEndIp) {
                return true;
            }
        }
        return false;
    }

    protected void validateCacheMode(String cacheMode){
        if(cacheMode != null &&
                !Enums.getIfPresent(DiskOffering.DiskCacheMode.class,
                        cacheMode.toUpperCase()).isPresent()) {
            throw new InvalidParameterValueException(String.format("Invalid cache mode (%s). Please specify one of the following " +
                    "valid cache mode parameters: none, writeback, writethrough or hypervisor_default.", cacheMode));
        }
    }

    public List<SecurityChecker> getSecChecker() {
        return _secChecker;
    }


    @Override
    public Boolean isAccountAllowedToCreateOfferingsWithTags(IsAccountAllowedToCreateOfferingsWithTagsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Account targetAccount = _accountMgr.getAccount(cmd.getId());
        _accountMgr.checkAccess(caller, null, true, targetAccount);
        return ALLOW_DOMAIN_ADMINS_TO_CREATE_TAGGED_OFFERINGS.valueIn(cmd.getId());
    }

    @Inject
    public void setSecChecker(final List<SecurityChecker> secChecker) {
        _secChecker = secChecker;
    }

    @Override
    public String getConfigComponentName() {
        return ConfigurationManagerImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {SystemVMUseLocalStorage, IOPS_MAX_READ_LENGTH, IOPS_MAX_WRITE_LENGTH,
                BYTES_MAX_READ_LENGTH, BYTES_MAX_WRITE_LENGTH, ADD_HOST_ON_SERVICE_RESTART_KVM, SET_HOST_DOWN_TO_MAINTENANCE,
                VM_SERVICE_OFFERING_MAX_CPU_CORES, VM_SERVICE_OFFERING_MAX_RAM_SIZE, MIGRATE_VM_ACROSS_CLUSTERS,
                ENABLE_ACCOUNT_SETTINGS_FOR_DOMAIN, ENABLE_DOMAIN_SETTINGS_FOR_CHILD_DOMAIN,
                ALLOW_DOMAIN_ADMINS_TO_CREATE_TAGGED_OFFERINGS, EXPOSE_ERRORS_TO_USER, DELETE_QUERY_BATCH_SIZE, AllowNonRFC1918CompliantIPs, HostCapacityTypeCpuMemoryWeight
        };
    }

    /**
     * Returns a string representing the specified configuration's type.
     * @param configName name of the configuration.
     * @return if the configuration exists, returns its type; if not, returns {@link Configuration.ValueType#String}.
     */
    @Override
    public String getConfigurationType(final String configName) {
        final ConfigurationVO cfg = _configDao.findByName(configName);
        if (cfg == null) {
            logger.warn("Configuration [{}] not found", configName);
            return Configuration.ValueType.String.name();
        }

        if (weightBasedParametersForValidation.contains(configName)) {
            return Configuration.ValueType.Range.name();
        }

        Class<?> type = getConfigurationTypeWrapperClass(configName);
        return parseConfigurationTypeIntoString(type, cfg);
    }

    /**
     * Parses a configuration type's wrapper class into its string representation.
     */
    protected String parseConfigurationTypeIntoString(Class<?> type, ConfigurationVO cfg) {
        return ConfigurationValueValidator.parseConfigurationTypeIntoString(type, cfg);
    }

    @Override
    public Pair<String, String> getConfigurationGroupAndSubGroup(final String configName) {
        if (StringUtils.isBlank(configName)) {
            throw new CloudRuntimeException("Empty configuration name provided");
        }

        final ConfigurationVO cfg = _configDao.findByName(configName);
        if (cfg == null) {
            logger.warn("Configuration " + configName + " not found");
            throw new InvalidParameterValueException("configuration with name " + configName + " doesn't exist");
        }

        String groupName = "Miscellaneous";
        String subGroupName = "Others";
        ConfigurationSubGroupVO configSubGroup = _configSubGroupDao.findById(cfg.getSubGroupId());
        if (configSubGroup != null) {
            subGroupName = configSubGroup.getName();
        }

        ConfigurationGroupVO configGroup = _configGroupDao.findById(cfg.getGroupId());
        if (configGroup != null) {
            groupName = configGroup.getName();
        }

        return new Pair<>(groupName, subGroupName);
    }

    @Override
    public List<ConfigurationSubGroupVO> getConfigurationSubGroups(final Long groupId) {
        return _configSubGroupDao.findByGroup(groupId);
    }

    static class ParamCountPair {
        private Long id;
        private int paramCount;
        private String scope;

        public ParamCountPair(Long id, int paramCount, String scope) {
            this.id = id;
            this.paramCount = paramCount;
            this.scope = scope;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public int getParamCount() {
            return paramCount;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }
    }
}
