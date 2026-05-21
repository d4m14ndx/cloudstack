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
package org.apache.cloudstack.network.tungsten.api.command;

import com.cloud.user.Account;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.junit.Assert;
import org.junit.Test;

public class TungstenFabricAsyncCmdTest {

    @Test
    public void setSuccessResponseUsesCommandResponseName() {
        TestTungstenFabricAsyncCmd cmd = new TestTungstenFabricAsyncCmd();

        cmd.setSuccessResponse();

        SuccessResponse response = (SuccessResponse) cmd.getResponseObject();
        Assert.assertTrue(response.getSuccess());
        Assert.assertEquals(TestTungstenFabricAsyncCmd.COMMAND_RESPONSE_NAME, response.getResponseName());
    }

    private static class TestTungstenFabricAsyncCmd extends TungstenFabricAsyncCmd {
        private static final String COMMAND_RESPONSE_NAME = "testTungstenFabricResponse";

        @Override
        public void execute() {
        }

        @Override
        public String getEventType() {
            return "TEST";
        }

        @Override
        public String getEventDescription() {
            return "test";
        }

        @Override
        public String getCommandName() {
            return COMMAND_RESPONSE_NAME;
        }

        @Override
        public long getEntityOwnerId() {
            return Account.ACCOUNT_ID_SYSTEM;
        }
    }
}
