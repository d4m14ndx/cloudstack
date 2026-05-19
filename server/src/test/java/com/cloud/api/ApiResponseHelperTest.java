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
package com.cloud.api;

import static org.mockito.ArgumentMatchers.any;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

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
import org.springframework.test.util.ReflectionTestUtils;

import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiConstants.HostDetails;
import org.apache.cloudstack.api.ApiConstants.VMDetails;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPairPermission;
import org.apache.cloudstack.api.response.AutoScalePolicyResponse;
import org.apache.cloudstack.api.response.AutoScaleVmGroupResponse;
import org.apache.cloudstack.api.response.AutoScaleVmProfileResponse;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.ApplicationLoadBalancerResponse;
import org.apache.cloudstack.api.response.ApiKeyPairResponse;
import org.apache.cloudstack.api.response.BaseRolePermissionResponse;
import org.apache.cloudstack.api.response.BucketResponse;
import org.apache.cloudstack.api.response.ConditionResponse;
import org.apache.cloudstack.api.response.ConsoleSessionResponse;
import org.apache.cloudstack.api.response.CounterResponse;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.DirectDownloadCertificateHostStatusResponse;
import org.apache.cloudstack.api.response.DirectDownloadCertificateResponse;
import org.apache.cloudstack.api.response.ConfigurationGroupResponse;
import org.apache.cloudstack.api.response.ConfigurationResponse;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.DomainRouterResponse;
import org.apache.cloudstack.api.response.ExtractResponse;
import org.apache.cloudstack.api.response.FirewallResponse;
import org.apache.cloudstack.api.response.FirewallRuleResponse;
import org.apache.cloudstack.api.response.GlobalLoadBalancerResponse;
import org.apache.cloudstack.api.response.GuestOSCategoryResponse;
import org.apache.cloudstack.api.response.HostForMigrationResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.IPAddressResponse;
import org.apache.cloudstack.api.response.IpForwardingRuleResponse;
import org.apache.cloudstack.api.response.ImageStoreResponse;
import org.apache.cloudstack.api.response.InstanceGroupResponse;
import org.apache.cloudstack.api.response.IpQuarantineResponse;
import org.apache.cloudstack.api.response.LBHealthCheckResponse;
import org.apache.cloudstack.api.response.LBStickinessResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.LoadBalancerResponse;
import org.apache.cloudstack.api.response.NetworkACLItemResponse;
import org.apache.cloudstack.api.response.NicSecondaryIpResponse;
import org.apache.cloudstack.api.response.ObjectStoreResponse;
import org.apache.cloudstack.api.response.PodResponse;
import org.apache.cloudstack.api.response.ResourceCountResponse;
import org.apache.cloudstack.api.response.ResourceIconResponse;
import org.apache.cloudstack.api.response.ResourceLimitResponse;
import org.apache.cloudstack.api.response.RouterHealthCheckResultResponse;
import org.apache.cloudstack.api.response.SecondaryStorageHeuristicsResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.SnapshotPolicyResponse;
import org.apache.cloudstack.api.response.SnapshotResponse;
import org.apache.cloudstack.api.response.SnapshotScheduleResponse;
import org.apache.cloudstack.api.response.PrivateGatewayResponse;
import org.apache.cloudstack.api.response.RemoteAccessVpnResponse;
import org.apache.cloudstack.api.response.Site2SiteCustomerGatewayResponse;
import org.apache.cloudstack.api.response.Site2SiteVpnConnectionResponse;
import org.apache.cloudstack.api.response.Site2SiteVpnGatewayResponse;
import org.apache.cloudstack.api.response.StaticRouteResponse;
import org.apache.cloudstack.api.response.StorageNetworkIpRangeResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.SystemVmInstanceResponse;
import org.apache.cloudstack.api.response.SystemVmResponse;
import org.apache.cloudstack.api.response.TemplatePermissionsResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.UpgradeRouterTemplateResponse;
import org.apache.cloudstack.api.response.UnmanagedInstanceResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.UsageRecordResponse;
import org.apache.cloudstack.api.response.TrafficTypeResponse;
import org.apache.cloudstack.api.response.UserResponse;
import org.apache.cloudstack.api.response.VMSnapshotResponse;
import org.apache.cloudstack.api.response.VlanIpRangeResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.api.response.VpcOfferingResponse;
import org.apache.cloudstack.api.response.VpcResponse;
import org.apache.cloudstack.api.response.VpnUsersResponse;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.config.ConfigurationGroup;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.network.lb.ApplicationLoadBalancerRule;
import org.apache.cloudstack.secstorage.heuristics.Heuristic;
import org.apache.cloudstack.storage.object.Bucket;
import org.apache.cloudstack.storage.object.ObjectStore;
import org.apache.cloudstack.usage.UsageService;
import org.apache.cloudstack.vm.UnmanagedInstanceTO;
import org.apache.cloudstack.direct.download.DirectDownloadCertificate;
import org.apache.cloudstack.direct.download.DirectDownloadCertificateHostMap;
import org.apache.cloudstack.direct.download.DirectDownloadManager;

import com.cloud.capacity.Capacity;
import com.cloud.configuration.Resource;
import com.cloud.configuration.ResourceCount;
import com.cloud.configuration.ResourceLimit;
import com.cloud.dc.DataCenter;
import com.cloud.dc.StorageNetworkIpRange;
import com.cloud.dc.Vlan;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.host.Host;
import com.cloud.network.IpAddress;
import com.cloud.network.Networks;
import com.cloud.network.PhysicalNetworkTrafficType;
import com.cloud.network.PublicIpQuarantine;
import com.cloud.network.RouterHealthCheckResult;
import com.cloud.network.as.AutoScalePolicy;
import com.cloud.network.as.AutoScaleVmGroup;
import com.cloud.network.as.AutoScaleVmProfile;
import com.cloud.network.as.Condition;
import com.cloud.network.as.Counter;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeVO;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.Site2SiteCustomerGateway;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.Site2SiteVpnGateway;
import com.cloud.network.VpnUser;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.HealthCheckPolicy;
import com.cloud.network.rules.LoadBalancer;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.StaticNatRule;
import com.cloud.network.rules.StickinessPolicy;
import com.cloud.network.vpc.PrivateGateway;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.StaticRoute;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcOffering;
import com.cloud.network.router.VirtualRouter;
import com.cloud.org.Cluster;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.region.ha.GlobalLoadBalancerRule;
import com.cloud.resource.icon.ResourceIconVO;
import com.cloud.server.ResourceIcon;
import com.cloud.server.ResourceIconManager;
import com.cloud.server.ResourceTag;
import com.cloud.storage.ImageStore;
import com.cloud.storage.Snapshot;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.snapshot.SnapshotPolicy;
import com.cloud.storage.snapshot.SnapshotSchedule;
import com.cloud.storage.GuestOsCategory;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.usage.UsageVO;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserAccount;
import com.cloud.user.UserVO;
import com.cloud.uservm.UserVm;
import com.cloud.utils.net.Ip;
import com.cloud.utils.Pair;
import com.cloud.vm.ConsoleSessionVO;
import com.cloud.vm.InstanceGroup;
import com.cloud.vm.NicSecondaryIp;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.VirtualMachine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@RunWith(MockitoJUnitRunner.class)
public class ApiResponseHelperTest {

    @Mock
    UsageService usageService;

    ApiResponseHelper helper;

    @Mock
    AccountManager accountManagerMock;

    @Mock
    AnnotationDao annotationDaoMock;

    @Mock
    IPAddressDao ipAddressDaoMock;

    @Mock
    ResourceIconManager resourceIconManager;
    @Mock
    private ApiConsoleSessionResponseService apiConsoleSessionResponseService;
    @Mock
    private ApiKeyPairResponseService apiKeyPairResponseService;
    @Mock
    private ApiUnmanagedInstanceResponseService apiUnmanagedInstanceResponseService;
    @Mock
    private ApiDirectDownloadCertificateResponseService apiDirectDownloadCertificateResponseService;
    @Mock
    private ApiOfferingConfigurationResponseService apiOfferingConfigurationResponseService;
    @Mock
    private ApiAutoscaleResponseService apiAutoscaleResponseService;
    @Mock
    private ApiSnapshotResponseService apiSnapshotResponseService;
    @Mock
    private ApiAddressVlanResponseService apiAddressVlanResponseService;
    @Mock
    private ApiStorageResponseService apiStorageResponseService;
    @Mock
    private ApiIdentityAccountResponseService apiIdentityAccountResponseService;
    @Mock
    private ApiHostZoneCapacityResponseService apiHostZoneCapacityResponseService;
    @Mock
    private ApiVpcVpnResponseService apiVpcVpnResponseService;
    @Mock
    private ApiResponseOwnerService apiResponseOwnerService;
    @Mock
    private ApiLoadBalancerFirewallResponseService apiLoadBalancerFirewallResponseService;
    @Mock
    private ApiTemplateIsoResponseService apiTemplateIsoResponseService;
    @Mock
    private ApiVmSystemResponseService apiVmSystemResponseService;

    @Mock
    private ConsoleSessionVO consoleSessionMock;
    @Mock
    private ApiKeyPair apiKeyPairMock;
    @Mock
    private VMTemplateVO templateMock;
    @Spy
    @InjectMocks
    ApiResponseHelper apiResponseHelper = new ApiResponseHelper();

    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss ZZZ");

    static long zoneId = 1L;
    static long domainId = 2L;
    static long accountId = 3L;
    static long serviceOfferingId = 4L;
    static long templateId  = 5L;
    static String userdata = "userdata";
    static long userdataId = 6L;
    static String userdataDetails = "userdataDetails";
    static String userdataNew = "userdataNew";

    static long autoScaleUserId = 7L;

    @Before
    public void injectMocks() throws SecurityException, NoSuchFieldException,
            IllegalArgumentException, IllegalAccessException {
        ApiUsageResponseService apiUsageResponseService = new ApiUsageResponseService();
        ReflectionTestUtils.setField(apiUsageResponseService, "_usageSvc", usageService);

        helper = new ApiResponseHelper();
        ReflectionTestUtils.setField(helper, "apiUsageResponseService", apiUsageResponseService);
        ReflectionTestUtils.setField(helper, "apiConsoleSessionResponseService", apiConsoleSessionResponseService);
        ReflectionTestUtils.setField(helper, "apiKeyPairResponseService", apiKeyPairResponseService);
        ReflectionTestUtils.setField(helper, "apiUnmanagedInstanceResponseService", apiUnmanagedInstanceResponseService);
        ReflectionTestUtils.setField(helper, "apiDirectDownloadCertificateResponseService", apiDirectDownloadCertificateResponseService);
        ReflectionTestUtils.setField(helper, "apiOfferingConfigurationResponseService", apiOfferingConfigurationResponseService);
        ReflectionTestUtils.setField(helper, "apiAutoscaleResponseService", apiAutoscaleResponseService);
        ReflectionTestUtils.setField(helper, "apiSnapshotResponseService", apiSnapshotResponseService);
        ReflectionTestUtils.setField(helper, "apiAddressVlanResponseService", apiAddressVlanResponseService);
        ReflectionTestUtils.setField(helper, "apiStorageResponseService", apiStorageResponseService);
        ReflectionTestUtils.setField(helper, "apiIdentityAccountResponseService", apiIdentityAccountResponseService);
        ReflectionTestUtils.setField(helper, "apiHostZoneCapacityResponseService", apiHostZoneCapacityResponseService);
        ReflectionTestUtils.setField(helper, "apiVpcVpnResponseService", apiVpcVpnResponseService);
        ReflectionTestUtils.setField(helper, "apiResponseOwnerService", new ApiResponseOwnerServiceImpl());
        ReflectionTestUtils.setField(apiResponseHelper, "apiVpcVpnResponseService", apiVpcVpnResponseService);
        ReflectionTestUtils.setField(apiResponseHelper, "apiResponseOwnerService", new ApiResponseOwnerServiceImpl());
        ReflectionTestUtils.setField(helper, "apiLoadBalancerFirewallResponseService", apiLoadBalancerFirewallResponseService);
        ReflectionTestUtils.setField(apiResponseHelper, "apiHostZoneCapacityResponseService", apiHostZoneCapacityResponseService);
        ReflectionTestUtils.setField(helper, "apiTemplateIsoResponseService", apiTemplateIsoResponseService);
        ReflectionTestUtils.setField(helper, "apiVmSystemResponseService", apiVmSystemResponseService);
    }

    @Before
    public void setup() {
        AccountVO account = new AccountVO("testaccount", 1L, "networkdomain", Account.Type.NORMAL, "uuid");
        account.setId(1);
        UserVO user = new UserVO(1, "testuser", "password", "firstname", "lastName", "email", "timezone",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);

        CallContext.register(user, account);
    }

    @After
    public void cleanup() {
        CallContext.unregister();
    }

    @Test
    public void createDiskOfferingResponseDelegatesToOfferingConfigurationResponseService() {
        DiskOffering offering = Mockito.mock(DiskOffering.class);
        DiskOfferingResponse expectedResponse = new DiskOfferingResponse();
        when(apiOfferingConfigurationResponseService.createDiskOfferingResponse(offering)).thenReturn(expectedResponse);

        assertSame(expectedResponse, helper.createDiskOfferingResponse(offering));
    }

    @Test
    public void createUserResponseFromUserDelegatesToIdentityAccountResponseService() {
        User user = Mockito.mock(User.class);
        UserResponse expectedResponse = new UserResponse();
        when(apiIdentityAccountResponseService.createUserResponse(user)).thenReturn(expectedResponse);

        assertSame(expectedResponse, helper.createUserResponse(user));
    }

    @Test
    public void createUserAccountResponseDelegatesToIdentityAccountResponseService() {
        UserAccount userAccount = Mockito.mock(UserAccount.class);
        AccountResponse expectedResponse = new AccountResponse();
        when(apiIdentityAccountResponseService.createUserAccountResponse(ResponseObject.ResponseView.Full, userAccount)).thenReturn(expectedResponse);

        assertSame(expectedResponse, helper.createUserAccountResponse(ResponseObject.ResponseView.Full, userAccount));
    }

    @Test
    public void createAccountResponseDelegatesToIdentityAccountResponseService() {
        Account account = Mockito.mock(Account.class);
        AccountResponse expectedResponse = new AccountResponse();
        when(apiIdentityAccountResponseService.createAccountResponse(ResponseObject.ResponseView.Restricted, account)).thenReturn(expectedResponse);

        assertSame(expectedResponse, helper.createAccountResponse(ResponseObject.ResponseView.Restricted, account));
    }

    @Test
    public void createUserResponseFromUserAccountDelegatesToIdentityAccountResponseService() {
        UserAccount userAccount = Mockito.mock(UserAccount.class);
        UserResponse expectedResponse = new UserResponse();
        when(apiIdentityAccountResponseService.createUserResponse(userAccount)).thenReturn(expectedResponse);

        assertSame(expectedResponse, helper.createUserResponse(userAccount));
    }

    @Test
    public void createDomainResponseDelegatesToIdentityAccountResponseService() {
        Domain domain = Mockito.mock(Domain.class);
        DomainResponse expectedResponse = new DomainResponse();
        when(apiIdentityAccountResponseService.createDomainResponse(domain)).thenReturn(expectedResponse);

        assertSame(expectedResponse, helper.createDomainResponse(domain));
    }

    @Test
    public void identityAccountFindersDelegateToIdentityAccountResponseService() {
        User user = Mockito.mock(User.class);
        Account account = Mockito.mock(Account.class);
        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
        when(apiIdentityAccountResponseService.findUserById(1L)).thenReturn(user);
        when(apiIdentityAccountResponseService.findAccountByNameDomain("account", 2L)).thenReturn(account);
        when(apiIdentityAccountResponseService.findTemplateById(3L)).thenReturn(template);
        when(apiIdentityAccountResponseService.findDiskOfferingById(4L)).thenReturn(diskOffering);

        assertSame(user, helper.findUserById(1L));
        assertSame(account, helper.findAccountByNameDomain("account", 2L));
        assertSame(template, helper.findTemplateById(3L));
        assertSame(diskOffering, helper.findDiskOfferingById(4L));
    }

    @Test
    public void createResourceLimitResponseDelegatesToOfferingConfigurationResponseService() {
        ResourceLimit limit = Mockito.mock(ResourceLimit.class);
        ResourceLimitResponse expectedResponse = new ResourceLimitResponse();
        when(apiOfferingConfigurationResponseService.createResourceLimitResponse(any(ResourceLimit.class), any(), any(), any())).thenReturn(expectedResponse);

        assertSame(expectedResponse, helper.createResourceLimitResponse(limit));
    }

    @Test
    public void createResourceCountResponseDelegatesToOfferingConfigurationResponseService() {
        ResourceCount resourceCount = Mockito.mock(ResourceCount.class);
        ResourceCountResponse expectedResponse = new ResourceCountResponse();
        when(apiOfferingConfigurationResponseService.createResourceCountResponse(any(ResourceCount.class), any(), any(), any())).thenReturn(expectedResponse);

        assertSame(expectedResponse, helper.createResourceCountResponse(resourceCount));
    }

    @Test
    public void createServiceOfferingResponseDelegatesToOfferingConfigurationResponseService() {
        ServiceOffering offering = Mockito.mock(ServiceOffering.class);
        ServiceOfferingResponse expectedResponse = new ServiceOfferingResponse();
        when(apiOfferingConfigurationResponseService.createServiceOfferingResponse(offering)).thenReturn(expectedResponse);

        assertSame(expectedResponse, helper.createServiceOfferingResponse(offering));
    }

    @Test
    public void createConfigurationResponseDelegatesToOfferingConfigurationResponseService() {
        Configuration configuration = Mockito.mock(Configuration.class);
        ConfigurationResponse expectedResponse = new ConfigurationResponse();
        when(apiOfferingConfigurationResponseService.createConfigurationResponse(configuration)).thenReturn(expectedResponse);

        assertSame(expectedResponse, helper.createConfigurationResponse(configuration));
    }

    @Test
    public void createConfigurationGroupResponseDelegatesToOfferingConfigurationResponseService() {
        ConfigurationGroup configurationGroup = Mockito.mock(ConfigurationGroup.class);
        ConfigurationGroupResponse expectedResponse = new ConfigurationGroupResponse();
        when(apiOfferingConfigurationResponseService.createConfigurationGroupResponse(configurationGroup)).thenReturn(expectedResponse);

        assertSame(expectedResponse, helper.createConfigurationGroupResponse(configurationGroup));
    }

    @Test
    public void getDateStringInternal() throws ParseException {
        Mockito.when(usageService.getUsageTimezone()).thenReturn(
                TimeZone.getTimeZone("UTC"));
        assertEquals("2014-06-29'T'23:45:00+00:00", helper
                .getDateStringInternal(dateFormat.parse("2014-06-29 23:45:00 UTC")));
        assertEquals("2014-06-29'T'23:45:01+00:00", helper
                .getDateStringInternal(dateFormat.parse("2014-06-29 23:45:01 UTC")));
        assertEquals("2014-06-29'T'23:45:11+00:00", helper
                .getDateStringInternal(dateFormat.parse("2014-06-29 23:45:11 UTC")));
        assertEquals("2014-06-29'T'23:05:11+00:00", helper
                .getDateStringInternal(dateFormat.parse("2014-06-29 23:05:11 UTC")));
        assertEquals("2014-05-29'T'08:45:11+00:00", helper
                .getDateStringInternal(dateFormat.parse("2014-05-29 08:45:11 UTC")));
    }

    @Test
    public void testUsageRecordResponse(){
        //Creating the usageVO object to be passed to the createUsageResponse.
        Long zoneId = null;
        Long accountId = 1L;
        Long domainId = 1L;
        String Description = "Test Object";
        String usageDisplay = " ";
        int usageType = -1;
        Double rawUsage = null;
        Long vmId = null;
        String vmName = " ";
        Long offeringId = null;
        Long templateId = null;
        Long usageId = null;
        Date startDate = null;
        Date endDate = null;
        String type = " ";
        UsageVO usage = new UsageVO(zoneId,accountId,domainId,Description,usageDisplay,usageType,rawUsage,vmId,vmName,offeringId,templateId,usageId,startDate,endDate,type);

        DomainVO domain = new DomainVO();
        domain.setName("DomainName");

        AccountVO account = new AccountVO();

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findAccountById(anyLong())).thenReturn(account);
            when(ApiDBUtils.findDomainById(anyLong())).thenReturn(domain);

            UsageRecordResponse MockResponse = helper.createUsageResponse(usage);
            assertEquals("DomainName", MockResponse.getDomainName());
        }
    }

    @Test
    public void setResponseIpAddressTestIpv4() {
        NicSecondaryIp result = Mockito.mock(NicSecondaryIp.class);
        NicSecondaryIpResponse response = new NicSecondaryIpResponse();
        setResult(result, "ipv4", "ipv6");

        ApiResponseHelper.setResponseIpAddress(result, response);

        assertTrue(response.getIpAddr().equals("ipv4"));
    }

    private void setResult(NicSecondaryIp result, String ipv4, String ipv6) {
        when(result.getIp4Address()).thenReturn(ipv4);
        when(result.getIp6Address()).thenReturn(ipv6);
    }

    @Test
    public void setResponseIpAddressTestIpv6() {
        NicSecondaryIp result = Mockito.mock(NicSecondaryIp.class);
        NicSecondaryIpResponse response = new NicSecondaryIpResponse();
        setResult(result, null, "ipv6");

        ApiResponseHelper.setResponseIpAddress(result, response);

        assertTrue(response.getIpAddr().equals("ipv6"));
    }

    @Test
    public void createVlanIpRangeResponseDelegatesToService() {
        Vlan vlan = Mockito.mock(Vlan.class);
        VlanIpRangeResponse expected = new VlanIpRangeResponse();
        when(apiAddressVlanResponseService.createVlanIpRangeResponse(vlan)).thenReturn(expected);

        VlanIpRangeResponse response = helper.createVlanIpRangeResponse(vlan);

        Assert.assertSame(expected, response);
        verify(apiAddressVlanResponseService).createVlanIpRangeResponse(vlan);
    }

    @Test
    public void createVlanIpRangeResponseSubclassDelegatesToService() {
        Vlan vlan = Mockito.mock(Vlan.class);
        VlanIpRangeResponse expected = new VlanIpRangeResponse();
        when(apiAddressVlanResponseService.createVlanIpRangeResponse(VlanIpRangeResponse.class, vlan)).thenReturn(expected);

        VlanIpRangeResponse response = helper.createVlanIpRangeResponse(VlanIpRangeResponse.class, vlan);

        Assert.assertSame(expected, response);
        verify(apiAddressVlanResponseService).createVlanIpRangeResponse(VlanIpRangeResponse.class, vlan);
    }

    @Test
    public void createIPAddressResponseDelegatesToService() {
        IpAddress ipAddress = Mockito.mock(IpAddress.class);
        IPAddressResponse expected = new IPAddressResponse();
        when(apiAddressVlanResponseService.createIPAddressResponse(ResponseObject.ResponseView.Full, ipAddress)).thenReturn(expected);

        IPAddressResponse response = helper.createIPAddressResponse(ResponseObject.ResponseView.Full, ipAddress);

        Assert.assertSame(expected, response);
        verify(apiAddressVlanResponseService).createIPAddressResponse(ResponseObject.ResponseView.Full, ipAddress);
    }

    @Test
    public void createSecondaryIPToNicResponseDelegatesToService() {
        NicSecondaryIp secondaryIp = Mockito.mock(NicSecondaryIp.class);
        NicSecondaryIpResponse expected = new NicSecondaryIpResponse();
        when(apiAddressVlanResponseService.createSecondaryIPToNicResponse(secondaryIp)).thenReturn(expected);

        NicSecondaryIpResponse response = helper.createSecondaryIPToNicResponse(secondaryIp);

        Assert.assertSame(expected, response);
        verify(apiAddressVlanResponseService).createSecondaryIPToNicResponse(secondaryIp);
    }

    @Test
    public void testHandleCertificateResponse() {
        String certStr = "certificate";
        DirectDownloadCertificateResponse response = new DirectDownloadCertificateResponse();

        helper.handleCertificateResponse(certStr, response);

        verify(apiDirectDownloadCertificateResponseService).handleCertificateResponse(certStr, response);
    }

    @Test
    public void testCreateCounterResponseDelegatesToAutoscaleResponseService() {
        Counter counter = Mockito.mock(Counter.class);
        CounterResponse expected = new CounterResponse();
        when(apiAutoscaleResponseService.createCounterResponse(counter)).thenReturn(expected);

        CounterResponse response = apiResponseHelper.createCounterResponse(counter);

        assertEquals(expected, response);
        verify(apiAutoscaleResponseService).createCounterResponse(counter);
    }

    @Test
    public void testCreateConditionResponseDelegatesToAutoscaleResponseService() {
        Condition condition = Mockito.mock(Condition.class);
        ConditionResponse expected = new ConditionResponse();
        when(apiAutoscaleResponseService.createConditionResponse(condition)).thenReturn(expected);

        ConditionResponse response = apiResponseHelper.createConditionResponse(condition);

        assertEquals(expected, response);
        verify(apiAutoscaleResponseService).createConditionResponse(condition);
    }

    @Test
    public void testCreateAutoScaleVmProfileResponseDelegatesToAutoscaleResponseService() {
        AutoScaleVmProfile profile = Mockito.mock(AutoScaleVmProfile.class);
        AutoScaleVmProfileResponse expected = new AutoScaleVmProfileResponse();
        when(apiAutoscaleResponseService.createAutoScaleVmProfileResponse(profile)).thenReturn(expected);

        AutoScaleVmProfileResponse response = apiResponseHelper.createAutoScaleVmProfileResponse(profile);

        assertEquals(expected, response);
        verify(apiAutoscaleResponseService).createAutoScaleVmProfileResponse(profile);
    }

    @Test
    public void testCreateAutoScalePolicyResponseDelegatesToAutoscaleResponseService() {
        AutoScalePolicy policy = Mockito.mock(AutoScalePolicy.class);
        AutoScalePolicyResponse expected = new AutoScalePolicyResponse();
        when(apiAutoscaleResponseService.createAutoScalePolicyResponse(policy)).thenReturn(expected);

        AutoScalePolicyResponse response = apiResponseHelper.createAutoScalePolicyResponse(policy);

        assertEquals(expected, response);
        verify(apiAutoscaleResponseService).createAutoScalePolicyResponse(policy);
    }

    @Test
    public void testCreateAutoScaleVmGroupResponseDelegatesToAutoscaleResponseService() {
        AutoScaleVmGroup vmGroup = Mockito.mock(AutoScaleVmGroup.class);
        AutoScaleVmGroupResponse expected = new AutoScaleVmGroupResponse();
        when(apiAutoscaleResponseService.createAutoScaleVmGroupResponse(vmGroup)).thenReturn(expected);

        AutoScaleVmGroupResponse response = apiResponseHelper.createAutoScaleVmGroupResponse(vmGroup);

        assertEquals(expected, response);
        verify(apiAutoscaleResponseService).createAutoScaleVmGroupResponse(vmGroup);
    }

    @Test
    public void testCreateTrafficTypeResponse() {
        PhysicalNetworkVO pnet = new PhysicalNetworkVO();
        pnet.addIsolationMethod("VXLAN");
        pnet.addIsolationMethod("STT");

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findPhysicalNetworkById(anyLong())).thenReturn(pnet);
            String xenLabel = "xen";
            String kvmLabel = "kvm";
            String vmwareLabel = "vmware";
            String simulatorLabel = "simulator";
            String hypervLabel = "hyperv";
            String vlan = "vlan";
            String trafficType = "Public";
            PhysicalNetworkTrafficType pnetTrafficType = new PhysicalNetworkTrafficTypeVO(pnet.getId(), Networks.TrafficType.getTrafficType(trafficType), xenLabel, kvmLabel, vmwareLabel, simulatorLabel, vlan, hypervLabel, null);

            TrafficTypeResponse response = apiResponseHelper.createTrafficTypeResponse(pnetTrafficType);
            assertFalse(UUID.fromString(response.getId()).toString().isEmpty());
            assertEquals(response.getphysicalNetworkId(), pnet.getUuid());
            assertEquals(response.getTrafficType(), trafficType);
            assertEquals(response.getXenLabel(), xenLabel);
            assertEquals(response.getKvmLabel(), kvmLabel);
            assertEquals(response.getVmwareLabel(), vmwareLabel);
            assertEquals(response.getHypervLabel(), hypervLabel);
            assertEquals(response.getVlan(), vlan);
            assertEquals(response.getIsolationMethods(), "VXLAN,STT");

        }
    }

    @Test
    public void testCreateUnmanagedInstanceResponseDelegatesToService() {
        UnmanagedInstanceTO instance = Mockito.mock(UnmanagedInstanceTO.class);
        Cluster cluster = Mockito.mock(Cluster.class);
        Host host = Mockito.mock(Host.class);
        UnmanagedInstanceResponse expectedResponse = new UnmanagedInstanceResponse();
        Mockito.when(apiUnmanagedInstanceResponseService.createUnmanagedInstanceResponse(instance, cluster, host)).thenReturn(expectedResponse);

        UnmanagedInstanceResponse response = apiResponseHelper.createUnmanagedInstanceResponse(instance, cluster, host);

        Assert.assertSame(expectedResponse, response);
        verify(apiUnmanagedInstanceResponseService).createUnmanagedInstanceResponse(instance, cluster, host);
    }

    @Test
    public void vmSystemResponsesDelegateToService() {
        UserVm userVm = Mockito.mock(UserVm.class);
        List<UserVmResponse> userVmResponses = Collections.singletonList(new UserVmResponse());
        EnumSet<VMDetails> details = EnumSet.of(VMDetails.nics);
        Mockito.when(apiVmSystemResponseService.createUserVmResponse(ResponseObject.ResponseView.Full, "uservm", details, userVm)).thenReturn(userVmResponses);
        Mockito.when(apiVmSystemResponseService.createUserVmResponse(ResponseObject.ResponseView.Restricted, "virtualmachine", userVm)).thenReturn(userVmResponses);

        VirtualRouter router = Mockito.mock(VirtualRouter.class);
        DomainRouterResponse routerResponse = new DomainRouterResponse();
        Mockito.when(apiVmSystemResponseService.createDomainRouterResponse(router)).thenReturn(routerResponse);

        VirtualMachine systemVm = Mockito.mock(VirtualMachine.class);
        SystemVmResponse systemVmResponse = new SystemVmResponse();
        SystemVmInstanceResponse systemVmInstanceResponse = new SystemVmInstanceResponse();
        Mockito.when(apiVmSystemResponseService.createSystemVmResponse(systemVm)).thenReturn(systemVmResponse);
        Mockito.when(apiVmSystemResponseService.createSystemVmInstanceResponse(systemVm)).thenReturn(systemVmInstanceResponse);

        InstanceGroup group = Mockito.mock(InstanceGroup.class);
        InstanceGroupResponse groupResponse = new InstanceGroupResponse();
        Mockito.when(apiVmSystemResponseService.createInstanceGroupResponse(group)).thenReturn(groupResponse);

        List<Long> jobIds = Collections.singletonList(1L);
        ListResponse<UpgradeRouterTemplateResponse> upgradeResponse = new ListResponse<>();
        Mockito.when(apiVmSystemResponseService.createUpgradeRouterTemplateResponse(jobIds)).thenReturn(upgradeResponse);

        List<RouterHealthCheckResult> healthCheckResults = Collections.singletonList(Mockito.mock(RouterHealthCheckResult.class));
        List<RouterHealthCheckResultResponse> healthCheckResponses = Collections.singletonList(new RouterHealthCheckResultResponse());
        Mockito.when(apiVmSystemResponseService.createHealthCheckResponse(systemVm, healthCheckResults)).thenReturn(healthCheckResponses);

        Assert.assertSame(userVmResponses, helper.createUserVmResponse(ResponseObject.ResponseView.Full, "uservm", details, userVm));
        Assert.assertSame(userVmResponses, helper.createUserVmResponse(ResponseObject.ResponseView.Restricted, "virtualmachine", userVm));
        Assert.assertSame(routerResponse, helper.createDomainRouterResponse(router));
        Assert.assertSame(systemVmResponse, helper.createSystemVmResponse(systemVm));
        Assert.assertSame(groupResponse, helper.createInstanceGroupResponse(group));
        Assert.assertSame(systemVmInstanceResponse, helper.createSystemVmInstanceResponse(systemVm));
        Assert.assertSame(upgradeResponse, helper.createUpgradeRouterTemplateResponse(jobIds));
        Assert.assertSame(healthCheckResponses, helper.createHealthCheckResponse(systemVm, healthCheckResults));

        verify(apiVmSystemResponseService).createUserVmResponse(ResponseObject.ResponseView.Full, "uservm", details, userVm);
        verify(apiVmSystemResponseService).createUserVmResponse(ResponseObject.ResponseView.Restricted, "virtualmachine", userVm);
        verify(apiVmSystemResponseService).createDomainRouterResponse(router);
        verify(apiVmSystemResponseService).createSystemVmResponse(systemVm);
        verify(apiVmSystemResponseService).createInstanceGroupResponse(group);
        verify(apiVmSystemResponseService).createSystemVmInstanceResponse(systemVm);
        verify(apiVmSystemResponseService).createUpgradeRouterTemplateResponse(jobIds);
        verify(apiVmSystemResponseService).createHealthCheckResponse(systemVm, healthCheckResults);
    }

    @Test
    public void createSnapshotResponseDelegatesToService() {
        Snapshot snapshot = Mockito.mock(Snapshot.class);
        SnapshotResponse expectedResponse = new SnapshotResponse();
        when(apiSnapshotResponseService.createSnapshotResponse(snapshot)).thenReturn(expectedResponse);

        SnapshotResponse response = helper.createSnapshotResponse(snapshot);

        Assert.assertSame(expectedResponse, response);
        verify(apiSnapshotResponseService).createSnapshotResponse(snapshot);
    }

    @Test
    public void createVMSnapshotResponseDelegatesToService() {
        VMSnapshot vmSnapshot = Mockito.mock(VMSnapshot.class);
        VMSnapshotResponse expectedResponse = new VMSnapshotResponse();
        when(apiSnapshotResponseService.createVMSnapshotResponse(vmSnapshot)).thenReturn(expectedResponse);

        VMSnapshotResponse response = helper.createVMSnapshotResponse(vmSnapshot);

        Assert.assertSame(expectedResponse, response);
        verify(apiSnapshotResponseService).createVMSnapshotResponse(vmSnapshot);
    }

    @Test
    public void createSnapshotPolicyResponseDelegatesToService() {
        SnapshotPolicy policy = Mockito.mock(SnapshotPolicy.class);
        SnapshotPolicyResponse expectedResponse = new SnapshotPolicyResponse();
        when(apiSnapshotResponseService.createSnapshotPolicyResponse(policy)).thenReturn(expectedResponse);

        SnapshotPolicyResponse response = helper.createSnapshotPolicyResponse(policy);

        Assert.assertSame(expectedResponse, response);
        verify(apiSnapshotResponseService).createSnapshotPolicyResponse(policy);
    }

    @Test
    public void createHostResponseDelegatesToHostZoneCapacityResponseService() {
        Host host = Mockito.mock(Host.class);
        HostResponse expectedResponse = new HostResponse();
        when(apiHostZoneCapacityResponseService.createHostResponse(host)).thenReturn(expectedResponse);

        HostResponse response = helper.createHostResponse(host);

        Assert.assertSame(expectedResponse, response);
        verify(apiHostZoneCapacityResponseService).createHostResponse(host);
    }

    @Test
    public void createHostResponseWithDetailsDelegatesToHostZoneCapacityResponseService() {
        Host host = Mockito.mock(Host.class);
        EnumSet<HostDetails> details = EnumSet.of(HostDetails.stats);
        HostResponse expectedResponse = new HostResponse();
        when(apiHostZoneCapacityResponseService.createHostResponse(host, details)).thenReturn(expectedResponse);

        HostResponse response = helper.createHostResponse(host, details);

        Assert.assertSame(expectedResponse, response);
        verify(apiHostZoneCapacityResponseService).createHostResponse(host, details);
    }

    @Test
    public void createHostForMigrationResponseDelegatesToHostZoneCapacityResponseService() {
        Host host = Mockito.mock(Host.class);
        HostForMigrationResponse expectedResponse = new HostForMigrationResponse();
        when(apiHostZoneCapacityResponseService.createHostForMigrationResponse(host)).thenReturn(expectedResponse);

        HostForMigrationResponse response = helper.createHostForMigrationResponse(host);

        Assert.assertSame(expectedResponse, response);
        verify(apiHostZoneCapacityResponseService).createHostForMigrationResponse(host);
    }

    @Test
    public void createHostForMigrationResponseWithDetailsDelegatesToHostZoneCapacityResponseService() {
        Host host = Mockito.mock(Host.class);
        EnumSet<HostDetails> details = EnumSet.of(HostDetails.capacity);
        HostForMigrationResponse expectedResponse = new HostForMigrationResponse();
        when(apiHostZoneCapacityResponseService.createHostForMigrationResponse(host, details)).thenReturn(expectedResponse);

        HostForMigrationResponse response = helper.createHostForMigrationResponse(host, details);

        Assert.assertSame(expectedResponse, response);
        verify(apiHostZoneCapacityResponseService).createHostForMigrationResponse(host, details);
    }

    @Test
    public void createMinimalPodResponseDelegatesToHostZoneCapacityResponseService() {
        com.cloud.dc.Pod pod = Mockito.mock(com.cloud.dc.Pod.class);
        PodResponse expectedResponse = new PodResponse();
        when(apiHostZoneCapacityResponseService.createMinimalPodResponse(pod)).thenReturn(expectedResponse);

        PodResponse response = helper.createMinimalPodResponse(pod);

        Assert.assertSame(expectedResponse, response);
        verify(apiHostZoneCapacityResponseService).createMinimalPodResponse(pod);
    }

    @Test
    public void createPodResponseDelegatesToHostZoneCapacityResponseService() {
        com.cloud.dc.Pod pod = Mockito.mock(com.cloud.dc.Pod.class);
        PodResponse expectedResponse = new PodResponse();
        when(apiHostZoneCapacityResponseService.createPodResponse(pod, true)).thenReturn(expectedResponse);

        PodResponse response = helper.createPodResponse(pod, true);

        Assert.assertSame(expectedResponse, response);
        verify(apiHostZoneCapacityResponseService).createPodResponse(pod, true);
    }

    @Test
    public void createZoneResponseDelegatesToHostZoneCapacityResponseService() {
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        ZoneResponse expectedResponse = new ZoneResponse();
        when(apiHostZoneCapacityResponseService.createZoneResponse(ResponseObject.ResponseView.Full, dataCenter, true, false)).thenReturn(expectedResponse);

        ZoneResponse response = helper.createZoneResponse(ResponseObject.ResponseView.Full, dataCenter, true, false);

        Assert.assertSame(expectedResponse, response);
        verify(apiHostZoneCapacityResponseService).createZoneResponse(ResponseObject.ResponseView.Full, dataCenter, true, false);
    }

    @Test
    public void createMinimalClusterResponseDelegatesToHostZoneCapacityResponseService() {
        Cluster cluster = Mockito.mock(Cluster.class);
        ClusterResponse expectedResponse = new ClusterResponse();
        when(apiHostZoneCapacityResponseService.createMinimalClusterResponse(cluster)).thenReturn(expectedResponse);

        ClusterResponse response = helper.createMinimalClusterResponse(cluster);

        Assert.assertSame(expectedResponse, response);
        verify(apiHostZoneCapacityResponseService).createMinimalClusterResponse(cluster);
    }

    @Test
    public void createClusterResponseDelegatesToHostZoneCapacityResponseService() {
        Cluster cluster = Mockito.mock(Cluster.class);
        ClusterResponse expectedResponse = new ClusterResponse();
        when(apiHostZoneCapacityResponseService.createClusterResponse(cluster, true)).thenReturn(expectedResponse);

        ClusterResponse response = helper.createClusterResponse(cluster, true);

        Assert.assertSame(expectedResponse, response);
        verify(apiHostZoneCapacityResponseService).createClusterResponse(cluster, true);
    }

    @Test
    public void createSnapshotScheduleResponseDelegatesToService() {
        SnapshotSchedule schedule = Mockito.mock(SnapshotSchedule.class);
        SnapshotScheduleResponse expectedResponse = new SnapshotScheduleResponse();
        when(apiSnapshotResponseService.createSnapshotScheduleResponse(schedule)).thenReturn(expectedResponse);

        SnapshotScheduleResponse response = helper.createSnapshotScheduleResponse(schedule);

        Assert.assertSame(expectedResponse, response);
        verify(apiSnapshotResponseService).createSnapshotScheduleResponse(schedule);
    }

    @Test
    public void createQuarantinedIpsResponseDelegatesToService() {
        PublicIpQuarantine quarantinedIp = Mockito.mock(PublicIpQuarantine.class);
        IpQuarantineResponse expected = new IpQuarantineResponse();
        when(apiAddressVlanResponseService.createQuarantinedIpsResponse(quarantinedIp)).thenReturn(expected);

        IpQuarantineResponse response = apiResponseHelper.createQuarantinedIpsResponse(quarantinedIp);

        Assert.assertSame(expected, response);
        verify(apiAddressVlanResponseService).createQuarantinedIpsResponse(quarantinedIp);
    }

    @Test
    public void createVolumeResponseDelegatesToStorageResponseService() {
        Volume volume = Mockito.mock(Volume.class);
        VolumeResponse expected = new VolumeResponse();
        when(apiStorageResponseService.createVolumeResponse(ResponseObject.ResponseView.Full, volume)).thenReturn(expected);

        VolumeResponse response = helper.createVolumeResponse(ResponseObject.ResponseView.Full, volume);

        Assert.assertSame(expected, response);
        verify(apiStorageResponseService).createVolumeResponse(ResponseObject.ResponseView.Full, volume);
    }

    @Test
    public void createStoragePoolResponseDelegatesToStorageResponseService() {
        StoragePool pool = Mockito.mock(StoragePool.class);
        StoragePoolResponse expected = new StoragePoolResponse();
        when(apiStorageResponseService.createStoragePoolResponse(pool)).thenReturn(expected);

        StoragePoolResponse response = helper.createStoragePoolResponse(pool);

        Assert.assertSame(expected, response);
        verify(apiStorageResponseService).createStoragePoolResponse(pool);
    }

    @Test
    public void createImageStoreResponseDelegatesToStorageResponseService() {
        ImageStore imageStore = Mockito.mock(ImageStore.class);
        ImageStoreResponse expected = new ImageStoreResponse();
        when(apiStorageResponseService.createImageStoreResponse(imageStore)).thenReturn(expected);

        ImageStoreResponse response = helper.createImageStoreResponse(imageStore);

        Assert.assertSame(expected, response);
        verify(apiStorageResponseService).createImageStoreResponse(imageStore);
    }

    @Test
    public void createStoragePoolForMigrationResponseDelegatesToStorageResponseService() {
        StoragePool pool = Mockito.mock(StoragePool.class);
        StoragePoolResponse expected = new StoragePoolResponse();
        when(apiStorageResponseService.createStoragePoolForMigrationResponse(pool)).thenReturn(expected);

        StoragePoolResponse response = helper.createStoragePoolForMigrationResponse(pool);

        Assert.assertSame(expected, response);
        verify(apiStorageResponseService).createStoragePoolForMigrationResponse(pool);
    }

    @Test
    public void createStorageNetworkIpRangeResponseDelegatesToStorageResponseService() {
        StorageNetworkIpRange range = Mockito.mock(StorageNetworkIpRange.class);
        StorageNetworkIpRangeResponse expected = new StorageNetworkIpRangeResponse();
        when(apiStorageResponseService.createStorageNetworkIpRangeResponse(range)).thenReturn(expected);

        StorageNetworkIpRangeResponse response = helper.createStorageNetworkIpRangeResponse(range);

        Assert.assertSame(expected, response);
        verify(apiStorageResponseService).createStorageNetworkIpRangeResponse(range);
    }

    @Test
    public void createSecondaryStorageSelectorResponseDelegatesToStorageResponseService() {
        Heuristic heuristic = Mockito.mock(Heuristic.class);
        SecondaryStorageHeuristicsResponse expected = new SecondaryStorageHeuristicsResponse("id", "name", "description", "zone-id", "type", "rule", null, null);
        when(apiStorageResponseService.createSecondaryStorageSelectorResponse(heuristic)).thenReturn(expected);

        SecondaryStorageHeuristicsResponse response = helper.createSecondaryStorageSelectorResponse(heuristic);

        Assert.assertSame(expected, response);
        verify(apiStorageResponseService).createSecondaryStorageSelectorResponse(heuristic);
    }

    @Test
    public void createObjectStoreResponseDelegatesToStorageResponseService() {
        ObjectStore objectStore = Mockito.mock(ObjectStore.class);
        ObjectStoreResponse expected = new ObjectStoreResponse();
        when(apiStorageResponseService.createObjectStoreResponse(objectStore)).thenReturn(expected);

        ObjectStoreResponse response = helper.createObjectStoreResponse(objectStore);

        Assert.assertSame(expected, response);
        verify(apiStorageResponseService).createObjectStoreResponse(objectStore);
    }

    @Test
    public void createBucketResponseDelegatesToStorageResponseService() {
        Bucket bucket = Mockito.mock(Bucket.class);
        BucketResponse expected = new BucketResponse();
        when(apiStorageResponseService.createBucketResponse(bucket)).thenReturn(expected);

        BucketResponse response = helper.createBucketResponse(bucket);

        Assert.assertSame(expected, response);
        verify(apiStorageResponseService).createBucketResponse(bucket);
    }

    @Test
    public void createVpcOfferingResponseDelegatesToService() {
        VpcOffering offering = Mockito.mock(VpcOffering.class);
        VpcOfferingResponse expected = new VpcOfferingResponse();
        when(apiVpcVpnResponseService.createVpcOfferingResponse(offering)).thenReturn(expected);

        VpcOfferingResponse response = helper.createVpcOfferingResponse(offering);

        Assert.assertSame(expected, response);
        verify(apiVpcVpnResponseService).createVpcOfferingResponse(offering);
    }

    @Test
    public void createVpcResponseDelegatesToService() {
        Vpc vpc = Mockito.mock(Vpc.class);
        VpcResponse expected = new VpcResponse();
        when(apiVpcVpnResponseService.createVpcResponse(ResponseObject.ResponseView.Full, vpc)).thenReturn(expected);

        VpcResponse response = helper.createVpcResponse(ResponseObject.ResponseView.Full, vpc);

        Assert.assertSame(expected, response);
        verify(apiVpcVpnResponseService).createVpcResponse(ResponseObject.ResponseView.Full, vpc);
    }

    @Test
    public void createPrivateGatewayResponseDelegatesToService() {
        PrivateGateway gateway = Mockito.mock(PrivateGateway.class);
        PrivateGatewayResponse expected = new PrivateGatewayResponse();
        when(apiVpcVpnResponseService.createPrivateGatewayResponse(ResponseObject.ResponseView.Full, gateway)).thenReturn(expected);

        PrivateGatewayResponse response = helper.createPrivateGatewayResponse(ResponseObject.ResponseView.Full, gateway);

        Assert.assertSame(expected, response);
        verify(apiVpcVpnResponseService).createPrivateGatewayResponse(ResponseObject.ResponseView.Full, gateway);
    }

    @Test
    public void createStaticRouteResponseDelegatesToService() {
        StaticRoute route = Mockito.mock(StaticRoute.class);
        StaticRouteResponse expected = new StaticRouteResponse();
        when(apiVpcVpnResponseService.createStaticRouteResponse(route)).thenReturn(expected);

        StaticRouteResponse response = helper.createStaticRouteResponse(route);

        Assert.assertSame(expected, response);
        verify(apiVpcVpnResponseService).createStaticRouteResponse(route);
    }

    @Test
    public void createSite2SiteVpnGatewayResponseDelegatesToService() {
        Site2SiteVpnGateway gateway = Mockito.mock(Site2SiteVpnGateway.class);
        Site2SiteVpnGatewayResponse expected = new Site2SiteVpnGatewayResponse();
        when(apiVpcVpnResponseService.createSite2SiteVpnGatewayResponse(gateway)).thenReturn(expected);

        Site2SiteVpnGatewayResponse response = helper.createSite2SiteVpnGatewayResponse(gateway);

        Assert.assertSame(expected, response);
        verify(apiVpcVpnResponseService).createSite2SiteVpnGatewayResponse(gateway);
    }

    @Test
    public void createSite2SiteCustomerGatewayResponseDelegatesToService() {
        Site2SiteCustomerGateway gateway = Mockito.mock(Site2SiteCustomerGateway.class);
        Site2SiteCustomerGatewayResponse expected = new Site2SiteCustomerGatewayResponse();
        when(apiVpcVpnResponseService.createSite2SiteCustomerGatewayResponse(gateway)).thenReturn(expected);

        Site2SiteCustomerGatewayResponse response = helper.createSite2SiteCustomerGatewayResponse(gateway);

        Assert.assertSame(expected, response);
        verify(apiVpcVpnResponseService).createSite2SiteCustomerGatewayResponse(gateway);
    }

    @Test
    public void createSite2SiteVpnConnectionResponseDelegatesToService() {
        Site2SiteVpnConnection connection = Mockito.mock(Site2SiteVpnConnection.class);
        Site2SiteVpnConnectionResponse expected = new Site2SiteVpnConnectionResponse();
        when(apiVpcVpnResponseService.createSite2SiteVpnConnectionResponse(connection)).thenReturn(expected);

        Site2SiteVpnConnectionResponse response = helper.createSite2SiteVpnConnectionResponse(connection);

        Assert.assertSame(expected, response);
        verify(apiVpcVpnResponseService).createSite2SiteVpnConnectionResponse(connection);
    }

    @Test
    public void createVpnUserResponseDelegatesToService() {
        VpnUser vpnUser = Mockito.mock(VpnUser.class);
        VpnUsersResponse expected = new VpnUsersResponse();
        when(apiVpcVpnResponseService.createVpnUserResponse(vpnUser)).thenReturn(expected);

        VpnUsersResponse response = helper.createVpnUserResponse(vpnUser);

        Assert.assertSame(expected, response);
        verify(apiVpcVpnResponseService).createVpnUserResponse(vpnUser);
    }

    @Test
    public void createRemoteAccessVpnResponseDelegatesToService() {
        RemoteAccessVpn vpn = Mockito.mock(RemoteAccessVpn.class);
        RemoteAccessVpnResponse expected = new RemoteAccessVpnResponse();
        when(apiVpcVpnResponseService.createRemoteAccessVpnResponse(vpn)).thenReturn(expected);

        RemoteAccessVpnResponse response = helper.createRemoteAccessVpnResponse(vpn);

        Assert.assertSame(expected, response);
        verify(apiVpcVpnResponseService).createRemoteAccessVpnResponse(vpn);
    }

    @Test
    public void testCapacityListingForSingleTag() {
        Capacity c1 = Mockito.mock(Capacity.class);
        Mockito.when(c1.getTag()).thenReturn("tag1");
        Capacity c2 = Mockito.mock(Capacity.class);
        Mockito.when(c2.getTag()).thenReturn("tag1");
        Capacity c3 = Mockito.mock(Capacity.class);
        Mockito.when(c3.getTag()).thenReturn("tag2");
        Capacity c4 = Mockito.mock(Capacity.class);
        Assert.assertTrue(apiResponseHelper.capacityListingForSingleTag(List.of(c1, c2)));
        Assert.assertFalse(apiResponseHelper.capacityListingForSingleTag(List.of(c1, c2, c3)));
        Assert.assertFalse(apiResponseHelper.capacityListingForSingleTag(List.of(c4, c2, c3)));
    }

    @Test
    public void testCapacityListingForSingleNonGpuType() {
        Capacity c1 = Mockito.mock(Capacity.class);
        Mockito.when(c1.getCapacityType()).thenReturn((short)Resource.ResourceType.user_vm.getOrdinal());
        Capacity c2 = Mockito.mock(Capacity.class);
        Mockito.when(c2.getCapacityType()).thenReturn((short)Resource.ResourceType.user_vm.getOrdinal());
        Capacity c3 = Mockito.mock(Capacity.class);
        Mockito.when(c3.getCapacityType()).thenReturn((short)Resource.ResourceType.volume.getOrdinal());
        Capacity c4 = Mockito.mock(Capacity.class);
        Assert.assertTrue(apiResponseHelper.capacityListingForSingleNonGpuType(List.of(c1, c2)));
        Assert.assertFalse(apiResponseHelper.capacityListingForSingleNonGpuType(List.of(c1, c2, c3)));
    }

    @Test
    public void testCreateGuestOSCategoryResponse_WithResourceIcon() {
        GuestOsCategory guestOsCategory = Mockito.mock(GuestOsCategory.class);
        ResourceIconVO resourceIconVO = Mockito.mock(ResourceIconVO.class);
        String uuid = UUID.randomUUID().toString();
        String name = "Ubuntu";
        boolean featured = true;
        Mockito.when(guestOsCategory.getUuid()).thenReturn(uuid);
        Mockito.when(guestOsCategory.getName()).thenReturn(name);
        Mockito.when(guestOsCategory.isFeatured()).thenReturn(featured);
        ResourceIconResponse mockIconResponse = Mockito.mock(ResourceIconResponse.class);
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            Mockito.when(ApiDBUtils.getResourceIconByResourceUUID(uuid, ResourceTag.ResourceObjectType.GuestOsCategory)).thenReturn(resourceIconVO);
            Mockito.when(ApiDBUtils.newResourceIconResponse(resourceIconVO)).thenReturn(mockIconResponse);
            GuestOSCategoryResponse response = apiResponseHelper.createGuestOSCategoryResponse(guestOsCategory);
            Assert.assertNotNull(response);
            Assert.assertEquals(uuid, response.getId());
            Assert.assertEquals(name, response.getName());
            Object obj = ReflectionTestUtils.getField(response, "featured");
            if (obj == null) {
                Assert.fail("Invalid featured value");
            }
            Assert.assertTrue((Boolean)obj);
            obj = ReflectionTestUtils.getField(response, "resourceIconResponse");
            Assert.assertNotNull(obj);
            Assert.assertEquals("oscategory", response.getObjectName());
        }
    }

    @Test
    public void testCreateGuestOSCategoryResponse_WithoutResourceIcon() {
        GuestOsCategory guestOsCategory = Mockito.mock(GuestOsCategory.class);
        String uuid = "1234";
        String name = "Ubuntu";
        boolean featured = false;
        Mockito.when(guestOsCategory.getUuid()).thenReturn(uuid);
        Mockito.when(guestOsCategory.getName()).thenReturn(name);
        Mockito.when(guestOsCategory.isFeatured()).thenReturn(featured);
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.getResourceIconByResourceUUID(uuid, ResourceTag.ResourceObjectType.GuestOsCategory)).thenReturn(null);
            GuestOSCategoryResponse response = apiResponseHelper.createGuestOSCategoryResponse(guestOsCategory);
            Assert.assertNotNull(response);
            Assert.assertEquals(uuid, response.getId());
            Assert.assertEquals(name, response.getName());
            Object obj = ReflectionTestUtils.getField(response, "featured");
            if (obj == null) {
                Assert.fail("Invalid featured value");
            }
            Assert.assertFalse((Boolean)obj);
            obj = ReflectionTestUtils.getField(response, "resourceIconResponse");
            Assert.assertNull(obj);
            Assert.assertEquals("oscategory", response.getObjectName());
        }
    }

    @Test
    public void testCreateGuestOSCategoryResponse_WithShowIconFalse() {
        GuestOsCategory guestOsCategory = Mockito.mock(GuestOsCategory.class);
        Mockito.when(guestOsCategory.getUuid()).thenReturn(UUID.randomUUID().toString());
        try (MockedStatic<ApiDBUtils> mockedStatic = Mockito.mockStatic(ApiDBUtils.class)) {
            GuestOSCategoryResponse response = apiResponseHelper.createGuestOSCategoryResponse(guestOsCategory, false);
            Assert.assertNotNull(response);
            mockedStatic.verify(() -> ApiDBUtils.getResourceIconByResourceUUID(Mockito.any(), Mockito.any()),
                    Mockito.never());
        }
    }

    @Test
    public void testGetResourceIconsUsingOsCategory_withValidData() {
        TemplateResponse template1 = Mockito.mock(TemplateResponse.class);
        when(template1.getId()).thenReturn("t1");
        when(template1.getOsTypeCategoryId()).thenReturn(100L);
        TemplateResponse template2 = Mockito.mock(TemplateResponse.class);
        when(template2.getId()).thenReturn("t2");
        when(template2.getOsTypeCategoryId()).thenReturn(200L);
        List<TemplateResponse> responses = Arrays.asList(template1, template2);
        Map<Long, ResourceIcon> icons = new HashMap<>();
        ResourceIcon icon1 = Mockito.mock(ResourceIcon.class);
        ResourceIcon icon2 = Mockito.mock(ResourceIcon.class);
        icons.put(100L, icon1);
        icons.put(200L, icon2);
        when(resourceIconManager.getByResourceTypeAndIds(Mockito.eq(ResourceTag.ResourceObjectType.GuestOsCategory), Mockito.anySet()))
                .thenReturn(icons);
        Map<String, ResourceIcon> result = apiResponseHelper.getResourceIconsUsingOsCategory(responses);
        assertEquals(2, result.size());
        assertEquals(icon1, result.get("t1"));
        assertEquals(icon2, result.get("t2"));
    }

    @Test
    public void testGetResourceIconsUsingOsCategory_missingIcons() {
        TemplateResponse template1 = Mockito.mock(TemplateResponse.class);
        when(template1.getId()).thenReturn("t1");
        when(template1.getOsTypeCategoryId()).thenReturn(100L);
        List<TemplateResponse> responses = List.of(template1);
        when(resourceIconManager.getByResourceTypeAndIds(Mockito.eq(ResourceTag.ResourceObjectType.GuestOsCategory), Mockito.anySet())).thenReturn(Collections.emptyMap());
        Map<String, ResourceIcon> result = apiResponseHelper.getResourceIconsUsingOsCategory(responses);
        assertTrue(result.containsKey("t1"));
        assertNull(result.get("t1"));
    }

    @Test
    public void testUpdateTemplateIsoResponsesForIcons_withMixedIcons() {
        TemplateResponse template1 = Mockito.mock(TemplateResponse.class);
        when(template1.getId()).thenReturn("t1");
        TemplateResponse template2 = Mockito.mock(TemplateResponse.class);
        when(template2.getId()).thenReturn("t2");
        List<TemplateResponse> responses = Arrays.asList(template1, template2);
        Map<String, ResourceIcon> isoIcons = new HashMap<>();
        isoIcons.put("t1", Mockito.mock(ResourceIcon.class));
        when(resourceIconManager.getByResourceTypeAndUuids(ResourceTag.ResourceObjectType.ISO, Set.of("t1", "t2")))
                .thenReturn(isoIcons);
        Map<String, ResourceIcon> fallbackIcons = Map.of("t2", Mockito.mock(ResourceIcon.class));
        Mockito.doReturn(fallbackIcons).when(apiResponseHelper).getResourceIconsUsingOsCategory(Mockito.anyList());
        ResourceIconResponse iconResponse1 = new ResourceIconResponse();
        ResourceIconResponse iconResponse2 = new ResourceIconResponse();
        Mockito.doReturn(iconResponse1).when(apiResponseHelper).createResourceIconResponse(isoIcons.get("t1"));
        Mockito.doReturn(iconResponse2).when(apiResponseHelper).createResourceIconResponse(fallbackIcons.get("t2"));
        apiResponseHelper.updateTemplateIsoResponsesForIcons(responses, ResourceTag.ResourceObjectType.ISO);
        verify(template1).setResourceIconResponse(iconResponse1);
        verify(template2).setResourceIconResponse(iconResponse2);
    }

    @Test
    public void testUpdateTemplateIsoResponsesForIcons_emptyInput() {
        apiResponseHelper.updateTemplateIsoResponsesForIcons(Collections.emptyList(),
                ResourceTag.ResourceObjectType.Template);
        Mockito.verify(resourceIconManager, Mockito.never()).getByResourceTypeAndUuids(Mockito.any(),
                Mockito.anyCollection());
    }

    @Test
    public void createConsoleSessionResponseDelegatesToServiceForRestrictedResponse() {
        ConsoleSessionResponse expected = new ConsoleSessionResponse();
        when(apiConsoleSessionResponseService.createConsoleSessionResponse(consoleSessionMock, ResponseObject.ResponseView.Restricted)).thenReturn(expected);

        ConsoleSessionResponse response = apiResponseHelper.createConsoleSessionResponse(consoleSessionMock, ResponseObject.ResponseView.Restricted);

        Assert.assertSame(expected, response);
        verify(apiConsoleSessionResponseService).createConsoleSessionResponse(consoleSessionMock, ResponseObject.ResponseView.Restricted);
    }

    @Test
    public void createConsoleSessionResponseDelegatesToServiceForFullResponse() {
        ConsoleSessionResponse expected = new ConsoleSessionResponse();
        when(apiConsoleSessionResponseService.createConsoleSessionResponse(consoleSessionMock, ResponseObject.ResponseView.Full)).thenReturn(expected);

        ConsoleSessionResponse response = apiResponseHelper.createConsoleSessionResponse(consoleSessionMock, ResponseObject.ResponseView.Full);

        Assert.assertSame(expected, response);
        verify(apiConsoleSessionResponseService).createConsoleSessionResponse(consoleSessionMock, ResponseObject.ResponseView.Full);
    }

    @Test
    public void createKeyPairResponseDelegatesToService() {
        ApiKeyPairResponse expected = new ApiKeyPairResponse();
        when(apiKeyPairResponseService.createKeyPairResponse(apiKeyPairMock)).thenReturn(expected);

        ApiKeyPairResponse response = apiResponseHelper.createKeyPairResponse(apiKeyPairMock);

        Assert.assertSame(expected, response);
        verify(apiKeyPairResponseService).createKeyPairResponse(apiKeyPairMock);
    }

    @Test
    public void createKeypairPermissionsResponseDelegatesToService() {
        List<ApiKeyPairPermission> permissions = Collections.emptyList();
        ListResponse<BaseRolePermissionResponse> expected = new ListResponse<>();
        when(apiKeyPairResponseService.createKeypairPermissionsResponse(permissions)).thenReturn(expected);

        ListResponse<BaseRolePermissionResponse> response = apiResponseHelper.createKeypairPermissionsResponse(permissions);

        Assert.assertSame(expected, response);
        verify(apiKeyPairResponseService).createKeypairPermissionsResponse(permissions);
    }

    @Test
    public void createDirectDownloadCertificateResponseDelegatesToService() {
        DirectDownloadCertificate certificate = Mockito.mock(DirectDownloadCertificate.class);
        DirectDownloadCertificateResponse expected = new DirectDownloadCertificateResponse();
        when(apiDirectDownloadCertificateResponseService.createDirectDownloadCertificateResponse(certificate)).thenReturn(expected);

        DirectDownloadCertificateResponse response = helper.createDirectDownloadCertificateResponse(certificate);

        Assert.assertSame(expected, response);
        verify(apiDirectDownloadCertificateResponseService).createDirectDownloadCertificateResponse(certificate);
    }

    @Test
    public void createDirectDownloadCertificateHostMapResponseDelegatesToService() {
        List<DirectDownloadCertificateHostMap> hostMappings = Collections.singletonList(Mockito.mock(DirectDownloadCertificateHostMap.class));
        List<DirectDownloadCertificateHostStatusResponse> expected = Collections.singletonList(new DirectDownloadCertificateHostStatusResponse());
        when(apiDirectDownloadCertificateResponseService.createDirectDownloadCertificateHostMapResponse(hostMappings)).thenReturn(expected);

        List<DirectDownloadCertificateHostStatusResponse> response = helper.createDirectDownloadCertificateHostMapResponse(hostMappings);

        Assert.assertSame(expected, response);
        verify(apiDirectDownloadCertificateResponseService).createDirectDownloadCertificateHostMapResponse(hostMappings);
    }

    @Test
    public void createDirectDownloadCertificateHostStatusResponseDelegatesToService() {
        DirectDownloadManager.HostCertificateStatus hostStatus = Mockito.mock(DirectDownloadManager.HostCertificateStatus.class);
        DirectDownloadCertificateHostStatusResponse expected = new DirectDownloadCertificateHostStatusResponse();
        when(apiDirectDownloadCertificateResponseService.createDirectDownloadCertificateHostStatusResponse(hostStatus)).thenReturn(expected);

        DirectDownloadCertificateHostStatusResponse response = helper.createDirectDownloadCertificateHostStatusResponse(hostStatus);

        Assert.assertSame(expected, response);
        verify(apiDirectDownloadCertificateResponseService).createDirectDownloadCertificateHostStatusResponse(hostStatus);
    }

    @Test
    public void createDirectDownloadCertificateProvisionResponseDelegatesToService() {
        Pair<Boolean, String> result = new Pair<>(true, "uploaded");
        DirectDownloadCertificateHostStatusResponse expected = new DirectDownloadCertificateHostStatusResponse();
        when(apiDirectDownloadCertificateResponseService.createDirectDownloadCertificateProvisionResponse(1L, 2L, result)).thenReturn(expected);

        DirectDownloadCertificateHostStatusResponse response = helper.createDirectDownloadCertificateProvisionResponse(1L, 2L, result);

        Assert.assertSame(expected, response);
        verify(apiDirectDownloadCertificateResponseService).createDirectDownloadCertificateProvisionResponse(1L, 2L, result);
    }

    @Test
    public void createLoadBalancerResponseDelegatesToService() {
        LoadBalancer loadBalancer = Mockito.mock(LoadBalancer.class);
        LoadBalancerResponse expected = new LoadBalancerResponse();
        when(apiLoadBalancerFirewallResponseService.createLoadBalancerResponse(loadBalancer)).thenReturn(expected);

        LoadBalancerResponse response = helper.createLoadBalancerResponse(loadBalancer);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createLoadBalancerResponse(loadBalancer);
    }

    @Test
    public void createGlobalLoadBalancerResponseDelegatesToService() {
        GlobalLoadBalancerRule globalLoadBalancerRule = Mockito.mock(GlobalLoadBalancerRule.class);
        GlobalLoadBalancerResponse expected = new GlobalLoadBalancerResponse();
        when(apiLoadBalancerFirewallResponseService.createGlobalLoadBalancerResponse(globalLoadBalancerRule)).thenReturn(expected);

        GlobalLoadBalancerResponse response = helper.createGlobalLoadBalancerResponse(globalLoadBalancerRule);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createGlobalLoadBalancerResponse(globalLoadBalancerRule);
    }

    @Test
    public void createPortForwardingRuleResponseDelegatesToService() {
        PortForwardingRule rule = Mockito.mock(PortForwardingRule.class);
        FirewallRuleResponse expected = new FirewallRuleResponse();
        when(apiLoadBalancerFirewallResponseService.createPortForwardingRuleResponse(rule)).thenReturn(expected);

        FirewallRuleResponse response = helper.createPortForwardingRuleResponse(rule);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createPortForwardingRuleResponse(rule);
    }

    @Test
    public void createIpForwardingRuleResponseDelegatesToService() {
        StaticNatRule rule = Mockito.mock(StaticNatRule.class);
        IpForwardingRuleResponse expected = new IpForwardingRuleResponse();
        when(apiLoadBalancerFirewallResponseService.createIpForwardingRuleResponse(rule)).thenReturn(expected);

        IpForwardingRuleResponse response = helper.createIpForwardingRuleResponse(rule);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createIpForwardingRuleResponse(rule);
    }

    @Test
    public void createFirewallResponseDelegatesToService() {
        FirewallRule rule = Mockito.mock(FirewallRule.class);
        FirewallResponse expected = new FirewallResponse();
        when(apiLoadBalancerFirewallResponseService.createFirewallResponse(rule)).thenReturn(expected);

        FirewallResponse response = helper.createFirewallResponse(rule);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createFirewallResponse(rule);
    }

    @Test
    public void createNetworkACLItemResponseDelegatesToService() {
        NetworkACLItem aclItem = Mockito.mock(NetworkACLItem.class);
        NetworkACLItemResponse expected = new NetworkACLItemResponse();
        when(apiLoadBalancerFirewallResponseService.createNetworkACLItemResponse(aclItem)).thenReturn(expected);

        NetworkACLItemResponse response = helper.createNetworkACLItemResponse(aclItem);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createNetworkACLItemResponse(aclItem);
    }

    @Test
    public void createSingleLBStickinessPolicyResponseDelegatesToService() {
        StickinessPolicy stickinessPolicy = Mockito.mock(StickinessPolicy.class);
        LoadBalancer loadBalancer = Mockito.mock(LoadBalancer.class);
        LBStickinessResponse expected = new LBStickinessResponse();
        when(apiLoadBalancerFirewallResponseService.createLBStickinessPolicyResponse(stickinessPolicy, loadBalancer)).thenReturn(expected);

        LBStickinessResponse response = helper.createLBStickinessPolicyResponse(stickinessPolicy, loadBalancer);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createLBStickinessPolicyResponse(stickinessPolicy, loadBalancer);
    }

    @Test
    public void createLBStickinessPolicyListResponseDelegatesToService() {
        List<StickinessPolicy> policies = Collections.singletonList(Mockito.mock(StickinessPolicy.class));
        LoadBalancer loadBalancer = Mockito.mock(LoadBalancer.class);
        LBStickinessResponse expected = new LBStickinessResponse();
        when(apiLoadBalancerFirewallResponseService.createLBStickinessPolicyResponse(policies, loadBalancer)).thenReturn(expected);

        LBStickinessResponse response = helper.createLBStickinessPolicyResponse(policies, loadBalancer);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createLBStickinessPolicyResponse(policies, loadBalancer);
    }

    @Test
    public void createLBHealthCheckPolicyListResponseDelegatesToService() {
        List<HealthCheckPolicy> policies = Collections.singletonList(Mockito.mock(HealthCheckPolicy.class));
        LoadBalancer loadBalancer = Mockito.mock(LoadBalancer.class);
        LBHealthCheckResponse expected = new LBHealthCheckResponse();
        when(apiLoadBalancerFirewallResponseService.createLBHealthCheckPolicyResponse(policies, loadBalancer)).thenReturn(expected);

        LBHealthCheckResponse response = helper.createLBHealthCheckPolicyResponse(policies, loadBalancer);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createLBHealthCheckPolicyResponse(policies, loadBalancer);
    }

    @Test
    public void createSingleLBHealthCheckPolicyResponseDelegatesToService() {
        HealthCheckPolicy healthCheckPolicy = Mockito.mock(HealthCheckPolicy.class);
        LoadBalancer loadBalancer = Mockito.mock(LoadBalancer.class);
        LBHealthCheckResponse expected = new LBHealthCheckResponse();
        when(apiLoadBalancerFirewallResponseService.createLBHealthCheckPolicyResponse(healthCheckPolicy, loadBalancer)).thenReturn(expected);

        LBHealthCheckResponse response = helper.createLBHealthCheckPolicyResponse(healthCheckPolicy, loadBalancer);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createLBHealthCheckPolicyResponse(healthCheckPolicy, loadBalancer);
    }

    @Test
    public void createLoadBalancerContainerReponseDelegatesToService() {
        ApplicationLoadBalancerRule rule = Mockito.mock(ApplicationLoadBalancerRule.class);
        Map<Ip, UserVm> instances = Collections.singletonMap(new Ip("10.1.1.10"), Mockito.mock(UserVm.class));
        ApplicationLoadBalancerResponse expected = new ApplicationLoadBalancerResponse();
        when(apiLoadBalancerFirewallResponseService.createLoadBalancerContainerReponse(rule, instances)).thenReturn(expected);

        ApplicationLoadBalancerResponse response = helper.createLoadBalancerContainerReponse(rule, instances);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createLoadBalancerContainerReponse(rule, instances);
    }

    @Test
    public void createIpv6FirewallRuleResponseDelegatesToService() {
        FirewallRule rule = Mockito.mock(FirewallRule.class);
        FirewallResponse expected = new FirewallResponse();
        when(apiLoadBalancerFirewallResponseService.createIpv6FirewallRuleResponse(rule)).thenReturn(expected);

        FirewallResponse response = helper.createIpv6FirewallRuleResponse(rule);

        Assert.assertSame(expected, response);
        verify(apiLoadBalancerFirewallResponseService).createIpv6FirewallRuleResponse(rule);
    }

    @Test
    public void createTemplateResponsesForTemplateIdDelegatesToService() {
        List<TemplateResponse> expected = Collections.singletonList(new TemplateResponse());
        when(apiTemplateIsoResponseService.createTemplateResponses(ResponseView.Full, templateId, zoneId, true)).thenReturn(expected);

        List<TemplateResponse> response = helper.createTemplateResponses(ResponseView.Full, templateId, zoneId, true);

        Assert.assertSame(expected, response);
        verify(apiTemplateIsoResponseService).createTemplateResponses(ResponseView.Full, templateId, zoneId, true);
    }

    @Test
    public void createTemplateUpdateResponseDelegatesToService() {
        TemplateResponse expected = new TemplateResponse();
        when(apiTemplateIsoResponseService.createTemplateUpdateResponse(ResponseView.Restricted, templateMock)).thenReturn(expected);

        TemplateResponse response = helper.createTemplateUpdateResponse(ResponseView.Restricted, templateMock);

        Assert.assertSame(expected, response);
        verify(apiTemplateIsoResponseService).createTemplateUpdateResponse(ResponseView.Restricted, templateMock);
    }

    @Test
    public void createVolumeExtractResponseDelegatesToService() {
        ExtractResponse expected = new ExtractResponse();
        when(apiTemplateIsoResponseService.createVolumeExtractResponse(1L, 2L, 3L, "HTTP_DOWNLOAD", "https://download.example/volume")).thenReturn(expected);

        ExtractResponse response = helper.createVolumeExtractResponse(1L, 2L, 3L, "HTTP_DOWNLOAD", "https://download.example/volume");

        Assert.assertSame(expected, response);
        verify(apiTemplateIsoResponseService).createVolumeExtractResponse(1L, 2L, 3L, "HTTP_DOWNLOAD", "https://download.example/volume");
    }

    @Test
    public void createTemplatePermissionsResponseDelegatesToService() {
        List<String> accountNames = Collections.singletonList("account");
        TemplatePermissionsResponse expected = new TemplatePermissionsResponse();
        when(apiTemplateIsoResponseService.createTemplatePermissionsResponse(ResponseView.Full, accountNames, templateId)).thenReturn(expected);

        TemplatePermissionsResponse response = helper.createTemplatePermissionsResponse(ResponseView.Full, accountNames, templateId);

        Assert.assertSame(expected, response);
        verify(apiTemplateIsoResponseService).createTemplatePermissionsResponse(ResponseView.Full, accountNames, templateId);
    }
}
