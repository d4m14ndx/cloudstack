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
package com.cloud.network.as;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.apache.cloudstack.api.command.user.autoscale.CreateConditionCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateConditionCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceInUseException;
import com.cloud.network.as.dao.AutoScalePolicyConditionMapDao;
import com.cloud.network.as.dao.AutoScaleVmGroupDao;
import com.cloud.network.as.dao.AutoScaleVmGroupPolicyMapDao;
import com.cloud.network.as.dao.AutoScaleVmGroupStatisticsDao;
import com.cloud.network.as.dao.ConditionDao;
import com.cloud.network.as.dao.CounterDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class ConditionServiceImplTest {

    private static final long COUNTER_ID = 1L;
    private static final long CONDITION_ID = 2L;
    private static final long POLICY_ID = 11L;
    private static final long THRESHOLD = 100L;
    private static final Condition.Operator OP = Condition.Operator.GT;
    private static final String INVALID = "invalid";

    @Mock
    private AccountManager accountMgr;
    @Mock
    private CounterDao counterDao;
    @Mock
    private ConditionDao conditionDao;
    @Mock
    private AutoScalePolicyConditionMapDao autoScalePolicyConditionMapDao;
    @Mock
    private AutoScaleVmGroupDao autoScaleVmGroupDao;
    @Mock
    private AutoScaleVmGroupPolicyMapDao autoScaleVmGroupPolicyMapDao;
    @Mock
    private AutoScaleVmGroupStatisticsDao asGroupStatisticsDao;

    @Mock
    private CounterVO counterMock;
    @Mock
    private ConditionVO conditionMock;
    @Mock
    private AutoScalePolicyConditionMapVO autoScalePolicyConditionMapVOMock;
    @Mock
    private AutoScaleVmGroupPolicyMapVO autoScaleVmGroupPolicyMapVOMock;
    @Mock
    private AutoScaleVmGroupVO asVmGroupMock;

    @InjectMocks
    private ConditionServiceImpl service;

    private AccountVO account;
    private UserVO user;

    @Before
    public void setUp() {
        account = new AccountVO("test-account", 1L, "domain", Account.Type.NORMAL, "acc-uuid");
        account.setId(5L);
        user = new UserVO(1, "test-user", "password", "first", "last", "email", "tz",
                "user-uuid", User.Source.UNKNOWN);
        CallContext.register(user, account);

        lenient().when(accountMgr.finalizeOwner(any(), any(), any(), any())).thenReturn(account);
        lenient().when(conditionDao.findById(any())).thenReturn(conditionMock);
        lenient().when(conditionDao.persist(any(ConditionVO.class))).thenReturn(conditionMock);
        lenient().when(conditionMock.getUuid()).thenReturn("cond-uuid");
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ---- createCondition ----

    @Test
    public void createConditionPersistsValidCondition() {
        CreateConditionCmd cmd = new CreateConditionCmd();
        ReflectionTestUtils.setField(cmd, "counterId", COUNTER_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", String.valueOf(OP));
        ReflectionTestUtils.setField(cmd, "threshold", THRESHOLD);

        when(counterDao.findById(COUNTER_ID)).thenReturn(counterMock);

        Condition result = service.createCondition(cmd);

        assertSame(conditionMock, result);
        ArgumentCaptor<ConditionVO> captor = ArgumentCaptor.forClass(ConditionVO.class);
        verify(conditionDao).persist(captor.capture());
        ConditionVO persisted = captor.getValue();
        assertEquals(COUNTER_ID, persisted.getCounterId());
        assertEquals(THRESHOLD, persisted.getThreshold());
        assertEquals(OP, persisted.getRelationalOperator());
    }

    @Test
    public void createConditionNormalizesOperatorCase() {
        CreateConditionCmd cmd = new CreateConditionCmd();
        ReflectionTestUtils.setField(cmd, "counterId", COUNTER_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", "gt");
        ReflectionTestUtils.setField(cmd, "threshold", THRESHOLD);

        when(counterDao.findById(COUNTER_ID)).thenReturn(counterMock);

        Condition result = service.createCondition(cmd);

        assertSame(conditionMock, result);
        ArgumentCaptor<ConditionVO> captor = ArgumentCaptor.forClass(ConditionVO.class);
        verify(conditionDao).persist(captor.capture());
        assertEquals(Condition.Operator.GT, captor.getValue().getRelationalOperator());
    }

    @Test
    public void createConditionRejectsInvalidOperator() {
        CreateConditionCmd cmd = new CreateConditionCmd();
        ReflectionTestUtils.setField(cmd, "counterId", COUNTER_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", INVALID);
        ReflectionTestUtils.setField(cmd, "threshold", THRESHOLD);

        assertThrows(InvalidParameterValueException.class, () -> service.createCondition(cmd));
        verify(conditionDao, never()).persist(any(ConditionVO.class));
    }

    @Test
    public void createConditionRejectsNegativeThreshold() {
        CreateConditionCmd cmd = new CreateConditionCmd();
        ReflectionTestUtils.setField(cmd, "counterId", COUNTER_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", String.valueOf(OP));
        ReflectionTestUtils.setField(cmd, "threshold", -1L);

        assertThrows(InvalidParameterValueException.class, () -> service.createCondition(cmd));
        verify(conditionDao, never()).persist(any(ConditionVO.class));
    }

    @Test
    public void createConditionRejectsUnknownCounter() {
        CreateConditionCmd cmd = new CreateConditionCmd();
        ReflectionTestUtils.setField(cmd, "counterId", COUNTER_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", String.valueOf(OP));
        ReflectionTestUtils.setField(cmd, "threshold", THRESHOLD);

        when(counterDao.findById(COUNTER_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class, () -> service.createCondition(cmd));
        verify(conditionDao, never()).persist(any(ConditionVO.class));
    }

    @Test
    public void createConditionAllowsZeroThreshold() {
        CreateConditionCmd cmd = new CreateConditionCmd();
        ReflectionTestUtils.setField(cmd, "counterId", COUNTER_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", String.valueOf(OP));
        ReflectionTestUtils.setField(cmd, "threshold", 0L);

        when(counterDao.findById(COUNTER_ID)).thenReturn(counterMock);

        Condition result = service.createCondition(cmd);

        assertSame(conditionMock, result);
        verify(conditionDao).persist(any(ConditionVO.class));
    }

    // ---- deleteCondition ----

    @Test
    public void deleteConditionRemovesUnusedCondition() throws ResourceInUseException {
        when(conditionDao.findById(CONDITION_ID)).thenReturn(conditionMock);
        when(autoScalePolicyConditionMapDao.isConditionInUse(CONDITION_ID)).thenReturn(false);
        when(conditionDao.remove(CONDITION_ID)).thenReturn(true);

        boolean success = service.deleteCondition(CONDITION_ID);

        assertTrue(success);
        verify(conditionDao).remove(CONDITION_ID);
    }

    @Test
    public void deleteConditionReturnsFalseWhenDaoRemoveFails() throws ResourceInUseException {
        when(conditionDao.findById(CONDITION_ID)).thenReturn(conditionMock);
        when(autoScalePolicyConditionMapDao.isConditionInUse(CONDITION_ID)).thenReturn(false);
        when(conditionDao.remove(CONDITION_ID)).thenReturn(false);

        boolean success = service.deleteCondition(CONDITION_ID);

        assertFalse(success);
        verify(conditionDao).remove(CONDITION_ID);
    }

    @Test
    public void deleteConditionRejectsUnknownCondition() {
        when(conditionDao.findById(CONDITION_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class, () -> service.deleteCondition(CONDITION_ID));
        verify(conditionDao, never()).remove(anyLong());
    }

    @Test
    public void deleteConditionRefusesWhenInUse() {
        when(conditionDao.findById(CONDITION_ID)).thenReturn(conditionMock);
        when(autoScalePolicyConditionMapDao.isConditionInUse(CONDITION_ID)).thenReturn(true);

        assertThrows(ResourceInUseException.class, () -> service.deleteCondition(CONDITION_ID));
        verify(conditionDao, never()).remove(anyLong());
    }

    // ---- updateCondition ----

    @Test
    @SuppressWarnings("unchecked")
    public void updateConditionAppliesChangesWhenNoPolicies() throws ResourceInUseException {
        GenericSearchBuilder<AutoScalePolicyConditionMapVO, Long> searchBuilder = mockGenericSearchBuilder();

        UpdateConditionCmd cmd = new UpdateConditionCmd();
        ReflectionTestUtils.setField(cmd, "id", CONDITION_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", String.valueOf(OP));
        ReflectionTestUtils.setField(cmd, "threshold", THRESHOLD);

        when(autoScalePolicyConditionMapDao.customSearch(any(), isNull())).thenReturn(Collections.emptyList());
        when(conditionDao.update(eq(CONDITION_ID), any(ConditionVO.class))).thenReturn(true);

        Condition result = service.updateCondition(cmd);

        assertSame(conditionMock, result);
        verify(conditionDao).update(eq(CONDITION_ID), any(ConditionVO.class));
        verify(asGroupStatisticsDao, never()).updateStateByGroup(any(), any(), any());
    }

    @Test
    public void updateConditionRejectsInvalidOperator() {
        UpdateConditionCmd cmd = new UpdateConditionCmd();
        ReflectionTestUtils.setField(cmd, "id", CONDITION_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", INVALID);
        ReflectionTestUtils.setField(cmd, "threshold", THRESHOLD);

        assertThrows(InvalidParameterValueException.class, () -> service.updateCondition(cmd));
        verify(conditionDao, never()).update(anyLong(), any(ConditionVO.class));
    }

    @Test
    public void updateConditionRejectsNegativeThreshold() {
        UpdateConditionCmd cmd = new UpdateConditionCmd();
        ReflectionTestUtils.setField(cmd, "id", CONDITION_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", String.valueOf(OP));
        ReflectionTestUtils.setField(cmd, "threshold", -1L);

        assertThrows(InvalidParameterValueException.class, () -> service.updateCondition(cmd));
        verify(conditionDao, never()).update(anyLong(), any(ConditionVO.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void updateConditionRefusesWhenUsedByActiveVmGroup() throws ResourceInUseException {
        mockGenericSearchBuilder();

        SearchBuilder<AutoScaleVmGroupPolicyMapVO> policySB = mockSearchBuilder(autoScaleVmGroupPolicyMapDao, autoScaleVmGroupPolicyMapVOMock);
        SearchBuilder<AutoScaleVmGroupVO> vmGroupSB = mockSearchBuilder(autoScaleVmGroupDao, asVmGroupMock);
        SearchCriteria<AutoScaleVmGroupVO> sc2 = (SearchCriteria<AutoScaleVmGroupVO>) org.mockito.Mockito.mock(SearchCriteria.class);
        when(vmGroupSB.create()).thenReturn(sc2);
        when(autoScaleVmGroupDao.search(eq(sc2), isNull())).thenReturn(Arrays.asList(asVmGroupMock));

        when(autoScalePolicyConditionMapDao.customSearch(any(), isNull())).thenReturn(Arrays.asList(POLICY_ID));

        UpdateConditionCmd cmd = new UpdateConditionCmd();
        ReflectionTestUtils.setField(cmd, "id", CONDITION_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", String.valueOf(OP));
        ReflectionTestUtils.setField(cmd, "threshold", THRESHOLD);

        assertThrows(ResourceInUseException.class, () -> service.updateCondition(cmd));
        verify(conditionDao, never()).update(anyLong(), any(ConditionVO.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void updateConditionMarksPolicyStatsInactiveAfterSuccess() throws ResourceInUseException {
        mockGenericSearchBuilder();

        SearchBuilder<AutoScaleVmGroupPolicyMapVO> policySB = mockSearchBuilder(autoScaleVmGroupPolicyMapDao, autoScaleVmGroupPolicyMapVOMock);
        SearchBuilder<AutoScaleVmGroupVO> vmGroupSB = mockSearchBuilder(autoScaleVmGroupDao, asVmGroupMock);
        SearchCriteria<AutoScaleVmGroupVO> sc2 = (SearchCriteria<AutoScaleVmGroupVO>) org.mockito.Mockito.mock(SearchCriteria.class);
        when(vmGroupSB.create()).thenReturn(sc2);
        when(autoScaleVmGroupDao.search(eq(sc2), isNull())).thenReturn(Collections.emptyList());

        when(autoScalePolicyConditionMapDao.customSearch(any(), isNull())).thenReturn(Arrays.asList(POLICY_ID));
        when(conditionDao.update(eq(CONDITION_ID), any(ConditionVO.class))).thenReturn(true);

        UpdateConditionCmd cmd = new UpdateConditionCmd();
        ReflectionTestUtils.setField(cmd, "id", CONDITION_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", String.valueOf(OP));
        ReflectionTestUtils.setField(cmd, "threshold", THRESHOLD);

        Condition result = service.updateCondition(cmd);

        assertSame(conditionMock, result);
        verify(conditionDao).update(eq(CONDITION_ID), any(ConditionVO.class));
        verify(asGroupStatisticsDao).updateStateByGroup(isNull(), eq(POLICY_ID),
                eq(AutoScaleVmGroupStatisticsVO.State.INACTIVE));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void updateConditionSkipsStatsUpdateWhenDaoUpdateFails() throws ResourceInUseException {
        mockGenericSearchBuilder();

        when(autoScalePolicyConditionMapDao.customSearch(any(), isNull())).thenReturn(Collections.emptyList());
        when(conditionDao.update(eq(CONDITION_ID), any(ConditionVO.class))).thenReturn(false);

        UpdateConditionCmd cmd = new UpdateConditionCmd();
        ReflectionTestUtils.setField(cmd, "id", CONDITION_ID);
        ReflectionTestUtils.setField(cmd, "relationalOperator", String.valueOf(OP));
        ReflectionTestUtils.setField(cmd, "threshold", THRESHOLD);

        Condition result = service.updateCondition(cmd);

        assertSame(conditionMock, result);
        verify(asGroupStatisticsDao, never()).updateStateByGroup(any(), any(), any());
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private GenericSearchBuilder<AutoScalePolicyConditionMapVO, Long> mockGenericSearchBuilder() {
        GenericSearchBuilder<AutoScalePolicyConditionMapVO, Long> searchBuilder =
                (GenericSearchBuilder<AutoScalePolicyConditionMapVO, Long>) org.mockito.Mockito.mock(GenericSearchBuilder.class);
        SearchCriteria<Long> sc = (SearchCriteria<Long>) org.mockito.Mockito.mock(SearchCriteria.class);
        when(autoScalePolicyConditionMapDao.createSearchBuilder(Long.class)).thenReturn(searchBuilder);
        when(searchBuilder.entity()).thenReturn(autoScalePolicyConditionMapVOMock);
        when(searchBuilder.create()).thenReturn(sc);
        return searchBuilder;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> SearchBuilder<T> mockSearchBuilder(Object dao, T entity) {
        SearchBuilder<T> sb = (SearchBuilder<T>) org.mockito.Mockito.mock(SearchBuilder.class);
        try {
            java.lang.reflect.Method m = dao.getClass().getMethod("createSearchBuilder");
            when((SearchBuilder) m.invoke(dao)).thenReturn(sb);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        when(sb.entity()).thenReturn(entity);
        return sb;
    }
}
