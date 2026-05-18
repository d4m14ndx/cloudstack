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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.command.admin.storage.ListObjectStoragePoolsCmd;
import org.apache.cloudstack.api.command.admin.user.ListUsersCmd;
import org.apache.cloudstack.api.command.admin.vm.ListAffectedVmsForStorageScopeChangeCmd;
import org.apache.cloudstack.api.command.user.account.ListAccountsCmd;
import org.apache.cloudstack.api.command.user.bucket.ListBucketsCmd;
import org.apache.cloudstack.api.command.user.event.ListEventsCmd;
import org.apache.cloudstack.api.command.user.offering.ListDiskOfferingsCmd;
import org.apache.cloudstack.api.command.user.offering.ListServiceOfferingsCmd;
import org.apache.cloudstack.api.command.user.resource.ListDetailOptionsCmd;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.DetailOptionsResponse;
import org.apache.cloudstack.api.response.EventResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ObjectStoreResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.UserResponse;
import org.apache.cloudstack.api.response.VirtualMachineResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
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

import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.api.query.dao.UserAccountJoinDao;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.AccountJoinVO;
import com.cloud.api.query.vo.EventJoinVO;
import com.cloud.api.query.vo.UserAccountJoinVO;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.EventVO;
import com.cloud.event.dao.EventDao;
import com.cloud.event.dao.EventJoinDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.VNF;
import com.cloud.network.dao.NetworkVO;
import com.cloud.server.ResourceTag;
import com.cloud.storage.BucketVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.dao.BucketDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class QueryManagerImplTest {
    public static final long USER_ID = 1;
    public static final long ACCOUNT_ID = 1;

    @Spy
    @InjectMocks
    private QueryManagerImpl queryManagerImplSpy;

    @Mock
    EntityManager entityManager;

    @Mock
    AccountManager accountManager;

    @Mock
    EventDao eventDao;

    @Mock
    EventJoinDao eventJoinDao;

    @Mock
    Account accountMock;

    @Mock
    TemplateJoinDao templateJoinDaoMock;

    @Mock
    SearchCriteria searchCriteriaMock;

    @Mock
    ObjectStoreDao objectStoreDao;

    @Mock
    VMInstanceDao vmInstanceDao;

    @Mock
    PrimaryDataStoreDao storagePoolDao;

    @Mock
    HostDao hostDao;

    @Mock
    ClusterDao clusterDao;

    @Mock
    BucketDao bucketDao;

    @Mock
    UserVmJoinDao userVmJoinDao;

    @Mock
    UserAccountJoinDao userAccountJoinDao;

    @Mock
    DomainDao domainDao;

    @Mock
    AccountDao accountDao;

    @Mock
    ExtensionHelper extensionHelper;

    @Mock
    SnapshotQueryService snapshotQueryService;

    @Mock
    ImageStoreQueryService imageStoreQueryService;

    @Mock
    TemplateQueryService templateQueryService;

    @Mock
    DiskOfferingQueryService diskOfferingQueryService;

    @Mock
    ServiceOfferingQueryService serviceOfferingQueryService;

    @Mock
    AccountQueryService accountQueryService;

    private AccountVO account;
    private UserVO user;

    @Before
    public void setUp() throws Exception {
        setupCommonMocks();
    }

    @InjectMocks
    private QueryManagerImpl queryManager = new QueryManagerImpl();

    private void setupCommonMocks() {
        account = new AccountVO("testaccount", 1L, "networkdomain", Account.Type.NORMAL, "uuid");
        account.setId(ACCOUNT_ID);
        user = new UserVO(1, "testuser", "password", "firstname", "lastName", "email", "timezone",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);
        Mockito.when(accountManager.isRootAdmin(account.getId())).thenReturn(false);
        final SearchBuilder<EventVO> eventSearchBuilder = mock(SearchBuilder.class);
        final SearchCriteria<EventVO> eventSearchCriteria = mock(SearchCriteria.class);
        final EventVO eventVO = mock(EventVO.class);
        when(eventSearchBuilder.entity()).thenReturn(eventVO);
        when(eventSearchBuilder.create()).thenReturn(eventSearchCriteria);
        Mockito.when(eventDao.createSearchBuilder()).thenReturn(eventSearchBuilder);

        // Wire the extracted EventQueryService with the same mocks so existing
        // searchForEvents test paths continue to flow through the orchestration
        // (QueryManagerImpl.searchForEvents -> EventQueryServiceImpl).
        EventQueryServiceImpl eventQuery = new EventQueryServiceImpl();
        ReflectionTestUtils.setField(eventQuery, "accountMgr", accountManager);
        ReflectionTestUtils.setField(eventQuery, "entityManager", entityManager);
        ReflectionTestUtils.setField(eventQuery, "eventDao", eventDao);
        ReflectionTestUtils.setField(eventQuery, "eventJoinDao", eventJoinDao);
        ReflectionTestUtils.setField(queryManagerImplSpy, "eventQueryService", eventQuery);
        ReflectionTestUtils.setField(queryManager, "eventQueryService", eventQuery);

        // Wire the SnapshotQueryService mock so QueryManagerImpl.snapshotQueryService is non-null.
        ReflectionTestUtils.setField(queryManagerImplSpy, "snapshotQueryService", snapshotQueryService);
        ReflectionTestUtils.setField(queryManager, "snapshotQueryService", snapshotQueryService);

        // Wire the ImageStoreQueryService mock so QueryManagerImpl.imageStoreQueryService is non-null.
        ReflectionTestUtils.setField(queryManagerImplSpy, "imageStoreQueryService", imageStoreQueryService);
        ReflectionTestUtils.setField(queryManager, "imageStoreQueryService", imageStoreQueryService);

        // Wire the TemplateQueryService mock so QueryManagerImpl.templateQueryService is non-null.
        ReflectionTestUtils.setField(queryManagerImplSpy, "templateQueryService", templateQueryService);
        ReflectionTestUtils.setField(queryManager, "templateQueryService", templateQueryService);

        ReflectionTestUtils.setField(queryManagerImplSpy, "diskOfferingQueryService", diskOfferingQueryService);
        ReflectionTestUtils.setField(queryManager, "diskOfferingQueryService", diskOfferingQueryService);

        ReflectionTestUtils.setField(queryManagerImplSpy, "serviceOfferingQueryService", serviceOfferingQueryService);
        ReflectionTestUtils.setField(queryManager, "serviceOfferingQueryService", serviceOfferingQueryService);

        ReflectionTestUtils.setField(queryManagerImplSpy, "accountQueryService", accountQueryService);
        ReflectionTestUtils.setField(queryManager, "accountQueryService", accountQueryService);
    }

    private ListEventsCmd setupMockListEventsCmd() {
        ListEventsCmd cmd = mock(ListEventsCmd.class);
        Mockito.when(cmd.getEntryTime()).thenReturn(null);
        Mockito.when(cmd.listAll()).thenReturn(true);
        return cmd;
    }

    @Test
    public void searchForEventsSuccess() {
        ListEventsCmd cmd = setupMockListEventsCmd();
        String uuid = UUID.randomUUID().toString();
        Mockito.when(cmd.getResourceId()).thenReturn(uuid);
        Mockito.when(cmd.getResourceType()).thenReturn(ApiCommandResourceType.Network.toString());
        List<EventVO> events = new ArrayList<>();
        events.add(mock(EventVO.class));
        events.add(mock(EventVO.class));
        events.add(mock(EventVO.class));
        Pair<List<EventVO>, Integer> pair = new Pair<>(events, events.size());

        List<EventJoinVO> eventJoins = new ArrayList<>();
        eventJoins.add(mock(EventJoinVO.class));
        eventJoins.add(mock(EventJoinVO.class));
        eventJoins.add(mock(EventJoinVO.class));

        NetworkVO network = mock(NetworkVO.class);
        Mockito.when(network.getId()).thenReturn(1L);
        Mockito.when(network.getAccountId()).thenReturn(account.getId());
        Mockito.when(entityManager.findByUuidIncludingRemoved(Network.class, uuid)).thenReturn(network);
        Mockito.doNothing().when(accountManager).checkAccess(account, SecurityChecker.AccessType.ListEntry, true, network);
        Mockito.when(eventDao.searchAndCount(Mockito.any(), Mockito.any(Filter.class))).thenReturn(pair);
        Mockito.lenient().when(eventJoinDao.searchByIds(Mockito.any())).thenReturn(eventJoins);
        List<EventResponse> respList = new ArrayList<EventResponse>();
        for (EventJoinVO vt : eventJoins) {
            respList.add(eventJoinDao.newEventResponse(vt));
        }
        try (MockedStatic<ViewResponseHelper> ignored = Mockito.mockStatic(ViewResponseHelper.class)) {
            Mockito.when(ViewResponseHelper.createEventResponse(Mockito.any())).thenReturn(respList);
            ListResponse<EventResponse> result = queryManager.searchForEvents(cmd);
            Assert.assertEquals((int) result.getCount(), events.size());
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForEventsFailResourceTypeNull() {
        ListEventsCmd cmd  = setupMockListEventsCmd();
        String uuid = UUID.randomUUID().toString();
        Mockito.when(cmd.getResourceId()).thenReturn(uuid);
        Mockito.when(cmd.getResourceType()).thenReturn(null);
        queryManager.searchForEvents(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForEventsFailResourceTypeInvalid() {
        ListEventsCmd cmd  = setupMockListEventsCmd();
        Mockito.when(cmd.getResourceType()).thenReturn("Some");
        queryManager.searchForEvents(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForEventsFailResourceIdInvalid() {
        ListEventsCmd cmd  = setupMockListEventsCmd();
        Mockito.when(cmd.getResourceId()).thenReturn("random");
        Mockito.lenient().when(cmd.getResourceType()).thenReturn(ApiCommandResourceType.VirtualMachine.toString());
        queryManager.searchForEvents(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForEventsFailResourceNotFound() {
        ListEventsCmd cmd  = setupMockListEventsCmd();
        String uuid = UUID.randomUUID().toString();
        Mockito.when(cmd.getResourceId()).thenReturn(uuid);
        Mockito.when(cmd.getResourceType()).thenReturn(ApiCommandResourceType.VirtualMachine.toString());
        Mockito.when(entityManager.findByUuidIncludingRemoved(VirtualMachine.class, uuid)).thenReturn(null);
        queryManager.searchForEvents(cmd);
    }

    @Test(expected = PermissionDeniedException.class)
    public void searchForEventsFailPermissionDenied() {
        ListEventsCmd cmd  = setupMockListEventsCmd();
        String uuid = UUID.randomUUID().toString();
        Mockito.when(cmd.getResourceId()).thenReturn(uuid);
        Mockito.when(cmd.getResourceType()).thenReturn(ApiCommandResourceType.Network.toString());
        NetworkVO network = mock(NetworkVO.class);
        Mockito.when(network.getId()).thenReturn(1L);
        Mockito.when(network.getAccountId()).thenReturn(2L);
        Mockito.when(entityManager.findByUuidIncludingRemoved(Network.class, uuid)).thenReturn(network);
        Mockito.doThrow(new PermissionDeniedException("Denied")).when(accountManager).checkAccess(account, SecurityChecker.AccessType.ListEntry, false, network);
        queryManager.searchForEvents(cmd);
    }

    @Test
    public void listVnfDetailOptionsCmd() {
        ListDetailOptionsCmd cmd = mock(ListDetailOptionsCmd.class);
        Mockito.lenient().when(cmd.getResourceType()).thenReturn(ResourceTag.ResourceObjectType.VnfTemplate);

        // templateQueryService now owns the implementation — fabricate a response
        Map<String, List<String>> vnfOptions = new java.util.HashMap<>();
        for (VNF.AccessDetail detail : VNF.AccessDetail.values()) {
            if (VNF.AccessDetail.ACCESS_METHODS.equals(detail)) {
                vnfOptions.put(detail.name().toLowerCase(),
                        Arrays.stream(VNF.AccessMethod.values()).map(VNF.AccessMethod::toString).sorted().collect(Collectors.toList()));
            } else {
                vnfOptions.put(detail.name().toLowerCase(), Collections.emptyList());
            }
        }
        for (VNF.VnfDetail detail : VNF.VnfDetail.values()) {
            vnfOptions.put(detail.name().toLowerCase(), Collections.emptyList());
        }
        DetailOptionsResponse fakeResponse = new DetailOptionsResponse(vnfOptions);
        Mockito.when(templateQueryService.listDetailOptions(cmd)).thenReturn(fakeResponse);

        DetailOptionsResponse response = queryManager.listDetailOptions(cmd);
        Map<String, List<String>> options = response.getDetails();

        int expectedLength = VNF.AccessDetail.values().length + VNF.VnfDetail.values().length;
        Assert.assertEquals(expectedLength, options.size());
        Set<String> keys = options.keySet();
        for (VNF.AccessDetail detail : VNF.AccessDetail.values()) {
            Assert.assertTrue(keys.contains(detail.name().toLowerCase()));
        }
        for (VNF.VnfDetail detail : VNF.VnfDetail.values()) {
            Assert.assertTrue(keys.contains(detail.name().toLowerCase()));
        }
        List<String> expectedAccessMethods = Arrays.stream(VNF.AccessMethod.values()).map(method -> method.toString()).sorted().collect(Collectors.toList());
        Assert.assertEquals(expectedAccessMethods, options.get(VNF.AccessDetail.ACCESS_METHODS.name().toLowerCase()));
    }

    @Test
    public void applyPublicTemplateRestrictionsTestDelegatesToTemplateQueryService() {
        // The wrapper in QueryManagerImpl now delegates to templateQueryService.
        queryManagerImplSpy.applyPublicTemplateSharingRestrictions(searchCriteriaMock, accountMock);
        verify(templateQueryService).applyPublicTemplateSharingRestrictions(searchCriteriaMock, accountMock);
    }

    @Test
    public void addDomainIdToSetIfDomainDoesNotShareTemplatesDelegatesToTemplateQueryService() {
        long domainId = 1L;
        Set<Long> set = new HashSet<>();

        queryManagerImplSpy.addDomainIdToSetIfDomainDoesNotShareTemplates(domainId, accountMock, set);
        verify(templateQueryService).addDomainIdToSetIfDomainDoesNotShareTemplates(domainId, accountMock, set);
    }

    @Test
    public void testSearchForObjectStores() {
        ListObjectStoragePoolsCmd cmd = new ListObjectStoragePoolsCmd();
        List<ObjectStoreVO> objectStores = new ArrayList<>();
        ObjectStoreVO os1 = new ObjectStoreVO();
        os1.setName("MinIOStore");
        ObjectStoreVO os2 = new ObjectStoreVO();
        os1.setName("Simulator");
        objectStores.add(os1);
        objectStores.add(os2);
        SearchBuilder<ObjectStoreVO> sb = mock(SearchBuilder.class);
        ObjectStoreVO objectStoreVO = mock(ObjectStoreVO.class);
        when(sb.entity()).thenReturn(objectStoreVO);
        when(objectStoreDao.createSearchBuilder()).thenReturn(sb);
        when(objectStoreDao.searchAndCount(any(), any())).thenReturn(new Pair<>(objectStores, 2));
        ListResponse<ObjectStoreResponse> result = queryManagerImplSpy.searchForObjectStores(cmd);
        assertEquals(2, result.getCount().intValue());
    }

    @Test
    public void testSearchForBuckets() {
        ListBucketsCmd listBucketsCmd = new ListBucketsCmd();
        List<BucketVO> buckets = new ArrayList<>();
        BucketVO b1 = new BucketVO();
        b1.setName("test-bucket-1");
        BucketVO b2 = new BucketVO();
        b2.setName("test-bucket-1");
        buckets.add(b1);
        buckets.add(b2);
        SearchBuilder<BucketVO> sb = mock(SearchBuilder.class);
        BucketVO bucketVO = mock(BucketVO.class);
        when(sb.entity()).thenReturn(bucketVO);
        when(bucketDao.createSearchBuilder()).thenReturn(sb);
        when(bucketDao.searchAndCount(any(), any())).thenReturn(new Pair<>(buckets, 2));
        queryManagerImplSpy.searchForBuckets(listBucketsCmd);
    }

    @Test
    public void searchForServiceOfferingsDelegatesToServiceOfferingQueryService() {
        ListServiceOfferingsCmd cmd = mock(ListServiceOfferingsCmd.class);
        ListResponse<ServiceOfferingResponse> expected = new ListResponse<>();
        when(serviceOfferingQueryService.searchForServiceOfferings(cmd)).thenReturn(expected);

        ListResponse<ServiceOfferingResponse> actual = queryManager.searchForServiceOfferings(cmd);

        Assert.assertSame(expected, actual);
        verify(serviceOfferingQueryService).searchForServiceOfferings(cmd);
    }

    @Test
    public void getHostTagsFromTemplateForServiceOfferingsListingDelegatesToServiceOfferingQueryService() {
        Account caller = mock(Account.class);
        List<String> expected = Collections.singletonList("tag");
        when(serviceOfferingQueryService.getHostTagsFromTemplateForServiceOfferingsListing(caller, 1L)).thenReturn(expected);

        List<String> actual = queryManager.getHostTagsFromTemplateForServiceOfferingsListing(caller, 1L);

        Assert.assertSame(expected, actual);
        verify(serviceOfferingQueryService).getHostTagsFromTemplateForServiceOfferingsListing(caller, 1L);
    }

    @Test
    public void addVmCurrentClusterHostTagsDelegatesToServiceOfferingQueryService() {
        VMInstanceVO vmInstance = mock(VMInstanceVO.class);
        List<String> hostTags = new ArrayList<>();

        queryManagerImplSpy.addVmCurrentClusterHostTags(vmInstance, hostTags);

        verify(serviceOfferingQueryService).addVmCurrentClusterHostTags(vmInstance, hostTags);
    }

    public void testListAffectedVmsForScopeChange() {
        Long clusterId = 1L;
        Long poolId = 2L;
        Long hostId = 3L;
        Long vmId = 4L;
        String vmName = "VM1";

        ListAffectedVmsForStorageScopeChangeCmd cmd = new ListAffectedVmsForStorageScopeChangeCmd();
        ReflectionTestUtils.setField(cmd, "clusterIdForScopeChange", clusterId);
        ReflectionTestUtils.setField(cmd, "storageId", poolId);

        StoragePoolVO pool = mock(StoragePoolVO.class);
        Mockito.when(pool.getScope()).thenReturn(ScopeType.CLUSTER);
        Mockito.when(storagePoolDao.findById(poolId)).thenReturn(pool);
        ListResponse<VirtualMachineResponse> response = queryManager.listAffectedVmsForStorageScopeChange(cmd);
        Assert.assertEquals(response.getResponses().size(), 0);

        VMInstanceVO instance = mock(VMInstanceVO.class);
        UserVmJoinVO userVM = mock(UserVmJoinVO.class);
        String instanceUuid = String.valueOf(UUID.randomUUID());
        Pair<List<VMInstanceVO>, Integer> vms = new Pair<>(List.of(instance), 1);
        HostVO host = mock(HostVO.class);
        ClusterVO cluster = mock(ClusterVO.class);

        Mockito.when(pool.getScope()).thenReturn(ScopeType.ZONE);
        Mockito.when(instance.getUuid()).thenReturn(instanceUuid);
        Mockito.when(instance.getType()).thenReturn(VirtualMachine.Type.Instance);
        Mockito.when(instance.getHostId()).thenReturn(hostId);
        Mockito.when(instance.getId()).thenReturn(vmId);
        Mockito.when(userVM.getDisplayName()).thenReturn(vmName);
        Mockito.when(vmInstanceDao.listByVmsNotInClusterUsingPool(clusterId, poolId)).thenReturn(vms);
        Mockito.when(userVmJoinDao.findById(vmId)).thenReturn(userVM);
        Mockito.when(hostDao.findById(hostId)).thenReturn(host);
        Mockito.when(host.getClusterId()).thenReturn(clusterId);
        Mockito.when(clusterDao.findById(clusterId)).thenReturn(cluster);

        response = queryManager.listAffectedVmsForStorageScopeChange(cmd);
        Assert.assertEquals(response.getResponses().get(0).getId(), instanceUuid);
        Assert.assertEquals(response.getResponses().get(0).getName(), vmName);
    }

    @Test
    public void testSearchForUsers() {
        ListUsersCmd cmd = mock(ListUsersCmd.class);
        String username = "Admin";
        String accountName = "Admin";
        Account.Type accountType = Account.Type.ADMIN;
        Long domainId = 1L;
        String apiKeyAccess = "Disabled";
        User.Source userSource = User.Source.NATIVE;
        Mockito.when(cmd.getUsername()).thenReturn(username);
        Mockito.when(cmd.getAccountName()).thenReturn(accountName);
        Mockito.when(cmd.getAccountType()).thenReturn(accountType);
        Mockito.when(cmd.getDomainId()).thenReturn(domainId);
        Mockito.when(cmd.getApiKeyAccess()).thenReturn(apiKeyAccess);
        Mockito.when(cmd.getUserSource()).thenReturn(userSource);

        UserAccountJoinVO user = new UserAccountJoinVO();
        DomainVO domain = mock(DomainVO.class);
        SearchBuilder<UserAccountJoinVO> sb = mock(SearchBuilder.class);
        SearchCriteria<UserAccountJoinVO> sc = mock(SearchCriteria.class);
        List<UserAccountJoinVO> users = new ArrayList<>();
        Pair<List<UserAccountJoinVO>, Integer> result = new Pair<>(users, 0);
        UserResponse response = mock(UserResponse.class);

        Mockito.when(userAccountJoinDao.createSearchBuilder()).thenReturn(sb);
        Mockito.when(sb.entity()).thenReturn(user);
        Mockito.when(sb.create()).thenReturn(sc);
        Mockito.when(userAccountJoinDao.searchAndCount(any(SearchCriteria.class), any(Filter.class))).thenReturn(result);

        queryManager.searchForUsers(ResponseObject.ResponseView.Restricted, cmd);

        verify(sc).setParameters("username", username);
        verify(sc).setParameters("accountName", accountName);
        verify(sc).setParameters("type", accountType);
        verify(sc).setParameters("domainId", domainId);
        verify(sc).setParameters("apiKeyAccess", false);
        verify(sc).setParameters("userSource", userSource.toString());
        verify(userAccountJoinDao, Mockito.times(1)).searchAndCount(
                any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void testSearchForAccounts() {
        ListAccountsCmd cmd = mock(ListAccountsCmd.class);
        AccountJoinVO accountJoin = mock(AccountJoinVO.class);
        Pair<List<AccountJoinVO>, Integer> result = new Pair<>(Collections.singletonList(accountJoin), 1);
        AccountResponse accountResponse = mock(AccountResponse.class);
        List<AccountResponse> accountResponses = Collections.singletonList(accountResponse);
        when(accountQueryService.searchForAccountsInternal(cmd)).thenReturn(result);

        try (MockedStatic<ViewResponseHelper> viewResponseHelperMocked = Mockito.mockStatic(ViewResponseHelper.class)) {
            viewResponseHelperMocked.when(() -> ViewResponseHelper.createAccountResponse(
                    Mockito.eq(ResponseObject.ResponseView.Restricted), Mockito.any(), Mockito.any(AccountJoinVO[].class))).thenReturn(accountResponses);

            ListResponse<AccountResponse> response = queryManager.searchForAccounts(cmd);

            Assert.assertSame(accountResponses, response.getResponses());
            assertEquals(Integer.valueOf(1), response.getCount());
        }

        verify(accountQueryService, Mockito.times(1)).searchForAccountsInternal(cmd);
    }

    @Test
    public void searchForDiskOfferingsDelegatesToDiskOfferingQueryService() {
        ListDiskOfferingsCmd cmd = mock(ListDiskOfferingsCmd.class);
        ListResponse<DiskOfferingResponse> expected = new ListResponse<>();
        when(diskOfferingQueryService.searchForDiskOfferings(cmd)).thenReturn(expected);

        ListResponse<DiskOfferingResponse> actual = queryManager.searchForDiskOfferings(cmd);

        Assert.assertSame(expected, actual);
        verify(diskOfferingQueryService).searchForDiskOfferings(cmd);
    }

    @Test
    public void updateHostsExtensions_emptyList_noAction() {
        queryManagerImplSpy.updateHostsExtensions(Collections.emptyList());
        // No exception, nothing to verify
    }

    @Test
    public void updateHostsExtensions_nullList_noAction() {
        queryManagerImplSpy.updateHostsExtensions(null);
        // No exception, nothing to verify
    }

    @Test
    public void updateHostsExtensions_withHostResponses_setsExtension() {
        HostResponse host1 = mock(HostResponse.class);
        HostResponse host2 = mock(HostResponse.class);

        when(host1.getClusterInternalId()).thenReturn(1L);
        when(host1.getHypervisor()).thenReturn(Hypervisor.HypervisorType.External.name());
        when(host2.getClusterInternalId()).thenReturn(2L);
        when(host2.getHypervisor()).thenReturn(Hypervisor.HypervisorType.External.name());

        Extension ext1 = mock(Extension.class);
        when(ext1.getUuid()).thenReturn("a");
        Extension ext2 = mock(Extension.class);
        when(ext2.getUuid()).thenReturn("b");

        when(extensionHelper.getExtensionForCluster(1L)).thenReturn(ext1);
        when(extensionHelper.getExtensionForCluster(2L)).thenReturn(ext2);

        List<HostResponse> hosts = Arrays.asList(host1, host2);

        queryManagerImplSpy.updateHostsExtensions(hosts);

        verify(host1).setExtensionId("a");
        verify(host2).setExtensionId("b");
    }

}
