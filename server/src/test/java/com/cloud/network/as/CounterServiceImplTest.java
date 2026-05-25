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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.autoscale.CreateCounterCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListCountersCmd;
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
import com.cloud.network.Network;
import com.cloud.network.as.dao.ConditionDao;
import com.cloud.network.as.dao.CounterDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.db.Filter;

@RunWith(MockitoJUnitRunner.class)
public class CounterServiceImplTest {

    private static final Long COUNTER_ID = 1L;
    private static final String COUNTER_NAME = "test-counter";
    private static final String COUNTER_VALUE = "test-value";
    private static final String COUNTER_PROVIDER = "VirtualRouter";
    private static final String COUNTER_SOURCE = "CPU";
    private static final String INVALID = "invalid";

    @Mock
    private CounterDao counterDao;
    @Mock
    private ConditionDao conditionDao;

    @Mock
    private CounterVO counterMock;
    @Mock
    private ConditionVO conditionMock;

    @InjectMocks
    private CounterServiceImpl service;

    private AccountVO account;
    private UserVO user;

    @Before
    public void setUp() {
        account = new AccountVO("test-account", 1L, "domain", Account.Type.NORMAL, "acc-uuid");
        account.setId(5L);
        user = new UserVO(1, "test-user", "password", "first", "last", "email", "tz",
                "user-uuid", User.Source.UNKNOWN);
        CallContext.register(user, account);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ---- createCounter ----

    @Test
    public void createCounterPersistsValidCounter() {
        CreateCounterCmd cmd = new CreateCounterCmd();
        ReflectionTestUtils.setField(cmd, ApiConstants.NAME, COUNTER_NAME);
        ReflectionTestUtils.setField(cmd, ApiConstants.VALUE, COUNTER_VALUE);
        ReflectionTestUtils.setField(cmd, ApiConstants.PROVIDER, COUNTER_PROVIDER);
        ReflectionTestUtils.setField(cmd, ApiConstants.SOURCE, COUNTER_SOURCE);

        when(counterDao.findByNameProviderValue(eq(COUNTER_NAME), eq(COUNTER_VALUE), any())).thenReturn(null);
        when(counterMock.getUuid()).thenReturn("counter-uuid");
        when(counterDao.persist(any(CounterVO.class))).thenReturn(counterMock);

        Counter result = service.createCounter(cmd);

        assertSame(counterMock, result);
        ArgumentCaptor<CounterVO> captor = ArgumentCaptor.forClass(CounterVO.class);
        verify(counterDao).persist(captor.capture());
        CounterVO persisted = captor.getValue();
        assertEquals(COUNTER_NAME, persisted.getName());
        assertEquals(COUNTER_VALUE, persisted.getValue());
        assertEquals(Counter.Source.CPU, persisted.getSource());
    }

    @Test
    public void createCounterLowerCasesSourceAndValidates() {
        CreateCounterCmd cmd = new CreateCounterCmd();
        ReflectionTestUtils.setField(cmd, ApiConstants.NAME, COUNTER_NAME);
        ReflectionTestUtils.setField(cmd, ApiConstants.VALUE, COUNTER_VALUE);
        ReflectionTestUtils.setField(cmd, ApiConstants.PROVIDER, COUNTER_PROVIDER);
        ReflectionTestUtils.setField(cmd, ApiConstants.SOURCE, "cpu");

        when(counterDao.findByNameProviderValue(any(), any(), any())).thenReturn(null);
        when(counterMock.getUuid()).thenReturn("counter-uuid");
        when(counterDao.persist(any(CounterVO.class))).thenReturn(counterMock);

        Counter result = service.createCounter(cmd);

        assertSame(counterMock, result);
        verify(counterDao).persist(any(CounterVO.class));
    }

    @Test
    public void createCounterRejectsInvalidSource() {
        CreateCounterCmd cmd = new CreateCounterCmd();
        ReflectionTestUtils.setField(cmd, ApiConstants.NAME, COUNTER_NAME);
        ReflectionTestUtils.setField(cmd, ApiConstants.VALUE, COUNTER_VALUE);
        ReflectionTestUtils.setField(cmd, ApiConstants.PROVIDER, COUNTER_PROVIDER);
        ReflectionTestUtils.setField(cmd, ApiConstants.SOURCE, INVALID);

        assertThrows(InvalidParameterValueException.class, () -> service.createCounter(cmd));
        verify(counterDao, never()).persist(any(CounterVO.class));
    }

    @Test
    public void createCounterRejectsInvalidProvider() {
        CreateCounterCmd cmd = new CreateCounterCmd();
        ReflectionTestUtils.setField(cmd, ApiConstants.NAME, COUNTER_NAME);
        ReflectionTestUtils.setField(cmd, ApiConstants.VALUE, COUNTER_VALUE);
        ReflectionTestUtils.setField(cmd, ApiConstants.PROVIDER, INVALID);
        ReflectionTestUtils.setField(cmd, ApiConstants.SOURCE, COUNTER_SOURCE);

        assertThrows(InvalidParameterValueException.class, () -> service.createCounter(cmd));
        verify(counterDao, never()).persist(any(CounterVO.class));
    }

    @Test
    public void createCounterRejectsDuplicate() {
        CreateCounterCmd cmd = new CreateCounterCmd();
        ReflectionTestUtils.setField(cmd, ApiConstants.NAME, COUNTER_NAME);
        ReflectionTestUtils.setField(cmd, ApiConstants.VALUE, COUNTER_VALUE);
        ReflectionTestUtils.setField(cmd, ApiConstants.PROVIDER, COUNTER_PROVIDER);
        ReflectionTestUtils.setField(cmd, ApiConstants.SOURCE, COUNTER_SOURCE);

        when(counterDao.findByNameProviderValue(eq(COUNTER_NAME), eq(COUNTER_VALUE), any())).thenReturn(counterMock);

        assertThrows(InvalidParameterValueException.class, () -> service.createCounter(cmd));
        verify(counterDao, never()).persist(any(CounterVO.class));
    }

    @Test
    public void createCounterNormalizesProviderName() {
        CreateCounterCmd cmd = new CreateCounterCmd();
        ReflectionTestUtils.setField(cmd, ApiConstants.NAME, COUNTER_NAME);
        ReflectionTestUtils.setField(cmd, ApiConstants.VALUE, COUNTER_VALUE);
        ReflectionTestUtils.setField(cmd, ApiConstants.PROVIDER, "virtualrouter");
        ReflectionTestUtils.setField(cmd, ApiConstants.SOURCE, COUNTER_SOURCE);

        when(counterDao.findByNameProviderValue(eq(COUNTER_NAME), eq(COUNTER_VALUE),
                eq(Network.Provider.VirtualRouter.getName()))).thenReturn(null);
        when(counterMock.getUuid()).thenReturn("counter-uuid");
        when(counterDao.persist(any(CounterVO.class))).thenReturn(counterMock);

        Counter result = service.createCounter(cmd);
        assertSame(counterMock, result);
        verify(counterDao).findByNameProviderValue(eq(COUNTER_NAME), eq(COUNTER_VALUE),
                eq(Network.Provider.VirtualRouter.getName()));
    }

    // ---- getCounter ----

    @Test
    public void getCounterDelegatesToDao() {
        when(counterDao.findById(COUNTER_ID)).thenReturn(counterMock);
        Counter result = service.getCounter(COUNTER_ID);
        assertSame(counterMock, result);
        verify(counterDao).findById(COUNTER_ID);
    }

    @Test
    public void getCounterReturnsNullWhenMissing() {
        when(counterDao.findById(anyLong())).thenReturn(null);
        assertNull(service.getCounter(99L));
    }

    // ---- listCounters ----

    @Test
    public void listCountersDelegatesToDao() {
        List<CounterVO> mocks = Arrays.asList(counterMock);
        when(counterDao.listCounters(any(), any(), any(), any(), any(), any(Filter.class))).thenReturn(mocks);

        ListCountersCmd cmd = new ListCountersCmd();
        ReflectionTestUtils.setField(cmd, ApiConstants.PROVIDER, COUNTER_PROVIDER);

        List<? extends Counter> result = service.listCounters(cmd);
        assertEquals(mocks, result);
    }

    @Test
    public void listCountersWorksWithNoFilters() {
        when(counterDao.listCounters(any(), any(), any(), any(), any(), any(Filter.class)))
                .thenReturn(Arrays.asList(counterMock));

        ListCountersCmd cmd = new ListCountersCmd();
        List<? extends Counter> result = service.listCounters(cmd);
        assertEquals(1, result.size());
    }

    @Test
    public void listCountersUpperCasesSource() {
        when(counterDao.listCounters(any(), any(), eq("CPU"), any(), any(), any(Filter.class)))
                .thenReturn(Arrays.asList(counterMock));

        ListCountersCmd cmd = new ListCountersCmd();
        ReflectionTestUtils.setField(cmd, ApiConstants.SOURCE, "cpu");

        List<? extends Counter> result = service.listCounters(cmd);
        assertEquals(1, result.size());
        verify(counterDao).listCounters(any(), any(), eq("CPU"), any(), any(), any(Filter.class));
    }

    @Test
    public void listCountersNormalizesProviderName() {
        when(counterDao.listCounters(any(), any(), any(), eq(Network.Provider.VirtualRouter.getName()),
                any(), any(Filter.class))).thenReturn(Arrays.asList(counterMock));

        ListCountersCmd cmd = new ListCountersCmd();
        ReflectionTestUtils.setField(cmd, ApiConstants.PROVIDER, "virtualrouter");

        List<? extends Counter> result = service.listCounters(cmd);
        assertEquals(1, result.size());
        verify(counterDao).listCounters(any(), any(), any(), eq(Network.Provider.VirtualRouter.getName()),
                any(), any(Filter.class));
    }

    @Test
    public void listCountersRejectsInvalidProvider() {
        ListCountersCmd cmd = new ListCountersCmd();
        ReflectionTestUtils.setField(cmd, ApiConstants.PROVIDER, INVALID);

        assertThrows(InvalidParameterValueException.class, () -> service.listCounters(cmd));
    }

    // ---- deleteCounter ----

    @Test
    public void deleteCounterRemovesUnusedCounter() throws ResourceInUseException {
        when(counterDao.findById(COUNTER_ID)).thenReturn(counterMock);
        when(conditionDao.findByCounterId(COUNTER_ID)).thenReturn(null);
        when(counterDao.remove(COUNTER_ID)).thenReturn(true);

        boolean result = service.deleteCounter(COUNTER_ID);

        assertTrue(result);
        verify(counterDao).remove(COUNTER_ID);
    }

    @Test
    public void deleteCounterReturnsFalseWhenDaoRemoveFails() throws ResourceInUseException {
        when(counterDao.findById(COUNTER_ID)).thenReturn(counterMock);
        when(conditionDao.findByCounterId(COUNTER_ID)).thenReturn(null);
        when(counterDao.remove(COUNTER_ID)).thenReturn(false);

        boolean result = service.deleteCounter(COUNTER_ID);

        assertFalse(result);
        verify(counterDao).remove(COUNTER_ID);
    }

    @Test
    public void deleteCounterRejectsUnknownCounter() {
        when(counterDao.findById(COUNTER_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class, () -> service.deleteCounter(COUNTER_ID));
        verify(counterDao, never()).remove(anyLong());
    }

    @Test
    public void deleteCounterRefusesWhenInUseByCondition() {
        when(counterDao.findById(COUNTER_ID)).thenReturn(counterMock);
        when(conditionDao.findByCounterId(COUNTER_ID)).thenReturn(conditionMock);

        assertThrows(ResourceInUseException.class, () -> service.deleteCounter(COUNTER_ID));
        verify(counterDao, never()).remove(anyLong());
    }
}
