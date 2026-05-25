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
package com.cloud.api.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.offering.ListServiceOfferingsCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.query.QueryService;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.dao.DataCenterJoinDao;
import com.cloud.api.query.dao.ServiceOfferingJoinDao;
import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

public class ServiceOfferingQueryServiceImplTest {

    private AccountManager accountMgr;
    private ServiceOfferingJoinDao srvOfferingJoinDao;
    private ServiceOfferingDao srvOfferingDao;
    private ServiceOfferingDetailsDao srvOfferingDetailsDao;
    private DiskOfferingDao diskOfferingDao;
    private DataCenterJoinDao dcJoinDao;
    private DomainDao domainDao;
    private VMTemplateDao templateDao;
    private VMInstanceDao vmInstanceDao;
    private UserVmDao userVmDao;
    private VirtualMachineManager virtualMachineManager;
    private HostDao hostDao;
    private HostTagsDao hostTagDao;

    private SearchBuilder<ServiceOfferingVO> serviceOfferingSearchBuilder;
    private SearchBuilder<ServiceOfferingDetailsVO> serviceOfferingDetailsSearchBuilder;
    private SearchBuilder<DiskOfferingVO> diskOfferingSearchBuilder;
    private SearchCriteria<ServiceOfferingVO> searchCriteria;

    private ServiceOfferingQueryServiceImpl service;

    private AccountVO account;
    private UserVO user;
    private DomainVO callerDomain;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() {
        accountMgr = mock(AccountManager.class);
        srvOfferingJoinDao = mock(ServiceOfferingJoinDao.class);
        srvOfferingDao = mock(ServiceOfferingDao.class);
        srvOfferingDetailsDao = mock(ServiceOfferingDetailsDao.class);
        diskOfferingDao = mock(DiskOfferingDao.class);
        dcJoinDao = mock(DataCenterJoinDao.class);
        domainDao = mock(DomainDao.class);
        templateDao = mock(VMTemplateDao.class);
        vmInstanceDao = mock(VMInstanceDao.class);
        userVmDao = mock(UserVmDao.class);
        virtualMachineManager = mock(VirtualMachineManager.class);
        hostDao = mock(HostDao.class);
        hostTagDao = mock(HostTagsDao.class);

        serviceOfferingSearchBuilder = (SearchBuilder<ServiceOfferingVO>)mock(SearchBuilder.class);
        serviceOfferingDetailsSearchBuilder = (SearchBuilder<ServiceOfferingDetailsVO>)mock(SearchBuilder.class);
        diskOfferingSearchBuilder = (SearchBuilder<DiskOfferingVO>)mock(SearchBuilder.class);
        searchCriteria = (SearchCriteria<ServiceOfferingVO>)mock(SearchCriteria.class);

        ServiceOfferingVO serviceOfferingEntity = mock(ServiceOfferingVO.class);
        ServiceOfferingDetailsVO detailsEntity = mock(ServiceOfferingDetailsVO.class);
        DiskOfferingVO diskOfferingEntity = mock(DiskOfferingVO.class);

        when(srvOfferingDao.createSearchBuilder()).thenReturn(serviceOfferingSearchBuilder);
        when(serviceOfferingSearchBuilder.entity()).thenReturn(serviceOfferingEntity);
        when(serviceOfferingSearchBuilder.create()).thenReturn(searchCriteria);
        when(srvOfferingDetailsDao.createSearchBuilder()).thenReturn(serviceOfferingDetailsSearchBuilder);
        when(serviceOfferingDetailsSearchBuilder.entity()).thenReturn(detailsEntity);
        when(diskOfferingDao.createSearchBuilder()).thenReturn(diskOfferingSearchBuilder);
        when(diskOfferingSearchBuilder.entity()).thenReturn(diskOfferingEntity);

        stubSearchBuilder(serviceOfferingSearchBuilder);
        stubSearchBuilder(serviceOfferingDetailsSearchBuilder);
        stubSearchBuilder(diskOfferingSearchBuilder);

        service = new ServiceOfferingQueryServiceImpl();
        ReflectionTestUtils.setField(service, "accountMgr", accountMgr);
        ReflectionTestUtils.setField(service, "_srvOfferingJoinDao", srvOfferingJoinDao);
        ReflectionTestUtils.setField(service, "_srvOfferingDao", srvOfferingDao);
        ReflectionTestUtils.setField(service, "_srvOfferingDetailsDao", srvOfferingDetailsDao);
        ReflectionTestUtils.setField(service, "_diskOfferingDao", diskOfferingDao);
        ReflectionTestUtils.setField(service, "_dcJoinDao", dcJoinDao);
        ReflectionTestUtils.setField(service, "_domainDao", domainDao);
        ReflectionTestUtils.setField(service, "_templateDao", templateDao);
        ReflectionTestUtils.setField(service, "_vmInstanceDao", vmInstanceDao);
        ReflectionTestUtils.setField(service, "userVmDao", userVmDao);
        ReflectionTestUtils.setField(service, "virtualMachineManager", virtualMachineManager);
        ReflectionTestUtils.setField(service, "hostDao", hostDao);
        ReflectionTestUtils.setField(service, "_hostTagDao", hostTagDao);

        account = new AccountVO("normaluser", 1L, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        account.setId(11L);
        user = new UserVO(1L, "normaluser", "password", "Test", "User",
                "test@example.com", "UTC", UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);

        when(accountMgr.isRootAdmin(account.getId())).thenReturn(false);
        when(accountMgr.isNormalUser(account.getId())).thenReturn(true);
        when(accountMgr.isDomainAdmin(account.getId())).thenReturn(false);
        when(accountMgr.finalizeOwner(any(Account.class), any(), any(), any())).thenReturn(account);

        callerDomain = new DomainVO();
        callerDomain.setId(account.getDomainId());
        callerDomain.setPath("ROOT/test");
        when(domainDao.findById(account.getDomainId())).thenReturn(callerDomain);
        when(domainDao.getDomainParentIds(account.getDomainId())).thenReturn(Collections.singleton(account.getDomainId()));
        when(srvOfferingDao.searchAndCount(eq(searchCriteria), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubSearchBuilder(SearchBuilder builder) {
        Mockito.lenient().when(builder.and()).thenReturn(builder);
        Mockito.lenient().when(builder.or()).thenReturn(builder);
        Mockito.lenient().when(builder.op()).thenReturn(builder);
        Mockito.lenient().when(builder.cp()).thenReturn(builder);
        Mockito.lenient().when(builder.and(any(String.class), any(), any(SearchCriteria.Op.class))).thenReturn(builder);
        Mockito.lenient().when(builder.and(any(String.class), any(String.class), any(), any(SearchCriteria.Op.class))).thenReturn(builder);
        Mockito.lenient().when(builder.or(any(String.class), any(), any(SearchCriteria.Op.class))).thenReturn(builder);
        Mockito.lenient().when(builder.or(any(String.class), any(String.class), any(), any(SearchCriteria.Op.class))).thenReturn(builder);
        Mockito.lenient().when(builder.op(any(String.class), any(), any(SearchCriteria.Op.class))).thenReturn(builder);
        Mockito.lenient().when(builder.op(any(String.class), any(String.class), any(), any(SearchCriteria.Op.class))).thenReturn(builder);
        Mockito.lenient().when(builder.join(any(String.class), any(SearchBase.class), any(JoinBuilder.JoinType.class), any(JoinBuilder.JoinCondition.class), any(Object[].class))).thenReturn(builder);
    }

    private static class StubListServiceOfferingsCmd extends ListServiceOfferingsCmd {
        Long id;
        String serviceOfferingName;
        String keyword;
        Long virtualMachineId;
        Boolean isSystem = false;
        String systemVmType;
        Long domainId;
        Long projectId;
        String accountName;
        boolean recursive;
        Long zoneId;
        Integer cpuNumber;
        Integer memory;
        Integer cpuSpeed;
        Boolean encryptRoot;
        String storageType;
        ServiceOffering.State state = ServiceOffering.State.Active;
        Long vgpuProfileId;
        Boolean gpuEnabled;

        @Override public Long getId() { return id; }
        @Override public String getServiceOfferingName() { return serviceOfferingName; }
        @Override public String getKeyword() { return keyword; }
        @Override public Long getVirtualMachineId() { return virtualMachineId; }
        @Override public Boolean getIsSystem() { return isSystem == null ? false : isSystem; }
        @Override public String getSystemVmType() { return systemVmType; }
        @Override public Long getDomainId() { return domainId; }
        @Override public Long getProjectId() { return projectId; }
        @Override public String getAccountName() { return accountName; }
        @Override public boolean isRecursive() { return recursive; }
        @Override public Long getZoneId() { return zoneId; }
        @Override public Integer getCpuNumber() { return cpuNumber; }
        @Override public Integer getMemory() { return memory; }
        @Override public Integer getCpuSpeed() { return cpuSpeed; }
        @Override public Boolean getEncryptRoot() { return encryptRoot; }
        @Override public String getStorageType() { return storageType; }
        @Override public ServiceOffering.State getState() { return state; }
        @Override public Long getVgpuProfileId() { return vgpuProfileId; }
        @Override public Boolean getGpuEnabled() { return gpuEnabled; }
        @Override public Long getStartIndex() { return 0L; }
        @Override public Long getPageSizeVal() { return 20L; }
    }

    private StubListServiceOfferingsCmd newCmd() {
        return new StubListServiceOfferingsCmd();
    }

    private ListResponse<ServiceOfferingResponse> executeSearch(StubListServiceOfferingsCmd cmd) {
        try (MockedStatic<ViewResponseHelper> viewResponseHelper = mockStatic(ViewResponseHelper.class)) {
            viewResponseHelper.when(() -> ViewResponseHelper.createServiceOfferingResponse(any(ServiceOfferingJoinVO[].class)))
                    .thenReturn(Collections.emptyList());
            return service.searchForServiceOfferings(cmd);
        }
    }

    private void makeCallerRootAdmin() {
        account.setType(Account.Type.ADMIN);
        when(accountMgr.isRootAdmin(account.getId())).thenReturn(true);
        when(accountMgr.isNormalUser(account.getId())).thenReturn(false);
        when(accountMgr.isDomainAdmin(account.getId())).thenReturn(false);
        when(accountMgr.finalizeOwner(any(Account.class), any(), any(), any())).thenReturn(account);
    }

    @Test
    public void searchForServiceOfferingsReturnsEmptyResponseWhenSearchCountIsZero() {
        StubListServiceOfferingsCmd cmd = newCmd();

        ListResponse<ServiceOfferingResponse> response = executeSearch(cmd);

        assertNotNull(response);
        assertEquals(0, response.getCount().intValue());
        verify(srvOfferingJoinDao, never()).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForServiceOfferingsFetchesJoinRowsAndCreatesResponsesWhenIdsExist() {
        StubListServiceOfferingsCmd cmd = newCmd();
        ServiceOfferingVO offering1 = mock(ServiceOfferingVO.class);
        ServiceOfferingVO offering2 = mock(ServiceOfferingVO.class);
        ServiceOfferingJoinVO join1 = mock(ServiceOfferingJoinVO.class);
        ServiceOfferingJoinVO join2 = mock(ServiceOfferingJoinVO.class);
        ServiceOfferingResponse response1 = mock(ServiceOfferingResponse.class);
        ServiceOfferingResponse response2 = mock(ServiceOfferingResponse.class);
        when(offering1.getId()).thenReturn(101L);
        when(offering2.getId()).thenReturn(102L);
        when(srvOfferingDao.searchAndCount(eq(searchCriteria), any(Filter.class))).thenReturn(new Pair<>(Arrays.asList(offering1, offering2), 2));
        when(srvOfferingJoinDao.searchByIds(any(Long[].class))).thenReturn(Arrays.asList(join1, join2));

        try (MockedStatic<ViewResponseHelper> viewResponseHelper = mockStatic(ViewResponseHelper.class)) {
            viewResponseHelper.when(() -> ViewResponseHelper.createServiceOfferingResponse(any(ServiceOfferingJoinVO[].class)))
                    .thenReturn(Arrays.asList(response1, response2));

            ListResponse<ServiceOfferingResponse> response = service.searchForServiceOfferings(cmd);

            assertEquals(2, response.getCount().intValue());
            assertEquals(2, response.getResponses().size());
            verify(srvOfferingJoinDao).searchByIds(any(Long[].class));
        }
    }

    @Test
    public void searchForServiceOfferingsRejectsSystemOfferingsForNonRootCaller() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.isSystem = true;

        Assert.assertThrows(InvalidParameterValueException.class, () -> executeSearch(cmd));

        verify(srvOfferingDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void searchForServiceOfferingsRejectsNonRootDomainOutsideHierarchy() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.domainId = 99L;
        AccountVO owner = new AccountVO("owner", 2L, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        owner.setId(22L);
        DomainVO ownerDomain = new DomainVO();
        ownerDomain.setId(2L);
        when(accountMgr.finalizeOwner(any(Account.class), any(), eq(99L), any())).thenReturn(owner);
        when(accountMgr.isRootAdmin(account.getId())).thenReturn(false);
        when(domainDao.findById(2L)).thenReturn(ownerDomain);

        Assert.assertThrows(PermissionDeniedException.class, () -> executeSearch(cmd));

        verify(srvOfferingDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void searchForServiceOfferingsAllowsNonRootDomainAncestor() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.domainId = 5L;
        AccountVO owner = new AccountVO("owner", 7L, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        owner.setId(22L);
        DomainVO ownerDomain = new DomainVO();
        ownerDomain.setId(7L);
        ownerDomain.setParent(5L);
        ownerDomain.setPath("ROOT/parent/owner");
        DomainVO offeringDomain = new DomainVO();
        offeringDomain.setId(5L);
        when(accountMgr.finalizeOwner(any(Account.class), any(), eq(5L), any())).thenReturn(owner);
        when(accountMgr.isNormalUser(owner.getId())).thenReturn(true);
        when(domainDao.findById(7L)).thenReturn(ownerDomain);
        when(domainDao.findById(5L)).thenReturn(offeringDomain);
        when(domainDao.getDomainParentIds(7L)).thenReturn(new java.util.HashSet<>(Arrays.asList(1L, 5L, 7L)));

        executeSearch(cmd);

        ArgumentCaptor<Object[]> domainIds = ArgumentCaptor.forClass(Object[].class);
        verify(searchCriteria).setJoinParameters(eq("domainDetailSearchNormalUser"), eq("domainIdIN"), domainIds.capture());
        assertTrue(Arrays.asList(domainIds.getValue()).containsAll(Arrays.asList(1L, 5L, 7L)));
    }

    @Test
    public void searchForServiceOfferingsRejectsRecursiveForNormalOwner() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.recursive = true;

        Assert.assertThrows(InvalidParameterValueException.class, () -> executeSearch(cmd));

        verify(srvOfferingDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void searchForServiceOfferingsAddsChildDomainsForRecursiveDomainAdmin() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.recursive = true;
        AccountVO owner = new AccountVO("domainadmin", 5L, "domain", Account.Type.DOMAIN_ADMIN, UUID.randomUUID().toString());
        owner.setId(33L);
        DomainVO ownerDomain = new DomainVO();
        ownerDomain.setId(5L);
        ownerDomain.setPath("ROOT/domain");
        when(accountMgr.finalizeOwner(any(Account.class), any(), any(), any())).thenReturn(owner);
        when(accountMgr.isNormalUser(owner.getId())).thenReturn(false);
        when(accountMgr.isDomainAdmin(owner.getId())).thenReturn(true);
        when(domainDao.findById(5L)).thenReturn(ownerDomain);
        when(domainDao.getDomainParentIds(5L)).thenReturn(new java.util.HashSet<>(Arrays.asList(1L, 5L)));
        when(domainDao.getDomainChildrenIds("ROOT/domain")).thenReturn(Arrays.asList(6L, 7L));

        executeSearch(cmd);

        ArgumentCaptor<Object[]> domainIds = ArgumentCaptor.forClass(Object[].class);
        verify(searchCriteria).setJoinParameters(eq("domainDetailSearchNormalUser"), eq("domainIdIN"), domainIds.capture());
        assertTrue(Arrays.asList(domainIds.getValue()).containsAll(Arrays.asList(1L, 5L, 6L, 7L)));
    }

    @Test
    public void searchForServiceOfferingsAppliesKeywordParameters() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.keyword = "small";

        executeSearch(cmd);

        verify(searchCriteria).setParameters("keywordName", "%small%");
        verify(searchCriteria).setParameters("keywordDisplayText", "%small%");
    }

    @Test
    public void searchForServiceOfferingsAppliesSimpleCommandFilters() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.id = 44L;
        cmd.serviceOfferingName = "small";
        cmd.isSystem = true;
        cmd.systemVmType = VirtualMachine.Type.ConsoleProxy.toString();
        cmd.state = ServiceOffering.State.Inactive;
        cmd.vgpuProfileId = 55L;
        cmd.gpuEnabled = true;
        makeCallerRootAdmin();

        executeSearch(cmd);

        verify(searchCriteria).setParameters("id", 44L);
        verify(searchCriteria).setParameters("name", "small");
        verify(searchCriteria).setParameters("systemUse", true);
        verify(searchCriteria).setParameters("svmType", VirtualMachine.Type.ConsoleProxy.toString());
        verify(searchCriteria).setParameters("state", ServiceOffering.State.Inactive);
        verify(searchCriteria).setParameters("vgpuProfileId", 55L);
        verify(srvOfferingDao).addCheckForGpuEnabled(serviceOfferingSearchBuilder, true);
    }

    @Test
    public void searchForServiceOfferingsAppliesLocalStorageType() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.storageType = ServiceOffering.StorageType.local.toString();

        executeSearch(cmd);

        verify(searchCriteria).addAnd("useLocalStorage", SearchCriteria.Op.EQ, true);
    }

    @Test
    public void searchForServiceOfferingsAppliesSharedStorageType() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.storageType = ServiceOffering.StorageType.shared.toString();

        executeSearch(cmd);

        verify(searchCriteria).addAnd("useLocalStorage", SearchCriteria.Op.EQ, false);
    }

    @Test
    public void getHostTagsFromTemplateReturnsEmptyWhenTemplateIdIsNull() {
        List<String> result = service.getHostTagsFromTemplateForServiceOfferingsListing(account, null);

        assertTrue(result.isEmpty());
        verify(templateDao, never()).findByIdIncludingRemoved(any());
    }

    @Test
    public void getHostTagsFromTemplateThrowsWhenTemplateIsMissing() {
        when(templateDao.findByIdIncludingRemoved(1L)).thenReturn(null);

        Assert.assertThrows(InvalidParameterValueException.class,
                () -> service.getHostTagsFromTemplateForServiceOfferingsListing(account, 1L));
    }

    @Test
    public void getHostTagsFromTemplateChecksAccessForNonAdminAndReturnsTag() {
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(templateDao.findByIdIncludingRemoved(1L)).thenReturn(template);
        when(template.getTemplateTag()).thenReturn("template-tag");

        List<String> result = service.getHostTagsFromTemplateForServiceOfferingsListing(account, 1L);

        assertEquals(Collections.singletonList("template-tag"), result);
        verify(accountMgr).checkAccess(account, null, false, template);
    }

    @Test
    public void getHostTagsFromTemplateSkipsAccessForAdmin() {
        AccountVO admin = new AccountVO("admin", 1L, "domain", Account.Type.ADMIN, UUID.randomUUID().toString());
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(templateDao.findByIdIncludingRemoved(1L)).thenReturn(template);
        when(template.getTemplateTag()).thenReturn("template-tag");

        List<String> result = service.getHostTagsFromTemplateForServiceOfferingsListing(admin, 1L);

        assertEquals(Collections.singletonList("template-tag"), result);
        verify(accountMgr, never()).checkAccess(any(Account.class), any(), anyBoolean(), any());
    }

    @Test
    public void addVmCurrentClusterHostTagsUsesLastHostAndAddsMissingClusterTags() {
        VMInstanceVO vmInstance = mock(VMInstanceVO.class);
        HostVO host = mock(HostVO.class);
        when(vmInstance.getHostId()).thenReturn(null);
        when(vmInstance.getLastHostId()).thenReturn(1L);
        when(hostDao.findById(1L)).thenReturn(host);
        when(host.getClusterId()).thenReturn(10L);
        when(hostTagDao.listByClusterId(10L)).thenReturn(Arrays.asList("tag1", "tag2"));
        List<String> hostTags = new ArrayList<>(Collections.singletonList("tag1"));

        service.addVmCurrentClusterHostTags(vmInstance, hostTags);

        assertEquals(Arrays.asList("tag1", "tag2"), hostTags);
    }

    @Test
    public void addVmCurrentClusterHostTagsNoopsWhenVmOrHostIdOrHostIsMissing() {
        List<String> hostTags = new ArrayList<>(Collections.singletonList("tag1"));
        service.addVmCurrentClusterHostTags(null, hostTags);
        verifyNoInteractions(hostDao, hostTagDao);

        VMInstanceVO vmWithoutHost = mock(VMInstanceVO.class);
        when(vmWithoutHost.getHostId()).thenReturn(null);
        when(vmWithoutHost.getLastHostId()).thenReturn(null);
        service.addVmCurrentClusterHostTags(vmWithoutHost, hostTags);
        verifyNoInteractions(hostDao, hostTagDao);

        VMInstanceVO vmWithMissingHost = mock(VMInstanceVO.class);
        when(vmWithMissingHost.getHostId()).thenReturn(2L);
        clearInvocations(hostDao, hostTagDao);
        service.addVmCurrentClusterHostTags(vmWithMissingHost, hostTags);

        verify(hostDao).findById(2L);
        verifyNoInteractions(hostTagDao);
        assertEquals(Collections.singletonList("tag1"), hostTags);
    }

    @Test
    public void searchForServiceOfferingsWithVmIdAppliesVmScaleFilters() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.virtualMachineId = 700L;
        VMInstanceVO vmInstance = mock(VMInstanceVO.class);
        ServiceOfferingVO currentOffering = new ServiceOfferingVO("current", 2, 2048, 1000, null, null,
                false, "current", false, VirtualMachine.Type.User, false);
        currentOffering.setId(801L);
        currentOffering.setDiskOfferingId(901L);
        currentOffering.setDiskOfferingStrictness(true);
        currentOffering.setDynamicScalingEnabled(true);
        DiskOfferingVO diskOffering = new DiskOfferingVO();
        when(vmInstance.getId()).thenReturn(700L);
        when(vmInstance.getServiceOfferingId()).thenReturn(801L);
        when(vmInstance.getState()).thenReturn(VirtualMachine.State.Running);
        when(vmInstance.getType()).thenReturn(VirtualMachine.Type.User);
        when(vmInstanceDao.findById(700L)).thenReturn(vmInstance);
        when(srvOfferingDao.findByIdIncludingRemoved(700L, 801L)).thenReturn(currentOffering);
        when(diskOfferingDao.findByIdIncludingRemoved(901L)).thenReturn(diskOffering);
        when(virtualMachineManager.isRootVolumeOnLocalStorage(700L)).thenReturn(true);

        executeSearch(cmd);

        verify(searchCriteria).setParameters("idNEQ", 801L);
        verify(searchCriteria).setParameters("diskOfferingId", 901L);
        verify(searchCriteria).setParameters("diskOfferingStrictness", true);
        verify(searchCriteria).setJoinParameters("diskOfferingSearch", "useLocalStorage", true);
        verify(searchCriteria).setParameters("dynamicScalingEnabled", true);
    }

    @Test
    public void searchForServiceOfferingsWithRunningUserVmLoadsDynamicDetailsWhenOfferingComputeIsNull() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.virtualMachineId = 701L;
        VMInstanceVO vmInstance = mock(VMInstanceVO.class);
        ServiceOfferingVO currentOffering = mock(ServiceOfferingVO.class);
        DiskOfferingVO diskOffering = new DiskOfferingVO();
        UserVmVO userVm = mock(UserVmVO.class);
        when(vmInstance.getId()).thenReturn(701L);
        when(vmInstance.getServiceOfferingId()).thenReturn(802L);
        when(vmInstance.getState()).thenReturn(VirtualMachine.State.Running);
        when(vmInstance.getType()).thenReturn(VirtualMachine.Type.User);
        when(vmInstanceDao.findById(701L)).thenReturn(vmInstance);
        when(srvOfferingDao.findByIdIncludingRemoved(701L, 802L)).thenReturn(currentOffering);
        when(currentOffering.getDiskOfferingId()).thenReturn(902L);
        when(currentOffering.getDiskOfferingStrictness()).thenReturn(false);
        when(currentOffering.getCpu()).thenReturn(null);
        when(currentOffering.getRamSize()).thenReturn(null);
        when(currentOffering.getSpeed()).thenReturn(null);
        when(currentOffering.isDynamic()).thenReturn(true);
        when(currentOffering.isDynamicScalingEnabled()).thenReturn(false);
        when(diskOfferingDao.findByIdIncludingRemoved(902L)).thenReturn(diskOffering);
        when(userVmDao.findById(701L)).thenReturn(userVm);
        when(userVm.getDetails()).thenReturn(java.util.Map.of(ApiConstants.CPU_NUMBER, "4", ApiConstants.CPU_SPEED, "1200", ApiConstants.MEMORY, "4096"));

        executeSearch(cmd);

        verify(userVmDao, atLeastOnce()).loadDetails(userVm);
        verify(searchCriteria).setParameters("vmCpu", 4);
        verify(searchCriteria).setParameters("speedGTEQ", 1200);
        verify(searchCriteria).setParameters("vmMemory", 4096);
        verify(searchCriteria).setParameters("dynamicScalingEnabled", false);
    }

    @Test
    public void searchForServiceOfferingsUsesQueryServiceSortConfig() {
        StubListServiceOfferingsCmd cmd = newCmd();

        executeSearch(cmd);

        Assert.assertNotNull(QueryService.SortKeyAscending.value());
    }

    @Test
    public void addVmCurrentClusterHostTagsNoopsWhenClusterTagsAreEmpty() {
        VMInstanceVO vmInstance = mock(VMInstanceVO.class);
        HostVO host = mock(HostVO.class);
        when(vmInstance.getHostId()).thenReturn(1L);
        when(hostDao.findById(1L)).thenReturn(host);
        when(host.getClusterId()).thenReturn(10L);
        when(hostTagDao.listByClusterId(10L)).thenReturn(Collections.emptyList());
        List<String> hostTags = new ArrayList<>(Collections.singletonList("tag1"));

        service.addVmCurrentClusterHostTags(vmInstance, hostTags);

        assertEquals(Collections.singletonList("tag1"), hostTags);
    }

    @Test
    public void searchForServiceOfferingsWithDiskOfferingStorageTagsAddsFindInSetJoinParametersWhenConfigIsEnabled() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.virtualMachineId = 702L;
        VMInstanceVO vmInstance = mock(VMInstanceVO.class);
        ServiceOfferingVO currentOffering = new ServiceOfferingVO("current", 2, 2048, 1000, null, null,
                false, "current", false, VirtualMachine.Type.User, false);
        currentOffering.setId(803L);
        currentOffering.setDiskOfferingId(903L);
        currentOffering.setDiskOfferingStrictness(false);
        DiskOfferingVO diskOffering = new DiskOfferingVO();
        diskOffering.setTags("fast,ssd");
        when(vmInstance.getId()).thenReturn(702L);
        when(vmInstance.getServiceOfferingId()).thenReturn(803L);
        when(vmInstance.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vmInstanceDao.findById(702L)).thenReturn(vmInstance);
        when(srvOfferingDao.findByIdIncludingRemoved(702L, 803L)).thenReturn(currentOffering);
        when(diskOfferingDao.findByIdIncludingRemoved(903L)).thenReturn(diskOffering);

        executeSearch(cmd);

        verify(searchCriteria).setJoinParameters("diskOfferingSearch", "storageTagfast", "fast");
        verify(searchCriteria).setJoinParameters("diskOfferingSearch", "storageTagssd", "ssd");
    }

    @Test
    public void searchForServiceOfferingsWithCurrentOfferingHostTagsAddsHostTagParameters() {
        StubListServiceOfferingsCmd cmd = newCmd();
        cmd.virtualMachineId = 703L;
        VMInstanceVO vmInstance = mock(VMInstanceVO.class);
        ServiceOfferingVO currentOffering = new ServiceOfferingVO("current", 2, 2048, 1000, null, null,
                false, "current", false, VirtualMachine.Type.User, false);
        currentOffering.setId(804L);
        currentOffering.setDiskOfferingId(904L);
        currentOffering.setDiskOfferingStrictness(false);
        currentOffering.setHostTag("compute,fast");
        DiskOfferingVO diskOffering = new DiskOfferingVO();
        when(vmInstance.getId()).thenReturn(703L);
        when(vmInstance.getServiceOfferingId()).thenReturn(804L);
        when(vmInstance.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vmInstanceDao.findById(703L)).thenReturn(vmInstance);
        when(srvOfferingDao.findByIdIncludingRemoved(703L, 804L)).thenReturn(currentOffering);
        when(diskOfferingDao.findByIdIncludingRemoved(904L)).thenReturn(diskOffering);

        executeSearch(cmd);

        verify(searchCriteria).setParameters("hostTagcompute", "compute");
        verify(searchCriteria).setParameters("hostTagfast", "fast");
    }
}
