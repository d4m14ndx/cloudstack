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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.command.user.securitygroup.ListSecurityGroupsCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.SecurityGroupJoinDao;
import com.cloud.api.query.vo.SecurityGroupJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.security.SecurityGroupVMMapVO;
import com.cloud.network.security.dao.SecurityGroupVMMapDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class SecurityGroupQueryServiceImplTest {

    @Mock private AccountManager accountMgr;
    @Mock private UserVmDao userVmDao;
    @Mock private SecurityGroupJoinDao securityGroupJoinDao;
    @Mock private SecurityGroupVMMapDao securityGroupVMMapDao;

    @Mock private SearchBuilder<SecurityGroupJoinVO> searchBuilder;
    @Mock private SearchCriteria<SecurityGroupJoinVO> searchCriteria;
    @Mock private SearchCriteria<SecurityGroupJoinVO> nestedCriteriaOne;
    @Mock private SearchCriteria<SecurityGroupJoinVO> nestedCriteriaTwo;
    @Mock private SecurityGroupJoinVO entityProxy;
    @Mock private Account callingAccount;

    @InjectMocks
    private SecurityGroupQueryServiceImpl service;

    private MockedStatic<CallContext> callContextStatic;

    @Before
    public void setUp() {
        when(securityGroupJoinDao.createSearchBuilder()).thenReturn(searchBuilder);
        when(searchBuilder.entity()).thenReturn(entityProxy);
        when(searchBuilder.create()).thenReturn(searchCriteria);

        CallContext ctx = mock(CallContext.class);
        when(ctx.getCallingAccount()).thenReturn(callingAccount);
        callContextStatic = Mockito.mockStatic(CallContext.class);
        callContextStatic.when(CallContext::current).thenReturn(ctx);
    }

    @After
    public void tearDown() {
        callContextStatic.close();
    }

    @Test
    public void searchForSecurityGroupsInternalByVmIdThrowsWhenVmMissing() {
        ListSecurityGroupsCmd cmd = cmdWithDefaults();
        when(cmd.getVirtualMachineId()).thenReturn(99L);
        when(userVmDao.findById(99L)).thenReturn(null);

        try {
            service.searchForSecurityGroupsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            assertTrue(expected.getMessage().contains("99"));
        }

        verify(accountMgr, never()).checkAccess(any(), any(), anyBoolean(), any(UserVmVO.class));
    }

    @Test
    public void searchForSecurityGroupsInternalByVmIdChecksAccessAndReturnsEmptyWhenNoMappings() {
        ListSecurityGroupsCmd cmd = cmdWithDefaults();
        when(cmd.getVirtualMachineId()).thenReturn(7L);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);
        UserVmVO vm = mock(UserVmVO.class);
        when(userVmDao.findById(7L)).thenReturn(vm);
        when(securityGroupVMMapDao.listByInstanceId(eq(7L), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        Pair<List<SecurityGroupJoinVO>, Integer> result = service.searchForSecurityGroupsInternal(cmd);

        assertEquals(Integer.valueOf(0), result.second());
        assertTrue(result.first().isEmpty());
        verify(accountMgr).checkAccess(eq(callingAccount), isNull(), eq(true), eq(vm));
        verify(securityGroupJoinDao, never()).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForSecurityGroupsInternalByVmIdHydratesMappedSecurityGroupsByIds() {
        ListSecurityGroupsCmd cmd = cmdWithDefaults();
        when(cmd.getVirtualMachineId()).thenReturn(7L);
        UserVmVO vm = mock(UserVmVO.class);
        when(userVmDao.findById(7L)).thenReturn(vm);

        SecurityGroupVMMapVO firstMap = mock(SecurityGroupVMMapVO.class);
        SecurityGroupVMMapVO secondMap = mock(SecurityGroupVMMapVO.class);
        when(firstMap.getSecurityGroupId()).thenReturn(11L);
        when(secondMap.getSecurityGroupId()).thenReturn(12L);
        when(securityGroupVMMapDao.listByInstanceId(eq(7L), any()))
                .thenReturn(new Pair<>(Arrays.asList(firstMap, secondMap), 2));

        SecurityGroupJoinVO hydrated = mock(SecurityGroupJoinVO.class);
        when(securityGroupJoinDao.searchByIds(any(Long[].class)))
                .thenReturn(Collections.singletonList(hydrated));

        Pair<List<SecurityGroupJoinVO>, Integer> result = service.searchForSecurityGroupsInternal(cmd);

        assertEquals(Integer.valueOf(2), result.second());
        assertSame(hydrated, result.first().get(0));
        verify(securityGroupJoinDao).searchByIds(eq(new Long[] {11L, 12L}));
    }

    @Test
    public void searchForSecurityGroupsInternalCallsAccountManagerForAclSetup() {
        ListSecurityGroupsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(5L);
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getProjectId()).thenReturn(99L);
        when(cmd.listAll()).thenReturn(true);
        when(securityGroupJoinDao.searchAndCount(eq(searchCriteria), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSecurityGroupsInternal(cmd);

        verify(accountMgr).buildACLSearchParameters(eq(callingAccount), eq(5L), eq("alice"), eq(99L),
                anyList(), any(Ternary.class), eq(true), eq(false));
        verify(accountMgr).buildACLViewSearchBuilder(eq(searchBuilder), isNull(), eq(false), anyList(), isNull());
        verify(accountMgr).buildACLViewSearchCriteria(eq(searchCriteria), isNull(), eq(false), anyList(), isNull());
    }

    @Test
    public void searchForSecurityGroupsInternalAppliesIdNameTagsAndKeywordFilters() {
        ListSecurityGroupsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(42L);
        when(cmd.getSecurityGroupName()).thenReturn("web-sg");
        when(cmd.getKeyword()).thenReturn("frontend");
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("tier", "web");
        when(cmd.getTags()).thenReturn(tags);
        when(securityGroupJoinDao.searchAndCount(eq(searchCriteria), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));
        when(securityGroupJoinDao.createSearchCriteria()).thenReturn(nestedCriteriaOne, nestedCriteriaTwo, nestedCriteriaOne);

        service.searchForSecurityGroupsInternal(cmd);

        verify(searchCriteria).setParameters("id", 42L);
        verify(searchCriteria).setParameters("name", "web-sg");
        verify(nestedCriteriaTwo).addAnd("tagKey", SearchCriteria.Op.EQ, "tier");
        verify(nestedCriteriaTwo).addAnd("tagValue", SearchCriteria.Op.EQ, "web");
        verify(nestedCriteriaOne).addOr("tagKey", SearchCriteria.Op.SC, nestedCriteriaTwo);
        verify(searchCriteria).addAnd("tagKey", SearchCriteria.Op.SC, nestedCriteriaOne);
        verify(nestedCriteriaOne).addOr("name", SearchCriteria.Op.LIKE, "%frontend%");
        verify(nestedCriteriaOne).addOr("description", SearchCriteria.Op.LIKE, "%frontend%");
        verify(searchCriteria).addAnd("name", SearchCriteria.Op.SC, nestedCriteriaOne);
    }

    @Test
    public void searchForSecurityGroupsInternalReturnsDaoPairWhenCountIsZero() {
        ListSecurityGroupsCmd cmd = cmdWithDefaults();
        Pair<List<SecurityGroupJoinVO>, Integer> daoPair = new Pair<>(Collections.emptyList(), 0);
        when(securityGroupJoinDao.searchAndCount(eq(searchCriteria), any())).thenReturn(daoPair);

        Pair<List<SecurityGroupJoinVO>, Integer> result = service.searchForSecurityGroupsInternal(cmd);

        assertSame(daoPair, result);
        verify(securityGroupJoinDao, never()).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForSecurityGroupsInternalHydratesDistinctIdsWhenCountIsNonZero() {
        ListSecurityGroupsCmd cmd = cmdWithDefaults();
        SecurityGroupJoinVO firstDistinct = mock(SecurityGroupJoinVO.class);
        SecurityGroupJoinVO secondDistinct = mock(SecurityGroupJoinVO.class);
        when(firstDistinct.getId()).thenReturn(21L);
        when(secondDistinct.getId()).thenReturn(22L);
        when(securityGroupJoinDao.searchAndCount(eq(searchCriteria), any()))
                .thenReturn(new Pair<>(Arrays.asList(firstDistinct, secondDistinct), 2));

        SecurityGroupJoinVO hydrated = mock(SecurityGroupJoinVO.class);
        when(securityGroupJoinDao.searchByIds(any(Long[].class))).thenReturn(Collections.singletonList(hydrated));

        Pair<List<SecurityGroupJoinVO>, Integer> result = service.searchForSecurityGroupsInternal(cmd);

        assertEquals(Integer.valueOf(2), result.second());
        assertSame(hydrated, result.first().get(0));
        verify(securityGroupJoinDao).searchByIds(eq(new Long[] {21L, 22L}));
    }

    private ListSecurityGroupsCmd cmdWithDefaults() {
        ListSecurityGroupsCmd cmd = mock(ListSecurityGroupsCmd.class);
        when(cmd.getVirtualMachineId()).thenReturn(null);
        when(cmd.getSecurityGroupName()).thenReturn(null);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getTags()).thenReturn(null);
        when(cmd.getDomainId()).thenReturn(null);
        when(cmd.isRecursive()).thenReturn(false);
        when(cmd.getAccountName()).thenReturn(null);
        when(cmd.getProjectId()).thenReturn(null);
        when(cmd.listAll()).thenReturn(false);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(50L);
        return cmd;
    }
}
