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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import com.cloud.network.as.AutoScaleManager;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.BaseCmd.HTTPMethod;
import org.apache.cloudstack.api.command.admin.vm.AssignVMCmd;
import org.apache.cloudstack.api.command.admin.vm.ExpungeVMCmd;
import org.apache.cloudstack.api.command.user.vm.CreateVMFromBackupCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVnfApplianceCmd;
import org.apache.cloudstack.api.command.user.vm.DestroyVMCmd;
import org.apache.cloudstack.api.command.user.vm.RestoreVMCmd;
import org.apache.cloudstack.api.command.user.vm.ScaleVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVmNicCmd;
import org.apache.cloudstack.api.command.user.vm.UpgradeVMCmd;
import org.apache.cloudstack.api.command.user.volume.ResizeVolumeCmd;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.template.VnfTemplateManager;
import org.apache.cloudstack.userdata.UserDataManager;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.dao.ServiceOfferingJoinDao;
import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.configuration.Resource;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.hypervisor.kvm.dpdk.DpdkHelper;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.network.security.SecurityGroupVO;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.resource.ResourceManager;
import com.cloud.server.ManagementService;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotPolicyDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.UserData;
import com.cloud.user.UserDataVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.user.dao.UserDao;
import com.cloud.user.dao.UserDataDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.UUIDManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExceptionProxyObject;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@RunWith(MockitoJUnitRunner.class)
public class UserVmManagerImplTest {

    @Spy
    @InjectMocks
    private UserVmManagerImpl userVmManagerImpl = new UserVmManagerImpl();

    // Slice 11: spy on the destroy-permission service so the five existing
    // checkExpungeVmPermission tests that stub getConfigAllowUserExpungeRecoverVm
    // continue to control the config branch via the new isUserExpungeRecoverVmAllowed
    // method. Initialized in beforeTest() once accountManager is available.
    private VmDestroyPermissionServiceImpl vmDestroyPermissionServiceSpy;

    @Mock
    private ServiceOfferingDao _serviceOfferingDao;

    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    private DataCenterDao _dcDao;

    @Mock
    private DataCenterVO _dcMock;

    @Mock
    protected NicDao nicDao;

    @Mock
    private NetworkDao _networkDao;

    @Mock
    private NetworkOrchestrationService _networkMgr;

    @Mock
    private NetworkVO networkMock;

    @Mock
    private GuestOSDao guestOSDao;

    @Mock
    private UserVmDao userVmDao;

    @Mock
    private UpdateVMCmd updateVmCommand;

    @Mock
    private UpdateVmNicCmd updateVmNicCmd;

    @Mock
    private AccountManager accountManager;

    @Mock
    private AccountService accountService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @Mock
    private UserVmVO userVmVoMock;

    @Mock
    private NetworkModel networkModel;

    @Mock
    private Account accountMock;

    @Mock
    private AccountVO callerAccount;

    @Mock
    private UserVO callerUser;

    @Mock
    private NicVO nicMock;

    @Mock
    private VMTemplateDao templateDao;

    @Mock
    private AccountDao accountDao;

    @Mock
    private UserDao userDao;

    @Mock
    ResourceLimitService resourceLimitMgr;

    @Mock
    VolumeApiService volumeApiService;

    @Mock
    UserDataDao userDataDao;

    @Mock
    PrimaryDataStoreDao primaryDataStoreDao;

    @Mock
    BackupManager backupManager;

    @Mock
    private VmBackupInstanceLifecycleService vmBackupInstanceLifecycleService;
    @Mock
    private VmExpungeFailureTransitionService vmExpungeFailureTransitionService;
    @Mock
    private VmExpungeResourceCleanupService vmExpungeResourceCleanupService;

    @Mock
    VirtualMachineManager virtualMachineManager;

    @Mock
    DeploymentPlanningManager planningManager;

    @Mock
    HostDao hostDao;

    @Mock
    HostPodDao hostPodDao;

    @Mock
    ClusterDao clusterDao;

    @Mock
    ResourceManager resourceManager;

    @Mock
    HypervisorCapabilitiesDao hypervisorCapabilitiesDao;

    @Mock
    DpdkHelper dpdkHelper;

    @Mock
    SnapshotHelper snapshotHelper;

    @Mock
    VmStatsCollectionService vmStatsCollectionService;

    @Mock
    private VolumeVO volumeVOMock;

    @Mock
    private VolumeDao volumeDaoMock;

    @Mock
    private SnapshotDao snapshotDaoMock;

    @Mock
    private VMSnapshotDao vmSnapshotDaoMock;

    @Mock
    AccountVO account;

    @Mock
    VMTemplateVO vmTemplateVoMock;

    @Mock
    ManagementService managementServiceMock;

    @Mock
    AssignVMCmd assignVmCmdMock;

    @Mock
    PortForwardingRulesDao portForwardingRulesDaoMock;

    @Mock
    List<PortForwardingRule> portForwardingRulesListMock;

    @Mock
    FirewallRulesDao firewallRulesDaoMock;

    @Mock
    List<FirewallRuleVO> firewallRuleVoListMock;

    @Mock
    LoadBalancerVMMapDao loadBalancerVmMapDaoMock;

    @Mock
    List<LoadBalancerVMMapVO> loadBalancerVmMapVoListMock;

    @Mock
    IPAddressDao ipAddressDaoMock;

    @Mock
    IPAddressVO ipAddressVoMock;

    @Mock
    VirtualMachineTemplate virtualMachineTemplateMock;

    @Mock
    VirtualMachineProfileImpl virtualMachineProfileMock;

    @Mock
    List<NetworkVO> networkVoListMock;

    @Mock
    SecurityGroupManager securityGroupManagerMock;

    @Mock
    NetworkOfferingDao networkOfferingDaoMock;

    @Mock
    NetworkOfferingVO networkOfferingVoMock;

    @Mock
    List<NetworkOfferingVO> networkOfferingVoListMock;

    @Mock
    PhysicalNetworkDao physicalNetworkDaoMock;

    @Mock
    SecurityGroupVO securityGroupVoMock;

    @Mock
    DomainDao domainDaoMock;

    @Mock
    DomainVO domainVoMock;

    @Mock
    SnapshotVO snapshotVoMock;

    @Mock
    ServiceOfferingVO serviceOfferingVoMock;

    @Mock
    private ServiceOfferingVO serviceOffering;

    @Mock
    UserDataManager userDataManager;

    @Mock
    VirtualMachineProfile virtualMachineProfile;

    @Mock
    VirtualMachineTemplate templateMock;

    @Mock
    VnfTemplateManager vnfTemplateManager;

    @Mock
    ServiceOfferingJoinDao serviceOfferingJoinDao;

    @Mock
    SSHKeyPairDao sshKeyPairDao;

    @Mock
    private VMInstanceVO vmInstanceMock;

    @Mock
    StorageManager storageManager;

    @Mock
    private VolumeDataFactory volumeDataFactory;

    @Mock
    private VolumeInfo volumeInfo;

    @Mock
    private SnapshotVO snapshotMock;

    @Mock
    private PrimaryDataStore primaryDataStore;

    @Mock
    private Scope scopeMock;

    @Mock
    private AutoScaleManager autoScaleManager;

    @Mock
    private UUIDManager uuidMgr;


    @Mock
    private SnapshotPolicyDao snapshotPolicyDao;

    @Mock
    private BackupScheduleDao backupScheduleDao;

    @Mock
    ServiceOfferingDetailsDao serviceOfferingDetailsDao;

    @Mock
    VmRecoveryService vmRecoveryService;

    @Mock
    VmPasswordSSHKeyResetService vmPasswordSSHKeyResetService;

    @Mock
    VmStartOrchestrationService vmStartOrchestrationService;

    @Mock
    VmDeployStartService vmDeployStartService;

    @Mock
    VmRestoreService vmRestoreService;

    @Mock
    VmRootDiskOfferingChangeService vmRootDiskOfferingChangeService;

    @Mock
    VmServiceOfferingScaleService vmServiceOfferingScaleService;

    @Mock
    VmStorageMigrationService vmStorageMigrationService;

    @Mock
    VmMigrationDedicationService vmMigrationDedicationService;

    @Mock
    VmUnmanageService vmUnmanageService;

    @Mock
    VmUpdateOrchestrationService vmUpdateOrchestrationService;

    private static final long vmId = 1l;
    private static final long zoneId = 2L;
    private static final long accountId = 3L;
    private static final long nicId = 4L;
    private static final long networkId = 5L;
    private static final long serviceOfferingId = 10L;
    private static final long templateId = 11L;
    private static final long volumeId = 1L;
    private static final long snashotId = 1L;
    private static final String vmUuid = UUID.randomUUID().toString();

    private static final long GiB_TO_BYTES = 1024 * 1024 * 1024;

    private Map<String, String> customParameters = new HashMap<>();

    String[] detailsConstants = {VmDetailConstants.MEMORY, VmDetailConstants.CPU_NUMBER, VmDetailConstants.CPU_SPEED};

    private DiskOfferingVO smallerDisdkOffering = Mockito.mock(DiskOfferingVO.class);
    Class<InvalidParameterValueException> expectedInvalidParameterValueException = InvalidParameterValueException.class;
    Class<CloudRuntimeException> expectedCloudRuntimeException = CloudRuntimeException.class;

    private Map<ConfigKey, Object> originalConfigValues = new HashMap<>();


    private void updateDefaultConfigValue(final ConfigKey configKey, final Object o, boolean revert) {
        try {
            final String name = "_defaultValue";
            Field f = ConfigKey.class.getDeclaredField(name);
            f.setAccessible(true);
            String stringVal = String.valueOf(o);
            if (!revert) {
                originalConfigValues.put(configKey, f.get(configKey));
            }
            f.set(configKey, stringVal);
        } catch (IllegalAccessException | NoSuchFieldException  e) {
            Assert.fail("Failed to mock config " + configKey.key() + " value due to " + e.getMessage());
        }
    }

    @Before
    public void beforeTest() {
        userVmManagerImpl.resourceLimitService = resourceLimitMgr;
        // The serviceOfferingValidator field was added as part of the Phase 4
        // Spring-component decomposition. The tests below exercise validation
        // behavior through the manager's public methods, so wire up a real
        // ServiceOfferingValidatorImpl with the test's existing mocked DAOs.
        ServiceOfferingValidatorImpl validator = new ServiceOfferingValidatorImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(validator, "serviceOfferingDetailsDao", serviceOfferingDetailsDao);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "serviceOfferingValidator", validator);
        // Same wiring approach for the Phase 4 VmNicService extraction: build a real
        // VmNicServiceImpl backed by the test's mocked DAOs/managers so the 13 tests
        // that exercise validateOrReplaceMacAddress / updateVirtualMachineNic through
        // the manager continue to work.
        VmNicServiceImpl nicService = new VmNicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(nicService, "vmDao", userVmDao);
        org.springframework.test.util.ReflectionTestUtils.setField(nicService, "nicDao", nicDao);
        org.springframework.test.util.ReflectionTestUtils.setField(nicService, "networkDao", _networkDao);
        org.springframework.test.util.ReflectionTestUtils.setField(nicService, "networkModel", networkModel);
        org.springframework.test.util.ReflectionTestUtils.setField(nicService, "accountManager", accountManager);
        org.springframework.test.util.ReflectionTestUtils.setField(nicService, "itMgr", virtualMachineManager);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmNicService", nicService);
        // Slice 4: wire VmRootDiskValidatorImpl with the test's existing mocks
        VmRootDiskValidatorImpl rootDiskValidator = new VmRootDiskValidatorImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(rootDiskValidator, "volumeService", volumeApiService);
        org.springframework.test.util.ReflectionTestUtils.setField(rootDiskValidator, "templateDao", templateDao);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmRootDiskValidator", rootDiskValidator);
        // Slice 5: wire VmUpdateValidatorImpl with the test's existing mocks so the
        // update-VM input validation + service-offering detail-merging tests still
        // exercise the same logic through the manager's delegating wrappers.
        VmUpdateValidatorImpl updateValidator = new VmUpdateValidatorImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(updateValidator, "userVmDao", userVmDao);
        org.springframework.test.util.ReflectionTestUtils.setField(updateValidator, "guestOSDao", guestOSDao);
        org.springframework.test.util.ReflectionTestUtils.setField(updateValidator, "accountManager", accountManager);
        org.springframework.test.util.ReflectionTestUtils.setField(updateValidator, "serviceOfferingDao", _serviceOfferingDao);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmUpdateValidator", updateValidator);
        // Slice 6: wire VmLeaseServiceImpl with the existing vmInstanceDetailsDao mock.
        // Slice 6b: wire VmLeaseApplicationServiceImpl so the manager's lease
        // compatibility wrappers remain thin delegators.
        VmLeaseServiceImpl leaseService = new VmLeaseServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(leaseService, "vmInstanceDetailsDao", vmInstanceDetailsDao);
        VmLeaseApplicationServiceImpl leaseApplicationService = new VmLeaseApplicationServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(leaseApplicationService, "vmLeaseService", leaseService);
        org.springframework.test.util.ReflectionTestUtils.setField(leaseApplicationService, "vmInstanceDetailsDao", vmInstanceDetailsDao);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmLeaseApplicationService", leaseApplicationService);
        // Slice 7: wire VmAssignmentValidatorImpl with the existing assign-flow mocks
        // so the moveVmToUser orchestration tests and the per-helper tests still
        // exercise the same code paths through the manager's delegating wrappers.
        VmAssignmentValidatorImpl assignValidator = new VmAssignmentValidatorImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(assignValidator, "portForwardingDao", portForwardingRulesDaoMock);
        org.springframework.test.util.ReflectionTestUtils.setField(assignValidator, "rulesDao", firewallRulesDaoMock);
        org.springframework.test.util.ReflectionTestUtils.setField(assignValidator, "loadBalancerVMMapDao", loadBalancerVmMapDaoMock);
        org.springframework.test.util.ReflectionTestUtils.setField(assignValidator, "ipAddressDao", ipAddressDaoMock);
        org.springframework.test.util.ReflectionTestUtils.setField(assignValidator, "snapshotDao", snapshotDaoMock);
        org.springframework.test.util.ReflectionTestUtils.setField(assignValidator, "accountManager", accountManager);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmAssignmentValidator", assignValidator);
        // Slice 8: wire VmExtraConfigServiceImpl. The KVM/Xen/VMware extra-config
        // helpers all delegate here, while addExtraConfig orchestration stays on
        // the manager so existing spy stubs for persistExtraConfigKvm still fire.
        VmExtraConfigServiceImpl extraConfigService = new VmExtraConfigServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(extraConfigService, "vmInstanceDetailsDao", vmInstanceDetailsDao);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmExtraConfigService", extraConfigService);
        // Slice 9: wire VmMigrationValidatorImpl. The existing migration tests in
        // this class exercise validateStrictHostTagCheck (needs serviceOfferingDao
        // + templateDao) and validateStorageAccessGroupsOnHosts (needs storageManager);
        // the other validator methods are covered standalone in VmMigrationValidatorImplTest.
        VmMigrationValidatorImpl migrationValidator = new VmMigrationValidatorImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(migrationValidator, "hostDao", hostDao);
        org.springframework.test.util.ReflectionTestUtils.setField(migrationValidator, "storageManager", storageManager);
        org.springframework.test.util.ReflectionTestUtils.setField(migrationValidator, "serviceOfferingDao", _serviceOfferingDao);
        org.springframework.test.util.ReflectionTestUtils.setField(migrationValidator, "templateDao", templateDao);
        org.springframework.test.util.ReflectionTestUtils.setField(migrationValidator, "accountManager", accountManager);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmMigrationValidator", migrationValidator);
        // Slice 10: wire VmCreationValidatorImpl. createVirtualMachine tests reach
        // verifyServiceOffering/verifyTemplate/verifyDetails through the manager's
        // delegating wrappers, so the validator's deps need real mock backings.
        VmCreationValidatorImpl creationValidator = new VmCreationValidatorImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(creationValidator, "serviceOfferingJoinDao", serviceOfferingJoinDao);
        org.springframework.test.util.ReflectionTestUtils.setField(creationValidator, "vnfTemplateManager", vnfTemplateManager);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmCreationValidator", creationValidator);
        // Slice 11: wire VmDestroyPermissionServiceImpl. The destroy/expunge/force-stop
        // permission helpers in this class are exercised via the manager's wrappers,
        // so the per-helper tests in VmDestroyPermissionServiceImplTest cover the
        // direct branches; here we just need a working impl behind the wrapper for
        // orchestration tests that pass through destroyVm / stopVirtualMachine.
        vmDestroyPermissionServiceSpy = Mockito.spy(new VmDestroyPermissionServiceImpl());
        org.springframework.test.util.ReflectionTestUtils.setField(vmDestroyPermissionServiceSpy, "accountManager", accountManager);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmDestroyPermissionService", vmDestroyPermissionServiceSpy);
        // Slice 26: wire volume lifecycle validation/cleanup services so destroy,
        // migration, restore, and unmanage orchestration keep flowing through the
        // manager wrappers while the leaf behavior lives in focused service tests.
        VmVolumeLifecycleValidationServiceImpl volumeLifecycleValidationService = new VmVolumeLifecycleValidationServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(volumeLifecycleValidationService, "volumeDao", volumeDaoMock);
        org.springframework.test.util.ReflectionTestUtils.setField(volumeLifecycleValidationService, "snapshotDao", snapshotDaoMock);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmVolumeLifecycleValidationService", volumeLifecycleValidationService);
        VmVolumeDestroyCleanupServiceImpl volumeDestroyCleanupService = new VmVolumeDestroyCleanupServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(volumeDestroyCleanupService, "volumeService", volumeApiService);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmVolumeDestroyCleanupService", volumeDestroyCleanupService);
        // Slice 13: wire VmHostNameUniquenessServiceImpl so the verifyExtraDhcpOptionsNetwork /
        // checkIfHostNameUniqueInNtwkDomain wrappers don't NPE when updateVirtualMachine
        // tests pass through them. Per-branch behavior is covered by
        // VmHostNameUniquenessServiceImplTest; here we just need a non-null bean.
        VmHostNameUniquenessServiceImpl hostNameUniquenessService = new VmHostNameUniquenessServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmHostNameUniquenessService", hostNameUniquenessService);
        // Slice 31: wire start-placement lookup service so start/create VM host,
        // cluster, and pod validation uses the same test DAOs through the manager
        // wrappers. Branch behavior is covered in VmStartPlacementServiceImplTest.
        VmStartPlacementServiceImpl startPlacementService = new VmStartPlacementServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(startPlacementService, "hostPodDao", hostPodDao);
        org.springframework.test.util.ReflectionTestUtils.setField(startPlacementService, "clusterDao", clusterDao);
        org.springframework.test.util.ReflectionTestUtils.setField(startPlacementService, "hostDao", hostDao);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmStartPlacementService", startPlacementService);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmStartOrchestrationService", vmStartOrchestrationService);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmDeployStartService", vmDeployStartService);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl,
                "vmExpungeFailureTransitionService", vmExpungeFailureTransitionService);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl,
                "vmExpungeResourceCleanupService", vmExpungeResourceCleanupService);
        // Slice 14: wire VmSecurityGroupAssignmentServiceImpl so the
        // getSecurityGroupIdList / checkAndUpdateSecurityGroupForVM
        // wrappers don't NPE when updateVirtualMachine tests pass through
        // them. Per-branch behaviour is covered by
        // VmSecurityGroupAssignmentServiceImplTest; here we just need a
        // non-null bean.
        VmSecurityGroupAssignmentServiceImpl securityGroupAssignmentService = new VmSecurityGroupAssignmentServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(securityGroupAssignmentService, "securityGroupManager", securityGroupManagerMock);
        org.springframework.test.util.ReflectionTestUtils.setField(securityGroupAssignmentService, "vnfTemplateManager", vnfTemplateManager);
        org.springframework.test.util.ReflectionTestUtils.setField(securityGroupAssignmentService, "dataCenterDao", _dcDao);
        org.springframework.test.util.ReflectionTestUtils.setField(securityGroupAssignmentService, "networkModel", networkModel);
        org.springframework.test.util.ReflectionTestUtils.setField(securityGroupAssignmentService, "accountManager", accountManager);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmSecurityGroupAssignmentService", securityGroupAssignmentService);
        // Slice 15: wire VmCredentialResetServiceImpl so the finalizeUserData /
        // updateUserData / applyUserData / encryptAndStorePassword /
        // removeEncryptedPasswordFromUserVmVoDetails wrappers don't NPE.
        // The existing finalizeUserData / resetVMUserData tests in this class
        // rely on the same userDataDao / userDataManager / networkModel / nicDao
        // mocks that are already declared here, so we pass them through.
        VmCredentialResetServiceImpl credentialResetService = new VmCredentialResetServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(credentialResetService, "templateDao", templateDao);
        org.springframework.test.util.ReflectionTestUtils.setField(credentialResetService, "nicDao", nicDao);
        org.springframework.test.util.ReflectionTestUtils.setField(credentialResetService, "networkModel", networkModel);
        org.springframework.test.util.ReflectionTestUtils.setField(credentialResetService, "networkDao", _networkDao);
        org.springframework.test.util.ReflectionTestUtils.setField(credentialResetService, "userDataDao", userDataDao);
        org.springframework.test.util.ReflectionTestUtils.setField(credentialResetService, "userDataManager", userDataManager);
        org.springframework.test.util.ReflectionTestUtils.setField(credentialResetService, "userVmDao", userVmDao);
        org.springframework.test.util.ReflectionTestUtils.setField(credentialResetService, "vmInstanceDetailsDao", vmInstanceDetailsDao);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmCredentialResetService", credentialResetService);
        // Slice 21: wire VmRecoveryService mock so the delegating wrappers
        // recoverVirtualMachine / recoverRootVolume don't NPE. Per-branch
        // behaviour is covered by VmRecoveryServiceImplTest; the existing
        // recoverRootVolumeTestDestroyState test has been migrated there.
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmRecoveryService", vmRecoveryService);
        // Slice 22: wire VmPasswordSSHKeyResetService mock so the delegating
        // wrappers resetVMPassword / resetVMUserData / resetVMSSHKey /
        // resetVMPasswordInternal / resetVMSSHKeyInternal /
        // getCurrentVmPasswordOrDefineNewPassword don't NPE. Per-branch
        // behaviour is covered by VmPasswordSSHKeyResetServiceImplTest; the
        // migrated resetVMUserData / resetVMSSHKey / getCurrentVmPassword tests
        // have been removed from this class.
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmPasswordSSHKeyResetService", vmPasswordSSHKeyResetService);
        // Slice 23: wire VmRestoreService mock so restoreVirtualMachine /
        // restoreVMInternal / getRootVolumeSizeForVmRestore stay spy-compatible
        // through the manager wrappers while branch-heavy restore validation now
        // lives in VmRestoreServiceImplTest.
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmRestoreService", vmRestoreService);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl,
                "vmRootDiskOfferingChangeService", vmRootDiskOfferingChangeService);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl,
                "vmServiceOfferingScaleService", vmServiceOfferingScaleService);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl,
                "vmStorageMigrationService", vmStorageMigrationService);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl,
                "vmBackupInstanceLifecycleService", vmBackupInstanceLifecycleService);
        // Slice 38: wire VmAssignmentOwnershipServiceImpl so AssignVM owner
        // mutation wrappers stay spy-compatible while the leaf behavior lives in
        // VmAssignmentOwnershipServiceImplTest.
        VmAssignmentOwnershipServiceImpl assignmentOwnershipService = new VmAssignmentOwnershipServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(assignmentOwnershipService, "vmDao", userVmDao);
        org.springframework.test.util.ReflectionTestUtils.setField(assignmentOwnershipService, "volumeDao", volumeDaoMock);
        org.springframework.test.util.ReflectionTestUtils.setField(assignmentOwnershipService, "diskOfferingDao", diskOfferingDao);
        org.springframework.test.util.ReflectionTestUtils.setField(assignmentOwnershipService, "resourceLimitService", resourceLimitMgr);
        org.springframework.test.util.ReflectionTestUtils.setField(assignmentOwnershipService, "snapshotPolicyDao", snapshotPolicyDao);
        org.springframework.test.util.ReflectionTestUtils.setField(assignmentOwnershipService, "backupScheduleDao", backupScheduleDao);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl, "vmAssignmentOwnershipService", assignmentOwnershipService);
        // Slice 30: wire deploy-as-is OVF network mapping service so deploy paths
        // keep flowing through the manager wrapper. Branch behavior is covered in
        // VmDeployAsIsNetworkMappingServiceImplTest.
        VmDeployAsIsNetworkMappingServiceImpl deployAsIsNetworkMappingService = new VmDeployAsIsNetworkMappingServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(deployAsIsNetworkMappingService,
                "templateDeployAsIsDetailsDao", mock(com.cloud.deployasis.dao.TemplateDeployAsIsDetailsDao.class));
        org.springframework.test.util.ReflectionTestUtils.setField(deployAsIsNetworkMappingService, "networkModel", networkModel);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl,
                "vmDeployAsIsNetworkMappingService", deployAsIsNetworkMappingService);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl,
                "vmMigrationDedicationService", vmMigrationDedicationService);
        VmLiveMigrationOrchestrationServiceImpl liveMigrationOrchestrationService = new VmLiveMigrationOrchestrationServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "accountMgr", accountManager);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "capacityMgr", mock(com.cloud.capacity.CapacityManager.class));
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "clusterDao", clusterDao);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "dcDao", _dcDao);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "planningMgr", planningManager);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "diskOfferingDao", diskOfferingDao);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "dpdkHelper", dpdkHelper);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "hostDao", hostDao);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "podDao", hostPodDao);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "hypervisorCapabilitiesDao", hypervisorCapabilitiesDao);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "storagePoolDao", primaryDataStoreDao);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "resourceMgr", resourceManager);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "serviceOfferingDao", _serviceOfferingDao);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "serviceOfferingDetailsDao", serviceOfferingDetailsDao);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "snapshotHelper", snapshotHelper);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "storageManager", storageManager);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "itMgr", virtualMachineManager);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "vmMigrationDedicationService", vmMigrationDedicationService);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "vmMigrationValidator", migrationValidator);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "vmStatsCollectionService", vmStatsCollectionService);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "vmVolumeLifecycleValidationService", volumeLifecycleValidationService);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "vmSnapshotDao", vmSnapshotDaoMock);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "vmDao", userVmDao);
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "vmInstanceDao", mock(com.cloud.vm.dao.VMInstanceDao.class));
        org.springframework.test.util.ReflectionTestUtils.setField(liveMigrationOrchestrationService, "volsDao", volumeDaoMock);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl,
                "vmLiveMigrationOrchestrationService", liveMigrationOrchestrationService);
        org.springframework.test.util.ReflectionTestUtils.setField(userVmManagerImpl,
                "vmUnmanageService", vmUnmanageService);

        Mockito.lenient().when(updateVmCommand.getId()).thenReturn(vmId);

        lenient().when(_dcDao.findById(anyLong())).thenReturn(_dcMock);

        Mockito.when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);

        Mockito.when(callerAccount.getType()).thenReturn(Account.Type.ADMIN);
        CallContext.register(callerUser, callerAccount);

        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, "123");
        customParameters.put(VmDetailConstants.MEMORY, "2048");
        customParameters.put(VmDetailConstants.CPU_NUMBER, "4");
        customParameters.put(VmDetailConstants.CPU_SPEED, "1000");

        lenient().doNothing().when(resourceLimitMgr).incrementResourceCount(anyLong(), any(Resource.ResourceType.class));
        lenient().doNothing().when(resourceLimitMgr).decrementResourceCount(anyLong(), any(Resource.ResourceType.class), anyLong());

        Mockito.when(virtualMachineProfile.getId()).thenReturn(vmId);
    }

    @After
    public void afterTest() {
        CallContext.unregister();
        for (Map.Entry<ConfigKey, Object> entry : originalConfigValues.entrySet()) {
            updateDefaultConfigValue(entry.getKey(), entry.getValue(), true);
        }
    }

    @Test
    public void startVirtualMachineHostOverloadDelegatesToStartOrchestrationService()
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
        Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> expected = new Pair<>(userVmVoMock, params);
        Long hostId = 44L;
        when(vmStartOrchestrationService.startVirtualMachine(vmId, hostId, params, "planner")).thenReturn(expected);

        Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> result =
                userVmManagerImpl.startVirtualMachine(vmId, hostId, params, "planner");

        assertSame(expected, result);
        verify(vmStartOrchestrationService).startVirtualMachine(vmId, hostId, params, "planner");
    }

    @Test
    public void startVirtualMachinePlacementOverloadDelegatesToStartOrchestrationService()
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
        Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> expected = new Pair<>(userVmVoMock, params);
        Long podId = 11L;
        Long clusterId = 22L;
        Long hostId = 33L;
        when(vmStartOrchestrationService.startVirtualMachine(vmId, podId, clusterId, hostId, params, "planner")).thenReturn(expected);

        Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> result =
                userVmManagerImpl.startVirtualMachine(vmId, podId, clusterId, hostId, params, "planner");

        assertSame(expected, result);
        verify(vmStartOrchestrationService).startVirtualMachine(vmId, podId, clusterId, hostId, params, "planner");
    }

    @Test
    public void startVirtualMachineExplicitHostOverloadDelegatesToStartOrchestrationService()
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
        Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> expected = new Pair<>(userVmVoMock, params);
        Long podId = 11L;
        Long clusterId = 22L;
        Long hostId = 33L;
        when(vmStartOrchestrationService.startVirtualMachine(vmId, podId, clusterId, hostId, params, "planner", false)).thenReturn(expected);

        Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> result =
                userVmManagerImpl.startVirtualMachine(vmId, podId, clusterId, hostId, params, "planner", false);

        assertSame(expected, result);
        verify(vmStartOrchestrationService).startVirtualMachine(vmId, podId, clusterId, hostId, params, "planner", false);
    }

    @Test
    public void startVirtualMachineDeployCommandDelegatesToDeployStartService()
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        UserVm expected = mock(UserVm.class);
        when(vmDeployStartService.startVirtualMachine(eq(cmd), any(VmDeployStartService.ManagerOperations.class))).thenReturn(expected);

        UserVm result = userVmManagerImpl.startVirtualMachine(cmd);

        assertSame(expected, result);
        verify(vmDeployStartService).startVirtualMachine(eq(cmd), any(VmDeployStartService.ManagerOperations.class));
    }

    @Test
    public void deployStartOverloadDelegatesToDeployStartService()
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        Map<Long, DiskOffering> diskOfferingMap = new HashMap<>();
        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
        UserVm expected = mock(UserVm.class);
        Long podId = 11L;
        Long clusterId = 22L;
        Long hostId = 33L;
        when(vmDeployStartService.startVirtualMachine(eq(vmId), eq(podId), eq(clusterId), eq(hostId), eq(diskOfferingMap), eq(params), eq("planner"),
                any(VmDeployStartService.ManagerOperations.class))).thenReturn(expected);

        UserVm result = userVmManagerImpl.startVirtualMachine(vmId, podId, clusterId, hostId, diskOfferingMap, params, "planner");

        assertSame(expected, result);
        verify(vmDeployStartService).startVirtualMachine(eq(vmId), eq(podId), eq(clusterId), eq(hostId), eq(diskOfferingMap), eq(params), eq("planner"),
                any(VmDeployStartService.ManagerOperations.class));
    }

    @Test
    public void addVmUefiBootOptionsToParamsDelegatesToDeployStartService() {
        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();

        userVmManagerImpl.addVmUefiBootOptionsToParams(params, "UEFI", "SECURE");

        verify(vmDeployStartService).addVmUefiBootOptionsToParams(params, "UEFI", "SECURE");
    }

    @Test
    public void upgradeVirtualMachineUpgradeCmdDelegatesToScaleService() throws ResourceAllocationException {
        UpgradeVMCmd cmd = mock(UpgradeVMCmd.class);
        UserVm expected = mock(UserVm.class);
        when(vmServiceOfferingScaleService.upgradeVirtualMachine(cmd)).thenReturn(expected);

        UserVm result = userVmManagerImpl.upgradeVirtualMachine(cmd);

        Assert.assertSame(expected, result);
        verify(vmServiceOfferingScaleService).upgradeVirtualMachine(cmd);
    }

    @Test
    public void upgradeVirtualMachineScaleCmdDelegatesToScaleService() throws ResourceUnavailableException,
            ConcurrentOperationException, ManagementServerException, VirtualMachineMigrationException {
        ScaleVMCmd cmd = mock(ScaleVMCmd.class);
        UserVm expected = mock(UserVm.class);
        when(vmServiceOfferingScaleService.upgradeVirtualMachine(cmd)).thenReturn(expected);

        UserVm result = userVmManagerImpl.upgradeVirtualMachine(cmd);

        Assert.assertSame(expected, result);
        verify(vmServiceOfferingScaleService).upgradeVirtualMachine(cmd);
    }

    @Test
    public void upgradeVirtualMachineByIdsDelegatesToScaleService() throws ResourceUnavailableException,
            ConcurrentOperationException, ManagementServerException, VirtualMachineMigrationException {
        when(vmServiceOfferingScaleService.upgradeVirtualMachine(vmId, serviceOfferingId, customParameters)).thenReturn(true);

        boolean result = userVmManagerImpl.upgradeVirtualMachine(vmId, serviceOfferingId, customParameters);

        Assert.assertTrue(result);
        verify(vmServiceOfferingScaleService).upgradeVirtualMachine(vmId, serviceOfferingId, customParameters);
    }

    @Test
    public void vmStorageMigrationWithDestinationPoolDelegatesToVmStorageMigrationService() {
        StoragePool destPool = mock(StoragePool.class);
        VirtualMachine expectedVm = mock(VirtualMachine.class);
        when(vmStorageMigrationService.vmStorageMigration(vmId, destPool)).thenReturn(expectedVm);

        VirtualMachine result = userVmManagerImpl.vmStorageMigration(vmId, destPool);

        assertEquals(expectedVm, result);
        verify(vmStorageMigrationService).vmStorageMigration(vmId, destPool);
    }

    @Test
    public void vmStorageMigrationWithVolumePoolMapDelegatesToVmStorageMigrationService() {
        Map<String, String> volumeToPool = new HashMap<>();
        volumeToPool.put("volume-uuid", "pool-uuid");
        VirtualMachine expectedVm = mock(VirtualMachine.class);
        when(vmStorageMigrationService.vmStorageMigration(vmId, volumeToPool)).thenReturn(expectedVm);

        VirtualMachine result = userVmManagerImpl.vmStorageMigration(vmId, volumeToPool);

        assertEquals(expectedVm, result);
        verify(vmStorageMigrationService).vmStorageMigration(vmId, volumeToPool);
    }

    @Test
    public void validateGuestOsIdForUpdateVirtualMachineCommandTestOsTypeNull() {
        Mockito.when(updateVmCommand.getOsTypeId()).thenReturn(null);
        userVmManagerImpl.validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateGuestOsIdForUpdateVirtualMachineCommandTestOsTypeNotFound() {
        Mockito.when(updateVmCommand.getOsTypeId()).thenReturn(1l);

        userVmManagerImpl.validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
    }

    @Test
    public void validateGuestOsIdForUpdateVirtualMachineCommandTestOsTypeFound() {
        Mockito.when(updateVmCommand.getOsTypeId()).thenReturn(1l);
        Mockito.when(guestOSDao.findById(1l)).thenReturn(Mockito.mock(GuestOSVO.class));

        userVmManagerImpl.validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateInputsAndPermissionForUpdateVirtualMachineCommandTestVmNotFound() {
        Mockito.doThrow(new InvalidParameterValueException("unable to find virtual machine with id: " + vmId))
                .when(vmUpdateOrchestrationService).validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);

        userVmManagerImpl.validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);
    }

    private ServiceOfferingVO getSvcoffering(int ramSize) {
        String name = "name";
        String displayText = "displayText";
        int cpu = 1;
        int speed = 128;

        boolean ha = false;
        boolean useLocalStorage = false;

        ServiceOfferingVO serviceOffering = new ServiceOfferingVO(name, cpu, ramSize, speed, null, null, ha, displayText, false, null,
                false);
        serviceOffering.setDiskOfferingId(1l);
        return serviceOffering;
    }

    @Test
    public void validateInputsAndPermissionForUpdateVirtualMachineCommandTest() {
        userVmManagerImpl.validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);

        verify(vmUpdateOrchestrationService).validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);
    }

    @Test
    public void updateVirtualMachineCommandDelegatesToUpdateOrchestrationService() throws ResourceUnavailableException, InsufficientCapacityException {
        UserVm expected = mock(UserVm.class);
        when(vmUpdateOrchestrationService.updateVirtualMachine(updateVmCommand)).thenReturn(expected);

        UserVm result = userVmManagerImpl.updateVirtualMachine(updateVmCommand);

        assertSame(expected, result);
        verify(vmUpdateOrchestrationService).updateVirtualMachine(updateVmCommand);
    }

    @Test
    public void updateVirtualMachineByIdDelegatesToUpdateOrchestrationService() throws ResourceUnavailableException, InsufficientCapacityException {
        UserVm expected = mock(UserVm.class);
        List<Long> securityGroupIds = List.of(1L, 2L);
        Map<String, Map<Integer, String>> dhcpOptions = new HashMap<>();
        when(vmUpdateOrchestrationService.updateVirtualMachine(vmId, "display", "group", true, false, true,
                2L, "userdata", 3L, "userdata-details", true, HTTPMethod.POST, "custom", "host",
                "instance", securityGroupIds, dhcpOptions)).thenReturn(expected);

        UserVm result = userVmManagerImpl.updateVirtualMachine(vmId, "display", "group", true, false, true,
                2L, "userdata", 3L, "userdata-details", true, HTTPMethod.POST, "custom", "host",
                "instance", securityGroupIds, dhcpOptions);

        assertSame(expected, result);
        verify(vmUpdateOrchestrationService).updateVirtualMachine(vmId, "display", "group", true, false, true,
                2L, "userdata", 3L, "userdata-details", true, HTTPMethod.POST, "custom", "host",
                "instance", securityGroupIds, dhcpOptions);
    }

    @Test
    public void verifyVmLimitsDelegatesToUpdateOrchestrationService() {
        Map<String, String> details = new HashMap<>();

        userVmManagerImpl.verifyVmLimits(userVmVoMock, details);

        verify(vmUpdateOrchestrationService).verifyVmLimits(userVmVoMock, details);
    }

    @Test
    public void updateDisplayVmFlagDelegatesToUpdateOrchestrationService() {
        userVmManagerImpl.updateDisplayVmFlag(false, vmId, userVmVoMock);

        verify(vmUpdateOrchestrationService).updateDisplayVmFlag(false, vmId, userVmVoMock);
    }

    @Test
    public void updateUserDataDelegatesToUpdateOrchestrationService() throws ResourceUnavailableException, InsufficientCapacityException {
        userVmManagerImpl.updateUserData(userVmVoMock);

        verify(vmUpdateOrchestrationService).updateUserData(userVmVoMock);
    }

    @SuppressWarnings("unchecked")
    private void configureDoNothingForMethodsThatWeDoNotWantToTest() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        Mockito.lenient().doNothing().when(userVmManagerImpl).validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);
        Mockito.lenient().doReturn(new ArrayList<Long>()).when(userVmManagerImpl).getSecurityGroupIdList(updateVmCommand);

        Mockito.lenient().doReturn(Mockito.mock(UserVm.class)).when(userVmManagerImpl).updateVirtualMachine(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(),
                Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any(HTTPMethod.class), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), anyList(), Mockito.any());

        Mockito.doNothing().when(userVmManagerImpl).validateIfVmSupportsMigration(Mockito.any(), Mockito.anyLong());
        Mockito.doNothing().when(userVmManagerImpl).validateOldAndNewAccounts(Mockito.nullable(Account.class), Mockito.nullable(Account.class), Mockito.anyLong(), Mockito.nullable(String.class), Mockito.nullable(Long.class));
        Mockito.doNothing().when(userVmManagerImpl).validateIfVmHasNoRules(Mockito.any(), Mockito.anyLong());
        Mockito.doNothing().when(userVmManagerImpl).removeInstanceFromInstanceGroup(Mockito.anyLong());
        Mockito.doNothing().when(userVmManagerImpl).validateIfNewOwnerHasAccessToTemplate(Mockito.any(), Mockito.any(), Mockito.any());

        Mockito.doNothing().when(userVmManagerImpl).updateVmOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doNothing().when(userVmManagerImpl).updateVolumesOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doNothing().when(userVmManagerImpl).updateVmNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        Mockito.doNothing().when(userVmManagerImpl).resourceCountIncrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressValid() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(0, "01:23:45:67:89:ab", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNull() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, null, "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressBlank() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, " ", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressEmpty() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption1() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "abcdef:gh:ij:kl", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption2() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "01:23:45:67:89:", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption3() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "01:23:45:67:89:az", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption4() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "@1:23:45:67:89:ab", "01:23:45:67:89:ab");
    }

    private void configureValidateOrReplaceMacAddressTest(int times, String macAddress, String expectedMacAddress) throws InsufficientAddressCapacityException {
        Mockito.when(networkModel.getNextAvailableMacAddressInNetwork(Mockito.anyLong())).thenReturn(expectedMacAddress);

        String returnedMacAddress = userVmManagerImpl.validateOrReplaceMacAddress(macAddress, networkMock);

        Mockito.verify(networkModel, times(times)).getNextAvailableMacAddressInNetwork(Mockito.anyLong());
        assertEquals(expectedMacAddress, returnedMacAddress);
    }

    @Test
    public void testValidatekeyValuePair() throws Exception {
        assertTrue(userVmManagerImpl.isValidKeyValuePair("is-a-template=true\nHVM-boot-policy=\nPV-bootloader=pygrub\nPV-args=hvc0"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("is-a-template=true HVM-boot-policy= PV-bootloader=pygrub PV-args=hvc0"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("nvp.vm-uuid=34b3d5ea-1c25-4bb0-9250-8dc3388bfa9b"));
        assertFalse(userVmManagerImpl.isValidKeyValuePair("key"));
        //key-1=value1, param:key-2=value2, my.config.v0=False"
        assertTrue(userVmManagerImpl.isValidKeyValuePair("key-1=value1"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("param:key-2=value2"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("my.config.v0=False"));
    }

    @Test
    public void configureCustomRootDiskSizeTest() {
        String vmDetailsRootDiskSize = "123";
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, vmDetailsRootDiskSize);
        long expectedRootDiskSize = 123l * GiB_TO_BYTES;
        long offeringRootDiskSize = 0l;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void configureCustomRootDiskSizeTestExpectExceptionZero() {
        String vmDetailsRootDiskSize = "0";
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, vmDetailsRootDiskSize);
        long expectedRootDiskSize = 0l;
        long offeringRootDiskSize = 0l;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void configureCustomRootDiskSizeTestExpectExceptionNegativeNum() {
        String vmDetailsRootDiskSize = "-123";
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, vmDetailsRootDiskSize);
        long expectedRootDiskSize = -123l * GiB_TO_BYTES;
        long offeringRootDiskSize = 0l;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test
    public void configureCustomRootDiskSizeTestEmptyParameters() {
        Map<String, String> customParameters = new HashMap<>();
        long expectedRootDiskSize = 99l * GiB_TO_BYTES;
        long offeringRootDiskSize = 0l;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test
    public void configureCustomRootDiskSizeTestEmptyParametersAndOfferingRootSize() {
        Map<String, String> customParameters = new HashMap<>();
        long expectedRootDiskSize = 10l * GiB_TO_BYTES;
        long offeringRootDiskSize = 10l * GiB_TO_BYTES;

        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    private void prepareAndRunConfigureCustomRootDiskSizeTest(Map<String, String> customParameters, long expectedRootDiskSize, int timesVerifyIfHypervisorSupports, Long offeringRootDiskSize) {
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getId()).thenReturn(1l);
        Mockito.when(template.getSize()).thenReturn(99L * GiB_TO_BYTES);
        Mockito.when(templateDao.findById(Mockito.anyLong())).thenReturn(template);

        DiskOfferingVO diskfferingVo = Mockito.mock(DiskOfferingVO.class);

        Mockito.when(diskfferingVo.getDiskSize()).thenReturn(offeringRootDiskSize);

        Mockito.when(volumeApiService.validateVolumeSizeInBytes(Mockito.anyLong())).thenReturn(true);
        long rootDiskSize = userVmManagerImpl.configureCustomRootDiskSize(customParameters, template, Hypervisor.HypervisorType.KVM, diskfferingVo);

        Assert.assertEquals(expectedRootDiskSize, rootDiskSize);
        Mockito.verify(userVmManagerImpl, times(timesVerifyIfHypervisorSupports)).verifyIfHypervisorSupportsRootdiskSizeOverride(Mockito.any());
    }

    @Test
    public void verifyIfHypervisorSupportRootdiskSizeOverrideTest() {
        Hypervisor.HypervisorType[] hypervisorTypeArray = Hypervisor.HypervisorType.values();
        int exceptionCounter = 0;
        int expectedExceptionCounter = hypervisorTypeArray.length - 6;

        for(int i = 0; i < hypervisorTypeArray.length; i++) {
            if (hypervisorTypeArray[i].isFunctionalitySupported(Hypervisor.HypervisorType.Functionality.RootDiskSizeOverride)) {
                userVmManagerImpl.verifyIfHypervisorSupportsRootdiskSizeOverride(hypervisorTypeArray[i]);
            } else {
                try {
                    userVmManagerImpl.verifyIfHypervisorSupportsRootdiskSizeOverride(hypervisorTypeArray[i]);
                } catch (InvalidParameterValueException e) {
                    exceptionCounter ++;
                }
            }
        }

        Assert.assertEquals(expectedExceptionCounter, exceptionCounter);
    }

    @Test
    public void prepareResizeVolumeCmdDelegatesToVmRootDiskOfferingChangeService() {
        VolumeVO rootVolume = Mockito.mock(VolumeVO.class);
        DiskOfferingVO currentOffering = Mockito.mock(DiskOfferingVO.class);
        DiskOfferingVO newOffering = Mockito.mock(DiskOfferingVO.class);
        ResizeVolumeCmd expected = Mockito.mock(ResizeVolumeCmd.class);
        Mockito.when(vmRootDiskOfferingChangeService.prepareResizeVolumeCmd(rootVolume, currentOffering, newOffering))
                .thenReturn(expected);

        ResizeVolumeCmd result = userVmManagerImpl.prepareResizeVolumeCmd(rootVolume, currentOffering, newOffering);

        Assert.assertSame(expected, result);
        Mockito.verify(vmRootDiskOfferingChangeService).prepareResizeVolumeCmd(rootVolume, currentOffering, newOffering);
    }

    @Test (expected = CloudRuntimeException.class)
    public void testUserDataDenyOverride() {
        Long userDataId = 1L;

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(2L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.DENYOVERRIDE);

        userVmManagerImpl.finalizeUserData(null, userDataId, template);
    }

    @Test
    public void testUserDataAllowOverride() {
        String templateUserData = "testTemplateUserdata";
        Long userDataId = 1L;

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(2L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.ALLOWOVERRIDE);

        UserDataVO apiUserDataVO = Mockito.mock(UserDataVO.class);
        doReturn(apiUserDataVO).when(userDataDao).findById(userDataId);
        when(apiUserDataVO.getUserData()).thenReturn(templateUserData);

        String finalUserdata = userVmManagerImpl.finalizeUserData(null, userDataId, template);

        Assert.assertEquals(finalUserdata, templateUserData);
    }

    @Test
    public void testUserDataWithoutTemplate() {
        String userData = "testUserdata";
        Long userDataId = 1L;

        UserDataVO apiUserDataVO = Mockito.mock(UserDataVO.class);
        doReturn(apiUserDataVO).when(userDataDao).findById(userDataId);
        when(apiUserDataVO.getUserData()).thenReturn(userData);

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(null);

        String finalUserdata = userVmManagerImpl.finalizeUserData(null, userDataId, template);

        Assert.assertEquals(finalUserdata, userData);
    }

    @Test
    public void testUserDataAllowOverrideWithoutAPIuserdata() {
        String templateUserData = "testTemplateUserdata";

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(2L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.ALLOWOVERRIDE);
        UserDataVO templateUserDataVO = Mockito.mock(UserDataVO.class);
        doReturn(templateUserDataVO).when(userDataDao).findById(2L);
        when(templateUserDataVO.getUserData()).thenReturn(templateUserData);

        String finalUserdata = userVmManagerImpl.finalizeUserData(null, null, template);

        Assert.assertEquals(finalUserdata, templateUserData);
    }

    @Test
    public void testUserDataAllowOverrideWithUserdataText() {
        String userData = "testUserdata";
        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(null);

        String finalUserdata = userVmManagerImpl.finalizeUserData(userData, null, template);

        Assert.assertEquals(finalUserdata, userData);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void createVirtualMachineWithInactiveServiceOffering() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = new DeployVMCmd();
        ReflectionTestUtils.setField(deployVMCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(deployVMCmd, "serviceOfferingId", serviceOfferingId);
        deployVMCmd._accountService = accountService;

        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(serviceOffering.getState()).thenReturn(ServiceOffering.State.Inactive);

        userVmManagerImpl.createVirtualMachine(deployVMCmd);
    }

    @Test
    public void createVirtualMachine() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = new DeployVMCmd();
        ReflectionTestUtils.setField(deployVMCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(deployVMCmd, "templateId", templateId);
        ReflectionTestUtils.setField(deployVMCmd, "serviceOfferingId", serviceOfferingId);
        deployVMCmd._accountService = accountService;

        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(serviceOffering.getState()).thenReturn(ServiceOffering.State.Active);

        when(entityManager.findById(VirtualMachineTemplate.class, templateId)).thenReturn(templateMock);
        when(templateMock.getTemplateType()).thenReturn(Storage.TemplateType.VNF);
        when(templateMock.isDeployAsIs()).thenReturn(false);
        when(templateMock.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateMock.getUserDataId()).thenReturn(null);
        Mockito.doNothing().when(vnfTemplateManager).validateVnfApplianceNics(any(), nullable(List.class), nullable(Map.class));

        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        when(serviceOfferingJoinDao.findById(anyLong())).thenReturn(svcOfferingMock);
        when(_dcMock.isLocalStorageEnabled()).thenReturn(true);
        when(_dcMock.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any());

        UserVm result = userVmManagerImpl.createVirtualMachine(deployVMCmd);
        assertEquals(userVmVoMock, result);
        Mockito.verify(vnfTemplateManager).validateVnfApplianceNics(templateMock, null, Collections.emptyMap());
        Mockito.verify(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any());
    }

    private List<VolumeVO> mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(int localVolumes, int nonLocalVolumes) {
        List<VolumeVO> volumes = new ArrayList<>();
        for (int i=0; i< localVolumes + nonLocalVolumes; ++i) {
            VolumeVO vol = Mockito.mock(VolumeVO.class);
            long index = i + 1;
            Mockito.when(vol.getDiskOfferingId()).thenReturn(index);
            Mockito.when(vol.getPoolId()).thenReturn(index);
            DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
            Mockito.when(diskOfferingDao.findById(index)).thenReturn(diskOffering);
            StoragePoolVO storagePool = Mockito.mock(StoragePoolVO.class);
            Mockito.when(primaryDataStoreDao.findById(index)).thenReturn(storagePool);
            if (i < localVolumes) {
                if ((localVolumes + nonLocalVolumes) % 2 == 0) {
                    Mockito.when(diskOffering.isUseLocalStorage()).thenReturn(true);
                } else {

                    Mockito.when(diskOffering.isUseLocalStorage()).thenReturn(false);
                    Mockito.when(storagePool.isLocal()).thenReturn(true);
                }
            } else {
                Mockito.when(diskOffering.isUseLocalStorage()).thenReturn(false);
                Mockito.when(storagePool.isLocal()).thenReturn(false);
            }
            volumes.add(vol);
        }
        return volumes;
    }

    @Test
    public void testIsAnyVmVolumeUsingLocalStorage() {
        try {
            Assert.assertTrue(userVmManagerImpl.isAnyVmVolumeUsingLocalStorage(mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(1, 0)));
            Assert.assertTrue(userVmManagerImpl.isAnyVmVolumeUsingLocalStorage(mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(2, 0)));
            Assert.assertTrue(userVmManagerImpl.isAnyVmVolumeUsingLocalStorage(mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(1, 1)));
            Assert.assertFalse(userVmManagerImpl.isAnyVmVolumeUsingLocalStorage(mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(0, 2)));
            Assert.assertFalse(userVmManagerImpl.isAnyVmVolumeUsingLocalStorage(mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(0, 0)));
        }catch (NullPointerException npe) {
            npe.printStackTrace();
        }
    }

    private List<VolumeVO> mockVolumesForIsAllVmVolumesOnZoneWideStore(int nullPoolIdVolumes, int nullPoolVolumes, int zoneVolumes, int nonZoneVolumes) {
        List<VolumeVO> volumes = new ArrayList<>();
        for (int i=0; i< nullPoolIdVolumes + nullPoolVolumes + zoneVolumes + nonZoneVolumes; ++i) {
            VolumeVO vol = Mockito.mock(VolumeVO.class);
            volumes.add(vol);
            if (i < nullPoolIdVolumes) {
                Mockito.when(vol.getPoolId()).thenReturn(null);
                continue;
            }
            long index = i + 1;
            Mockito.when(vol.getPoolId()).thenReturn(index);
            if (i < nullPoolVolumes) {
                Mockito.when(primaryDataStoreDao.findById(index)).thenReturn(null);
                continue;
            }
            StoragePoolVO storagePool = Mockito.mock(StoragePoolVO.class);
            Mockito.when(primaryDataStoreDao.findById(index)).thenReturn(storagePool);
            if (i < zoneVolumes) {
                Mockito.when(storagePool.getScope()).thenReturn(ScopeType.ZONE);
            } else {
                Mockito.when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
            }
        }
        return volumes;
    }

    @Test
    public void testIsAllVmVolumesOnZoneWideStoreCombinations() {
        Assert.assertTrue(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(0, 0, 1, 0)));
        Assert.assertTrue(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(0, 0, 2, 0)));
        Assert.assertFalse(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(0, 0, 1, 1)));
        Assert.assertFalse(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(0, 0, 0, 0)));
        Assert.assertFalse(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(1, 0, 1, 1)));
        Assert.assertFalse(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(0, 1, 1, 1)));
    }

    private Pair<VMInstanceVO, Host> mockObjectsForChooseVmMigrationDestinationUsingVolumePoolMapTest(boolean nullPlan, Host destinationHost) {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(1L);
        Mockito.when(vm.getServiceOfferingId()).thenReturn(1L);
        Host host = Mockito.mock(Host.class);
        Mockito.when(host.getId()).thenReturn(1L);
        Mockito.when(hostDao.findById(1L)).thenReturn(Mockito.mock(HostVO.class));
        Mockito.when(virtualMachineManager.getMigrationDeployment(Mockito.any(VirtualMachine.class),
                        Mockito.any(Host.class), Mockito.nullable(Long.class),
                        Mockito.any(DeploymentPlanner.ExcludeList.class)))
                .thenReturn(Mockito.mock(DataCenterDeployment.class));
        if (!nullPlan) {
            try {
                DeployDestination destination = Mockito.mock(DeployDestination.class);
                Mockito.when(destination.getHost()).thenReturn(destinationHost);
                Mockito.when(planningManager.planDeployment(Mockito.any(VirtualMachineProfile.class),
                                Mockito.any(DataCenterDeployment.class), Mockito.any(DeploymentPlanner.ExcludeList.class),
                                Mockito.nullable(DeploymentPlanner.class)))
                        .thenReturn(destination);
            } catch (InsufficientServerCapacityException e) {
                fail("Failed to mock DeployDestination");
            }
        }
        return new Pair<>(vm, host);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testChooseVmMigrationDestinationUsingVolumePoolMapNullDestination() {
        Pair<VMInstanceVO, Host> pair = mockObjectsForChooseVmMigrationDestinationUsingVolumePoolMapTest(true, null);
        userVmManagerImpl.chooseVmMigrationDestinationUsingVolumePoolMap(pair.first(), pair.second(), null);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testChooseVmMigrationDestinationUsingVolumePoolMapNullHost() {
        Pair<VMInstanceVO, Host> pair = mockObjectsForChooseVmMigrationDestinationUsingVolumePoolMapTest(false, null);
        userVmManagerImpl.chooseVmMigrationDestinationUsingVolumePoolMap(pair.first(), pair.second(), null);
    }

    @Test
    public void testChooseVmMigrationDestinationUsingVolumePoolMapValid() {
        Host destinationHost = Mockito.mock(Host.class);
        Pair<VMInstanceVO, Host> pair = mockObjectsForChooseVmMigrationDestinationUsingVolumePoolMapTest(false, destinationHost);
        Assert.assertEquals(destinationHost, userVmManagerImpl.chooseVmMigrationDestinationUsingVolumePoolMap(pair.first(), pair.second(), null));
    }

    @Test
    public void testUpdateVncPasswordIfItHasChanged() {
        String vncPassword = "12345678";
        userVmManagerImpl.updateVncPasswordIfItHasChanged(vncPassword, vncPassword, virtualMachineProfile);
        Mockito.verify(userVmDao, Mockito.never()).update(vmId, userVmVoMock);
    }

    @Test
    public void testUpdateVncPasswordIfItHasChangedNewPassword() {
        String vncPassword = "12345678";
        String newPassword = "87654321";
        Mockito.when(userVmVoMock.getId()).thenReturn(vmId);
        userVmManagerImpl.updateVncPasswordIfItHasChanged(vncPassword, newPassword, virtualMachineProfile);
        Mockito.verify(userVmDao).findById(vmId);
        Mockito.verify(userVmDao).update(vmId, userVmVoMock);
    }

    @Test
    public void testGetSecurityGroupIdList() {
        DeployVnfApplianceCmd cmd = Mockito.mock(DeployVnfApplianceCmd.class);
        Mockito.doReturn(new ArrayList<Long>()).when(userVmManagerImpl).getSecurityGroupIdList(cmd);
        SecurityGroupVO securityGroupVO = Mockito.mock(SecurityGroupVO.class);
        long securityGroupId = 100L;
        when(securityGroupVO.getId()).thenReturn(securityGroupId);
        Mockito.doReturn(securityGroupVO).when(vnfTemplateManager).createSecurityGroupForVnfAppliance(any(), any(), any(), any(DeployVnfApplianceCmd.class));

        List<Long> securityGroupIds = userVmManagerImpl.getSecurityGroupIdList(cmd, null, null, null);

        Assert.assertEquals(1, securityGroupIds.size());
        Assert.assertEquals(securityGroupId, securityGroupIds.get(0).longValue());

        Mockito.verify(userVmManagerImpl).getSecurityGroupIdList(cmd);
        Mockito.verify(vnfTemplateManager).createSecurityGroupForVnfAppliance(any(), any(), any(), any(DeployVnfApplianceCmd.class));
    }

    @Test
    public void testSetVmRequiredFieldsForImportNotImport() {
        userVmManagerImpl.setVmRequiredFieldsForImport(false, userVmVoMock, _dcMock,
                Hypervisor.HypervisorType.VMware, Mockito.mock(HostVO.class), Mockito.mock(HostVO.class), VirtualMachine.PowerState.PowerOn);
        Mockito.verify(userVmVoMock, never()).setDataCenterId(anyLong());
    }


    @Test
    public void createVirtualMachineWithCloudRuntimeException() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = new DeployVMCmd();
        ReflectionTestUtils.setField(deployVMCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(deployVMCmd, "templateId", templateId);
        ReflectionTestUtils.setField(deployVMCmd, "serviceOfferingId", serviceOfferingId);
        deployVMCmd._accountService = accountService;

        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(serviceOffering.getState()).thenReturn(ServiceOffering.State.Active);

        when(entityManager.findById(VirtualMachineTemplate.class, templateId)).thenReturn(templateMock);
        when(templateMock.getTemplateType()).thenReturn(Storage.TemplateType.VNF);
        when(templateMock.isDeployAsIs()).thenReturn(false);
        when(templateMock.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateMock.getUserDataId()).thenReturn(null);
        Mockito.doNothing().when(vnfTemplateManager).validateVnfApplianceNics(any(), nullable(List.class), nullable(Map.class));

        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        when(serviceOfferingJoinDao.findById(anyLong())).thenReturn(svcOfferingMock);
        when(_dcMock.isLocalStorageEnabled()).thenReturn(true);
        when(_dcMock.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        String vmId = "testId";
        CloudRuntimeException cre = new CloudRuntimeException("Error and CloudRuntimeException is thrown");
        cre.addProxyObject(vmId, "vmId");

        doThrow(cre).when(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any());

        CloudRuntimeException creThrown = assertThrows(CloudRuntimeException.class, () -> userVmManagerImpl.createVirtualMachine(deployVMCmd));
        ArrayList<ExceptionProxyObject> proxyIdList = creThrown.getIdProxyList();
        assertNotNull(proxyIdList != null );
        assertTrue(proxyIdList.stream().anyMatch( p -> p.getUuid().equals(vmId)));
    }

    @Test
    public void testSetVmRequiredFieldsForImportFromLastHost() {
        HostVO lastHost = Mockito.mock(HostVO.class);
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(_dcMock.getId()).thenReturn(1L);
        Mockito.when(host.getId()).thenReturn(1L);
        Mockito.when(lastHost.getId()).thenReturn(2L);
        userVmManagerImpl.setVmRequiredFieldsForImport(true, userVmVoMock, _dcMock,
                Hypervisor.HypervisorType.VMware, host, lastHost, VirtualMachine.PowerState.PowerOn);
        Mockito.verify(userVmVoMock).setLastHostId(2L);
        Mockito.verify(userVmVoMock).setState(VirtualMachine.State.Running);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testRestoreVMNoVM() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        RestoreVMCmd cmd = Mockito.mock(RestoreVMCmd.class);
        when(cmd.getVmId()).thenReturn(vmId);
        when(cmd.getTemplateId()).thenReturn(2L);
        when(userVmDao.findById(vmId)).thenReturn(null);

        userVmManagerImpl.restoreVM(cmd);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testRestoreVMWithVolumeSnapshots() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();
        Mockito.lenient().doNothing().when(accountManager).checkAccess(accountMock, null, true, userVmVoMock);

        RestoreVMCmd cmd = Mockito.mock(RestoreVMCmd.class);
        when(cmd.getVmId()).thenReturn(vmId);
        when(cmd.getTemplateId()).thenReturn(2L);
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        Mockito.doReturn(false).when(userVmManagerImpl).isVMPartOfAnyCKSCluster(userVmVoMock);

        userVmManagerImpl.restoreVM(cmd);
    }

    @Test
    public void restoreVirtualMachineDelegatesToVmRestoreService() throws ResourceUnavailableException, InsufficientCapacityException {
        UserVm restoredVm = Mockito.mock(UserVm.class);
        when(vmRestoreService.restoreVirtualMachine(accountMock, vmId, 2L, null, false, null)).thenReturn(restoredVm);

        UserVm result = userVmManagerImpl.restoreVirtualMachine(accountMock, vmId, 2L, null, false, null);

        assertEquals(restoredVm, result);
        verify(vmRestoreService).restoreVirtualMachine(accountMock, vmId, 2L, null, false, null);
    }

    @Test
    public void addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecifiedTestDetailsConstantIsNotNullDoNothing() {
        int currentValue = 123;

        for (String detailsConstant : detailsConstants) {
            userVmManagerImpl.addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(null, customParameters, detailsConstant, currentValue);
        }

        Assert.assertEquals(customParameters.get(VmDetailConstants.MEMORY), "2048");
        Assert.assertEquals(customParameters.get(VmDetailConstants.CPU_NUMBER), "4");
        Assert.assertEquals(customParameters.get(VmDetailConstants.CPU_SPEED), "1000");
    }

    @Test
    public void addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecifiedTestNewValueIsNotNullDoNothing() {
        Map<String, String> details = new HashMap<>();
        int currentValue = 123;

        for (String detailsConstant : detailsConstants) {
            userVmManagerImpl.addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(321, details, detailsConstant, currentValue);
        }

        Assert.assertNull(details.get(VmDetailConstants.MEMORY));
        Assert.assertNull(details.get(VmDetailConstants.CPU_NUMBER));
        Assert.assertNull(details.get(VmDetailConstants.CPU_SPEED));
    }

    @Test
    public void addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecifiedTestBothValuesAreNullKeepCurrentValue() {
        Map<String, String> details = new HashMap<>();
        int currentValue = 123;

        for (String detailsConstant : detailsConstants) {
            userVmManagerImpl.addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(null, details, detailsConstant, currentValue);
        }

        Assert.assertEquals(details.get(VmDetailConstants.MEMORY), String.valueOf(currentValue));
        Assert.assertEquals(details.get(VmDetailConstants.CPU_NUMBER), String.valueOf(currentValue));
        Assert.assertEquals(details.get(VmDetailConstants.CPU_SPEED),String.valueOf(currentValue));
    }

    @Test
    public void addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecifiedTestNeitherValueIsNullDoNothing() {
        int currentValue = 123;

        for (String detailsConstant : detailsConstants) {
            userVmManagerImpl.addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(321, customParameters, detailsConstant, currentValue);
        }

        Assert.assertEquals(customParameters.get(VmDetailConstants.MEMORY), "2048");
        Assert.assertEquals(customParameters.get(VmDetailConstants.CPU_NUMBER), "4");
        Assert.assertEquals(customParameters.get(VmDetailConstants.CPU_SPEED),"1000");
    }

    @Test
    public void updateInstanceDetailsMapWithCurrentValuesForAbsentDetailsTestAllConstantsAreUpdated() {
        Mockito.doReturn(serviceOffering).when(_serviceOfferingDao).findById(Mockito.anyLong());
        Mockito.doReturn(1L).when(vmInstanceMock).getId();
        Mockito.doReturn(1L).when(vmInstanceMock).getServiceOfferingId();
        Mockito.doReturn(serviceOffering).when(_serviceOfferingDao).findByIdIncludingRemoved(Mockito.anyLong(), Mockito.anyLong());
        userVmManagerImpl.updateInstanceDetailsMapWithCurrentValuesForAbsentDetails(null, vmInstanceMock, 0l);

        Mockito.verify(userVmManagerImpl).addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(Mockito.any(), Mockito.any(), Mockito.eq(VmDetailConstants.CPU_SPEED), Mockito.any());
        Mockito.verify(userVmManagerImpl).addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(Mockito.any(), Mockito.any(), Mockito.eq(VmDetailConstants.MEMORY), Mockito.any());
        Mockito.verify(userVmManagerImpl).addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(Mockito.any(), Mockito.any(), Mockito.eq(VmDetailConstants.CPU_NUMBER), Mockito.any());
    }

    @Test
    public void testCheckVolumesLimits() {
        long diskOffId1 = 1L;
        DiskOfferingVO diskOfferingVO1 = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(diskOffId1)).thenReturn(diskOfferingVO1);
        Mockito.when(resourceLimitMgr.getResourceLimitStorageTags(diskOfferingVO1)).thenReturn(List.of("tag1", "tag2"));
        long diskOffId2 = 2L;
        DiskOfferingVO diskOfferingVO2 = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(diskOffId2)).thenReturn(diskOfferingVO2);
        Mockito.when(resourceLimitMgr.getResourceLimitStorageTags(diskOfferingVO2)).thenReturn(List.of("tag2"));
        long diskOffId3 = 3L;
        DiskOfferingVO diskOfferingVO3 = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(diskOffId3)).thenReturn(diskOfferingVO3);
        Mockito.when(resourceLimitMgr.getResourceLimitStorageTags(diskOfferingVO3)).thenReturn(new ArrayList<>());

        VolumeVO vol1 = Mockito.mock(VolumeVO.class);
        Mockito.when(vol1.getDiskOfferingId()).thenReturn(diskOffId1);
        Mockito.when(vol1.getSize()).thenReturn(10L);
        Mockito.when(vol1.isDisplay()).thenReturn(true);
        VolumeVO undisplayedVolume = Mockito.mock(VolumeVO.class); // shouldn't be considered for limits
        Mockito.when(undisplayedVolume.isDisplay()).thenReturn(false);
        VolumeVO vol3 = Mockito.mock(VolumeVO.class);
        Mockito.when(vol3.getDiskOfferingId()).thenReturn(diskOffId2);
        Mockito.when(vol3.getSize()).thenReturn(30L);
        Mockito.when(vol3.isDisplay()).thenReturn(true);
        VolumeVO vol4 = Mockito.mock(VolumeVO.class);
        Mockito.when(vol4.getDiskOfferingId()).thenReturn(diskOffId3);
        Mockito.when(vol4.getSize()).thenReturn(40L);
        Mockito.when(vol4.isDisplay()).thenReturn(true);
        VolumeVO vol5 = Mockito.mock(VolumeVO.class);
        Mockito.when(vol5.getDiskOfferingId()).thenReturn(diskOffId1);
        Mockito.when(vol5.getSize()).thenReturn(50L);
        Mockito.when(vol5.isDisplay()).thenReturn(true);

        List<VolumeVO> volumes = List.of(vol1, undisplayedVolume, vol3, vol4, vol5);
        List<Reserver> reservations = new ArrayList<>();

        try (MockedConstruction<CheckedReservation> mockCheckedReservation = Mockito.mockConstruction(CheckedReservation.class)) {
            userVmManagerImpl.checkVolumesLimits(account, volumes, reservations);
            Assert.assertEquals(8, reservations.size());
        } catch (ResourceAllocationException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void testValidateStrictHostTagCheckPass() {
        ServiceOfferingVO serviceOffering = Mockito.mock(ServiceOfferingVO.class);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        HostVO destinationHostVO = Mockito.mock(HostVO.class);

        Mockito.when(_serviceOfferingDao.findByIdIncludingRemoved(1L)).thenReturn(serviceOffering);
        Mockito.when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);

        Mockito.when(vm.getServiceOfferingId()).thenReturn(1L);
        Mockito.when(vm.getTemplateId()).thenReturn(2L);

        Mockito.when(destinationHostVO.checkHostServiceOfferingAndTemplateTags(Mockito.any(ServiceOffering.class), Mockito.any(VirtualMachineTemplate.class), Mockito.anySet())).thenReturn(true);

        userVmManagerImpl.validateStrictHostTagCheck(vm, destinationHostVO);

        Mockito.verify(
                destinationHostVO, times(1)
        ).checkHostServiceOfferingAndTemplateTags(Mockito.any(ServiceOffering.class), Mockito.any(VirtualMachineTemplate.class), Mockito.anySet());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testValidateStrictHostTagCheckFail() {
        ServiceOfferingVO serviceOffering = Mockito.mock(ServiceOfferingVO.class);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        HostVO destinationHostVO = Mockito.mock(HostVO.class);

        Mockito.when(_serviceOfferingDao.findByIdIncludingRemoved(1L)).thenReturn(serviceOffering);
        Mockito.when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);

        Mockito.when(vm.getServiceOfferingId()).thenReturn(1L);
        Mockito.when(vm.getTemplateId()).thenReturn(2L);

        Mockito.when(destinationHostVO.checkHostServiceOfferingAndTemplateTags(Mockito.any(ServiceOffering.class), Mockito.any(VirtualMachineTemplate.class), Mockito.anySet())).thenReturn(false);
        userVmManagerImpl.validateStrictHostTagCheck(vm, destinationHostVO);
    }

    public void getRootVolumeSizeForVmRestoreDelegatesToVmRestoreService() {
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        DiskOffering diskOffering = Mockito.mock(DiskOffering.class);
        Map<String, String> details = new HashMap<>();
        when(vmRestoreService.getRootVolumeSizeForVmRestore(null, template, userVm, diskOffering, details, false)).thenReturn(16L);

        Long actualSize = userVmManagerImpl.getRootVolumeSizeForVmRestore(null, template, userVm, diskOffering, details, false);

        Assert.assertEquals(16L, actualSize.longValue());
        verify(vmRestoreService).getRootVolumeSizeForVmRestore(null, template, userVm, diskOffering, details, false);
    }

    // Slice 11: these five tests now drive the new VmDestroyPermissionServiceImpl
    // behind the manager's checkExpungeVmPermission wrapper. The config-branch stub
    // moved from the spied manager's removed getConfigAllowUserExpungeRecoverVm
    // call site to the equivalent isUserExpungeRecoverVmAllowed on the service spy.
    @Test
    public void checkExpungeVMPermissionTestAccountIsNotAdminConfigFalseThrowsPermissionDeniedException () {
        Mockito.doReturn(false).when(accountManager).isAdmin(Mockito.anyLong());
        Mockito.doReturn(false).when(vmDestroyPermissionServiceSpy).isUserExpungeRecoverVmAllowed(Mockito.anyLong());

        Assert.assertThrows(PermissionDeniedException.class, () -> userVmManagerImpl.checkExpungeVmPermission(accountMock, null));
    }
    @Test
    public void checkExpungeVmPermissionTestAccountIsNotAdminConfigTrueNoApiAccessThrowsPermissionDeniedException () {
        Mockito.doReturn(false).when(accountManager).isAdmin(Mockito.anyLong());
        Mockito.doReturn(true).when(vmDestroyPermissionServiceSpy).isUserExpungeRecoverVmAllowed(Mockito.anyLong());
        doThrow(PermissionDeniedException.class).when(accountManager).checkApiAccess(accountMock, "expungeVirtualMachine", null);

        Assert.assertThrows(PermissionDeniedException.class, () -> userVmManagerImpl.checkExpungeVmPermission(accountMock, null));
    }
    @Test
    public void checkExpungeVmPermissionTestAccountIsNotAdminConfigTrueHasApiAccessReturnNothing () {
        Mockito.doReturn(false).when(accountManager).isAdmin(Mockito.anyLong());
        Mockito.doReturn(true).when(vmDestroyPermissionServiceSpy).isUserExpungeRecoverVmAllowed(Mockito.anyLong());

        userVmManagerImpl.checkExpungeVmPermission(accountMock, null);
    }
    @Test
    public void checkExpungeVmPermissionTestAccountIsAdminNoApiAccessThrowsPermissionDeniedException () {
        Mockito.doReturn(true).when(accountManager).isAdmin(Mockito.anyLong());
        doThrow(PermissionDeniedException.class).when(accountManager).checkApiAccess(accountMock, "expungeVirtualMachine", null);

        Assert.assertThrows(PermissionDeniedException.class, () -> userVmManagerImpl.checkExpungeVmPermission(accountMock, null));
    }
    @Test
    public void checkExpungeVmPermissionTestAccountIsAdminHasApiAccessReturnNothing () {
        Mockito.doReturn(true).when(accountManager).isAdmin(Mockito.anyLong());

        userVmManagerImpl.checkExpungeVmPermission(accountMock, null);
    }

    @Test
    public void validateIfVmSupportsMigrationTestVmIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("There is no VM by ID [%s].", 1l);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmSupportsMigration(null, 1l);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmSupportsMigrationTestVmIsRunningThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Unable to move VM [%s] in [%s] state.", userVmVoMock, VirtualMachine.State.Running);
        Mockito.doReturn(VirtualMachine.State.Running).when(userVmVoMock).getState();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmSupportsMigration(userVmVoMock, 1l);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmSupportsMigrationTestVmIsSharedFileSystemInstanceThrowsInvalidParameterValueException() {
        Mockito.doReturn(UserVmManager.SHAREDFSVM).when(userVmVoMock).getUserVmType();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmSupportsMigration(userVmVoMock, 1l);
        });

        Assert.assertEquals("Migration is not supported for Shared FileSystem Instances.", assertThrows.getMessage());
    }

    @Test
    public void validateIfVmSupportsMigrationTestVmIsNotRunningDoesNotThrowInvalidParameterValueException() {
        userVmManagerImpl.validateIfVmSupportsMigration(userVmVoMock, 1l);
    }

    @Test
    public void validateOldAndNewAccountsTestBothAreValidDoNothing() {
        Account newAccount = Mockito.mock(Account.class);
        Mockito.doReturn(1l).when(newAccount).getAccountId();

        userVmManagerImpl.validateOldAndNewAccounts(accountMock, newAccount, 1L, "", 1L);
    }

    @Test
    public void validateOldAndNewAccountsTestOldAccountIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Invalid old account [%s] for VM in domain [%s].", userVmVoMock.getAccountId(), assignVmCmdMock.getDomainId());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateOldAndNewAccounts(null, accountMock, userVmVoMock.getAccountId(), "", assignVmCmdMock.getDomainId());
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateOldAndNewAccountsTestNewAccountIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Invalid new account [%s] for VM in domain [%s].", assignVmCmdMock.getAccountName(), assignVmCmdMock.getDomainId());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateOldAndNewAccounts(accountMock, null, 1l, assignVmCmdMock.getAccountName(), assignVmCmdMock.getDomainId());
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateOldAndNewAccountsTestNewAccountStateIsDisabledThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("The new account owner [%s] is disabled.", accountMock.toString());

        Mockito.doReturn(Account.State.DISABLED).when(accountMock).getState();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateOldAndNewAccounts(accountMock, accountMock, 1l, "", 1l);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateOldAndNewAccountsTestOldAccountIsTheSameAsNewAccountThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("The new account [%s] is the same as the old account.", accountMock.toString());

        Mockito.doReturn(Account.State.ENABLED).when(accountMock).getState();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateOldAndNewAccounts(accountMock, accountMock, 1l, "", 1l);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateOldAndNewAccountsTestOldAccountIsNotTheSameAsNewAccountDoesNotThrowInvalidParameterValueException() {
        AccountVO oldAccount = new AccountVO();
        Mockito.doReturn(1l).when(accountMock).getAccountId();

        userVmManagerImpl.validateOldAndNewAccounts(oldAccount, accountMock, 1l, "", 1l);
    }

    @Test
    public void checkCallerAccessToAccountsTestCallsCheckAccessToOldAccountAndNewAccount() {
        AccountVO oldAccount = new AccountVO();

        userVmManagerImpl.checkCallerAccessToAccounts(callerAccount, oldAccount, accountMock);

        Mockito.verify(accountManager).checkAccess(callerAccount, null, true, oldAccount);
        Mockito.verify(accountManager).checkAccess(callerAccount, null, true, accountMock);
    }

    @Test
    public void validateIfVmHasNoRulesTestPortForwardingRulesExistThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Remove any Port Forwarding rules for VM [%s] before assigning it to another user.", userVmVoMock);

        Mockito.doReturn(portForwardingRulesListMock).when(portForwardingRulesDaoMock).listByVm(Mockito.anyLong());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmHasNoRules(userVmVoMock, 1l);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmHasNoRulesTestStaticNatRulesExistThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Remove the StaticNat rules for VM [%s] before assigning it to another user.", userVmVoMock);

        Mockito.doReturn(firewallRuleVoListMock).when(firewallRulesDaoMock).listStaticNatByVmId(Mockito.anyLong());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmHasNoRules(userVmVoMock, 1l);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmHasNoRulesTestLoadBalancingRulesExistThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Remove the Load Balancing rules for VM [%s] before assigning it to another user.", userVmVoMock);

        Mockito.doReturn(loadBalancerVmMapVoListMock).when(loadBalancerVmMapDaoMock).listByInstanceId(Mockito.anyLong());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmHasNoRules(userVmVoMock, 1l);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmHasNoRulesTestOneToOneNatRulesExistThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Remove the One to One Nat rule for VM [%s] for IP [%s].", userVmVoMock, ipAddressVoMock.toString());

        LinkedList<IPAddressVO> ipAddressVoList = new LinkedList<IPAddressVO>();

        Mockito.doReturn(ipAddressVoList).when(ipAddressDaoMock).findAllByAssociatedVmId(Mockito.anyLong());
        ipAddressVoList.add(ipAddressVoMock);
        Mockito.doReturn(true).when(ipAddressVoMock).isOneToOneNat();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmHasNoRules(userVmVoMock, 1l);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmHasNoRulesTestOneToOneNatRulesDoNotExistDoesNotThrowInvalidParameterValueException() {
        userVmManagerImpl.validateIfVmHasNoRules(userVmVoMock, 1l);
    }

    @Test
    public void verifyResourceLimitsForAccountAndStorageTestCountOnlyRunningVmsInResourceLimitationIsTrueDoesNotCallVmResourceLimitCheck() throws ResourceAllocationException {
        List<Reserver> reservations = new ArrayList<>();
        LinkedList<VolumeVO> volumeVoList = new LinkedList<VolumeVO>();
        Mockito.doReturn(true).when(userVmManagerImpl).countOnlyRunningVmsInResourceLimitation();

        userVmManagerImpl.verifyResourceLimitsForAccountAndStorage(accountMock, userVmVoMock, serviceOfferingVoMock, volumeVoList, virtualMachineTemplateMock, reservations);

        Mockito.verify(resourceLimitMgr, Mockito.never()).checkVmResourceLimit(Mockito.any(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(resourceLimitMgr, Mockito.never()).checkVolumeResourceLimit(Mockito.any(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void verifyResourceLimitsForAccountAndStorageTestCountOnlyRunningVmsInResourceLimitationIsFalseCallsVmResourceLimitCheck() throws ResourceAllocationException {
        List<Reserver> reservations = new ArrayList<>();
        LinkedList<VolumeVO> volumeVoList = new LinkedList<VolumeVO>();
        Mockito.doReturn(false).when(userVmManagerImpl).countOnlyRunningVmsInResourceLimitation();

        userVmManagerImpl.verifyResourceLimitsForAccountAndStorage(accountMock, userVmVoMock, serviceOfferingVoMock, volumeVoList, virtualMachineTemplateMock, reservations);

        Mockito.verify(resourceLimitMgr).checkVmResourceLimit(Mockito.any(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).checkVolumesLimits(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void validateIfNewOwnerHasAccessToTemplateTestTemplateIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Template for VM [%s] cannot be found.", userVmVoMock.getUuid());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfNewOwnerHasAccessToTemplate(userVmVoMock, accountMock, null);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfNewOwnerHasAccessToTemplateTestCallCheckAccessWhenTemplateIsNotPublic() {
        userVmManagerImpl.validateIfNewOwnerHasAccessToTemplate(userVmVoMock, accountMock, virtualMachineTemplateMock);

        Mockito.verify(accountManager).checkAccess(accountMock, SecurityChecker.AccessType.UseEntry, true, virtualMachineTemplateMock);
    }

    @Test
    public void updateVmOwnerTestCallsSetAccountIdSetDomainIdAndPersist() {
        userVmManagerImpl.updateVmOwner(accountMock, userVmVoMock, 1l, 1l);

        Mockito.verify(userVmVoMock).setAccountId(Mockito.anyLong());
        Mockito.verify(userVmVoMock).setDomainId(Mockito.anyLong());
        Mockito.verify(userVmDao).persist(userVmVoMock);
    }

    @Test
    public void updateVmNetworkTestCallsUpdateBasicTypeNetworkForVmIfBasicTypeZone() throws InsufficientCapacityException, ResourceAllocationException {
        Mockito.doReturn(_dcMock).when(_dcDao).findById(Mockito.anyLong());
        Mockito.doReturn(DataCenter.NetworkType.Basic).when(_dcMock).getNetworkType();
        Mockito.doNothing().when(userVmManagerImpl).updateBasicTypeNetworkForVm(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any());

        userVmManagerImpl.updateVmNetwork(assignVmCmdMock, callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock);

        Mockito.verify(userVmManagerImpl).updateBasicTypeNetworkForVm(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any());
    }

    @Test
    public void updateVmNetworkTestCallsUpdateAdvancedTypeNetworkForVmIfNotBasicTypeZone() throws InsufficientCapacityException, ResourceAllocationException {
        Mockito.doReturn(_dcMock).when(_dcDao).findById(Mockito.anyLong());
        Mockito.doReturn(DataCenter.NetworkType.Advanced).when(_dcMock).getNetworkType();
        Mockito.doNothing().when(userVmManagerImpl).updateAdvancedTypeNetworkForVm(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());

        userVmManagerImpl.updateVmNetwork(assignVmCmdMock, callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock);

        Mockito.verify(userVmManagerImpl).updateAdvancedTypeNetworkForVm(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void cleanupOfOldOwnerNicsForNetworkTestCallsCleanupNicsAndRemoveNics() {
        userVmManagerImpl.cleanupOfOldOwnerNicsForNetwork(virtualMachineProfileMock);

        Mockito.verify(_networkMgr).cleanupNics(virtualMachineProfileMock);
        Mockito.verify(_networkMgr).removeNics(virtualMachineProfileMock);
    }

    @Test
    public void addDefaultNetworkToNetworkListTestDefaultNetworkIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = "Unable to find a default network to start a VM.";

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.addDefaultNetworkToNetworkList(networkVoListMock, null);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void addDefaultNetworkToNetworkListTestDefaultNetworkIsNotNullAddNetworkToNetworkList() {
        userVmManagerImpl.addDefaultNetworkToNetworkList(networkVoListMock, networkMock);

        Mockito.verify(networkVoListMock).add(Mockito.any());
    }

    @Test
    public void allocateNetworksForVmTestCallsNetworkManagerAllocate() throws InsufficientCapacityException {
        LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<Network, List<? extends NicProfile>>();

        Mockito.doReturn(userVmVoMock).when(virtualMachineManager).findById(Mockito.anyLong());

        userVmManagerImpl.allocateNetworksForVm(userVmVoMock, networks);

        Mockito.verify(_networkMgr).allocate(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void addSecurityGroupsToVmTestIsVmWareAndSecurityGroupIdListIsNotNullThrowsInvalidParameterValueException() {
        String expectedMessage = "Security group feature is not supported for VMWare hypervisor.";
        LinkedList<Long> securityGroupIdList = new LinkedList<Long>();

        Mockito.doReturn(Hypervisor.HypervisorType.VMware).when(virtualMachineTemplateMock).getHypervisorType();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.addSecurityGroupsToVm(accountMock, userVmVoMock, virtualMachineTemplateMock, securityGroupIdList, networkMock);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void addSecurityGroupsToVmTestIsNotVmWareDefaultNetworkIsNullAndNetworkModelCanAddDefaultSecurityGroupCallsAddDefaultSecurityGroupToSecurityGroupIdList() {
        LinkedList<Long> securityGroupIdList = new LinkedList<Long>();

        Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(virtualMachineTemplateMock).getHypervisorType();
        Mockito.doReturn(true).when(networkModel).canAddDefaultSecurityGroup();
        Mockito.doReturn(securityGroupVoMock).when(securityGroupManagerMock).getDefaultSecurityGroup(Mockito.anyLong());

        userVmManagerImpl.addSecurityGroupsToVm(accountMock, userVmVoMock, virtualMachineTemplateMock, securityGroupIdList, null);

        Mockito.verify(userVmManagerImpl).addDefaultSecurityGroupToSecurityGroupIdList(accountMock, securityGroupIdList);
        Mockito.verify(securityGroupManagerMock).addInstanceToGroups(Mockito.any(), Mockito.any());
    }

    @Test
    public void addNetworksToNetworkIdListTestCallsKeepOldSharedNetworkForVmAndAddAdditionalNetworksToVm() {
        LinkedList<Long> networkIdList = new LinkedList<Long>();
        HashSet<NetworkVO> applicableNetworks = new HashSet<NetworkVO>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        userVmManagerImpl.addNetworksToNetworkIdList(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics,
                requestedIPv6ForNics);

        Mockito.verify(userVmManagerImpl).keepOldSharedNetworkForVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
        Mockito.verify(userVmManagerImpl).addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
    }

    @Test
    public void getOfferingWithRequiredAvailabilityForNetworkCreationTestRequiredOfferingsListHasNoOfferingsThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Unable to find network offering with availability [%s] to automatically create the network as a part of VM creation.",
                NetworkOffering.Availability.Required);
        LinkedList<NetworkOfferingVO> requiredOfferings = new LinkedList<>();

        Mockito.doReturn(requiredOfferings).when(networkOfferingDaoMock).listByAvailability(NetworkOffering.Availability.Required, false);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.getOfferingWithRequiredAvailabilityForNetworkCreation();
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void getOfferingWithRequiredAvailabilityForNetworkCreationTestFirstOfferingIsNotEnabledThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Required network offering ID [%s] is not in [%s] state.", 1l, NetworkOffering.State.Enabled);

        Mockito.doReturn(networkOfferingVoListMock).when(networkOfferingDaoMock).listByAvailability(NetworkOffering.Availability.Required, false);
        Mockito.doReturn(networkOfferingVoMock).when(networkOfferingVoListMock).get(0);

        Mockito.doReturn(NetworkOffering.State.Disabled).when(networkOfferingVoMock).getState();

        Mockito.doReturn(1l).when(networkOfferingVoMock).getId();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.getOfferingWithRequiredAvailabilityForNetworkCreation();
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test(expected = CloudRuntimeException.class)
    public void selectApplicableNetworkToCreateVmTestVirtualNetworkIsEmptyThrowsException() throws InsufficientCapacityException,
            ResourceAllocationException {

        HashSet<NetworkVO> applicableNetworks = new HashSet<>();
        LinkedList<? extends Network> virtualNetworks = new LinkedList<>();

        Mockito.doReturn(virtualNetworks).when(networkModel).listNetworksForAccount(Mockito.anyLong(), Mockito.anyLong(), Mockito.any());

        userVmManagerImpl.selectApplicableNetworkToCreateVm(accountMock, _dcMock, applicableNetworks);
    }

    @Test
    public void selectApplicableNetworkToCreateVmTestVirtualNetworkHasMultipleNetworksThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("More than one default isolated network has been found for account [%s]; please specify networkIDs.", accountMock.toString());
        HashSet<NetworkVO> applicableNetworks = new HashSet<NetworkVO>();
        LinkedList<NetworkVO> virtualNetworks = new LinkedList<NetworkVO>();

        Mockito.doReturn(virtualNetworks).when(networkModel).listNetworksForAccount(Mockito.anyLong(), Mockito.anyLong(), Mockito.any());

        virtualNetworks.add(networkMock);
        virtualNetworks.add(networkMock);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.selectApplicableNetworkToCreateVm(accountMock, _dcMock, applicableNetworks);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void selectApplicableNetworkToCreateVmTestVirtualNetworkHasOneNetworkCallsNetworkDaoFindById() throws InsufficientCapacityException, ResourceAllocationException {
        HashSet<NetworkVO> applicableNetworks = new HashSet<NetworkVO>();

        Mockito.doReturn(networkVoListMock).when(networkModel).listNetworksForAccount(Mockito.anyLong(), Mockito.anyLong(), Mockito.any());

        Mockito.doReturn(false).when(networkVoListMock).isEmpty();
        Mockito.doReturn(1).when(networkVoListMock).size();
        Mockito.doReturn(networkMock).when(networkVoListMock).get(0);

        userVmManagerImpl.selectApplicableNetworkToCreateVm(accountMock, _dcMock, applicableNetworks);

        Mockito.verify(_networkDao).findById(Mockito.anyLong());
    }

    @Test
    public void addDefaultSecurityGroupToSecurityGroupIdListTestDefaultGroupIsNullCallsCreateSecurityGroup() {
        String expected = "";
        LinkedList<Long> securityGroupIdList = Mockito.spy(new LinkedList<Long>());

        Mockito.doReturn(null).when(securityGroupManagerMock).getDefaultSecurityGroup(Mockito.anyLong());
        Mockito.doReturn(securityGroupVoMock).when(securityGroupManagerMock).createSecurityGroup(SecurityGroupManager.DEFAULT_GROUP_NAME,
                SecurityGroupManager.DEFAULT_GROUP_DESCRIPTION, 1l, 1l, expected);

        Mockito.doReturn(1l).when(accountMock).getDomainId();
        Mockito.doReturn(1l).when(accountMock).getId();
        Mockito.doReturn(expected).when(accountMock).getAccountName();
        Mockito.doReturn(1l).when(securityGroupVoMock).getId();

        userVmManagerImpl.addDefaultSecurityGroupToSecurityGroupIdList(accountMock, securityGroupIdList);

        Mockito.verify(securityGroupManagerMock).createSecurityGroup(SecurityGroupManager.DEFAULT_GROUP_NAME, SecurityGroupManager.DEFAULT_GROUP_DESCRIPTION, 1l, 1l, expected);
        Mockito.verify(securityGroupIdList).add(1l);
    }

    @Test
    public void addDefaultSecurityGroupToSecurityGroupIdListTestDefaultGroupIsPresentDoesNotCallAddIdToSecurityGroupIdList() {
        LinkedList<Long> securityGroupIdList = Mockito.spy(new LinkedList<Long>());

        securityGroupIdList.addFirst(1l);
        Mockito.doReturn(securityGroupVoMock).when(securityGroupManagerMock).getDefaultSecurityGroup(Mockito.anyLong());
        Mockito.doReturn(1l).when(securityGroupVoMock).getId();

        userVmManagerImpl.addDefaultSecurityGroupToSecurityGroupIdList(accountMock, securityGroupIdList);

        Mockito.verify(securityGroupIdList, Mockito.never()).add(Mockito.anyLong());
    }

    @Test
    public void addDefaultSecurityGroupToSecurityGroupIdListTestDefaultGroupIsNotPresentCallsAddIdToSecurityGroupIdList() {
        LinkedList<Long> securityGroupIdList = Mockito.spy(new LinkedList<Long>());

        Mockito.doReturn(securityGroupVoMock).when(securityGroupManagerMock).getDefaultSecurityGroup(Mockito.anyLong());
        Mockito.doReturn(1l).when(securityGroupVoMock).getId();

        userVmManagerImpl.addDefaultSecurityGroupToSecurityGroupIdList(accountMock, securityGroupIdList);

        Mockito.verify(securityGroupIdList).add(1l);
    }

    @Test
    public void keepOldSharedNetworkForVmTestNetworkIdListIsNotNullOrEmptyDoesNotCallFindDefaultNicForVm() {
        LinkedList<Long> networkIdList = new LinkedList<Long>();
        HashSet<NetworkVO> applicableNetworks = new HashSet<NetworkVO>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        networkIdList.add(1l);

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao, Mockito.never()).findDefaultNicForVM(Mockito.anyLong());
    }

    @Test
    public void keepOldSharedNetworkForVmTestNetworkIdListIsNullCallsFindDefaultNicForVm() {
        HashSet<NetworkVO> applicableNetworks = new HashSet<NetworkVO>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao).findDefaultNicForVM(Mockito.anyLong());
    }

    @Test
    public void keepOldSharedNetworkForVmTestNetworkIdListIsEmptyCallsFindDefaultNicForVm() {
        LinkedList<Long> networkIdList = new LinkedList<Long>();
        HashSet<NetworkVO> applicableNetworks = new HashSet<NetworkVO>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao).findDefaultNicForVM(Mockito.anyLong());
    }

    @Test
    public void keepOldSharedNetworkForVmTestDefaultNicOldIsNullDoesNotCallNetworkDaoFindById() {
        HashSet<NetworkVO> applicableNetworks = new HashSet<NetworkVO>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        Mockito.doReturn(null).when(nicDao).findDefaultNicForVM(Mockito.anyLong());

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(_networkDao, Mockito.never()).findById(Mockito.anyLong());
    }

    @Test
    public void keepOldSharedNetworkForVmTestDefaultNicOldIsNotNullCallsNetworkDaoFindById() {
        HashSet<NetworkVO> applicableNetworks = new HashSet<NetworkVO>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        Mockito.doReturn(new NicVO()).when(nicDao).findDefaultNicForVM(Mockito.anyLong());

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(_networkDao).findById(Mockito.anyLong());
    }

    @Test
    public void keepOldSharedNetworkForVmTestAccountCanNotUseNetworkDoesNotAddNetworkToApplicableNetworks() {
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<NetworkVO>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        Mockito.doReturn(new NicVO()).when(nicDao).findDefaultNicForVM(Mockito.anyLong());
        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(false).when(userVmManagerImpl).canAccountUseNetwork(accountMock, networkMock);

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(applicableNetworks, Mockito.never()).add(Mockito.any());
    }

    @Test
    public void keepOldSharedNetworkForVmTestAccountCanUseNetworkAddsNetworkToApplicableNetworks() {
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<NetworkVO>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        Mockito.doReturn(new NicVO()).when(nicDao).findDefaultNicForVM(Mockito.anyLong());
        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(true).when(userVmManagerImpl).canAccountUseNetwork(accountMock, networkMock);

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(applicableNetworks).add(Mockito.any());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkIdListIsNullDoesNotCallCheckNetworkPermissions() {
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<NetworkVO>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(networkModel, Mockito.never()).checkNetworkPermissions(Mockito.any(), Mockito.any());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkIdListIsEmptyDoesNotCallCheckNetworkPermissions() {
        LinkedList<Long> networkIdList = new LinkedList<Long>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<NetworkVO>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(networkModel, Mockito.never()).checkNetworkPermissions(Mockito.any(), Mockito.any());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = "Unable to find specified Network ID.";
        LinkedList<Long> networkIdList = new LinkedList<Long>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<NetworkVO>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        networkIdList.add(1l);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkOfferingIsSystemOnlyThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Specified network [%s] is system only and cannot be used for VM deployment.", networkMock);
        LinkedList<Long> networkIdList = new LinkedList<Long>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<NetworkVO>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        networkIdList.add(1l);

        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(networkOfferingVoMock).when(entityManager).findById(Mockito.any(), Mockito.anyLong());
        Mockito.doReturn(true).when(networkOfferingVoMock).isSystemOnly();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkIsNotSharedGuestTypeDoesNotCallNicDaoFindByNtwkIdAndInstanceId() {
        LinkedList<Long> networkIdList = new LinkedList<Long>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<NetworkVO>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        networkIdList.add(1l);

        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(networkOfferingVoMock).when(entityManager).findById(Mockito.any(), Mockito.anyLong());
        Mockito.doReturn(false).when(networkOfferingVoMock).isSystemOnly();
        Mockito.doReturn(Network.GuestType.L2).when(networkMock).getGuestType();

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao, Mockito.never()).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkIsNotDomainAclTypeDoesNotCallNicDaoFindByNtwkIdAndInstanceId() {
        LinkedList<Long> networkIdList = new LinkedList<Long>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<NetworkVO>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        networkIdList.add(1l);

        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(networkOfferingVoMock).when(entityManager).findById(Mockito.any(), Mockito.anyLong());
        Mockito.doReturn(false).when(networkOfferingVoMock).isSystemOnly();
        Mockito.doReturn(Network.GuestType.Shared).when(networkMock).getGuestType();
        Mockito.doReturn(ControlledEntity.ACLType.Account).when(networkMock).getAclType();

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao, Mockito.never()).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    public void addAdditionalNetworksToVmTestOldNicIsNullDoesNotPutIpv4InRequestIpv4ForNics() {
        LinkedList<Long> networkIdList = new LinkedList<Long>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<NetworkVO>());
        HashMap<Long, String> requestedIPv4ForNics = Mockito.spy(new HashMap<Long, String>());
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        networkIdList.add(1l);

        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(networkOfferingVoMock).when(entityManager).findById(Mockito.any(), Mockito.anyLong());
        Mockito.doReturn(false).when(networkOfferingVoMock).isSystemOnly();
        Mockito.doReturn(Network.GuestType.Shared).when(networkMock).getGuestType();
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(null).when(nicDao).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());
        Mockito.verify(requestedIPv4ForNics, Mockito.never()).put(Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void addAdditionalNetworksToVmTestOldNicIsNotNullPutsIpv4InRequestIpv4ForNics() {
        LinkedList<Long> networkIdList = new LinkedList<Long>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<NetworkVO>());
        HashMap<Long, String> requestedIPv4ForNics = Mockito.spy(new HashMap<Long, String>());
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();

        networkIdList.add(1l);

        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(networkOfferingVoMock).when(entityManager).findById(Mockito.any(), Mockito.anyLong());
        Mockito.doReturn(false).when(networkOfferingVoMock).isSystemOnly();
        Mockito.doReturn(Network.GuestType.Shared).when(networkMock).getGuestType();
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(new NicVO()).when(nicDao).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());
        Mockito.verify(requestedIPv4ForNics).put(Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void createApplicableNetworkToCreateVmTestPhysicalNetworkIsNullThrowsInvalidParameterValueException() {
        Mockito.doReturn(networkOfferingVoMock).when(userVmManagerImpl).getOfferingWithRequiredAvailabilityForNetworkCreation();

        String expectedMessage = String.format("Unable to find physical network with ID [%s] and tag [%s].", 0l, null);
        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.createApplicableNetworkToCreateVm(accountMock, _dcMock);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void createApplicableNetworkToCreateVmTestFirstNetworkOfferingIsPersistentCallsImplementNetwork() throws InsufficientCapacityException, ResourceAllocationException {
        PhysicalNetworkVO physicalNetworkVo = new PhysicalNetworkVO();

        Mockito.doReturn(physicalNetworkVo).when(physicalNetworkDaoMock).findById(Mockito.anyLong());
        Mockito.doReturn(true).when(networkOfferingVoMock).isPersistent();
        Mockito.doReturn(networkOfferingVoMock).when(userVmManagerImpl).getOfferingWithRequiredAvailabilityForNetworkCreation();
        Mockito.doReturn(networkMock).when(userVmManagerImpl).implementNetwork(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(networkMock).when(_networkMgr).createGuestNetwork(Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        userVmManagerImpl.createApplicableNetworkToCreateVm(accountMock, _dcMock);

        Mockito.verify(userVmManagerImpl).implementNetwork(callerAccount, _dcMock, networkMock);
    }

    @Test
    public void createApplicableNetworkToCreateVmTestFirstNetworkOfferingIsNotPersistentDoesNotCallImplementNetwork() throws InsufficientCapacityException,
            ResourceAllocationException {

        PhysicalNetworkVO physicalNetworkVo = new PhysicalNetworkVO();

        Mockito.doReturn(physicalNetworkVo).when(physicalNetworkDaoMock).findById(Mockito.anyLong());
        Mockito.doReturn(networkMock).when(_networkMgr).createGuestNetwork(Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(networkOfferingVoMock).when(userVmManagerImpl).getOfferingWithRequiredAvailabilityForNetworkCreation();
        Mockito.doReturn(1l).when(networkMock).getId();
        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());

        userVmManagerImpl.createApplicableNetworkToCreateVm(accountMock, _dcMock);

        Mockito.verify(userVmManagerImpl, Mockito.never()).implementNetwork(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void canAccountUseNetworkTestNetworkIsNullReturnFalse() {
        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, null);

        Assert.assertFalse(canAccountUseNetwork);
    }

    @Test
    public void canAccountUseNetworkTestNetworkAclTypeIsNotDomainReturnFalse() {
        Mockito.doReturn(ControlledEntity.ACLType.Account).when(networkMock).getAclType();

        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, networkMock);

        Assert.assertFalse(canAccountUseNetwork);
    }

    @Test
    public void canAccountUseNetworkTestNetworkGuestTypeIsNotSharedOrL2ReturnFalse() {
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(Network.GuestType.Isolated).when(networkMock).getGuestType();

        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, networkMock);

        Assert.assertFalse(canAccountUseNetwork);
    }

    @Test
    public void canAccountUseNetworkTestNetworkGuestTypeIsSharedReturnTrue() {
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(Network.GuestType.Shared).when(networkMock).getGuestType();

        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, networkMock);

        Mockito.verify(networkModel).checkNetworkPermissions(accountMock, networkMock);
        Assert.assertTrue(canAccountUseNetwork);
    }

    @Test
    public void canAccountUseNetworkTestNetworkGuestTypeIsL2ReturnTrue() {
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(Network.GuestType.L2).when(networkMock).getGuestType();

        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, networkMock);

        Mockito.verify(networkModel).checkNetworkPermissions(accountMock, networkMock);
        Assert.assertTrue(canAccountUseNetwork);
    }

    @Test
    public void canAccountUseNetworkTestPermissionDeniedExceptionThrownReturnFalse() {
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(Network.GuestType.L2).when(networkMock).getGuestType();

        doThrow(PermissionDeniedException.class).when(networkModel).checkNetworkPermissions(accountMock, networkMock);

        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, networkMock);

        Assert.assertFalse(canAccountUseNetwork);
    }

    @Test
    public void implementNetworkTestImplementedNetworkIsNullReturnCurrentNewNetwork() throws ResourceUnavailableException, InsufficientCapacityException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        NetworkVO currentNetwork = Mockito.mock(NetworkVO.class);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            Mockito.when(CallContext.current()).thenReturn(callContextMock);

            Mockito.doReturn(1l).when(callContextMock).getCallingUserId();

            Mockito.doReturn(callerUser).when(userDao).findById(Mockito.anyLong());
            Mockito.doReturn(null).when(_networkMgr).implementNetwork(Mockito.anyLong(), Mockito.any(), Mockito.any());

            Network newNetwork = userVmManagerImpl.implementNetwork(accountMock, _dcMock, currentNetwork);

            Assert.assertEquals(newNetwork, currentNetwork);
        }
    }

    @Test
    public void implementNetworkTestImplementedNetworkFirstIsNullReturnCurrentNewNetwork() throws ResourceUnavailableException, InsufficientCapacityException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        NetworkVO currentNetwork = Mockito.mock(NetworkVO.class);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            Mockito.when(CallContext.current()).thenReturn(callContextMock);

            Mockito.doReturn(1l).when(callContextMock).getCallingUserId();

            Pair<? extends NetworkGuru, ? extends Network> implementedNetwork = Mockito.mock(Pair.class);

            Mockito.doReturn(callerUser).when(userDao).findById(Mockito.anyLong());
            Mockito.doReturn(null).when(implementedNetwork).first();
            Mockito.doReturn(implementedNetwork).when(_networkMgr).implementNetwork(Mockito.anyLong(), Mockito.any(), Mockito.any());

            Network newNetwork = userVmManagerImpl.implementNetwork(accountMock, _dcMock, currentNetwork);

            Assert.assertEquals(newNetwork, currentNetwork);
        }
    }

    @Test
    public void implementNetworkTestImplementedNetworkSecondIsNullReturnCurrentNewNetwork() throws ResourceUnavailableException, InsufficientCapacityException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        NetworkVO currentNetwork = Mockito.mock(NetworkVO.class);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            Mockito.when(CallContext.current()).thenReturn(callContextMock);

            Mockito.doReturn(1l).when(callContextMock).getCallingUserId();

            Pair<? extends NetworkGuru, ? extends Network> implementedNetwork = Mockito.mock(Pair.class);

            Mockito.doReturn(callerUser).when(userDao).findById(Mockito.anyLong());
            Mockito.doReturn(networkMock).when(implementedNetwork).first();
            Mockito.doReturn(null).when(implementedNetwork).second();
            Mockito.doReturn(implementedNetwork).when(_networkMgr).implementNetwork(Mockito.anyLong(), Mockito.any(), Mockito.any());

            Network newNetwork = userVmManagerImpl.implementNetwork(accountMock, _dcMock, currentNetwork);

            Assert.assertEquals(newNetwork, currentNetwork);
        }
    }

    @Test
    public void implementNetworkTestImplementedNetworkSecondIsNotNullReturnImplementedNetworkSecond() throws ResourceUnavailableException, InsufficientCapacityException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        NetworkVO currentNetwork = Mockito.mock(NetworkVO.class);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            Mockito.when(CallContext.current()).thenReturn(callContextMock);

            Mockito.doReturn(1l).when(callContextMock).getCallingUserId();

            Pair<? extends NetworkGuru, ? extends Network> implementedNetwork = Mockito.mock(Pair.class);

            Mockito.doReturn(callerUser).when(userDao).findById(Mockito.anyLong());
            Mockito.doReturn(networkMock).when(implementedNetwork).first();
            Mockito.doReturn(networkMock).when(implementedNetwork).second();
            Mockito.doReturn(implementedNetwork).when(_networkMgr).implementNetwork(Mockito.anyLong(), Mockito.any(), Mockito.any());

            Network newNetwork = userVmManagerImpl.implementNetwork(accountMock, _dcMock, currentNetwork);

            Assert.assertEquals(newNetwork, networkMock);
        }
    }

    @Test
    public void implementNetworkTestImplementedNetworkCatchException() throws ResourceUnavailableException, InsufficientCapacityException {
        String expectedMessage = String.format("Failed to implement network [%s] elements and resources as a part of network provision.", networkMock);

        CallContext callContextMock = Mockito.mock(CallContext.class);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            Mockito.when(CallContext.current()).thenReturn(callContextMock);

            Mockito.doReturn(1l).when(callContextMock).getCallingUserId();

            Pair<? extends NetworkGuru, ? extends Network> implementedNetwork = Mockito.mock(Pair.class);

            Mockito.doReturn(callerUser).when(userDao).findById(Mockito.anyLong());
            doThrow(InvalidParameterValueException.class).when(_networkMgr).implementNetwork(Mockito.anyLong(), Mockito.any(), Mockito.any());

            CloudRuntimeException assertThrows = Assert.assertThrows(expectedCloudRuntimeException, () -> {
                userVmManagerImpl.implementNetwork(accountMock, _dcMock, networkMock);
            });

            Assert.assertEquals(expectedMessage, assertThrows.getMessage());
        }
    }

    @Test
    public void updateBasicTypeNetworkForVmTestNetworkIdListIsNotEmptyThrowsInvalidParameterValueException() {
        String expectedMessage = "Cannot move VM with Network IDs; this is a basic zone VM.";
        LinkedList<Long> networkIdList = new LinkedList<Long>();
        LinkedList<Long> securityGroupIdList = new LinkedList<Long>();

        networkIdList.add(1l);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.updateBasicTypeNetworkForVm(userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock, networkIdList,
                    securityGroupIdList);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void updateBasicTypeNetworkForVmTestNetworkIdListIsNullCallsCleanupOfOldOwnerNicsForNetworkAddDefaultNetworkToNetworkListAllocateNetworksForVmAndAddSecurityGroupsToVm()
            throws InsufficientCapacityException {

        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);

        Mockito.doReturn(networkMock).when(networkModel).getExclusiveGuestNetwork(Mockito.anyLong());

        userVmManagerImpl.updateBasicTypeNetworkForVm(userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock, null,
                securityGroupIdList);

        Mockito.verify(userVmManagerImpl).cleanupOfOldOwnerNicsForNetwork(virtualMachineProfileMock);
        Mockito.verify(userVmManagerImpl).addDefaultNetworkToNetworkList(anyList(), Mockito.any());
        Mockito.verify(userVmManagerImpl).allocateNetworksForVm(Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).addSecurityGroupsToVm(accountMock, userVmVoMock,virtualMachineTemplateMock, securityGroupIdList, networkMock);
    }

    @Test
    public void updateBasicTypeNetworkForVmTestNetworkIdListIsEmptyCallsCleanupOfOldOwnerNicsForNetworkAddDefaultNetworkToNetworkListAllocateNetworksForVmAndAddSecurityGroupsToVm()
            throws InsufficientCapacityException {

        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<Long>();

        Mockito.doReturn(networkMock).when(networkModel).getExclusiveGuestNetwork(Mockito.anyLong());

        userVmManagerImpl.updateBasicTypeNetworkForVm(userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock, networkIdList,
                securityGroupIdList);

        Mockito.verify(userVmManagerImpl).cleanupOfOldOwnerNicsForNetwork(virtualMachineProfileMock);
        Mockito.verify(userVmManagerImpl).addDefaultNetworkToNetworkList(anyList(), Mockito.any());
        Mockito.verify(userVmManagerImpl).allocateNetworksForVm(Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).addSecurityGroupsToVm(accountMock, userVmVoMock,virtualMachineTemplateMock, securityGroupIdList, networkMock);
    }

    @Test
    public void updateAdvancedTypeNetworkForVmTestSecurityGroupIsEnabledApplicableNetworksIsEmptyThrowsInvalidParameterValueException() {
        String expectedMessage = "No network is specified, please specify one when you move the VM. For now, please add a network to VM on NICs tab.";
        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<Long>();

        Mockito.doReturn(true).when(networkModel).checkSecurityGroupSupportForNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.updateAdvancedTypeNetworkForVm(callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock,
                    _dcMock, networkIdList, securityGroupIdList);
        });

        Mockito.verify(securityGroupManagerMock).removeInstanceFromGroups(Mockito.any());
        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void updateAdvancedTypeNetworkForVmTestSecurityGroupIsEnabledApplicableNetworksIsNotEmptyCallsAllocateNetworksForVm() throws InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<Long>();

        Mockito.doReturn(new NicVO()).when(nicDao).findDefaultNicForVM(Mockito.anyLong());
        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(true).when(userVmManagerImpl).canAccountUseNetwork(accountMock, networkMock);

        Mockito.doReturn(true).when(networkModel).checkSecurityGroupSupportForNetwork(accountMock, _dcMock, networkIdList, securityGroupIdList);

        userVmManagerImpl.updateAdvancedTypeNetworkForVm(callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock,
                networkIdList, securityGroupIdList);

        Mockito.verify(securityGroupManagerMock).removeInstanceFromGroups(Mockito.any());
        Mockito.verify(userVmManagerImpl).allocateNetworksForVm(Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).addSecurityGroupsToVm(accountMock, userVmVoMock, virtualMachineTemplateMock, securityGroupIdList, networkMock);
    }

    @Test
    public void updateAdvancedTypeNetworkForVmTestSecurityGroupIsNotEnabledSecurityGroupIdListIsNotEmptyThrowsInvalidParameterValueException() {
        String expectedMessage = "Cannot move VM with security groups; security group feature is not enabled in this zone.";
        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<Long>();

        securityGroupIdList.add(1l);

        Mockito.doReturn(false).when(networkModel).checkSecurityGroupSupportForNetwork(accountMock, _dcMock, networkIdList, securityGroupIdList);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.updateAdvancedTypeNetworkForVm(callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock,
                    _dcMock, networkIdList, securityGroupIdList);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void updateAdvancedTypeNetworkForVmTestSecurityGroupIsNotEnabledApplicableNetworksIsEmptyCallsSelectApplicableNetworkToCreateVm() throws InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<Long>();

        Mockito.doReturn(networkMock).when(userVmManagerImpl).addNicsToApplicableNetworksAndReturnDefaultNetwork(Mockito.any(), Mockito.anyMap(), Mockito.anyMap(), Mockito.any());
        Mockito.doNothing().when(userVmManagerImpl).selectApplicableNetworkToCreateVm(Mockito.any(), Mockito.any(), Mockito.any());

        Mockito.doReturn(false).when(networkModel).checkSecurityGroupSupportForNetwork(accountMock, _dcMock, networkIdList, securityGroupIdList);
        Mockito.doReturn(true).when(securityGroupIdList).isEmpty();

        userVmManagerImpl.updateAdvancedTypeNetworkForVm(callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock,
                networkIdList, securityGroupIdList);

        Mockito.verify(userVmManagerImpl).addNetworksToNetworkIdList(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyMap(), Mockito.anyMap());
        Mockito.verify(userVmManagerImpl).cleanupOfOldOwnerNicsForNetwork(Mockito.any());
        Mockito.verify(userVmManagerImpl).selectApplicableNetworkToCreateVm(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).addNicsToApplicableNetworksAndReturnDefaultNetwork(Mockito.any(), Mockito.anyMap(), Mockito.anyMap(), Mockito.any());
        Mockito.verify(userVmManagerImpl).allocateNetworksForVm(Mockito.any(), Mockito.any());
    }

    @Test
    public void updateAdvancedTypeNetworkForVmTestSecurityGroupIsNotEnabledApplicableNetworksIsNotEmptyDoesNotCallSelectApplicableNetworkToCreateVm()
            throws InsufficientCapacityException, ResourceAllocationException {

        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<Long>();

        Mockito.doReturn(false).when(networkModel).checkSecurityGroupSupportForNetwork(accountMock, _dcMock, networkIdList, securityGroupIdList);
        Mockito.doReturn(true).when(securityGroupIdList).isEmpty();

        Mockito.doReturn(new NicVO()).when(nicDao).findDefaultNicForVM(Mockito.anyLong());
        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(true).when(userVmManagerImpl).canAccountUseNetwork(accountMock, networkMock);

        userVmManagerImpl.updateAdvancedTypeNetworkForVm(callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock,
                networkIdList, securityGroupIdList);

        Mockito.verify(userVmManagerImpl).addNetworksToNetworkIdList(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyMap(), Mockito.anyMap());
        Mockito.verify(userVmManagerImpl).cleanupOfOldOwnerNicsForNetwork(Mockito.any());
        Mockito.verify(userVmManagerImpl, Mockito.never()).selectApplicableNetworkToCreateVm(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).addNicsToApplicableNetworksAndReturnDefaultNetwork(Mockito.any(), Mockito.anyMap(), Mockito.anyMap(), Mockito.any());
        Mockito.verify(userVmManagerImpl).allocateNetworksForVm(Mockito.any(), Mockito.any());
    }

    @Test
    public void addNicsToApplicableNetworksAndReturnDefaultNetworkTestApplicableNetworkIsEmptyReturnNull() {
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();
        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<NetworkVO>();
        LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<Network, List<? extends NicProfile>>();

        NetworkVO defaultNetwork = userVmManagerImpl.addNicsToApplicableNetworksAndReturnDefaultNetwork(applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics, networks);

        Assert.assertNull(defaultNetwork);
    }

    @Test
    public void addNicsToApplicableNetworksAndReturnDefaultNetworkTestApplicableNetworkIsNotEmptyReturnFirstElement() {
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();
        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<NetworkVO>();
        LinkedHashMap<Network, List<? extends NicProfile>> networks = Mockito.spy(LinkedHashMap.class);

        applicableNetworks.add(networkMock);

        NetworkVO defaultNetwork = userVmManagerImpl.addNicsToApplicableNetworksAndReturnDefaultNetwork(applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics, networks);

        Mockito.verify(networks).put(Mockito.any(), Mockito.any());
        Assert.assertEquals(defaultNetwork, networkMock);
    }

    @Test
    public void addNicsToApplicableNetworksAndReturnDefaultNetworkTestApplicableNetworkIsNotEmptyPutTwoNetworksInNetworksMapAndReturnFirst() {
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<Long, String>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<Long, String>();
        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<NetworkVO>();
        LinkedHashMap<Network, List<? extends NicProfile>> networks = Mockito.spy(LinkedHashMap.class);

        NetworkVO networkVoMock2 = Mockito.mock(NetworkVO.class);
        applicableNetworks.add(networkMock);
        applicableNetworks.add(networkVoMock2);

        NetworkVO defaultNetwork = userVmManagerImpl.addNicsToApplicableNetworksAndReturnDefaultNetwork(applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics, networks);

        Mockito.verify(networks, times(2)).put(Mockito.any(), Mockito.any());
        Assert.assertEquals(defaultNetwork, networkMock);
    }

    @Test
    public void validateIfVolumesHaveNoSnapshotsTestVolumeHasSnapshotsThrowsInvalidParameterException() {
        String expectedMessage = String.format("Snapshots exist for volume [%s]. Detach volume or remove snapshots for the volume before assigning VM to another user.",
                volumeVOMock.getName());

        LinkedList<VolumeVO> volumes = new LinkedList<VolumeVO>();
        volumes.add(volumeVOMock);
        LinkedList<SnapshotVO> snapshots = new LinkedList<SnapshotVO>();
        snapshots.add(snapshotVoMock);

        Mockito.doReturn(snapshots).when(snapshotDaoMock).listByStatusNotIn(Mockito.anyLong(), Mockito.any(), Mockito.any());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVolumesHaveNoSnapshots(volumes);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVolumesHaveNoSnapshotsTestVolumeHasNoSnapshotsDoesNotThrowInvalidParameterException() {
        LinkedList<VolumeVO> volumes = new LinkedList<VolumeVO>();
        volumes.add(volumeVOMock);
        LinkedList<SnapshotVO> snapshots = new LinkedList<SnapshotVO>();

        Mockito.doReturn(snapshots).when(snapshotDaoMock).listByStatusNotIn(Mockito.anyLong(), Mockito.any(), Mockito.any());

        userVmManagerImpl.validateIfVolumesHaveNoSnapshots(volumes);
    }

    @Test
    public void moveVmToUserTestCallerIsNotRootAdminAndDomainAdminThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Only root or domain admins are allowed to assign VMs. Caller [%s] is of type [%s].", callerAccount, callerAccount.getType());

        Mockito.doReturn(false).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(false).when(accountManager).isDomainAdmin(Mockito.anyLong());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.moveVmToUser(assignVmCmdMock);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void moveVmToUserTestValidateVmExistsAndIsNotRunningThrowsInvalidParameterValueException() {
        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());

        doThrow(InvalidParameterValueException.class).when(userVmManagerImpl).validateIfVmSupportsMigration(Mockito.any(), Mockito.anyLong());

        Assert.assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestValidateAccountsAndCallerAccessToThemThrowsInvalidParameterValueException() {
        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());

        Assert.assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestProjectIdProvidedAndDomainIdIsNullThrowsInvalidParameterValueException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        String expectedMessage = "Please provide a valid domain ID; cannot assign VM to a project if domain ID is NULL.";

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(1l).when(assignVmCmdMock).getProjectId();
        Mockito.doReturn(null).when(assignVmCmdMock).getDomainId();

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.moveVmToUser(assignVmCmdMock);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void moveVmToUserTestValidateIfVmHasNoRulesThrowsInvalidParameterValueException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(null).when(assignVmCmdMock).getProjectId();

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        doThrow(InvalidParameterValueException.class).when(userVmManagerImpl).validateIfVmHasNoRules(Mockito.any(), Mockito.anyLong());

        Assert.assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestSnapshotsForVolumeExistThrowsInvalidParameterValueException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<VolumeVO>();
        volumes.add(volumeVOMock);

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(null).when(assignVmCmdMock).getProjectId();
        Mockito.doReturn(volumes).when(volumeDaoMock).findByInstance(Mockito.anyLong());

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        doThrow(InvalidParameterValueException.class).when(userVmManagerImpl).validateIfVolumesHaveNoSnapshots(Mockito.any());

        Assert.assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestVerifyResourceLimitsForAccountAndStorageThrowsResourceAllocationException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<VolumeVO>();

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(null).when(assignVmCmdMock).getProjectId();
        Mockito.doReturn(volumes).when(volumeDaoMock).findByInstance(Mockito.anyLong());

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        doThrow(ResourceAllocationException.class).when(userVmManagerImpl).verifyResourceLimitsForAccountAndStorage(Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        Assert.assertThrows(ResourceAllocationException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestVerifyValidateIfNewOwnerHasAccessToTemplateThrowsInvalidParameterValueException() throws ResourceUnavailableException,
            InsufficientCapacityException, ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<VolumeVO>();

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(null).when(assignVmCmdMock).getProjectId();
        Mockito.doReturn(volumes).when(volumeDaoMock).findByInstance(Mockito.anyLong());

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        doThrow(InvalidParameterValueException.class).when(userVmManagerImpl).validateIfNewOwnerHasAccessToTemplate(Mockito.any(), Mockito.any(), Mockito.any());

        Assert.assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestAccountManagerCheckAccessThrowsPermissionDeniedException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<VolumeVO>();

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(null).when(assignVmCmdMock).getProjectId();
        Mockito.doReturn(volumes).when(volumeDaoMock).findByInstance(Mockito.anyLong());
        Mockito.doReturn(accountMock).when(accountManager).finalizeOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(domainVoMock).when(domainDaoMock).findById(Mockito.anyLong());

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        doThrow(PermissionDeniedException.class).when(accountManager).checkAccess(Mockito.any(Account.class), Mockito.any(Domain.class));

        Assert.assertThrows(PermissionDeniedException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void executeStepsToChangeOwnershipOfVmTestUpdateVmNetworkThrowsInsufficientCapacityException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<VolumeVO>();

        try (MockedStatic<UsageEventUtils> ignored = mockStatic(UsageEventUtils.class)) {
            Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(userVmVoMock).getHypervisorType();

            configureDoNothingForMethodsThatWeDoNotWantToTest();

            doThrow(InsufficientAddressCapacityException.class).when(userVmManagerImpl).updateVmNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any());

            Assert.assertThrows(CloudRuntimeException.class, () -> userVmManagerImpl.executeStepsToChangeOwnershipOfVm(assignVmCmdMock, callerAccount, accountMock, accountMock,
                    userVmVoMock, serviceOfferingVoMock, volumes, virtualMachineTemplateMock, 1l));

            Mockito.verify(userVmManagerImpl).resourceCountDecrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl).updateVmOwner(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVolumesOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong());
        }
    }

    @Test
    public void executeStepsToChangeOwnershipOfVmTestUpdateVmNetworkThrowsResourceAllocationException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<VolumeVO>();

        try (MockedStatic<UsageEventUtils> ignored = mockStatic(UsageEventUtils.class)) {
            Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(userVmVoMock).getHypervisorType();

            configureDoNothingForMethodsThatWeDoNotWantToTest();

            doThrow(ResourceAllocationException.class).when(userVmManagerImpl).updateVmNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any());

            Assert.assertThrows(CloudRuntimeException.class, () -> userVmManagerImpl.executeStepsToChangeOwnershipOfVm(assignVmCmdMock, callerAccount, accountMock, accountMock,
                    userVmVoMock, serviceOfferingVoMock, volumes, virtualMachineTemplateMock, 1l));

            Mockito.verify(userVmManagerImpl).resourceCountDecrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl).updateVmOwner(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVolumesOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong());
        }
    }

    @Test
    public void executeStepsToChangeOwnershipOfVmTestResourceCountRunningVmsOnlyEnabledIsFalseCallsResourceCountIncrement() throws ResourceUnavailableException,
            InsufficientCapacityException, ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<VolumeVO>();


        try (MockedStatic<UsageEventUtils> ignored = mockStatic(UsageEventUtils.class)) {
            Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(userVmVoMock).getHypervisorType();
            Mockito.doReturn(false).when(userVmManagerImpl).isResourceCountRunningVmsOnlyEnabled();

            configureDoNothingForMethodsThatWeDoNotWantToTest();

            userVmManagerImpl.executeStepsToChangeOwnershipOfVm(assignVmCmdMock, callerAccount, accountMock, accountMock, userVmVoMock, serviceOfferingVoMock, volumes,
                    virtualMachineTemplateMock, 1L);

            Mockito.verify(userVmManagerImpl).resourceCountDecrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl).updateVmOwner(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVolumesOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVmNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl).resourceCountIncrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
        }
    }

    @Test
    public void executeStepsToChangeOwnershipOfVmTestResourceCountRunningVmsOnlyEnabledIsTrueDoesNotCallResourceCountIncrement() throws ResourceUnavailableException,
            InsufficientCapacityException, ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<VolumeVO>();

        try (MockedStatic<UsageEventUtils> ignored = mockStatic(UsageEventUtils.class)) {
            Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(userVmVoMock).getHypervisorType();
            Mockito.doReturn(true).when(userVmManagerImpl).isResourceCountRunningVmsOnlyEnabled();

            configureDoNothingForMethodsThatWeDoNotWantToTest();

            userVmManagerImpl.executeStepsToChangeOwnershipOfVm(assignVmCmdMock, callerAccount, accountMock, accountMock, userVmVoMock, serviceOfferingVoMock, volumes,
                    virtualMachineTemplateMock, 1l);

            Mockito.verify(userVmManagerImpl).resourceCountDecrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl).updateVmOwner(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVolumesOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVmNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl, Mockito.never()).resourceCountIncrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
        }
    }

    @Test
    public void validateStorageAccessGroupsOnHostsMatchingSAGsNoException() {
        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(new String[]{"sag1", "sag2"});
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(new String[]{"sag1", "sag2", "sag3"});

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);

        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, srcHost.getId());
        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, destHost.getId());
    }

    @Test(expected = CloudRuntimeException.class)
    public void validateSAGsOnHostsNonMatchingSAGsThrowsException() {
        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(new String[]{"sag1", "sag2"});
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(new String[]{"sag1", "sag3"});

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);
    }

    @Test
    public void validateEmptyStorageAccessGroupOnHosts() {
        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(new String[]{});
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(new String[]{});

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);

        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, srcHost.getId());
        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, destHost.getId());
    }

    @Test
    public void validateSAGsOnHostsNullStorageAccessGroups() {
        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(null);
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(null);

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);

        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, srcHost.getId());
        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, destHost.getId());
    }

    @Test(expected = CloudRuntimeException.class)
    public void validateSAGsOnDestHostNullStorageAccessGroups() {
        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(new String[]{"sag1", "sag2"});
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(null);

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);
    }

    @Test
    public void validateNullStorageAccessGroupsOnSrcHost() {

        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(null);
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(new String[]{"sag1", "sag2"});

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);

        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, srcHost.getId());
        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, destHost.getId());
    }
    @Test
    public void allocateVMFromBackupDelegatesToBackupLifecycleService()
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        when(vmBackupInstanceLifecycleService.allocateVMFromBackup(eq(cmd),
                any(VmBackupInstanceLifecycleService.ManagerOperations.class))).thenReturn(userVmVoMock);

        UserVm result = userVmManagerImpl.allocateVMFromBackup(cmd);

        assertEquals(userVmVoMock, result);
        verify(vmBackupInstanceLifecycleService).allocateVMFromBackup(eq(cmd),
                any(VmBackupInstanceLifecycleService.ManagerOperations.class));
    }

    @Test
    public void restoreVMFromBackupDelegatesToBackupLifecycleService()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        when(vmBackupInstanceLifecycleService.restoreVMFromBackup(eq(cmd),
                any(VmBackupInstanceLifecycleService.ManagerOperations.class))).thenReturn(userVmVoMock);

        UserVm result = userVmManagerImpl.restoreVMFromBackup(cmd);

        assertEquals(userVmVoMock, result);
        verify(vmBackupInstanceLifecycleService).restoreVMFromBackup(eq(cmd),
                any(VmBackupInstanceLifecycleService.ManagerOperations.class));
    }

    public void testDestroyVm() throws ResourceUnavailableException {
        Long volumeId = 4L;
        Long accountId = 5L;
        Long userId = 6L;
        boolean expunge = true;

        ReflectionTestUtils.setField(userVmManagerImpl, "_uuidMgr", uuidMgr);
        CallContext callContext = mock(CallContext.class);
        Account callingAccount = mock(Account.class);
        when(callingAccount.getId()).thenReturn(accountId);
        when(callContext.getCallingAccount()).thenReturn(callingAccount);
        when(accountManager.isAdmin(callingAccount.getId())).thenReturn(true);
        doNothing().when(accountManager).checkApiAccess(callingAccount, BaseCmd.getCommandNameByClass(ExpungeVMCmd.class), null);
        try (MockedStatic<CallContext> mockedCallContext = mockStatic(CallContext.class)) {
            mockedCallContext.when(CallContext::current).thenReturn(callContext);
            mockedCallContext.when(() -> CallContext.register(callContext, ApiCommandResourceType.Volume)).thenReturn(callContext);

            DestroyVMCmd cmd = mock(DestroyVMCmd.class);
            when(cmd.getId()).thenReturn(vmId);
            when(cmd.getExpunge()).thenReturn(expunge);
            List<Long> volumeIds = List.of(volumeId);
            when(cmd.getVolumeIds()).thenReturn(volumeIds);
            AsyncJobVO asyncJobMock = mock(AsyncJobVO.class);
            when(cmd.getJob()).thenReturn(asyncJobMock);
            when(asyncJobMock.getCmdInfo()).thenReturn("{}");

            UserVmVO vm = mock(UserVmVO.class);
            when(vm.getId()).thenReturn(vmId);
            when(vm.getState()).thenReturn(VirtualMachine.State.Running);
            when(vm.getUuid()).thenReturn("vm-uuid");
            when(vm.getUserVmType()).thenReturn("User");
            when(userVmDao.findById(vmId)).thenReturn(vm);

            VolumeVO vol = Mockito.mock(VolumeVO.class);
            when(vol.getInstanceId()).thenReturn(vmId);
            when(vol.getId()).thenReturn(volumeId);
            when(vol.getVolumeType()).thenReturn(Volume.Type.DATADISK);
            when(volumeDaoMock.findById(volumeId)).thenReturn(vol);

            List<VolumeVO> dataVolumes = new ArrayList<>();
            when(volumeDaoMock.findByInstanceAndType(vmId, Volume.Type.DATADISK)).thenReturn(dataVolumes);

            when(volumeApiService.destroyVolume(volumeId, CallContext.current().getCallingAccount(), expunge, false)).thenReturn(vol);

            doReturn(vm).when(userVmManagerImpl).stopVirtualMachine(anyLong(), anyBoolean());
            doReturn(vm).when(userVmManagerImpl).destroyVm(vmId, expunge);
            doReturn(true).when(userVmManagerImpl).expunge(vm);

            try (MockedStatic<UsageEventUtils> mockedUsageEventUtils = mockStatic(UsageEventUtils.class)) {

                UserVm result = userVmManagerImpl.destroyVm(cmd);

                assertNotNull(result);
                assertEquals(vm, result);
                Mockito.verify(userVmManagerImpl).stopVirtualMachine(vmId, false);
                Mockito.verify(backupManager).checkAndRemoveBackupOfferingBeforeExpunge(vm);
            }
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testValidateLeasePropertiesInvalidDuration() {
        userVmManagerImpl.validateLeaseProperties(-2, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testValidateLeasePropertiesNullActionValue() {
        userVmManagerImpl.validateLeaseProperties(20, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testValidateLeasePropertiesNullDurationValue() {
        userVmManagerImpl.validateLeaseProperties(null, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test
    public void testValidateLeasePropertiesMinusOneDuration() {
        userVmManagerImpl.validateLeaseProperties(-1, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testValidateLeasePropertiesZeroDayDuration() {
        userVmManagerImpl.validateLeaseProperties(0, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test
    public void testValidateLeasePropertiesValidValues() {
        userVmManagerImpl.validateLeaseProperties(20, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test
    public void testValidateLeasePropertiesBothNUll() {
        userVmManagerImpl.validateLeaseProperties(null, null);
    }

    @Test
    public void testAddLeaseDetailsForInstance() {
        UserVm userVm = mock(UserVm.class);
        when(userVm.getId()).thenReturn(vmId);
        when(userVm.getUuid()).thenReturn(UUID.randomUUID().toString());
        userVmManagerImpl.addLeaseDetailsForInstance(userVm, 10, VMLeaseManager.ExpiryAction.STOP);
        verify(vmInstanceDetailsDao).addDetail(eq(vmId), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION), eq(VMLeaseManager.ExpiryAction.STOP.name()), anyBoolean());
        verify(vmInstanceDetailsDao).addDetail(eq(vmId), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE), eq(getLeaseExpiryDate(10L)), anyBoolean());
    }

    @Test
    public void testAddNullDurationLeaseDetailsForInstance() {
        UserVm userVm = mock(UserVm.class);
        userVmManagerImpl.addLeaseDetailsForInstance(userVm, null, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).removeDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).removeDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE);
    }

    @Test
    public void testApplyLeaseOnCreateInstanceFeatureEnabled() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);
        when(userVm.getUuid()).thenReturn(UUID.randomUUID().toString());
        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        userVmManagerImpl.applyLeaseOnCreateInstance(userVm, 10, VMLeaseManager.ExpiryAction.DESTROY, svcOfferingMock);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(1)).addDetail(eq(vmId), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION),
                eq(VMLeaseManager.ExpiryAction.DESTROY.name()), anyBoolean());
    }

    @Test
    public void testApplyLeaseOnCreateInstanceNegativeLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        userVmManagerImpl.applyLeaseOnCreateInstance(userVm, -1, VMLeaseManager.ExpiryAction.DESTROY, null);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    public void testApplyLeaseOnCreateInstanceFromSvcOfferingWithoutLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        userVmManagerImpl.applyLeaseOnCreateInstance(userVm, null, VMLeaseManager.ExpiryAction.DESTROY, svcOfferingMock);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    public void testApplyLeaseOnCreateInstanceFromSvcOfferingWithLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);
        when(userVm.getUuid()).thenReturn(UUID.randomUUID().toString());
        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        when(svcOfferingMock.getLeaseDuration()).thenReturn(10);
        userVmManagerImpl.applyLeaseOnCreateInstance(userVm, null, VMLeaseManager.ExpiryAction.DESTROY, svcOfferingMock);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(1)).addDetail(eq(vmId), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION),
                eq(VMLeaseManager.ExpiryAction.DESTROY.name()), anyBoolean());
    }

    @Test
    public void testApplyLeaseOnCreateInstanceNullExpiryAction() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        userVmManagerImpl.applyLeaseOnCreateInstance(userVm, 10, null, svcOfferingMock);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testApplyLeaseOnUpdateInstanceForNoLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(5, VMLeaseManager.LeaseActionExecution.DISABLED.name()));
        userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, 10, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).addDetail(eq(vmId), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION),
                anyString(), anyBoolean());
    }

    @Test
    public void testApplyLeaseOnUpdateInstanceForLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);
        when(userVm.getUuid()).thenReturn(UUID.randomUUID().toString());
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(5, VMLeaseManager.LeaseActionExecution.PENDING.name()));
        userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, 10, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(1)).addDetail(eq(vmId), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION),
                eq(VMLeaseManager.ExpiryAction.STOP.name()), anyBoolean());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testApplyLeaseOnUpdateInstanceForDisabledLeaseInstance() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(5, VMLeaseManager.LeaseActionExecution.DISABLED.name()));
        userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, 10, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).addDetail(eq(vmId), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION),
                anyString(), anyBoolean());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testApplyLeaseOnUpdateInstanceForLeaseExpired() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(-2, VMLeaseManager.LeaseActionExecution.PENDING.name()));
        userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, 10, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).addDetail(anyLong(), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION),
                anyString(), anyBoolean());
    }

    @Test
    public void testApplyLeaseOnUpdateInstanceToRemoveLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);;
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(2, VMLeaseManager.LeaseActionExecution.PENDING.name()));
        try (MockedStatic<ActionEventUtils> ignored = mockStatic(ActionEventUtils.class)) {
            Mockito.when(ActionEventUtils.onActionEvent(Mockito.anyLong(), Mockito.anyLong(),
                    Mockito.anyLong(),
                    Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyLong(), Mockito.anyString())).thenReturn(1L);
            userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, -1, VMLeaseManager.ExpiryAction.STOP);
        }
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).addDetail(eq(vmId), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION),
                anyString(), anyBoolean());
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(1)).
                addDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXECUTION, VMLeaseManager.LeaseActionExecution.DISABLED.name(), false);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testApplyLeaseOnUpdateInstanceToRemoveLeaseForExpired() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(-2, VMLeaseManager.LeaseActionExecution.PENDING.name()));
        userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, -1, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).addDetail(eq(vmId), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION),
                anyString(), anyBoolean());
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).removeDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).removeDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE);
    }

    String getLeaseExpiryDate(long leaseDuration) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime leaseExpiryDateTime = now.plusDays(leaseDuration);
        Date leaseExpiryDate = Date.from(leaseExpiryDateTime.atZone(ZoneOffset.UTC).toInstant());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(leaseExpiryDate);
    }

    Map<String, String> getLeaseDetails(int leaseDuration, String leaseExecution) {

        Map<String, String> leaseDetails = new HashMap<>();
        leaseDetails.put(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, getLeaseExpiryDate(leaseDuration));
        leaseDetails.put(VmDetailConstants.INSTANCE_LEASE_EXECUTION, leaseExecution);
        return leaseDetails;
    }

    @Test
    public void createVirtualMachineWithExistingVolume() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = new DeployVMCmd();
        ReflectionTestUtils.setField(deployVMCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(deployVMCmd, "serviceOfferingId", serviceOfferingId);
        ReflectionTestUtils.setField(deployVMCmd, "volumeId", volumeId);
        deployVMCmd._accountService = accountService;

        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(entityManager.findById(DiskOffering.class, serviceOffering.getId())).thenReturn(smallerDisdkOffering);
        when(entityManager.findByIdIncludingRemoved(VirtualMachineTemplate.class, templateId)).thenReturn(templateMock);
        when(volumeDataFactory.getVolume(volumeId)).thenReturn(volumeInfo);
        when(volumeInfo.getTemplateId()).thenReturn(templateId);
        when(volumeInfo.getInstanceId()).thenReturn(null);
        when(volumeInfo.getDataStore()).thenReturn(primaryDataStore);
        when(primaryDataStore.getScope()).thenReturn(scopeMock);
        when(primaryDataStore.getScope().getScopeType()).thenReturn(ScopeType.ZONE);
        when(templateMock.getTemplateType()).thenReturn(Storage.TemplateType.VNF);
        when(templateMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(templateMock.isDeployAsIs()).thenReturn(false);
        when(templateMock.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateMock.getUserDataId()).thenReturn(null);
        Mockito.doNothing().when(vnfTemplateManager).validateVnfApplianceNics(any(), nullable(List.class), any());
        when(_dcMock.isLocalStorageEnabled()).thenReturn(false);
        when(_dcMock.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any());


        userVmManagerImpl.createVirtualMachine(deployVMCmd);
    }

    @Test
    public void createVirtualMachineWithExistingSnapshot() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = new DeployVMCmd();
        ReflectionTestUtils.setField(deployVMCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(deployVMCmd, "serviceOfferingId", serviceOfferingId);
        ReflectionTestUtils.setField(deployVMCmd, "snapshotId", snashotId);
        deployVMCmd._accountService = accountService;

        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(entityManager.findById(DiskOffering.class, serviceOffering.getId())).thenReturn(smallerDisdkOffering);
        when(snapshotDaoMock.findById(snashotId)).thenReturn(snapshotMock);
        when(entityManager.findByIdIncludingRemoved(VirtualMachineTemplate.class, templateId)).thenReturn(templateMock);
        when(volumeDataFactory.getVolume(volumeId)).thenReturn(volumeInfo);
        when(snapshotMock.getVolumeId()).thenReturn(volumeId);
        when(volumeInfo.getTemplateId()).thenReturn(templateId);
        when(volumeInfo.getInstanceId()).thenReturn(null);
        when(volumeInfo.getDataStore()).thenReturn(primaryDataStore);
        when(primaryDataStore.getScope()).thenReturn(scopeMock);
        when(primaryDataStore.getScope().getScopeType()).thenReturn(ScopeType.ZONE);
        when(templateMock.getTemplateType()).thenReturn(Storage.TemplateType.VNF);
        when(templateMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(templateMock.isDeployAsIs()).thenReturn(false);
        when(templateMock.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateMock.getUserDataId()).thenReturn(null);
        Mockito.doNothing().when(vnfTemplateManager).validateVnfApplianceNics(any(), nullable(List.class), any());
        when(_dcMock.isLocalStorageEnabled()).thenReturn(false);
        when(_dcMock.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any());


        userVmManagerImpl.createVirtualMachine(deployVMCmd);
    }

    @Test
    public void unmanageUserVMDelegatesToVmUnmanageService() {
        Pair<Boolean, String> expected = new Pair<>(true, "Unmanaged successfully");
        when(vmUnmanageService.unmanageUserVM(eq(vmId), nullable(Long.class),
                any(VmUnmanageService.ManagerOperations.class))).thenReturn(expected);

        Pair<Boolean, String> result = userVmManagerImpl.unmanageUserVM(vmId, null);

        assertEquals(expected, result);
        verify(vmUnmanageService).unmanageUserVM(eq(vmId), nullable(Long.class),
                any(VmUnmanageService.ManagerOperations.class));
    }

    @Test
    public void updateVmExtraConfigCleansUpWhenCleanupFlagIsTrue() {
        UserVmVO userVm = mock(UserVmVO.class);
        when(userVm.getUuid()).thenReturn("test-uuid");
        when(userVm.getId()).thenReturn(1L);

        userVmManagerImpl.updateVmExtraConfig(userVm, "someConfig", true);

        verify(vmInstanceDetailsDao, times(1)).removeDetailsWithPrefix(1L, ApiConstants.EXTRA_CONFIG);
        verifyNoMoreInteractions(vmInstanceDetailsDao);
    }

    @Test
    public void updateVmExtraConfigAddsConfigWhenValidAndEnabled() {
        UserVmVO userVm = mock(UserVmVO.class);
        when(userVm.getUuid()).thenReturn("test-uuid");
        when(userVm.getAccountId()).thenReturn(1L);
        when(userVm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        doNothing().when(userVmManagerImpl).persistExtraConfigKvm(anyString(), eq(userVm));
        updateDefaultConfigValue(UserVmManagerImpl.EnableAdditionalVmConfig, true, false);

        userVmManagerImpl.updateVmExtraConfig(userVm, "validConfig", false);

        verify(vmInstanceDetailsDao, never()).removeDetailsWithPrefix(anyLong(), anyString());
        verify(userVmManagerImpl, times(1)).addExtraConfig(userVm, "validConfig");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateVmExtraConfigThrowsExceptionWhenConfigDisabled() {
        UserVmVO userVm = mock(UserVmVO.class);
        when(userVm.getAccountId()).thenReturn(1L);
        updateDefaultConfigValue(UserVmManagerImpl.EnableAdditionalVmConfig, false, false);

        userVmManagerImpl.updateVmExtraConfig(userVm, "validConfig", false);
    }

    @Test
    public void updateVmExtraConfigDoesNothingWhenExtraConfigIsBlank() {
        UserVmVO userVm = mock(UserVmVO.class);

        userVmManagerImpl.updateVmExtraConfig(userVm, "", false);

        verify(vmInstanceDetailsDao, never()).removeDetailsWithPrefix(anyLong(), anyString());
        verify(userVmManagerImpl, never()).addExtraConfig(any(UserVmVO.class), anyString());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateVirtualMachineNicTestInvalidNicThrowInvalidParameterValueException() {
        Long invalidId = -1L;
        Mockito.doReturn(invalidId).when(updateVmNicCmd).getNicId();

        userVmManagerImpl.updateVirtualMachineNic(updateVmNicCmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateVirtualMachineNicTestInvalidNicUserVmThrowInvalidParameterValueException() {
        Mockito.doReturn(nicId).when(updateVmNicCmd).getNicId();
        Mockito.doReturn(nicMock).when(nicDao).findById(nicId);

        userVmManagerImpl.updateVirtualMachineNic(updateVmNicCmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateVirtualMachineNicTestInvalidNicNetworkThrowInvalidParameterValueException() {
        Mockito.doReturn(nicId).when(updateVmNicCmd).getNicId();
        Mockito.doReturn(true).when(updateVmNicCmd).isEnabled();
        Mockito.doReturn(nicMock).when(nicDao).findById(nicId);
        Mockito.doReturn(vmId).when(nicMock).getInstanceId();
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(vmId);

        userVmManagerImpl.updateVirtualMachineNic(updateVmNicCmd);
    }

    @Test(expected = CloudRuntimeException.class)
    public void updateVirtualMachineNicTestInvalidNicNetworkThrowCloudRuntimeException() {
        Mockito.doReturn(nicId).when(updateVmNicCmd).getNicId();
        Mockito.doReturn(true).when(updateVmNicCmd).isEnabled();
        Mockito.doReturn(nicMock).when(nicDao).findById(nicId);
        Mockito.doReturn(vmId).when(nicMock).getInstanceId();
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(vmId);

        userVmManagerImpl.updateVirtualMachineNic(updateVmNicCmd);
    }

    @Test
    public void updateVirtualMachineNicTestValidInputReturnNicUserVm() throws ResourceUnavailableException {
        Mockito.doReturn(nicId).when(updateVmNicCmd).getNicId();
        Mockito.doReturn(true).when(updateVmNicCmd).isEnabled();
        Mockito.doReturn(nicMock).when(nicDao).findById(nicId);
        Mockito.doReturn(vmId).when(nicMock).getInstanceId();
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(vmId);
        Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(userVmVoMock).getHypervisorType();
        Mockito.doReturn(networkId).when(nicMock).getNetworkId();
        Mockito.doReturn(networkMock).when(_networkDao).findById(networkId);
        Mockito.doReturn(true).when(virtualMachineManager).updateVmNic(Mockito.any(), Mockito.any(), Mockito.any());

        UserVm result = userVmManagerImpl.updateVirtualMachineNic(updateVmNicCmd);

        Assert.assertNotNull(result);
    }

    private ServiceOfferingVO getMockedServiceOffering(boolean custom, boolean customSpeed) {
        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        when(serviceOffering.getUuid()).thenReturn("offering-uuid");
        when(serviceOffering.isDynamic()).thenReturn(custom);
        when(serviceOffering.isCustomCpuSpeedSupported()).thenReturn(customSpeed);
        if (custom) {
            when(serviceOffering.getCpu()).thenReturn(null);
            when(serviceOffering.getRamSize()).thenReturn(null);
        }
        if (customSpeed) {
            when(serviceOffering.getSpeed()).thenReturn(null);
        } else {
            when(serviceOffering.isCustomCpuSpeedSupported()).thenReturn(false);
            when(serviceOffering.getSpeed()).thenReturn(1000);
        }
        return serviceOffering;
    }

    @Test
    public void customOfferingNeedsCustomizationThrowsException() {
        ServiceOfferingVO serviceOffering = getMockedServiceOffering(true, true);
        InvalidParameterValueException ex = Assert.assertThrows(InvalidParameterValueException.class, () ->
                userVmManagerImpl.validateCustomParameters(serviceOffering, Collections.emptyMap()));
        assertEquals("Need to specify custom parameter values cpu, cpu speed and memory when using custom offering", ex.getMessage());
    }

    @Test
    public void cpuSpeedCustomizationNotAllowedThrowsException() {
        ServiceOfferingVO serviceOffering = getMockedServiceOffering(true, false);

        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.CPU_NUMBER, "1");
        customParameters.put(VmDetailConstants.CPU_SPEED, "2500");

        InvalidParameterValueException ex = Assert.assertThrows(InvalidParameterValueException.class, () ->
                userVmManagerImpl.validateCustomParameters(serviceOffering, customParameters));
        Assert.assertTrue(ex.getMessage().startsWith("The CPU speed of this offering"));
    }

    @Test
    public void cpuSpeedCustomizationAllowedDoesNotThrowException() {
        ServiceOfferingVO serviceOffering = getMockedServiceOffering(true, true);

        when(serviceOfferingDetailsDao.listDetailsKeyPairs(anyLong())).thenReturn(
                Map.of(ApiConstants.MIN_CPU_NUMBER, "1",
                        ApiConstants.MAX_CPU_NUMBER, "4",
                        ApiConstants.MIN_MEMORY, "256",
                        ApiConstants.MAX_MEMORY, "8192"));

        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.CPU_NUMBER, "1");
        customParameters.put(VmDetailConstants.CPU_SPEED, "2500");
        customParameters.put(VmDetailConstants.MEMORY, "256");

        userVmManagerImpl.validateCustomParameters(serviceOffering, customParameters);
    }

    @Test
    public void verifyVmLimits_fixedOffering_throwsException() {
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.CPU_SPEED, "2500");
        InvalidParameterValueException expected = new InvalidParameterValueException("CPU number, Memory and CPU speed cannot be updated for a non-dynamic offering");
        doThrow(expected).when(vmUpdateOrchestrationService).verifyVmLimits(userVmVoMock, customParameters);

        InvalidParameterValueException ex = Assert.assertThrows(InvalidParameterValueException.class, () ->
                userVmManagerImpl.verifyVmLimits(userVmVoMock, customParameters));
        assertSame(expected, ex);
    }

    @Test
    public void verifyVmLimits_constrainedOffering_throwsException() {
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.CPU_NUMBER, "1");
        customParameters.put(VmDetailConstants.CPU_SPEED, "2500");
        InvalidParameterValueException expected = new InvalidParameterValueException("The CPU speed of this offering must be between 1 and 2");
        doThrow(expected).when(vmUpdateOrchestrationService).verifyVmLimits(userVmVoMock, customParameters);

        InvalidParameterValueException ex = Assert.assertThrows(InvalidParameterValueException.class, () ->
                userVmManagerImpl.verifyVmLimits(userVmVoMock, customParameters));
        assertSame(expected, ex);
    }

    @Test
    public void checkHostsDedicationDelegatesToMigrationDedicationService() {
        VMInstanceVO vm = new VMInstanceVO();

        userVmManagerImpl.checkHostsDedication(vm, 1L, 2L);

        verify(vmMigrationDedicationService).checkHostsDedication(vm, 1L, 2L);
    }
}
