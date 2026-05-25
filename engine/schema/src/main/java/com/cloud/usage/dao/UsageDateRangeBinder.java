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

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Date;
import java.util.TimeZone;

import com.cloud.utils.DateUtil;

final class UsageDateRangeBinder {
    private static final TimeZone GMT_TIME_ZONE = TimeZone.getTimeZone("GMT");

    private final String startDate;
    private final String endDate;

    private UsageDateRangeBinder(Date startDate, Date endDate) {
        this.startDate = DateUtil.getDateDisplayString(GMT_TIME_ZONE, startDate);
        this.endDate = DateUtil.getDateDisplayString(GMT_TIME_ZONE, endDate);
    }

    static UsageDateRangeBinder of(Date startDate, Date endDate) {
        return new UsageDateRangeBinder(startDate, endDate);
    }

    int bindEnd(PreparedStatement pstmt, int parameterIndex) throws SQLException {
        pstmt.setString(parameterIndex++, endDate);
        return parameterIndex;
    }

    int bindStartEnd(PreparedStatement pstmt, int parameterIndex) throws SQLException {
        pstmt.setString(parameterIndex++, startDate);
        pstmt.setString(parameterIndex++, endDate);
        return parameterIndex;
    }

    int bindStartEndPairs(PreparedStatement pstmt, int parameterIndex, int pairCount) throws SQLException {
        for (int i = 0; i < pairCount; i++) {
            parameterIndex = bindStartEnd(pstmt, parameterIndex);
        }
        return parameterIndex;
    }
}
