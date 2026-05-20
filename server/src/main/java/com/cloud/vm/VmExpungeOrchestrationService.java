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
package com.cloud.vm;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.uservm.UserVm;

public interface VmExpungeOrchestrationService {

    void configure(Map<String, String> configs);

    void scheduleExpungeTask(ScheduledExecutorService executor, ManagerOperations managerOperations);

    boolean expunge(UserVmVO vm);

    void transitionExpungingToError(long vmId);

    interface ManagerOperations {
        UserVm expungeVm(long vmId) throws ResourceUnavailableException, ConcurrentOperationException;
    }
}
