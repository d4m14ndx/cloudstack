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

package com.cloud.usage.dao;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.sql.PreparedStatement;
import java.util.Date;
import java.util.TimeZone;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.utils.DateUtil;

@RunWith(MockitoJUnitRunner.class)
public class UsageDateRangeBinderTest {

    @Mock
    private PreparedStatement preparedStatement;

    private final Date startDate = new Date(1715754600000L);
    private final Date endDate = new Date(1715841000000L);

    @Test
    public void bindStartEndPairsUsesGmtFormattedDatesAndReturnsNextIndex() throws Exception {
        UsageDateRangeBinder binder = UsageDateRangeBinder.of(startDate, endDate);

        int nextIndex = binder.bindStartEndPairs(preparedStatement, 3, 2);

        assertEquals(7, nextIndex);
        verify(preparedStatement).setString(3, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), startDate));
        verify(preparedStatement).setString(4, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), endDate));
        verify(preparedStatement).setString(5, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), startDate));
        verify(preparedStatement).setString(6, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), endDate));
        verifyNoMoreInteractions(preparedStatement);
    }

    @Test
    public void bindEndThenStartEndPairsPreservesExistingUsageDaoOrder() throws Exception {
        UsageDateRangeBinder binder = UsageDateRangeBinder.of(startDate, endDate);

        int nextIndex = binder.bindEnd(preparedStatement, 2);
        nextIndex = binder.bindStartEndPairs(preparedStatement, nextIndex, 3);

        assertEquals(9, nextIndex);
        verify(preparedStatement).setString(2, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), endDate));
        verify(preparedStatement).setString(3, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), startDate));
        verify(preparedStatement).setString(4, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), endDate));
        verify(preparedStatement).setString(5, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), startDate));
        verify(preparedStatement).setString(6, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), endDate));
        verify(preparedStatement).setString(7, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), startDate));
        verify(preparedStatement).setString(8, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), endDate));
        verifyNoMoreInteractions(preparedStatement);
    }
}
