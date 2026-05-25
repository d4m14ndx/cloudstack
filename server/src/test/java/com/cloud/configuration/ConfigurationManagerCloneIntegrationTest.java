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

import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.Networks;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.DomainHelper;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.VirtualMachine;
import com.cloud.gpu.dao.VgpuProfileDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.network.CloneNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CloneDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CloneServiceOfferingCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ConfigurationManagerCloneIntegrationTest {

    @InjectMocks
    @Spy
    private ConfigurationManagerImpl configurationManager;

    @Mock
    private ServiceOfferingDao serviceOfferingDao;

    @Mock
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;

    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    private DiskOfferingDetailsDao diskOfferingDetailsDao;

    @Mock
    private NetworkOfferingDao networkOfferingDao;

    @Mock
    private NetworkOfferingServiceMapDao networkOfferingServiceMapDao;

    @Mock
    private DomainDao domainDao;

    @Mock
    private DataCenterDao dataCenterDao;

    @Mock
    private EntityManager entityManager;

    @Mock
    private com.cloud.network.NetworkModel _networkModel;

    @Mock
    private com.cloud.offerings.dao.NetworkOfferingDetailsDao networkOfferingDetailsDao;

    @Mock
    private VgpuProfileDao vgpuProfileDao;

    @Mock
    private AccountDao accountDao;

    @Mock
    private UserDao userDao;

    @Mock
    private DomainHelper domainHelper;

    @Mock
    private DiskOfferingService diskOfferingService;

    private MockedStatic<CallContext> callContextMock;

    // Phase 4: spy on the real NetworkOfferingServiceImpl so tests can stub
    // createNetworkOffering without relying on configurationManager's method.
    NetworkOfferingServiceImpl networkOfferingServiceSpy;

    @Before
    public void setUp() {
        callContextMock = Mockito.mockStatic(CallContext.class);
        CallContext callContext = mock(CallContext.class);
        callContextMock.when(CallContext::current).thenReturn(callContext);

        AccountVO account = mock(AccountVO.class);
        User user = mock(User.class);
        Domain domain = mock(DomainVO.class);
        UserVO userVO = mock(UserVO.class);

        Mockito.lenient().when(callContext.getCallingAccount()).thenReturn(account);
        Mockito.lenient().when(callContext.getCallingUser()).thenReturn(user);
        Mockito.lenient().when(callContext.getCallingUserId()).thenReturn(1L);
        Mockito.lenient().when(account.getDomainId()).thenReturn(1L);
        Mockito.lenient().when(account.getId()).thenReturn(1L);
        Mockito.lenient().when(user.getId()).thenReturn(1L);
        Mockito.lenient().when(entityManager.findById(eq(Domain.class), anyLong())).thenReturn(domain);

        // User/Account DAO stubs used by createDiskOffering
        Mockito.lenient().when(userDao.findById(anyLong())).thenReturn(userVO);
        Mockito.lenient().when(userVO.getAccountId()).thenReturn(1L);
        Mockito.lenient().when(userVO.getRemoved()).thenReturn(null);
        Mockito.lenient().when(accountDao.findById(anyLong())).thenReturn(account);
        Mockito.lenient().when(account.getType()).thenReturn(Account.Type.ADMIN);

        // Phase 4: wire a SPY on NetworkOfferingServiceImpl so that cloneNetworkOffering
        // and related delegates reach the real implementation backed by the DAO mocks,
        // and individual tests can stub createNetworkOffering on the spy.
        NetworkOfferingServiceImpl networkOfferingServiceImpl = new NetworkOfferingServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_networkOfferingDao", networkOfferingDao);
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "networkOfferingJoinDao", Mockito.mock(com.cloud.api.query.dao.NetworkOfferingJoinDao.class));
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "networkOfferingDetailsDao", networkOfferingDetailsDao);
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_ntwkOffServiceMapDao", networkOfferingServiceMapDao);
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_physicalNetworkDao", Mockito.mock(com.cloud.network.dao.PhysicalNetworkDao.class));
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_zoneDao", dataCenterDao);
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_domainDao", domainDao);
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_networkDao", Mockito.mock(com.cloud.network.dao.NetworkDao.class));
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_configDao", Mockito.mock(org.apache.cloudstack.framework.config.dao.ConfigurationDao.class));
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_entityMgr", entityManager);
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "annotationDao", Mockito.mock(org.apache.cloudstack.annotation.dao.AnnotationDao.class));
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_accountMgr", Mockito.mock(com.cloud.user.AccountManager.class));
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_vpcMgr", Mockito.mock(com.cloud.network.vpc.VpcManager.class));
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_networkSvc", Mockito.mock(com.cloud.network.NetworkService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "_networkModel", _networkModel);
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "messageBus", Mockito.mock(org.apache.cloudstack.framework.messagebus.MessageBus.class));
        org.springframework.test.util.ReflectionTestUtils.setField(networkOfferingServiceImpl, "domainHelper", domainHelper);
        networkOfferingServiceSpy = Mockito.spy(networkOfferingServiceImpl);
        org.springframework.test.util.ReflectionTestUtils.setField(configurationManager, "networkOfferingService", networkOfferingServiceSpy);

        OfferingCloneParameterServiceImpl offeringCloneParameterServiceImpl = new OfferingCloneParameterServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(offeringCloneParameterServiceImpl, "_serviceOfferingDao", serviceOfferingDao);
        org.springframework.test.util.ReflectionTestUtils.setField(offeringCloneParameterServiceImpl, "_serviceOfferingDetailsDao", serviceOfferingDetailsDao);
        org.springframework.test.util.ReflectionTestUtils.setField(offeringCloneParameterServiceImpl, "_diskOfferingDao", diskOfferingDao);
        org.springframework.test.util.ReflectionTestUtils.setField(offeringCloneParameterServiceImpl, "diskOfferingDetailsDao", diskOfferingDetailsDao);
        org.springframework.test.util.ReflectionTestUtils.setField(configurationManager, "offeringCloneParameterService", offeringCloneParameterServiceImpl);
        org.springframework.test.util.ReflectionTestUtils.setField(configurationManager, "diskOfferingService", diskOfferingService);
    }

    @After
    public void tearDown() {
        if (callContextMock != null) {
            callContextMock.close();
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCloneServiceOfferingFailsWhenSourceNotFound() {
        CloneServiceOfferingCmd cmd = mock(CloneServiceOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(999L);
        when(cmd.getServiceOfferingName()).thenReturn("cloned-offering");
        when(serviceOfferingDao.findById(999L)).thenReturn(null);

        configurationManager.cloneServiceOffering(cmd);
    }

    @Test
    public void testCloneServiceOfferingInheritsAllPropertiesFromSource() {
        Long sourceId = 1L;

        ServiceOfferingVO sourceOffering = mock(ServiceOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(sourceId);
        when(sourceOffering.getName()).thenReturn("source-offering");
        when(sourceOffering.getDisplayText()).thenReturn("Source Display Text");
        when(sourceOffering.getCpu()).thenReturn(2);
        when(sourceOffering.getSpeed()).thenReturn(1000);
        when(sourceOffering.getRamSize()).thenReturn(2048);
        when(sourceOffering.isOfferHA()).thenReturn(true);
        when(sourceOffering.getLimitCpuUse()).thenReturn(false);
        when(sourceOffering.isVolatileVm()).thenReturn(false);
        when(sourceOffering.isCustomized()).thenReturn(false);
        when(sourceOffering.isDynamicScalingEnabled()).thenReturn(true);
        when(sourceOffering.getDiskOfferingStrictness()).thenReturn(false);
        when(sourceOffering.getHostTag()).thenReturn("host-tag");
        when(sourceOffering.getRateMbps()).thenReturn(100);
        when(sourceOffering.getDeploymentPlanner()).thenReturn("FirstFitPlanner");
        when(sourceOffering.isSystemUse()).thenReturn(false);
        when(sourceOffering.getVmType()).thenReturn(VirtualMachine.Type.User.toString());
        when(sourceOffering.getDiskOfferingId()).thenReturn(null);
        when(sourceOffering.getVgpuProfileId()).thenReturn(null);
        when(sourceOffering.getGpuCount()).thenReturn(null);
        when(sourceOffering.getGpuDisplay()).thenReturn(false);

        CloneServiceOfferingCmd cmd = mock(CloneServiceOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(sourceId);
        when(cmd.getServiceOfferingName()).thenReturn("cloned-offering");
        when(cmd.getFullUrlParams()).thenReturn(new HashMap<>());
        // Ensure no vGPU is specified in the command (explicitly stub to null)
        when(cmd.getVgpuProfileId()).thenReturn(null);
        when(cmd.getGpuCount()).thenReturn(null);

        when(serviceOfferingDao.findById(sourceId)).thenReturn(sourceOffering);

        ServiceOfferingVO clonedOffering = mock(ServiceOfferingVO.class);
        when(clonedOffering.getId()).thenReturn(2L);
        when(clonedOffering.getName()).thenReturn("cloned-offering");
        when(clonedOffering.getCpu()).thenReturn(2);
        when(clonedOffering.getSpeed()).thenReturn(1000);
        when(clonedOffering.getRamSize()).thenReturn(2048);

        when(serviceOfferingDao.persist(any(ServiceOfferingVO.class))).thenReturn(clonedOffering);

        DiskOfferingVO persistedDisk = mock(DiskOfferingVO.class);
        when(persistedDisk.getId()).thenReturn(999L);
        when(diskOfferingDao.findById(anyLong())).thenReturn(persistedDisk);
        when(persistedDisk.getProvisioningType()).thenReturn(Storage.ProvisioningType.THIN);
        when(diskOfferingDao.persist(any(DiskOfferingVO.class))).thenReturn(persistedDisk);

        Mockito.doReturn(clonedOffering).when(configurationManager).createServiceOffering(
            anyLong(), anyBoolean(), any(VirtualMachine.Type.class), any(),
            any(), any(), any(), any(), any(), anyBoolean(),
            anyBoolean(), anyBoolean(), anyBoolean(), any(), anyList(), anyList(), any(), any(),
            any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
            any(), any(), any(), anyBoolean(), any(), any()
        );

        ServiceOffering result = configurationManager.cloneServiceOffering(cmd);

        Assert.assertNotNull("Cloned offering should not be null", result);
        verify(serviceOfferingDao).findById(sourceId);
        Assert.assertEquals("Cloned offering should have correct name", "cloned-offering", result.getName());
        Assert.assertEquals("Cloned offering should inherit CPU count", Integer.valueOf(2), result.getCpu());
        Assert.assertEquals("Cloned offering should inherit CPU speed", Integer.valueOf(1000), result.getSpeed());
        Assert.assertEquals("Cloned offering should inherit RAM", Integer.valueOf(2048), result.getRamSize());
    }

    @Test
    public void testCloneServiceOfferingOverridesProvidedParameters() {
        Long sourceId = 1L;

        ServiceOfferingVO sourceOffering = mock(ServiceOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(sourceId);
        when(sourceOffering.getName()).thenReturn("source-offering");
        when(sourceOffering.getDisplayText()).thenReturn("Source Display Text");
        when(sourceOffering.getCpu()).thenReturn(2);
        when(sourceOffering.getSpeed()).thenReturn(1000);
        when(sourceOffering.getRamSize()).thenReturn(2048);
        when(sourceOffering.isOfferHA()).thenReturn(true);
        when(sourceOffering.getLimitCpuUse()).thenReturn(false);
        when(sourceOffering.isVolatileVm()).thenReturn(false);
        when(sourceOffering.isCustomized()).thenReturn(false);
        when(sourceOffering.isDynamicScalingEnabled()).thenReturn(true);
        when(sourceOffering.getDiskOfferingStrictness()).thenReturn(false);
        when(sourceOffering.isSystemUse()).thenReturn(false);
        when(sourceOffering.getVmType()).thenReturn(VirtualMachine.Type.User.toString());
        when(sourceOffering.getDiskOfferingId()).thenReturn(1L);
        when(sourceOffering.getVgpuProfileId()).thenReturn(null);
        when(sourceOffering.getGpuCount()).thenReturn(null);

        CloneServiceOfferingCmd cmd = mock(CloneServiceOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(sourceId);
        when(cmd.getServiceOfferingName()).thenReturn("cloned-offering");
        when(cmd.getDisplayText()).thenReturn("New Display Text");
        when(cmd.getCpuNumber()).thenReturn(4);
        when(cmd.getCpuSpeed()).thenReturn(2000);
        when(cmd.getMemory()).thenReturn(4096);
        when(cmd.getVgpuProfileId()).thenReturn(null);
        when(cmd.getGpuCount()).thenReturn(null);
        when(cmd.getDiskOfferingId()).thenReturn(null);

        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        when(diskOfferingDao.findById(1L)).thenReturn(diskOffering);
        when(diskOffering.getProvisioningType()).thenReturn(Storage.ProvisioningType.THIN);

        Map<String, String> params = new HashMap<>();
        params.put(ApiConstants.OFFER_HA, "false");
        when(cmd.getFullUrlParams()).thenReturn(params);
        when(cmd.isOfferHa()).thenReturn(false);

        when(serviceOfferingDao.findById(sourceId)).thenReturn(sourceOffering);

        ServiceOfferingVO clonedOffering = mock(ServiceOfferingVO.class);
        when(clonedOffering.getId()).thenReturn(2L);
        when(clonedOffering.getName()).thenReturn("cloned-offering");
        when(clonedOffering.getDisplayText()).thenReturn("New Display Text");
        when(clonedOffering.getCpu()).thenReturn(4);
        when(clonedOffering.getSpeed()).thenReturn(2000);
        when(clonedOffering.getRamSize()).thenReturn(4096);
        when(clonedOffering.isOfferHA()).thenReturn(false);

        when(serviceOfferingDao.persist(any(ServiceOfferingVO.class))).thenReturn(clonedOffering);

        Mockito.doReturn(clonedOffering).when(configurationManager).createServiceOffering(
            anyLong(), anyBoolean(), any(), any(), eq(4), eq(4096), eq(2000),
            any(), any(), anyBoolean(), eq(false), anyBoolean(), anyBoolean(),
            any(), anyList(), anyList(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), anyBoolean(), any(),
            anyBoolean(), anyBoolean(), anyBoolean(), any(), any(), any(),
            anyBoolean(), any(), any());

        ServiceOffering result = configurationManager.cloneServiceOffering(cmd);

        Assert.assertNotNull("Cloned offering should not be null", result);
        verify(serviceOfferingDao).findById(sourceId);
        Assert.assertEquals("Cloned offering should override display text", "New Display Text", result.getDisplayText());
        Assert.assertEquals("Cloned offering should override CPU count", Integer.valueOf(4), result.getCpu());
        Assert.assertEquals("Cloned offering should override CPU speed", Integer.valueOf(2000), result.getSpeed());
        Assert.assertEquals("Cloned offering should override RAM", Integer.valueOf(4096), result.getRamSize());
        Assert.assertEquals("Cloned offering should override HA", Boolean.FALSE, result.isOfferHA());
    }

    @Test
    public void testCloneDiskOfferingDelegatesToDiskOfferingService() {
        CloneDiskOfferingCmd cmd = mock(CloneDiskOfferingCmd.class);
        DiskOffering expected = mock(DiskOffering.class);
        when(diskOfferingService.cloneDiskOffering(cmd)).thenReturn(expected);

        DiskOffering result = configurationManager.cloneDiskOffering(cmd);

        Assert.assertSame(expected, result);
        verify(diskOfferingService).cloneDiskOffering(cmd);
    }

    @Test
    public void testCloneServiceOfferingCanInheritDetailsFromSource() {
        Long sourceId = 1L;

        ServiceOfferingVO sourceOffering = mock(ServiceOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(sourceId);
        when(sourceOffering.getCpu()).thenReturn(2);
        when(sourceOffering.getSpeed()).thenReturn(1000);
        when(sourceOffering.getRamSize()).thenReturn(2048);
        when(sourceOffering.isSystemUse()).thenReturn(false);
        when(sourceOffering.getVmType()).thenReturn(VirtualMachine.Type.User.toString());
        when(sourceOffering.getVgpuProfileId()).thenReturn(null);
        when(sourceOffering.getGpuCount()).thenReturn(null);

        CloneServiceOfferingCmd cmd = mock(CloneServiceOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(sourceId);
        when(cmd.getServiceOfferingName()).thenReturn("cloned-offering");
        when(cmd.getFullUrlParams()).thenReturn(new HashMap<>());
        when(cmd.getDetails()).thenReturn(null);
        when(cmd.getVgpuProfileId()).thenReturn(null);
        when(cmd.getGpuCount()).thenReturn(null);

        when(serviceOfferingDao.findById(sourceId)).thenReturn(sourceOffering);
        DiskOfferingVO diskOfferingVO = mock(DiskOfferingVO.class);
        when(diskOfferingDao.findById(anyLong())).thenReturn(diskOfferingVO);
        when(diskOfferingVO.getProvisioningType()).thenReturn(Storage.ProvisioningType.THIN);

        ServiceOfferingVO clonedOffering = mock(ServiceOfferingVO.class);
        when(clonedOffering.getId()).thenReturn(2L);
        when(serviceOfferingDao.persist(any(ServiceOfferingVO.class))).thenReturn(clonedOffering);

        Mockito.doReturn(clonedOffering).when(configurationManager).createServiceOffering(
            anyLong(), anyBoolean(), any(), any(), any(), any(), any(),
            any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
            any(), anyList(), anyList(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), anyBoolean(), any(),
            anyBoolean(), anyBoolean(), anyBoolean(), any(), any(), any(),
            anyBoolean(), any(), any());

        ServiceOffering result = configurationManager.cloneServiceOffering(cmd);

        Assert.assertNotNull("Cloned offering should not be null", result);
        verify(serviceOfferingDao).findById(sourceId);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCloneNetworkOfferingFailsWhenSourceNotFound() {
        CloneNetworkOfferingCmd cmd = mock(CloneNetworkOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(999L);
        when(cmd.getNetworkOfferingName()).thenReturn("cloned-network-offering");
        when(networkOfferingDao.findById(999L)).thenReturn(null);

        configurationManager.cloneNetworkOffering(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCloneNetworkOfferingFailsWhenNameIsNull() {
        CloneNetworkOfferingCmd cmd = mock(CloneNetworkOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(1L);
        when(cmd.getNetworkOfferingName()).thenReturn(null);

        NetworkOfferingVO sourceOffering = mock(NetworkOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(1L);
        when(networkOfferingDao.findById(1L)).thenReturn(sourceOffering);

        configurationManager.cloneNetworkOffering(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCloneNetworkOfferingFailsWhenNameAlreadyExists() {
        CloneNetworkOfferingCmd cmd = mock(CloneNetworkOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(1L);
        when(cmd.getNetworkOfferingName()).thenReturn("existing-offering");

        NetworkOfferingVO sourceOffering = mock(NetworkOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(1L);
        when(sourceOffering.getName()).thenReturn("source-offering");

        NetworkOfferingVO existingOffering = mock(NetworkOfferingVO.class);
        when(existingOffering.getId()).thenReturn(2L);

        when(networkOfferingDao.findById(1L)).thenReturn(sourceOffering);
        when(networkOfferingDao.findByUniqueName("existing-offering")).thenReturn(existingOffering);

        configurationManager.cloneNetworkOffering(cmd);
    }

    @Test
    public void testCloneNetworkOfferingInheritsAllPropertiesFromSource() {
        Long sourceId = 1L;

        NetworkOfferingVO sourceOffering = mock(NetworkOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(sourceId);
        when(sourceOffering.getName()).thenReturn("source-network-offering");
        when(sourceOffering.getDisplayText()).thenReturn("Source Network Offering");
        when(sourceOffering.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(sourceOffering.getTrafficType()).thenReturn(Networks.TrafficType.Guest);
        when(sourceOffering.getAvailability()).thenReturn(NetworkOffering.Availability.Optional);
        when(sourceOffering.getState()).thenReturn(NetworkOffering.State.Enabled);
        when(sourceOffering.isDefault()).thenReturn(false);
        when(sourceOffering.isConserveMode()).thenReturn(true);
        when(sourceOffering.isEgressDefaultPolicy()).thenReturn(false);
        when(sourceOffering.isPersistent()).thenReturn(false);

        CloneNetworkOfferingCmd cmd = mock(CloneNetworkOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(sourceId);
        when(cmd.getNetworkOfferingName()).thenReturn("cloned-network-offering");

        when(networkOfferingDao.findById(sourceId)).thenReturn(sourceOffering);
        when(networkOfferingDao.findByUniqueName("cloned-network-offering")).thenReturn(null);

        // Mock the network model to return service provider map
        Map<Network.Service, java.util.Set<Network.Provider>> serviceProviderMap = new HashMap<>();
        when(configurationManager._networkModel.getNetworkOfferingServiceProvidersMap(sourceId))
            .thenReturn(serviceProviderMap);

        NetworkOfferingVO clonedOffering = mock(NetworkOfferingVO.class);
        when(clonedOffering.getId()).thenReturn(2L);
        when(clonedOffering.getName()).thenReturn("cloned-network-offering");
        when(clonedOffering.getDisplayText()).thenReturn("Source Network Offering");
        when(clonedOffering.getGuestType()).thenReturn(Network.GuestType.Isolated);

        Mockito.doReturn(clonedOffering).when(networkOfferingServiceSpy).createNetworkOffering(any(org.apache.cloudstack.api.command.admin.network.NetworkOfferingBaseCmd.class));

        NetworkOffering result = configurationManager.cloneNetworkOffering(cmd);

        Assert.assertNotNull("Cloned network offering should not be null", result);
        verify(networkOfferingDao).findById(sourceId);
        Assert.assertEquals("Should have correct name", "cloned-network-offering", result.getName());
    }

    @Test
    public void testCloneNetworkOfferingOverridesDisplayText() {
        Long sourceId = 1L;

        NetworkOfferingVO sourceOffering = mock(NetworkOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(sourceId);
        when(sourceOffering.getName()).thenReturn("source-network-offering");
        when(sourceOffering.getDisplayText()).thenReturn("Source Network Offering");
        when(sourceOffering.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(sourceOffering.getTrafficType()).thenReturn(Networks.TrafficType.Guest);

        CloneNetworkOfferingCmd cmd = mock(CloneNetworkOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(sourceId);
        when(cmd.getNetworkOfferingName()).thenReturn("cloned-network-offering");
        when(cmd.getDisplayText()).thenReturn("New Display Text for Network");

        when(networkOfferingDao.findById(sourceId)).thenReturn(sourceOffering);
        when(networkOfferingDao.findByUniqueName("cloned-network-offering")).thenReturn(null);

        Map<Network.Service, java.util.Set<Network.Provider>> serviceProviderMap = new HashMap<>();
        when(configurationManager._networkModel.getNetworkOfferingServiceProvidersMap(sourceId))
            .thenReturn(serviceProviderMap);

        NetworkOfferingVO clonedOffering = mock(NetworkOfferingVO.class);
        when(clonedOffering.getId()).thenReturn(2L);
        when(clonedOffering.getName()).thenReturn("cloned-network-offering");
        when(clonedOffering.getDisplayText()).thenReturn("New Display Text for Network");

        Mockito.doReturn(clonedOffering).when(networkOfferingServiceSpy).createNetworkOffering(any(org.apache.cloudstack.api.command.admin.network.NetworkOfferingBaseCmd.class));

        NetworkOffering result = configurationManager.cloneNetworkOffering(cmd);

        Assert.assertNotNull("Cloned network offering should not be null", result);
        Assert.assertEquals("Should override display text", "New Display Text for Network", result.getDisplayText());
        verify(networkOfferingDao).findById(sourceId);
    }

    @Test
    public void testCloneNetworkOfferingHandlesAddServices() {
        Long sourceId = 1L;

        NetworkOfferingVO sourceOffering = mock(NetworkOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(sourceId);
        when(sourceOffering.getName()).thenReturn("source-network-offering");
        when(sourceOffering.getDisplayText()).thenReturn("Source Network Offering");
        when(sourceOffering.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(sourceOffering.getTrafficType()).thenReturn(Networks.TrafficType.Guest);

        CloneNetworkOfferingCmd cmd = mock(CloneNetworkOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(sourceId);
        when(cmd.getNetworkOfferingName()).thenReturn("cloned-network-offering");

        List<String> addServices = new ArrayList<>();
        addServices.add("Vpn");
        addServices.add("StaticNat");
        when(cmd.getAddServices()).thenReturn(addServices);
        when(cmd.getSupportedServices()).thenReturn(null);

        when(networkOfferingDao.findById(sourceId)).thenReturn(sourceOffering);
        when(networkOfferingDao.findByUniqueName("cloned-network-offering")).thenReturn(null);

        Map<Network.Service, java.util.Set<Network.Provider>> serviceProviderMap = new HashMap<>();
        java.util.Set<Network.Provider> dhcpProviders = new java.util.HashSet<>();
        dhcpProviders.add(Network.Provider.VirtualRouter);
        serviceProviderMap.put(Network.Service.Dhcp, dhcpProviders);

        when(configurationManager._networkModel.getNetworkOfferingServiceProvidersMap(sourceId))
            .thenReturn(serviceProviderMap);

        NetworkOfferingVO clonedOffering = mock(NetworkOfferingVO.class);
        when(clonedOffering.getId()).thenReturn(2L);
        when(clonedOffering.getName()).thenReturn("cloned-network-offering");

        Mockito.doReturn(clonedOffering).when(networkOfferingServiceSpy).createNetworkOffering(any(org.apache.cloudstack.api.command.admin.network.NetworkOfferingBaseCmd.class));

        NetworkOffering result = configurationManager.cloneNetworkOffering(cmd);

        Assert.assertNotNull("Cloned network offering should not be null", result);
        verify(networkOfferingDao).findById(sourceId);
    }

    @Test
    public void testCloneNetworkOfferingHandlesDropServices() {
        Long sourceId = 1L;

        NetworkOfferingVO sourceOffering = mock(NetworkOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(sourceId);
        when(sourceOffering.getName()).thenReturn("source-network-offering");
        when(sourceOffering.getDisplayText()).thenReturn("Source Network Offering");
        when(sourceOffering.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(sourceOffering.getTrafficType()).thenReturn(Networks.TrafficType.Guest);

        CloneNetworkOfferingCmd cmd = mock(CloneNetworkOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(sourceId);
        when(cmd.getNetworkOfferingName()).thenReturn("cloned-network-offering");

        List<String> dropServices = new ArrayList<>();
        dropServices.add("Firewall");
        when(cmd.getDropServices()).thenReturn(dropServices);
        when(cmd.getSupportedServices()).thenReturn(null);

        when(networkOfferingDao.findById(sourceId)).thenReturn(sourceOffering);
        when(networkOfferingDao.findByUniqueName("cloned-network-offering")).thenReturn(null);

        Map<Network.Service, java.util.Set<Network.Provider>> serviceProviderMap = new HashMap<>();
        java.util.Set<Network.Provider> dhcpProviders = new java.util.HashSet<>();
        dhcpProviders.add(Network.Provider.VirtualRouter);
        serviceProviderMap.put(Network.Service.Dhcp, dhcpProviders);

        java.util.Set<Network.Provider> firewallProviders = new java.util.HashSet<>();
        firewallProviders.add(Network.Provider.VirtualRouter);
        serviceProviderMap.put(Network.Service.Firewall, firewallProviders);

        when(configurationManager._networkModel.getNetworkOfferingServiceProvidersMap(sourceId))
            .thenReturn(serviceProviderMap);

        NetworkOfferingVO clonedOffering = mock(NetworkOfferingVO.class);
        when(clonedOffering.getId()).thenReturn(2L);
        when(clonedOffering.getName()).thenReturn("cloned-network-offering");

        Mockito.doReturn(clonedOffering).when(networkOfferingServiceSpy).createNetworkOffering(any(org.apache.cloudstack.api.command.admin.network.NetworkOfferingBaseCmd.class));

        NetworkOffering result = configurationManager.cloneNetworkOffering(cmd);

        Assert.assertNotNull("Cloned network offering should not be null", result);
        verify(networkOfferingDao).findById(sourceId);
    }

    @Test
    public void testCloneNetworkOfferingOverridesSupportedServices() {
        Long sourceId = 1L;

        NetworkOfferingVO sourceOffering = mock(NetworkOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(sourceId);
        when(sourceOffering.getName()).thenReturn("source-network-offering");
        when(sourceOffering.getDisplayText()).thenReturn("Source Network Offering");
        when(sourceOffering.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(sourceOffering.getTrafficType()).thenReturn(Networks.TrafficType.Guest);

        CloneNetworkOfferingCmd cmd = mock(CloneNetworkOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(sourceId);
        when(cmd.getNetworkOfferingName()).thenReturn("cloned-network-offering");

        List<String> supportedServices = new ArrayList<>();
        supportedServices.add("Dhcp");
        supportedServices.add("Dns");
        supportedServices.add("SourceNat");
        when(cmd.getSupportedServices()).thenReturn(supportedServices);

        when(networkOfferingDao.findById(sourceId)).thenReturn(sourceOffering);
        when(networkOfferingDao.findByUniqueName("cloned-network-offering")).thenReturn(null);

        Map<Network.Service, java.util.Set<Network.Provider>> serviceProviderMap = new HashMap<>();
        when(configurationManager._networkModel.getNetworkOfferingServiceProvidersMap(sourceId))
            .thenReturn(serviceProviderMap);

        NetworkOfferingVO clonedOffering = mock(NetworkOfferingVO.class);
        when(clonedOffering.getId()).thenReturn(2L);
        when(clonedOffering.getName()).thenReturn("cloned-network-offering");

        Mockito.doReturn(clonedOffering).when(networkOfferingServiceSpy).createNetworkOffering(any(org.apache.cloudstack.api.command.admin.network.NetworkOfferingBaseCmd.class));

        NetworkOffering result = configurationManager.cloneNetworkOffering(cmd);

        Assert.assertNotNull("Cloned network offering should not be null", result);
        verify(networkOfferingDao).findById(sourceId);
    }

    @Test
    public void testCloneNetworkOfferingInheritsGuestTypeAndTrafficType() {
        Long sourceId = 1L;

        NetworkOfferingVO sourceOffering = mock(NetworkOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(sourceId);
        when(sourceOffering.getName()).thenReturn("source-network-offering");
        when(sourceOffering.getDisplayText()).thenReturn("Source Network Offering");
        when(sourceOffering.getGuestType()).thenReturn(Network.GuestType.Shared);
        when(sourceOffering.getTrafficType()).thenReturn(Networks.TrafficType.Guest);
        when(sourceOffering.getAvailability()).thenReturn(NetworkOffering.Availability.Required);

        CloneNetworkOfferingCmd cmd = mock(CloneNetworkOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(sourceId);
        when(cmd.getNetworkOfferingName()).thenReturn("cloned-network-offering");
        when(cmd.getGuestIpType()).thenReturn(null); // Should inherit

        when(networkOfferingDao.findById(sourceId)).thenReturn(sourceOffering);
        when(networkOfferingDao.findByUniqueName("cloned-network-offering")).thenReturn(null);

        Map<Network.Service, java.util.Set<Network.Provider>> serviceProviderMap = new HashMap<>();
        when(configurationManager._networkModel.getNetworkOfferingServiceProvidersMap(sourceId))
            .thenReturn(serviceProviderMap);

        NetworkOfferingVO clonedOffering = mock(NetworkOfferingVO.class);
        when(clonedOffering.getId()).thenReturn(2L);
        when(clonedOffering.getName()).thenReturn("cloned-network-offering");
        when(clonedOffering.getGuestType()).thenReturn(Network.GuestType.Shared);
        when(clonedOffering.getTrafficType()).thenReturn(Networks.TrafficType.Guest);

        Mockito.doReturn(clonedOffering).when(networkOfferingServiceSpy).createNetworkOffering(any(org.apache.cloudstack.api.command.admin.network.NetworkOfferingBaseCmd.class));

        NetworkOffering result = configurationManager.cloneNetworkOffering(cmd);

        Assert.assertNotNull("Cloned network offering should not be null", result);
        Assert.assertEquals("Should inherit guest type", Network.GuestType.Shared, result.getGuestType());
        Assert.assertEquals("Should inherit traffic type", Networks.TrafficType.Guest, result.getTrafficType());
        verify(networkOfferingDao).findById(sourceId);
    }

    @Test
    public void testCloneNetworkOfferingInheritsAvailability() {
        Long sourceId = 1L;

        NetworkOfferingVO sourceOffering = mock(NetworkOfferingVO.class);
        when(sourceOffering.getId()).thenReturn(sourceId);
        when(sourceOffering.getName()).thenReturn("source-network-offering");
        when(sourceOffering.getDisplayText()).thenReturn("Source Network Offering");
        when(sourceOffering.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(sourceOffering.getTrafficType()).thenReturn(Networks.TrafficType.Guest);
        when(sourceOffering.getAvailability()).thenReturn(NetworkOffering.Availability.Required);

        CloneNetworkOfferingCmd cmd = mock(CloneNetworkOfferingCmd.class);
        when(cmd.getSourceOfferingId()).thenReturn(sourceId);
        when(cmd.getNetworkOfferingName()).thenReturn("cloned-network-offering");
        when(cmd.getAvailability()).thenReturn(null); // Should inherit

        when(networkOfferingDao.findById(sourceId)).thenReturn(sourceOffering);
        when(networkOfferingDao.findByUniqueName("cloned-network-offering")).thenReturn(null);

        Map<Network.Service, java.util.Set<Network.Provider>> serviceProviderMap = new HashMap<>();
        when(configurationManager._networkModel.getNetworkOfferingServiceProvidersMap(sourceId))
            .thenReturn(serviceProviderMap);

        NetworkOfferingVO clonedOffering = mock(NetworkOfferingVO.class);
        when(clonedOffering.getId()).thenReturn(2L);
        when(clonedOffering.getName()).thenReturn("cloned-network-offering");
        when(clonedOffering.getAvailability()).thenReturn(NetworkOffering.Availability.Required);

        Mockito.doReturn(clonedOffering).when(networkOfferingServiceSpy).createNetworkOffering(any(org.apache.cloudstack.api.command.admin.network.NetworkOfferingBaseCmd.class));

        NetworkOffering result = configurationManager.cloneNetworkOffering(cmd);

        Assert.assertNotNull("Cloned network offering should not be null", result);
        Assert.assertEquals("Should inherit availability", NetworkOffering.Availability.Required, result.getAvailability());
        verify(networkOfferingDao).findById(sourceId);
    }
}
