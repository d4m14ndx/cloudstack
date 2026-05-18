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
package com.cloud.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.cloudstack.api.command.user.network.ListNetworksCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.network.dao.NetworkPermissionDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.projects.ProjectManager;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.DomainManager;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class NetworkSearchServiceImplTest {

    @Mock
    NetworkDao _networksDao;
    @Mock
    AccountDao _accountDao;
    @Mock
    DomainDao _domainDao;
    @Mock
    NetworkOfferingDao _networkOfferingDao;
    @Mock
    DataCenterDao _dcDao;
    @Mock
    ResourceTagDao _resourceTagDao;
    @Mock
    NetworkDomainDao _networkDomainDao;
    @Mock
    NetworkPermissionDao _networkPermissionDao;
    @Mock
    NetworkDetailsDao _networkDetailsDao;
    @Mock
    AccountManager _accountMgr;
    @Mock
    DomainManager _domainMgr;
    @Mock
    ProjectManager _projectMgr;
    @Mock
    NetworkModel _networkModel;
    @Mock
    ConfigurationDao _configDao;

    @InjectMocks
    NetworkSearchServiceImpl service;

    private AccountVO account;
    private UserVO user;

    @Before
    public void setUp() {
        account = new AccountVO("testaccount", 1L, "networkdomain", Account.Type.NORMAL, "uuid");
        account.setId(1L);
        user = new UserVO(1, "testuser", "password", "firstname", "lastName", "email", "timezone",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);

        // Default search builder stubs — deep stubs so chained calls work
        SearchBuilder<NetworkVO> networkSB = Mockito.mock(SearchBuilder.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(_networksDao.createSearchBuilder()).thenReturn(networkSB);
        NetworkVO entityVO = Mockito.mock(NetworkVO.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(networkSB.entity()).thenReturn(entityVO);

        SearchBuilder<NetworkOfferingVO> noSB = Mockito.mock(SearchBuilder.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(_networkOfferingDao.createSearchBuilder()).thenReturn(noSB);
        NetworkOfferingVO noEntity = Mockito.mock(NetworkOfferingVO.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(noSB.entity()).thenReturn(noEntity);

        SearchBuilder<DataCenterVO> dcSB = Mockito.mock(SearchBuilder.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(_dcDao.createSearchBuilder()).thenReturn(dcSB);
        DataCenterVO dcEntity = Mockito.mock(DataCenterVO.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(dcSB.entity()).thenReturn(dcEntity);

        SearchBuilder<AccountVO> accountSB = Mockito.mock(SearchBuilder.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(_accountDao.createSearchBuilder()).thenReturn(accountSB);
        AccountVO accountEntity = Mockito.mock(AccountVO.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(accountSB.entity()).thenReturn(accountEntity);

        // Generic search builder for Long (used in addSharedNetworksByDomainPathToSearch)
        @SuppressWarnings("unchecked")
        GenericSearchBuilder<AccountVO, Long> genericAccountSB = Mockito.mock(GenericSearchBuilder.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(_accountDao.createSearchBuilder(Long.class)).thenReturn(genericAccountSB);
        Mockito.lenient().when(genericAccountSB.entity()).thenReturn(accountEntity);
        SearchCriteria<Long> longSC = Mockito.mock(SearchCriteria.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(genericAccountSB.create()).thenReturn(longSC);
        Mockito.lenient().when(_accountDao.customSearch(any(), any())).thenReturn(Collections.emptyList());

        SearchBuilder<DomainVO> domainSB = Mockito.mock(SearchBuilder.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(_domainDao.createSearchBuilder()).thenReturn(domainSB);
        DomainVO domainEntity = Mockito.mock(DomainVO.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(domainSB.entity()).thenReturn(domainEntity);

        // Default domain lookup
        DomainVO callerDomain = Mockito.mock(DomainVO.class);
        Mockito.lenient().when(callerDomain.getPath()).thenReturn("/testdomain/");
        Mockito.lenient().when(callerDomain.getId()).thenReturn(1L);
        Mockito.lenient().when(_domainDao.findById(anyLong())).thenReturn(callerDomain);
        DomainVO pathDomain = Mockito.mock(DomainVO.class);
        Mockito.lenient().when(pathDomain.getId()).thenReturn(1L);
        Mockito.lenient().when(_domainDao.findDomainByPath(any())).thenReturn(pathDomain);

        // Default searchAndCount — return empty pair
        Mockito.lenient().when(_networksDao.searchAndCount(any(), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));
        // Default createSearchCriteria — return a real-ish mock with empty values
        SearchCriteria<NetworkVO> additionalSCMock = Mockito.mock(SearchCriteria.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(additionalSCMock.getValues()).thenReturn(Collections.emptyList());
        Mockito.lenient().when(_networksDao.createSearchCriteria()).thenReturn(additionalSCMock);

        // Default accountMgr — not admin by default
        Mockito.lenient().when(_accountMgr.isAdmin(anyLong())).thenReturn(false);
        Mockito.lenient().when(_accountMgr.isRootAdmin(anyLong())).thenReturn(false);
        // Default networkPermissionDao
        Mockito.lenient().when(_networkPermissionDao.listPermittedNetworkIdsByAccounts(any()))
                .thenReturn(Collections.emptyList());
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // -------------------------------------------------------------------------
    // Migrated tests from NetworkServiceImplTest
    // -------------------------------------------------------------------------

    @Test
    public void addProjectNetworksConditionToSearch_includesProjectNetworksWhenNotSkipped() {
        SearchCriteria<NetworkVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria accountJoin = Mockito.mock(SearchCriteria.class);
        Mockito.when(sc.getJoin("account")).thenReturn(accountJoin);
        service.addProjectNetworksConditionToSearch(sc, false, -1L);
        Mockito.verify(accountJoin).addAnd("type", SearchCriteria.Op.NNULL);
        Mockito.verify(sc).addAnd("id", SearchCriteria.Op.SC, accountJoin);
    }

    @Test
    public void addProjectNetworksConditionToSearch_excludesProjectNetworksWhenSkipped() {
        SearchCriteria<NetworkVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria accountJoin = Mockito.mock(SearchCriteria.class);
        Mockito.when(sc.getJoin("account")).thenReturn(accountJoin);
        service.addProjectNetworksConditionToSearch(sc, true, -1L);
        Mockito.verify(accountJoin).addAnd("type", SearchCriteria.Op.NEQ, Account.Type.PROJECT);
        Mockito.verify(sc).addAnd("id", SearchCriteria.Op.SC, accountJoin);
    }

    @Test
    public void addProjectNetworksConditionToSearch_includesSpecificProjectWhenProjectIdProvided() {
        SearchCriteria<NetworkVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria accountJoin = Mockito.mock(SearchCriteria.class);
        Mockito.when(sc.getJoin("account")).thenReturn(accountJoin);
        service.addProjectNetworksConditionToSearch(sc, false, 123L);
        Mockito.verify(accountJoin).addAnd("type", SearchCriteria.Op.EQ, Account.Type.PROJECT);
        Mockito.verify(sc).addAnd("id", SearchCriteria.Op.SC, accountJoin);
    }

    // -------------------------------------------------------------------------
    // New tests — searchForNetworks branches
    // -------------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void searchForNetworks_invalidNetworkFilter_throws() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(cmd.getNetworkFilter()).thenReturn("bogus");
        service.searchForNetworks(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForNetworks_isSystemTrueWithAccountName_throws() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(cmd.getIsSystem()).thenReturn(true);
        Mockito.when(cmd.getAccountName()).thenReturn("someuser");
        // Make caller root admin so isSystem=true is not reset
        Mockito.when(_accountMgr.isRootAdmin(anyLong())).thenReturn(true);
        service.searchForNetworks(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForNetworks_invalidDomainId_throws() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(cmd.getDomainId()).thenReturn(42L);
        Mockito.when(_domainDao.findById(42L)).thenReturn(null);
        service.searchForNetworks(cmd);
    }

    @Test
    public void searchForNetworks_nonAdminCaller_returnsEmptyList() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(_accountMgr.isAdmin(anyLong())).thenReturn(false);

        Pair<List<? extends Network>, Integer> result = service.searchForNetworks(cmd);

        assertNotNull(result);
        assertEquals(0, (int) result.second());
    }

    @Test
    public void searchForNetworks_projectIdMinusOneAsAdmin_doesNotThrow() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(cmd.getProjectId()).thenReturn(-1L);
        Mockito.when(_accountMgr.isAdmin(anyLong())).thenReturn(true);

        Pair<List<? extends Network>, Integer> result = service.searchForNetworks(cmd);
        assertNotNull(result);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForNetworks_projectIdSpecificProjectNotFound_throws() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(cmd.getProjectId()).thenReturn(99L);
        Mockito.when(_projectMgr.getProject(99L)).thenReturn(null);
        service.searchForNetworks(cmd);
    }

    @Test
    public void searchForNetworks_listAllWithNoDomainId_returnsResult() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(cmd.listAll()).thenReturn(true);
        Mockito.when(cmd.getDomainId()).thenReturn(null);
        Mockito.when(_accountMgr.isAdmin(anyLong())).thenReturn(true);

        Pair<List<? extends Network>, Integer> result = service.searchForNetworks(cmd);
        assertNotNull(result);
    }

    @Test
    public void searchForNetworks_withAssociatedNetworkId_joinsNetworkDetails() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(cmd.getAssociatedNetworkId()).thenReturn(42L);

        SearchBuilder<NetworkDetailVO> detailSB = Mockito.mock(SearchBuilder.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(_networkDetailsDao.createSearchBuilder()).thenReturn(detailSB);
        NetworkDetailVO detailEntity = Mockito.mock(NetworkDetailVO.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(detailSB.entity()).thenReturn(detailEntity);

        service.searchForNetworks(cmd);

        Mockito.verify(_networkDetailsDao).createSearchBuilder();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void searchForNetworks_withTags_buildsTagSearch() {
        ListNetworksCmd cmd = buildCmd();
        Map<String, String> tags = new HashMap<>();
        tags.put("key1", "value1");
        Mockito.when(cmd.getTags()).thenReturn(tags);

        SearchBuilder<ResourceTagVO> tagSB = Mockito.mock(SearchBuilder.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(_resourceTagDao.createSearchBuilder()).thenReturn(tagSB);
        ResourceTagVO tagEntity = Mockito.mock(ResourceTagVO.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(tagSB.entity()).thenReturn(tagEntity);
        // or() returns the builder itself (SearchBase.or() returns J = this)
        Mockito.when(tagSB.or()).thenReturn(tagSB);

        service.searchForNetworks(cmd);

        Mockito.verify(_resourceTagDao).createSearchBuilder();
    }

    @Test
    public void searchForNetworks_isSystemTrue_callsSearchAndCount() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(cmd.getIsSystem()).thenReturn(true);
        Mockito.when(_accountMgr.isRootAdmin(anyLong())).thenReturn(true);
        Mockito.when(_accountMgr.isAdmin(anyLong())).thenReturn(true);

        // Stub sc.create() chain for isSystem=true branch
        SearchCriteria<NetworkVO> sc = Mockito.mock(SearchCriteria.class, Mockito.RETURNS_DEEP_STUBS);
        SearchBuilder<NetworkVO> networkSB = (SearchBuilder<NetworkVO>) _networksDao.createSearchBuilder();
        Mockito.when(networkSB.create()).thenReturn(sc);

        Pair<List<? extends Network>, Integer> result = service.searchForNetworks(cmd);

        Mockito.verify(_networksDao).searchAndCount(any(), any(Filter.class));
        assertNotNull(result);
    }

    @Test
    public void searchForNetworks_supportedServicesFilter_filtersNetworks() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(cmd.getSupportedServices()).thenReturn(Collections.singletonList("Dns"));
        // Use isSystem=true to go through the simple branch where searchAndCount is always called
        Mockito.when(cmd.getIsSystem()).thenReturn(true);
        Mockito.when(_accountMgr.isRootAdmin(anyLong())).thenReturn(true);
        Mockito.when(_accountMgr.isAdmin(anyLong())).thenReturn(true);

        NetworkVO net1 = Mockito.mock(NetworkVO.class);
        Mockito.when(net1.getId()).thenReturn(101L);
        NetworkVO net2 = Mockito.mock(NetworkVO.class);
        Mockito.when(net2.getId()).thenReturn(102L);
        List<NetworkVO> nets = Arrays.asList(net1, net2);

        // Stub sc creation for isSystem branch
        SearchCriteria<NetworkVO> sc = Mockito.mock(SearchCriteria.class, Mockito.RETURNS_DEEP_STUBS);
        SearchBuilder<NetworkVO> networkSB = (SearchBuilder<NetworkVO>) _networksDao.createSearchBuilder();
        Mockito.when(networkSB.create()).thenReturn(sc);

        Mockito.when(_networksDao.searchAndCount(any(), any(Filter.class)))
                .thenReturn(new Pair<>(nets, 2));
        Mockito.when(_networkModel.areServicesSupportedInNetwork(101L, Network.Service.Dns)).thenReturn(true);
        Mockito.when(_networkModel.areServicesSupportedInNetwork(102L, Network.Service.Dns)).thenReturn(false);

        Pair<List<? extends Network>, Integer> result = service.searchForNetworks(cmd);

        assertEquals(1, result.first().size());
        assertEquals(1, (int) result.second());
    }

    @Test
    public void searchForNetworks_canUseForDeployFilter_filtersResults() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(cmd.canUseForDeploy()).thenReturn(true);
        // Use isSystem=true to go through the simple branch where searchAndCount is always called
        Mockito.when(cmd.getIsSystem()).thenReturn(true);
        Mockito.when(_accountMgr.isRootAdmin(anyLong())).thenReturn(true);
        Mockito.when(_accountMgr.isAdmin(anyLong())).thenReturn(true);

        NetworkVO net1 = Mockito.mock(NetworkVO.class);
        NetworkVO net2 = Mockito.mock(NetworkVO.class);
        List<NetworkVO> nets = Arrays.asList(net1, net2);

        // Stub sc creation for isSystem branch
        SearchCriteria<NetworkVO> sc = Mockito.mock(SearchCriteria.class, Mockito.RETURNS_DEEP_STUBS);
        SearchBuilder<NetworkVO> networkSB = (SearchBuilder<NetworkVO>) _networksDao.createSearchBuilder();
        Mockito.when(networkSB.create()).thenReturn(sc);

        Mockito.when(_networksDao.searchAndCount(any(), any(Filter.class)))
                .thenReturn(new Pair<>(nets, 2));
        Mockito.when(_networkModel.canUseForDeploy(net1)).thenReturn(true);
        Mockito.when(_networkModel.canUseForDeploy(net2)).thenReturn(false);

        Pair<List<? extends Network>, Integer> result = service.searchForNetworks(cmd);

        assertEquals(1, result.first().size());
        assertEquals(1, (int) result.second());
    }

    @Test
    public void searchForNetworks_addProjectNetworksConditionToSearch_twoArgOverload() {
        SearchCriteria<NetworkVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria accountJoin = Mockito.mock(SearchCriteria.class);
        Mockito.when(sc.getJoin("account")).thenReturn(accountJoin);

        // 2-arg overload delegates to 3-arg with null
        service.addProjectNetworksConditionToSearch(sc, true);

        // skipProjectNetworks=true, projectId=null → NEQ branch
        Mockito.verify(accountJoin).addAnd("type", SearchCriteria.Op.NEQ, Account.Type.PROJECT);
        Mockito.verify(sc).addAnd("id", SearchCriteria.Op.SC, accountJoin);
    }

    @Test
    public void searchForNetworks_adminCallerNoDomainNoProjectId_returnsEmptyList() {
        ListNetworksCmd cmd = buildCmd();
        Mockito.when(_accountMgr.isAdmin(anyLong())).thenReturn(true);

        Pair<List<? extends Network>, Integer> result = service.searchForNetworks(cmd);
        assertNotNull(result);
    }

    // -------------------------------------------------------------------------
    // addSharedNetworksByAccountsToSearch
    // -------------------------------------------------------------------------

    @Test
    public void addSharedNetworksByAccountsToSearch_noSharedIds_doesNotCallSearchAndCount() {
        // Trigger the shared path: non-admin caller, network filter = Shared, permittedAccounts not empty.
        // The simplest way is to verify _networkPermissionDao interaction when it returns empty list.
        Mockito.when(_networkPermissionDao.listPermittedNetworkIdsByAccounts(any())).thenReturn(Collections.emptyList());

        ListNetworksCmd cmd = buildCmd();
        Mockito.when(cmd.getNetworkFilter()).thenReturn("Shared");
        Mockito.when(_accountMgr.isAdmin(anyLong())).thenReturn(false);

        service.searchForNetworks(cmd);

        // With empty sharedNetworkIds, additionalSC should have no OR added from the shared path
        // and _networksDao.searchAndCount should NOT be called (additionalSC.getValues() is empty)
        Mockito.verify(_networksDao, never()).searchAndCount(any(), any(Filter.class));
    }

    @Test
    public void addSharedNetworksByDomainPathToSearch_pathNull_doesNotQueryDomains() {
        // When path is null, getDomainChildrenIds should not be called
        // Force permittedAccounts to be empty and non-recursive to hit the path=null branch
        ListNetworksCmd cmd = buildCmd();
        // Admin with no projectId, no domainId → path computed from caller's domain
        // To get permittedAccounts empty AND path=null is tricky; simulate via domainDao returning null path
        DomainVO callerDomain2 = Mockito.mock(DomainVO.class);
        Mockito.when(callerDomain2.getPath()).thenReturn(null);
        Mockito.when(_domainDao.findById(anyLong())).thenReturn(callerDomain2);
        Mockito.when(_accountMgr.isAdmin(anyLong())).thenReturn(true);

        service.searchForNetworks(cmd);

        Mockito.verify(_domainMgr, never()).getDomainChildrenIds(any());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private ListNetworksCmd buildCmd() {
        ListNetworksCmd cmd = Mockito.mock(ListNetworksCmd.class);
        Mockito.lenient().when(cmd.getId()).thenReturn(null);
        Mockito.lenient().when(cmd.getName()).thenReturn(null);
        Mockito.lenient().when(cmd.getKeyword()).thenReturn(null);
        Mockito.lenient().when(cmd.getZoneId()).thenReturn(null);
        Mockito.lenient().when(cmd.getDomainId()).thenReturn(null);
        Mockito.lenient().when(cmd.getAccountName()).thenReturn(null);
        Mockito.lenient().when(cmd.getGuestIpType()).thenReturn(null);
        Mockito.lenient().when(cmd.getTrafficType()).thenReturn(null);
        Mockito.lenient().when(cmd.getIsSystem()).thenReturn(null);
        Mockito.lenient().when(cmd.getAclType()).thenReturn(null);
        Mockito.lenient().when(cmd.getProjectId()).thenReturn(null);
        Mockito.lenient().when(cmd.getPhysicalNetworkId()).thenReturn(null);
        Mockito.lenient().when(cmd.getSupportedServices()).thenReturn(null);
        Mockito.lenient().when(cmd.isRestartRequired()).thenReturn(null);
        Mockito.lenient().when(cmd.listAll()).thenReturn(false);
        Mockito.lenient().when(cmd.isRecursive()).thenReturn(false);
        Mockito.lenient().when(cmd.isSpecifyIpRanges()).thenReturn(null);
        Mockito.lenient().when(cmd.getVpcId()).thenReturn(null);
        Mockito.lenient().when(cmd.canUseForDeploy()).thenReturn(null);
        Mockito.lenient().when(cmd.getTags()).thenReturn(null);
        Mockito.lenient().when(cmd.getForVpc()).thenReturn(null);
        Mockito.lenient().when(cmd.getDisplay()).thenReturn(null);
        Mockito.lenient().when(cmd.getNetworkOfferingId()).thenReturn(null);
        Mockito.lenient().when(cmd.getAssociatedNetworkId()).thenReturn(null);
        Mockito.lenient().when(cmd.getNetworkFilter()).thenReturn(null);
        Mockito.lenient().when(cmd.getStartIndex()).thenReturn(null);
        Mockito.lenient().when(cmd.getPageSizeVal()).thenReturn(null);
        return cmd;
    }
}
