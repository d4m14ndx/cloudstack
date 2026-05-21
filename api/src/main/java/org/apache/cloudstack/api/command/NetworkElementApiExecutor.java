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
package org.apache.cloudstack.api.command;

import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;

public final class NetworkElementApiExecutor {

    private NetworkElementApiExecutor() {
    }

    public static void execute(ApiOperation operation) {
        try {
            operation.execute();
        } catch (InvalidParameterValueException invalidParamExcp) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, invalidParamExcp.getMessage());
        } catch (CloudRuntimeException runtimeExcp) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, runtimeExcp.getMessage());
        }
    }

    public static <T> T requireNonNull(T result, String failureMessage) {
        if (result == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, failureMessage);
        }
        return result;
    }

    public static void requireSuccess(boolean result, String failureMessage) {
        if (!result) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, failureMessage);
        }
    }

    @FunctionalInterface
    public interface ApiOperation {
        void execute();
    }
}
