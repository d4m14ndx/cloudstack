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
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPairPermission;
import org.apache.cloudstack.api.response.AutoScalePolicyResponse;
import org.apache.cloudstack.api.response.AutoScaleVmGroupResponse;
import org.apache.cloudstack.api.response.AutoScaleVmProfileResponse;
import org.apache.cloudstack.api.response.ApiKeyPairResponse;
import org.apache.cloudstack.api.response.BaseRolePermissionResponse;
import org.apache.cloudstack.api.response.ConditionResponse;
import org.apache.cloudstack.api.response.ConsoleSessionResponse;
import org.apache.cloudstack.api.response.CounterResponse;
import org.apache.cloudstack.api.response.DirectDownloadCertificateHostStatusResponse;
import org.apache.cloudstack.api.response.DirectDownloadCertificateResponse;
import org.apache.cloudstack.api.response.ConfigurationGroupResponse;
import org.apache.cloudstack.api.response.ConfigurationResponse;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.GuestOSCategoryResponse;
import org.apache.cloudstack.api.response.IpQuarantineResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.NicSecondaryIpResponse;
import org.apache.cloudstack.api.response.ResourceCountResponse;
import org.apache.cloudstack.api.response.ResourceIconResponse;
import org.apache.cloudstack.api.response.ResourceLimitResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.UnmanagedInstanceResponse;
import org.apache.cloudstack.api.response.UsageRecordResponse;
import org.apache.cloudstack.api.response.TrafficTypeResponse;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.config.ConfigurationGroup;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.usage.UsageService;
import org.apache.cloudstack.vm.UnmanagedInstanceTO;
import org.apache.cloudstack.direct.download.DirectDownloadCertificate;
import org.apache.cloudstack.direct.download.DirectDownloadCertificateHostMap;
import org.apache.cloudstack.direct.download.DirectDownloadManager;

import com.cloud.capacity.Capacity;
import com.cloud.configuration.Resource;
import com.cloud.configuration.ResourceCount;
import com.cloud.configuration.ResourceLimit;
import com.cloud.domain.DomainVO;
import com.cloud.host.Host;
import com.cloud.network.Networks;
import com.cloud.network.PhysicalNetworkTrafficType;
import com.cloud.network.PublicIpQuarantine;
import com.cloud.network.as.AutoScalePolicy;
import com.cloud.network.as.AutoScaleVmGroup;
import com.cloud.network.as.AutoScaleVmProfile;
import com.cloud.network.as.Condition;
import com.cloud.network.as.Counter;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeVO;
import com.cloud.org.Cluster;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.icon.ResourceIconVO;
import com.cloud.server.ResourceIcon;
import com.cloud.server.ResourceIconManager;
import com.cloud.server.ResourceTag;
import com.cloud.storage.GuestOsCategory;
import com.cloud.usage.UsageVO;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.Pair;
import com.cloud.utils.net.Ip;
import com.cloud.vm.ConsoleSessionVO;
import com.cloud.vm.NicSecondaryIp;

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
    private ConsoleSessionVO consoleSessionMock;
    @Mock
    private ApiKeyPair apiKeyPairMock;
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
    public void createQuarantinedIpsResponseTestReturnsObject() {
        String quarantinedIpUuid = "quarantined_ip_uuid";
        Long previousOwnerId = 300L;
        String previousOwnerUuid = "previous_owner_uuid";
        String previousOwnerName = "previous_owner_name";
        Long removerAccountId = 400L;
        String removerAccountUuid = "remover_account_uuid";
        Long publicIpAddressId = 500L;
        String publicIpAddress = "1.2.3.4";
        Date created = new Date(599L);
        Date removed = new Date(600L);
        Date endDate = new Date(601L);
        String removalReason = "removalReason";

        PublicIpQuarantine quarantinedIpMock = Mockito.mock(PublicIpQuarantine.class);
        IPAddressVO ipAddressVoMock = Mockito.mock(IPAddressVO.class);
        Account previousOwner = Mockito.mock(Account.class);
        Account removerAccount = Mockito.mock(Account.class);

        Mockito.when(quarantinedIpMock.getUuid()).thenReturn(quarantinedIpUuid);
        Mockito.when(quarantinedIpMock.getPreviousOwnerId()).thenReturn(previousOwnerId);
        Mockito.when(quarantinedIpMock.getPublicIpAddressId()).thenReturn(publicIpAddressId);
        Mockito.doReturn(ipAddressVoMock).when(ipAddressDaoMock).findById(publicIpAddressId);
        Mockito.when(ipAddressVoMock.getAddress()).thenReturn(new Ip(publicIpAddress));
        Mockito.doReturn(previousOwner).when(accountManagerMock).getAccount(previousOwnerId);
        Mockito.when(previousOwner.getUuid()).thenReturn(previousOwnerUuid);
        Mockito.when(previousOwner.getName()).thenReturn(previousOwnerName);
        Mockito.when(quarantinedIpMock.getCreated()).thenReturn(created);
        Mockito.when(quarantinedIpMock.getRemoved()).thenReturn(removed);
        Mockito.when(quarantinedIpMock.getEndDate()).thenReturn(endDate);
        Mockito.when(quarantinedIpMock.getRemovalReason()).thenReturn(removalReason);
        Mockito.when(quarantinedIpMock.getRemoverAccountId()).thenReturn(removerAccountId);
        Mockito.when(removerAccount.getUuid()).thenReturn(removerAccountUuid);
        Mockito.doReturn(removerAccount).when(accountManagerMock).getAccount(removerAccountId);

        IpQuarantineResponse result = apiResponseHelper.createQuarantinedIpsResponse(quarantinedIpMock);

        Assert.assertEquals(quarantinedIpUuid, result.getId());
        Assert.assertEquals(publicIpAddress, result.getPublicIpAddress());
        Assert.assertEquals(previousOwnerUuid, result.getPreviousOwnerId());
        Assert.assertEquals(previousOwnerName, result.getPreviousOwnerName());
        Assert.assertEquals(created, result.getCreated());
        Assert.assertEquals(removed, result.getRemoved());
        Assert.assertEquals(endDate, result.getEndDate());
        Assert.assertEquals(removalReason, result.getRemovalReason());
        Assert.assertEquals(removerAccountUuid, result.getRemoverAccountId());
        Assert.assertEquals("quarantinedip", result.getResponseName());
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
}
