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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.admin.network.DedicateGuestVlanRangeCmd;
import org.apache.cloudstack.api.command.admin.network.ListDedicatedGuestVlanRangesCmd;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenterVnetVO;
import com.cloud.dc.dao.DataCenterVnetDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.dao.AccountGuestVlanMapDao;
import com.cloud.network.dao.AccountGuestVlanMapVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.projects.ProjectManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class DedicatedGuestVlanRangeServiceImplTest {

    @Mock private AccountManager accountManager;
    @Mock private AccountDao accountDao;
    @Mock private ProjectManager projectManager;
    @Mock private PhysicalNetworkDao physicalNetworkDao;
    @Mock private DataCenterVnetDao dcVnetDao;
    @Mock private AccountGuestVlanMapDao accountGuestVlanMapDao;

    @Mock private DedicateGuestVlanRangeCmd dedicateCmd;
    @Mock private ListDedicatedGuestVlanRangesCmd listCmd;

    @InjectMocks
    private DedicatedGuestVlanRangeServiceImpl service;

    private static final Long PHYS_NET_ID = 1L;
    private static final Long DOMAIN_ID = 1L;
    private static final Long ACCOUNT_ID = 1L;
    private static final Long DC_ID = 1L;
    private static final String ACCOUNT_NAME = "accountname";

    private AccountVO ownerAccount;

    @Before
    public void setUp() {
        ownerAccount = new AccountVO("testaccount", 1, "networkdomain", Account.Type.NORMAL, "uuid");
        ownerAccount.setId(ACCOUNT_ID);
    }

    // ---- getVlanFromRange (helper, exercised through dedicate) ----

    @Test
    public void getVlanFromRangeParsesTwoTokens() {
        List<Integer> tokens = service.getVlanFromRange("2-5");
        assertEquals(Integer.valueOf(2), tokens.get(0));
        assertEquals(Integer.valueOf(5), tokens.get(1));
    }

    @Test
    public void getVlanFromRangeRejectsNonNumeric() {
        assertThrows(InvalidParameterValueException.class, () -> service.getVlanFromRange("a-b"));
    }

    // ---- dedicateGuestVlanRange ----

    private PhysicalNetworkVO physNetWithIsolationAndVnet(String vnet, String isolationMethod) {
        PhysicalNetworkVO net = new PhysicalNetworkVO(PHYS_NET_ID, DC_ID, vnet, "200", DOMAIN_ID, null, "testphysnet");
        net.addIsolationMethod(isolationMethod);
        return net;
    }

    private void stubAccountResolutionByName() {
        when(dedicateCmd.getAccountName()).thenReturn(ACCOUNT_NAME);
        when(dedicateCmd.getDomainId()).thenReturn(DOMAIN_ID);
        when(dedicateCmd.getPhysicalNetworkId()).thenReturn(PHYS_NET_ID);
        when(dedicateCmd.getProjectId()).thenReturn(null);
        when(accountDao.findActiveAccount(eq(ACCOUNT_NAME), eq(DOMAIN_ID))).thenReturn(ownerAccount);
    }

    @Test
    public void dedicateRejectsWhenAccountNameAndProjectIdBothGiven() {
        when(dedicateCmd.getProjectId()).thenReturn(99L);
        when(dedicateCmd.getAccountName()).thenReturn(ACCOUNT_NAME);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.dedicateGuestVlanRange(dedicateCmd));
        assertTrue(ex.getMessage().contains("accountName and projectId are mutually exclusive"));
    }

    @Test
    public void dedicateRejectsUnknownProject() {
        when(dedicateCmd.getProjectId()).thenReturn(99L);
        when(projectManager.getProject(eq(99L))).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.dedicateGuestVlanRange(dedicateCmd));
        assertTrue(ex.getMessage().contains("Unable to find project by id"));
    }

    @Test
    public void dedicateRejectsUnknownAccount() {
        when(dedicateCmd.getAccountName()).thenReturn(ACCOUNT_NAME);
        when(dedicateCmd.getDomainId()).thenReturn(DOMAIN_ID);
        when(dedicateCmd.getProjectId()).thenReturn(null);
        when(accountDao.findActiveAccount(anyString(), anyLong())).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.dedicateGuestVlanRange(dedicateCmd));
        assertTrue(ex.getMessage().contains("Unable to find account by name"));
    }

    @Test
    public void dedicateRejectsUnknownPhysicalNetwork() {
        stubAccountResolutionByName();
        when(physicalNetworkDao.findById(eq(PHYS_NET_ID))).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.dedicateGuestVlanRange(dedicateCmd));
        assertTrue(ex.getMessage().contains("Unable to find physical network by id"));
    }

    @Test
    public void dedicateRejectsNonVlanIsolation() {
        stubAccountResolutionByName();
        PhysicalNetworkVO physNet = physNetWithIsolationAndVnet("2-5", "GRE");
        when(physicalNetworkDao.findById(eq(PHYS_NET_ID))).thenReturn(physNet);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.dedicateGuestVlanRange(dedicateCmd));
        assertTrue(ex.getMessage().contains("is not VLAN nor VXLAN"));
    }

    @Test
    public void dedicateRejectsMalformedVlanString() {
        stubAccountResolutionByName();
        when(dedicateCmd.getVlan()).thenReturn("2");
        when(physicalNetworkDao.findById(eq(PHYS_NET_ID)))
                .thenReturn(physNetWithIsolationAndVnet("2-5", "VLAN"));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.dedicateGuestVlanRange(dedicateCmd));
        assertTrue(ex.getMessage().contains("Invalid format for parameter value vlan"));
    }

    @Test
    public void dedicateRejectsNonNumericVlanString() {
        stubAccountResolutionByName();
        when(dedicateCmd.getVlan()).thenReturn("a-b");
        when(physicalNetworkDao.findById(eq(PHYS_NET_ID)))
                .thenReturn(physNetWithIsolationAndVnet("2-5", "VLAN"));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.dedicateGuestVlanRange(dedicateCmd));
        assertTrue(ex.getMessage().contains("Please provide valid guest vlan range"));
    }

    @Test
    public void dedicateRejectsRangeOutsidePhysicalNetworkVnet() {
        stubAccountResolutionByName();
        when(dedicateCmd.getVlan()).thenReturn("2-5");
        when(physicalNetworkDao.findById(eq(PHYS_NET_ID)))
                .thenReturn(physNetWithIsolationAndVnet("6-10", "VLAN"));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.dedicateGuestVlanRange(dedicateCmd));
        assertTrue(ex.getMessage().contains("Unable to find guest vlan by range"));
    }

    @Test
    public void dedicateRejectsRangeAllocatedToDifferentAccount() {
        stubAccountResolutionByName();
        when(dedicateCmd.getVlan()).thenReturn("2-5");
        when(physicalNetworkDao.findById(eq(PHYS_NET_ID)))
                .thenReturn(physNetWithIsolationAndVnet("2-5", "VLAN"));

        DataCenterVnetVO dcVnet = new DataCenterVnetVO("2", DC_ID, PHYS_NET_ID);
        dcVnet.setAccountId(99L); // someone else's
        when(dcVnetDao.findVnet(anyLong(), anyLong(), anyString())).thenReturn(List.of(dcVnet));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.dedicateGuestVlanRange(dedicateCmd));
        assertTrue(ex.getMessage().contains("is allocated to a different Account"));
    }

    @Test
    public void dedicateRejectsRangeAlreadyDedicated() {
        stubAccountResolutionByName();
        when(dedicateCmd.getVlan()).thenReturn("2-5");
        when(physicalNetworkDao.findById(eq(PHYS_NET_ID)))
                .thenReturn(physNetWithIsolationAndVnet("2-5", "VLAN"));

        DataCenterVnetVO dcVnet = new DataCenterVnetVO("2", DC_ID, PHYS_NET_ID);
        when(dcVnetDao.findVnet(anyLong(), anyLong(), anyString())).thenReturn(List.of(dcVnet));

        AccountGuestVlanMapVO existing = new AccountGuestVlanMapVO(ACCOUNT_ID, PHYS_NET_ID);
        existing.setGuestVlanRange("2-5");
        when(accountGuestVlanMapDao.listAccountGuestVlanMapsByPhysicalNetwork(anyLong()))
                .thenReturn(new ArrayList<>(List.of(existing)));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.dedicateGuestVlanRange(dedicateCmd));
        assertTrue(ex.getMessage().contains("Vlan range is already dedicated"));
    }

    @Test
    public void dedicatePersistsNewRangeWhenNoExistingDedications() {
        stubAccountResolutionByName();
        when(dedicateCmd.getVlan()).thenReturn("2-5");
        when(physicalNetworkDao.findById(eq(PHYS_NET_ID)))
                .thenReturn(physNetWithIsolationAndVnet("2-5", "VLAN"));

        DataCenterVnetVO dcVnet = new DataCenterVnetVO("2", DC_ID, PHYS_NET_ID);
        // null accountId so the ownership check passes
        when(dcVnetDao.findVnet(anyLong(), anyLong(), anyString())).thenReturn(List.of(dcVnet));
        when(dcVnetDao.findVnet(anyLong(), anyLong(), anyString())).thenReturn(List.of(dcVnet));
        when(accountGuestVlanMapDao.listAccountGuestVlanMapsByPhysicalNetwork(anyLong()))
                .thenReturn(Collections.emptyList());

        AccountGuestVlanMapVO persisted = new AccountGuestVlanMapVO(ACCOUNT_ID, PHYS_NET_ID);
        persisted.setGuestVlanRange("2-5");
        when(accountGuestVlanMapDao.persist(any(AccountGuestVlanMapVO.class))).thenReturn(persisted);
        when(dcVnetDao.update(anyLong(), any(DataCenterVnetVO.class))).thenReturn(true);

        GuestVlanRange result = service.dedicateGuestVlanRange(dedicateCmd);

        assertNotNull(result);
        verify(accountGuestVlanMapDao, times(1)).persist(any(AccountGuestVlanMapVO.class));
        verify(accountGuestVlanMapDao, never()).update(anyLong(), any(AccountGuestVlanMapVO.class));
    }

    @Test
    public void dedicateExtendsAdjacentRangeOwnedBySameAccount() {
        stubAccountResolutionByName();
        when(dedicateCmd.getVlan()).thenReturn("2-3");
        when(physicalNetworkDao.findById(eq(PHYS_NET_ID)))
                .thenReturn(physNetWithIsolationAndVnet("2-10", "VLAN"));

        DataCenterVnetVO dcVnet = new DataCenterVnetVO("2", DC_ID, PHYS_NET_ID);
        when(dcVnetDao.findVnet(anyLong(), anyLong(), anyString())).thenReturn(List.of(dcVnet));

        AccountGuestVlanMapVO existing = new AccountGuestVlanMapVO(ACCOUNT_ID, PHYS_NET_ID);
        existing.setGuestVlanRange("4-6");
        when(accountGuestVlanMapDao.listAccountGuestVlanMapsByPhysicalNetwork(anyLong()))
                .thenReturn(new ArrayList<>(List.of(existing)));
        when(accountGuestVlanMapDao.findById(anyLong())).thenReturn(existing);
        when(dcVnetDao.update(anyLong(), any(DataCenterVnetVO.class))).thenReturn(true);

        GuestVlanRange result = service.dedicateGuestVlanRange(dedicateCmd);

        assertNotNull(result);
        // Should update the existing row, not persist a new one
        verify(accountGuestVlanMapDao, times(1)).update(anyLong(), any(AccountGuestVlanMapVO.class));
        verify(accountGuestVlanMapDao, never()).persist(any(AccountGuestVlanMapVO.class));
    }

    // ---- releaseDedicatedGuestVlanRange ----

    @Test
    public void releaseRejectsUnknownId() {
        when(accountGuestVlanMapDao.findById(anyLong())).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.releaseDedicatedGuestVlanRange(42L));
        assertTrue(ex.getMessage().contains("doesn't exist in the system"));
    }

    @Test
    public void releaseSucceedsWhenRowExists() {
        AccountGuestVlanMapVO map = new AccountGuestVlanMapVO(ACCOUNT_ID, PHYS_NET_ID);
        when(accountGuestVlanMapDao.findById(anyLong())).thenReturn(map);
        doNothing().when(dcVnetDao).releaseDedicatedGuestVlans(anyLong());
        when(accountGuestVlanMapDao.remove(eq(42L))).thenReturn(true);

        boolean result = service.releaseDedicatedGuestVlanRange(42L);

        assertTrue(result);
        verify(dcVnetDao, times(1)).releaseDedicatedGuestVlans(anyLong());
        verify(accountGuestVlanMapDao, times(1)).remove(eq(42L));
    }

    @Test
    public void releaseReturnsFalseWhenRemoveFails() {
        AccountGuestVlanMapVO map = new AccountGuestVlanMapVO(ACCOUNT_ID, PHYS_NET_ID);
        when(accountGuestVlanMapDao.findById(anyLong())).thenReturn(map);
        doNothing().when(dcVnetDao).releaseDedicatedGuestVlans(anyLong());
        when(accountGuestVlanMapDao.remove(eq(42L))).thenReturn(false);

        boolean result = service.releaseDedicatedGuestVlanRange(42L);

        assertFalse(result);
    }

    // ---- listDedicatedGuestVlanRanges ----

    @SuppressWarnings("unchecked")
    private void stubSearchBuilders() {
        SearchBuilder<AccountGuestVlanMapVO> sb = org.mockito.Mockito.mock(SearchBuilder.class);
        AccountGuestVlanMapVO entityProxy = org.mockito.Mockito.mock(AccountGuestVlanMapVO.class);
        when(sb.entity()).thenReturn(entityProxy);
        SearchCriteria<AccountGuestVlanMapVO> sc = org.mockito.Mockito.mock(SearchCriteria.class);
        when(sb.create()).thenReturn(sc);
        when(accountGuestVlanMapDao.createSearchBuilder()).thenReturn(sb);
    }

    @Test
    public void listRejectsAccountWithProject() {
        when(listCmd.getAccountName()).thenReturn(ACCOUNT_NAME);
        when(listCmd.getDomainId()).thenReturn(DOMAIN_ID);
        when(listCmd.getProjectId()).thenReturn(99L);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.listDedicatedGuestVlanRanges(listCmd));
        assertTrue(ex.getMessage().contains("Account and projectId can't be specified together"));
    }

    @Test
    public void listRejectsUnknownProject() {
        when(listCmd.getProjectId()).thenReturn(99L);
        when(projectManager.getProject(eq(99L))).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.listDedicatedGuestVlanRanges(listCmd));
        assertTrue(ex.getMessage().contains("Unable to find project by id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void listReturnsPaginatedResult() {
        stubSearchBuilders();
        when(listCmd.getProjectId()).thenReturn(null);
        when(listCmd.getZoneId()).thenReturn(null);
        AccountGuestVlanMapVO map = new AccountGuestVlanMapVO(ACCOUNT_ID, PHYS_NET_ID);
        Pair<List<AccountGuestVlanMapVO>, Integer> raw = new Pair<>(Arrays.asList(map), 1);
        when(accountGuestVlanMapDao.searchAndCount(any(SearchCriteria.class), any(Filter.class))).thenReturn(raw);
        when(listCmd.getStartIndex()).thenReturn(0L);
        when(listCmd.getPageSizeVal()).thenReturn(10L);

        Pair<List<? extends GuestVlanRange>, Integer> result = service.listDedicatedGuestVlanRanges(listCmd);

        assertEquals(Integer.valueOf(1), result.second());
        assertSame(map, result.first().get(0));
    }
}
