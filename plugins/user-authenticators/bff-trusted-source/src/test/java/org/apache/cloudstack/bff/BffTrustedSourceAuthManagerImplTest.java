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
package org.apache.cloudstack.bff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.cloudstack.bff.api.CreateUserSessionTokenCmd;
import org.junit.Test;

public class BffTrustedSourceAuthManagerImplTest {

    @Test
    public void getAuthCommandsWhenDisabledReturnsNoCommands() {
        BffTrustedSourceAuthManagerImpl manager = new BffTrustedSourceAuthManagerImpl();

        assertTrue(manager.getAuthCommands().isEmpty());
    }

    @Test
    public void getAuthCommandsWhenEnabledReturnsCreateUserSessionTokenCommand() {
        BffTrustedSourceAuthManagerImpl manager = new EnabledBffTrustedSourceAuthManager();

        List<Class<?>> commands = manager.getAuthCommands();

        assertEquals(1, commands.size());
        assertEquals(CreateUserSessionTokenCmd.class, commands.get(0));
    }

    private static class EnabledBffTrustedSourceAuthManager extends BffTrustedSourceAuthManagerImpl {
        @Override
        protected boolean isBffTrustedSourceEnabled() {
            return true;
        }
    }
}
