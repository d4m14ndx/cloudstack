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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.api.response.UsageRecordResponse;
import org.apache.cloudstack.usage.UsageService;
import org.apache.cloudstack.usage.UsageTypes;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.domain.DomainVO;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VolumeVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.usage.UsageVO;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.utils.db.EntityManager;

@RunWith(MockitoJUnitRunner.class)
public class ApiUsageResponseServiceTest {

    private static final long ACCOUNT_ID = 11L;
    private static final long DOMAIN_ID = 12L;

    @Mock
    private EntityManager entityManager;
    @Mock
    private UsageService usageService;
    @Mock
    private ResourceTagDao resourceTagDao;

    private ApiUsageResponseService service;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");

    @Before
    public void setup() {
        service = new ApiUsageResponseService();
        ReflectionTestUtils.setField(service, "_entityMgr", entityManager);
        ReflectionTestUtils.setField(service, "_usageSvc", usageService);
        ReflectionTestUtils.setField(service, "_resourceTagDao", resourceTagDao);
    }

    @Test
    public void getDateStringInternalPreservesUtcFormattingAndNullHandling() throws ParseException {
        when(usageService.getUsageTimezone()).thenReturn(TimeZone.getTimeZone("UTC"));

        assertNull(service.getDateStringInternal(null));
        assertEquals("2014-06-29'T'23:45:00+00:00", service.getDateStringInternal(dateFormat.parse("2014-06-29 23:45:00 UTC")));
        assertEquals("2014-06-29'T'23:05:11+00:00", service.getDateStringInternal(dateFormat.parse("2014-06-29 23:05:11 UTC")));
        assertEquals("2014-05-29'T'08:45:11+00:00", service.getDateStringInternal(dateFormat.parse("2014-05-29 08:45:11 UTC")));
    }

    @Test
    public void createUsageResponseForUnsupportedTypePreservesBaseUsageFields() {
        UsageVO usage = new UsageVO(null, ACCOUNT_ID, DOMAIN_ID, "Original description", "7 hours", -1, null,
                null, "vm-name", null, null, null, null, null, "unknown");

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            stubAccountAndDomain();

            UsageRecordResponse response = service.createUsageResponse(usage);

            assertEquals("account-uuid", ReflectionTestUtils.getField(response, "accountId"));
            assertEquals("account-name", ReflectionTestUtils.getField(response, "accountName"));
            assertEquals("domain-uuid", ReflectionTestUtils.getField(response, "domainId"));
            assertEquals("DomainName", response.getDomainName());
            assertEquals("Original description", ReflectionTestUtils.getField(response, "description"));
            assertEquals("7 hours", ReflectionTestUtils.getField(response, "usage"));
            assertEquals(-1, ReflectionTestUtils.getField(response, "usageType"));
        }
    }

    @Test
    public void createUsageResponseForIpAddressSetsUsageIdFlagsRawUsageAndDates() {
        Date startDate = new Date(1000L);
        Date endDate = new Date(2000L);
        UsageVO usage = new UsageVO(null, ACCOUNT_ID, DOMAIN_ID, "IP usage", "2 hours", UsageTypes.IP_ADDRESS,
                1.25, 101L, 1L, "SourceNat", startDate, endDate, false);
        IPAddressVO ipAddress = Mockito.mock(IPAddressVO.class);
        when(ipAddress.getId()).thenReturn(101L);
        when(ipAddress.getUuid()).thenReturn("ip-uuid");
        when(entityManager.findByIdIncludingRemoved(IPAddressVO.class, "101")).thenReturn(ipAddress);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            stubAccountAndDomain();

            UsageRecordResponse response = service.createUsageResponse(usage);

            assertEquals("ip-uuid", ReflectionTestUtils.getField(response, "usageId"));
            assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(response, "isSourceNat"));
            assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(response, "isSystem"));
            assertEquals("1.25", ReflectionTestUtils.getField(response, "rawUsage"));
            assertEquals(startDate, ReflectionTestUtils.getField(response, "startDate"));
            assertEquals(endDate, ReflectionTestUtils.getField(response, "endDate"));
        }
    }

    @Test
    public void createUsageResponseForVolumeBuildsDescriptionWithNewFormat() {
        UsageVO usage = new UsageVO(null, ACCOUNT_ID, DOMAIN_ID, "Old description", "3 hours", UsageTypes.VOLUME,
                null, null, null, 301L, null, 201L, 1024L, null, null);
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        when(volume.getId()).thenReturn(201L);
        when(volume.getUuid()).thenReturn("volume-uuid");
        when(volume.getName()).thenReturn("volume-name");
        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
        when(diskOffering.getUuid()).thenReturn("disk-offering-uuid");
        when(diskOffering.getName()).thenReturn("disk-offering-name");
        when(entityManager.findByIdIncludingRemoved(VolumeVO.class, "201")).thenReturn(volume);
        when(entityManager.findByIdIncludingRemoved(DiskOfferingVO.class, "301")).thenReturn(diskOffering);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            stubAccountAndDomain();

            UsageRecordResponse response = service.createUsageResponse(usage, null, false);

            assertEquals("volume-uuid", ReflectionTestUtils.getField(response, "usageId"));
            assertEquals(1024L, ReflectionTestUtils.getField(response, "size"));
            assertEquals("Volume usage for volume-name (volume-uuid) with disk offering disk-offering-name (disk-offering-uuid) and size (1.00 KB) 1024",
                    ReflectionTestUtils.getField(response, "description"));
        }
    }

    @Test
    public void createUsageResponseAppliesTagsByResourceIdAndType() {
        UsageVO usage = new UsageVO(null, ACCOUNT_ID, DOMAIN_ID, "Old description", "3 hours", UsageTypes.VOLUME,
                null, null, null, null, null, 201L, 1024L, null, null);
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        when(volume.getId()).thenReturn(201L);
        when(volume.getUuid()).thenReturn("volume-uuid");
        when(entityManager.findByIdIncludingRemoved(VolumeVO.class, "201")).thenReturn(volume);
        ResourceTagResponse tag = new ResourceTagResponse();
        tag.setKey("env");
        tag.setValue("test");
        Set<ResourceTagResponse> tags = Collections.singleton(tag);
        Map<String, Set<ResourceTagResponse>> tagMap = Collections.singletonMap("201:" + ResourceObjectType.Volume, tags);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            stubAccountAndDomain();

            UsageRecordResponse response = service.createUsageResponse(usage, tagMap, true);

            assertSame(tags, ReflectionTestUtils.getField(response, "tags"));
        }
    }

    @Test
    public void getUsageResourceTagsReturnsDaoTagsAndNullOnDaoException() {
        Map<String, Set<ResourceTagResponse>> tags = Collections.singletonMap("1:Volume", Collections.emptySet());
        when(resourceTagDao.listTags()).thenReturn(tags).thenThrow(new RuntimeException("dao"));

        assertSame(tags, service.getUsageResourceTags());
        assertNull(service.getUsageResourceTags());
    }

    private void stubAccountAndDomain() {
        AccountVO account = new AccountVO("account-name", DOMAIN_ID, "network-domain", Account.Type.NORMAL, "account-uuid");
        account.setId(ACCOUNT_ID);
        DomainVO domain = new DomainVO();
        domain.setUuid("domain-uuid");
        domain.setName("DomainName");
        domain.setPath("/ROOT/DomainName");
        when(ApiDBUtils.findAccountById(anyLong())).thenReturn(account);
        when(ApiDBUtils.findDomainById(DOMAIN_ID)).thenReturn(domain);
    }
}
