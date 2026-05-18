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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.api.command.user.offering.ListDiskOfferingsCmd;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.resourcedetail.DiskOfferingDetailVO;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.dao.DataCenterJoinDao;
import com.cloud.api.query.dao.DiskOfferingJoinDao;
import com.cloud.api.query.vo.DataCenterJoinVO;
import com.cloud.api.query.vo.DiskOfferingJoinVO;
import com.cloud.dc.DataCenter;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class DiskOfferingQueryServiceImplTest {

    private AccountManager accountMgr;
    private DiskOfferingJoinDao diskOfferingJoinDao;
    private DiskOfferingDetailsDao diskOfferingDetailsDao;
    private DiskOfferingDao diskOfferingDao;
    private DataCenterJoinDao dcJoinDao;
    private VolumeDao volumeDao;
    private DomainDao domainDao;
    private StoragePoolTagsDao storageTagDao;
    private UserVmDao userVmDao;

    private SearchBuilder<DiskOfferingVO> diskOfferingSearchBuilder;
    private SearchBuilder<DiskOfferingDetailVO> diskOfferingDetailSearchBuilder;
    private SearchCriteria<DiskOfferingVO> searchCriteria;

    private DiskOfferingQueryServiceImpl service;

    private AccountVO account;
    private UserVO user;
    private DomainVO callerDomain;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() {
        accountMgr = mock(AccountManager.class);
        diskOfferingJoinDao = mock(DiskOfferingJoinDao.class);
        diskOfferingDetailsDao = mock(DiskOfferingDetailsDao.class);
        diskOfferingDao = mock(DiskOfferingDao.class);
        dcJoinDao = mock(DataCenterJoinDao.class);
        volumeDao = mock(VolumeDao.class);
        domainDao = mock(DomainDao.class);
        storageTagDao = mock(StoragePoolTagsDao.class);
        userVmDao = mock(UserVmDao.class);

        diskOfferingSearchBuilder = (SearchBuilder<DiskOfferingVO>) mock(SearchBuilder.class);
        diskOfferingDetailSearchBuilder = (SearchBuilder<DiskOfferingDetailVO>) mock(SearchBuilder.class);
        searchCriteria = (SearchCriteria<DiskOfferingVO>) mock(SearchCriteria.class);

        DiskOfferingVO diskOfferingEntity = mock(DiskOfferingVO.class);
        DiskOfferingDetailVO detailEntity = mock(DiskOfferingDetailVO.class);

        when(diskOfferingDao.createSearchBuilder()).thenReturn(diskOfferingSearchBuilder);
        when(diskOfferingSearchBuilder.entity()).thenReturn(diskOfferingEntity);
        when(diskOfferingSearchBuilder.create()).thenReturn(searchCriteria);

        when(diskOfferingDetailsDao.createSearchBuilder()).thenReturn(diskOfferingDetailSearchBuilder);
        when(diskOfferingDetailSearchBuilder.entity()).thenReturn(detailEntity);

        Mockito.lenient().when(diskOfferingSearchBuilder.and()).thenReturn(diskOfferingSearchBuilder);
        Mockito.lenient().when(diskOfferingSearchBuilder.or()).thenReturn(diskOfferingSearchBuilder);
        Mockito.lenient().when(diskOfferingSearchBuilder.cp()).thenReturn(diskOfferingSearchBuilder);
        Mockito.lenient().when(diskOfferingSearchBuilder.op(any(String.class), any(), any(SearchCriteria.Op.class))).thenReturn(diskOfferingSearchBuilder);
        Mockito.lenient().when(diskOfferingDetailSearchBuilder.and()).thenReturn(diskOfferingDetailSearchBuilder);
        Mockito.lenient().when(diskOfferingDetailSearchBuilder.or()).thenReturn(diskOfferingDetailSearchBuilder);
        Mockito.lenient().when(diskOfferingDetailSearchBuilder.cp()).thenReturn(diskOfferingDetailSearchBuilder);
        Mockito.lenient().when(diskOfferingDetailSearchBuilder.op(any(String.class), any(), any(SearchCriteria.Op.class))).thenReturn(diskOfferingDetailSearchBuilder);

        service = new DiskOfferingQueryServiceImpl();
        ReflectionTestUtils.setField(service, "accountMgr", accountMgr);
        ReflectionTestUtils.setField(service, "_diskOfferingJoinDao", diskOfferingJoinDao);
        ReflectionTestUtils.setField(service, "_diskOfferingDetailsDao", diskOfferingDetailsDao);
        ReflectionTestUtils.setField(service, "_diskOfferingDao", diskOfferingDao);
        ReflectionTestUtils.setField(service, "_dcJoinDao", dcJoinDao);
        ReflectionTestUtils.setField(service, "volumeDao", volumeDao);
        ReflectionTestUtils.setField(service, "_domainDao", domainDao);
        ReflectionTestUtils.setField(service, "_storageTagDao", storageTagDao);
        ReflectionTestUtils.setField(service, "userVmDao", userVmDao);

        account = new AccountVO("normaluser", 1L, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        account.setId(11L);
        user = new UserVO(1L, "normaluser", "password", "Test", "User",
                "test@example.com", "UTC", UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);

        when(accountMgr.isRootAdmin(account.getId())).thenReturn(false);
        when(accountMgr.isRootAdmin(account.getAccountId())).thenReturn(false);
        when(accountMgr.isNormalUser(account.getId())).thenReturn(true);
        when(accountMgr.finalizeOwner(any(Account.class), any(), any(), any())).thenReturn(account);

        callerDomain = new DomainVO();
        callerDomain.setId(account.getDomainId());
        callerDomain.setPath("ROOT/test");
        when(domainDao.findById(account.getDomainId())).thenReturn(callerDomain);
        when(domainDao.getDomainParentIds(account.getDomainId())).thenReturn(Collections.singleton(account.getDomainId()));
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    private static class StubListDiskOfferingsCmd extends ListDiskOfferingsCmd {
        Long id;
        String diskOfferingName;
        String keyword;
        Long domainId;
        Long projectId;
        String accountName;
        boolean recursive;
        Long zoneId;
        Long volumeId;
        Long storagePoolId;
        Boolean encrypt;
        String storageType;
        DiskOffering.State state = DiskOffering.State.Active;
        Long virtualMachineId;

        @Override public Long getId() { return id; }
        @Override public String getDiskOfferingName() { return diskOfferingName; }
        @Override public String getKeyword() { return keyword; }
        @Override public Long getDomainId() { return domainId; }
        @Override public Long getProjectId() { return projectId; }
        @Override public String getAccountName() { return accountName; }
        @Override public boolean isRecursive() { return recursive; }
        @Override public Long getZoneId() { return zoneId; }
        @Override public Long getVolumeId() { return volumeId; }
        @Override public Long getStoragePoolId() { return storagePoolId; }
        @Override public Boolean getEncrypt() { return encrypt; }
        @Override public String getStorageType() { return storageType; }
        @Override public DiskOffering.State getState() { return state; }
        @Override public Long getVirtualMachineId() { return virtualMachineId; }
        @Override public Long getStartIndex() { return 0L; }
        @Override public Long getPageSizeVal() { return 20L; }
    }

    private StubListDiskOfferingsCmd newCmd() {
        return new StubListDiskOfferingsCmd();
    }

    private ListResponse<DiskOfferingResponse> executeSearch(StubListDiskOfferingsCmd cmd) {
        try (MockedStatic<ViewResponseHelper> viewResponseHelper = mockStatic(ViewResponseHelper.class)) {
            viewResponseHelper.when(() -> ViewResponseHelper.createDiskOfferingResponses(eq(cmd.getVirtualMachineId()), any(List.class)))
                    .thenReturn(Collections.emptyList());
            return service.searchForDiskOfferings(cmd);
        }
    }

    @Test
    public void searchForDiskOfferings_returnsEmptyResponseWhenSearchCountZero() {
        StubListDiskOfferingsCmd cmd = newCmd();
        when(diskOfferingDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        ListResponse<DiskOfferingResponse> response = executeSearch(cmd);

        assertNotNull(response);
        assertEquals(0, response.getCount().intValue());
        verify(diskOfferingJoinDao, never()).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForDiskOfferings_fetchesJoinRowsByIdsWhenResultsExist() {
        StubListDiskOfferingsCmd cmd = newCmd();
        DiskOfferingVO offering1 = mock(DiskOfferingVO.class);
        DiskOfferingVO offering2 = mock(DiskOfferingVO.class);
        when(offering1.getId()).thenReturn(101L);
        when(offering2.getId()).thenReturn(102L);
        when(diskOfferingDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Arrays.asList(offering1, offering2), 2));
        when(diskOfferingJoinDao.searchByIds(any(Long[].class)))
                .thenReturn(Arrays.asList(mock(DiskOfferingJoinVO.class), mock(DiskOfferingJoinVO.class)));

        ListResponse<DiskOfferingResponse> response = executeSearch(cmd);

        assertEquals(2, response.getCount().intValue());
        verify(diskOfferingJoinDao).searchByIds(any(Long[].class));
        verify(accountMgr).finalizeOwner(account, null, null, null);
    }

    @Test
    public void searchForDiskOfferings_filtersResultsMissingRequiredStorageTags() {
        StubListDiskOfferingsCmd cmd = newCmd();
        cmd.volumeId = 201L;

        VolumeVO volume = mock(VolumeVO.class);
        DiskOfferingVO currentDiskOffering = mock(DiskOfferingVO.class);
        DiskOfferingVO offering1 = mock(DiskOfferingVO.class);
        DiskOfferingVO offering2 = mock(DiskOfferingVO.class);
        DiskOfferingJoinVO join1 = mock(DiskOfferingJoinVO.class);
        DiskOfferingJoinVO join2 = mock(DiskOfferingJoinVO.class);

        when(volumeDao.findById(201L)).thenReturn(volume);
        when(volume.getDiskOfferingId()).thenReturn(301L);
        when(diskOfferingDao.findByIdIncludingRemoved(301L)).thenReturn(currentDiskOffering);
        when(currentDiskOffering.getId()).thenReturn(301L);
        when(currentDiskOffering.getTagsArray()).thenReturn(new String[] {"fast", "ssd"});
        when(offering1.getId()).thenReturn(401L);
        when(offering2.getId()).thenReturn(402L);
        when(diskOfferingDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Arrays.asList(offering1, offering2), 2));
        when(diskOfferingJoinDao.searchByIds(any(Long[].class))).thenReturn(new ArrayList<>(Arrays.asList(join1, join2)));
        when(join1.getTags()).thenReturn("fast,ssd");
        when(join2.getTags()).thenReturn("fast");

        ListResponse<DiskOfferingResponse> response = executeSearch(cmd);

        assertEquals(1, response.getCount().intValue());
        verify(diskOfferingJoinDao).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForDiskOfferings_keepsAllResultsWhenRequiredTagsEmpty() {
        StubListDiskOfferingsCmd cmd = newCmd();
        DiskOfferingVO offering1 = mock(DiskOfferingVO.class);
        DiskOfferingVO offering2 = mock(DiskOfferingVO.class);
        when(offering1.getId()).thenReturn(501L);
        when(offering2.getId()).thenReturn(502L);
        when(diskOfferingDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Arrays.asList(offering1, offering2), 2));
        when(diskOfferingJoinDao.searchByIds(any(Long[].class)))
                .thenReturn(Arrays.asList(mock(DiskOfferingJoinVO.class), mock(DiskOfferingJoinVO.class)));

        ListResponse<DiskOfferingResponse> response = executeSearch(cmd);

        assertEquals(2, response.getCount().intValue());
        verify(diskOfferingJoinDao).searchByIds(any(Long[].class));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForDiskOfferings_rejectsRecursiveForNormalUser() {
        StubListDiskOfferingsCmd cmd = newCmd();
        cmd.recursive = true;

        executeSearch(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForDiskOfferings_rejectsVolumeIdAndStoragePoolIdTogether() {
        StubListDiskOfferingsCmd cmd = newCmd();
        cmd.volumeId = 601L;
        cmd.storagePoolId = 602L;

        executeSearch(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForDiskOfferings_rejectsUnknownVolumeId() {
        StubListDiskOfferingsCmd cmd = newCmd();
        cmd.volumeId = 701L;
        when(volumeDao.findById(701L)).thenReturn(null);

        executeSearch(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForDiskOfferings_rejectsUnknownVmId() {
        StubListDiskOfferingsCmd cmd = newCmd();
        cmd.virtualMachineId = 801L;
        when(userVmDao.findById(801L)).thenReturn(null);

        executeSearch(cmd);
    }

    @Test
    public void searchForDiskOfferings_checksVmAccessForNonRootCaller() {
        StubListDiskOfferingsCmd cmd = newCmd();
        cmd.virtualMachineId = 901L;

        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        UserVmVO vm = mock(UserVmVO.class);
        when(offering.getId()).thenReturn(902L);
        when(diskOfferingDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.singletonList(offering), 1));
        when(diskOfferingJoinDao.searchByIds(any(Long[].class)))
                .thenReturn(Collections.singletonList(mock(DiskOfferingJoinVO.class)));
        when(userVmDao.findById(901L)).thenReturn(vm);

        ListResponse<DiskOfferingResponse> response = executeSearch(cmd);

        assertEquals(1, response.getCount().intValue());
        verify(accountMgr).checkAccess(account, null, false, vm);
    }

    @Test
    public void searchForDiskOfferings_allowsDomainScopedQueryForPermissibleDomain() {
        StubListDiskOfferingsCmd cmd = newCmd();
        cmd.domainId = account.getDomainId();

        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        when(offering.getId()).thenReturn(1001L);
        when(diskOfferingDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.singletonList(offering), 1));
        when(diskOfferingJoinDao.searchByIds(any(Long[].class)))
                .thenReturn(Collections.singletonList(mock(DiskOfferingJoinVO.class)));

        ListResponse<DiskOfferingResponse> response = executeSearch(cmd);

        assertEquals(1, response.getCount().intValue());
        verify(searchCriteria).setJoinParameters("domainDetailsSearch", "domainId", account.getDomainId());
        verify(accountMgr, never()).finalizeOwner(any(Account.class), any(), any(), any());
    }

    @Test(expected = PermissionDeniedException.class)
    public void searchForDiskOfferings_rejectsDomainScopedQueryOutsideHierarchy() {
        StubListDiskOfferingsCmd cmd = newCmd();
        cmd.domainId = 1101L;

        DomainVO rootlessCallerDomain = mock(DomainVO.class);
        when(rootlessCallerDomain.getId()).thenReturn(account.getDomainId());
        when(rootlessCallerDomain.getParent()).thenReturn(null);
        when(domainDao.findById(account.getDomainId())).thenReturn(rootlessCallerDomain);

        executeSearch(cmd);
    }

    @Test
    public void searchForDiskOfferings_forcesLocalStorageForEdgeZone() {
        StubListDiskOfferingsCmd cmd = newCmd();
        cmd.zoneId = 1201L;
        cmd.storageType = ServiceOffering.StorageType.shared.toString();

        DataCenterJoinVO edgeZone = mock(DataCenterJoinVO.class);
        when(edgeZone.getType()).thenReturn(DataCenter.Type.Edge);
        when(dcJoinDao.findById(1201L)).thenReturn(edgeZone);
        when(diskOfferingDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        ListResponse<DiskOfferingResponse> response = executeSearch(cmd);

        assertEquals(0, response.getCount().intValue());
        verify(searchCriteria).setParameters("useLocalStorage", true);
    }
}
