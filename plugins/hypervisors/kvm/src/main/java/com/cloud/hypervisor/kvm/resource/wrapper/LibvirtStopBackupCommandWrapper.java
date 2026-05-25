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

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.backup.StopBackupAnswer;
import org.apache.cloudstack.backup.StopBackupCommand;

@ResourceWrapper(handles = StopBackupCommand.class)
public class LibvirtStopBackupCommandWrapper extends CommandWrapper<StopBackupCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(StopBackupCommand command, LibvirtComputingResource resource) {
        Script script = new Script("virsh", resource.getCmdsTimeout(), logger);
        script.add("backup-end");
        script.add("--domain");
        script.add(command.getVmName());
        String result = script.execute();
        if (result != null) {
            return new StopBackupAnswer(command, false, result);
        }
        return new StopBackupAnswer(command, true, null);
    }
}
