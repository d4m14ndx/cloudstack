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
package com.cloud.api;

import org.apache.cloudstack.api.response.SnapshotPolicyResponse;
import org.apache.cloudstack.api.response.SnapshotResponse;
import org.apache.cloudstack.api.response.SnapshotScheduleResponse;
import org.apache.cloudstack.api.response.VMSnapshotResponse;

import com.cloud.storage.Snapshot;
import com.cloud.storage.snapshot.SnapshotPolicy;
import com.cloud.storage.snapshot.SnapshotSchedule;
import com.cloud.vm.snapshot.VMSnapshot;

public interface ApiSnapshotResponseService {

    SnapshotResponse createSnapshotResponse(Snapshot snapshot);

    VMSnapshotResponse createVMSnapshotResponse(VMSnapshot vmSnapshot);

    SnapshotPolicyResponse createSnapshotPolicyResponse(SnapshotPolicy policy);

    SnapshotScheduleResponse createSnapshotScheduleResponse(SnapshotSchedule snapshotSchedule);
}
