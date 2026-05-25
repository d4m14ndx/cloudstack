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
package org.apache.cloudstack.veeam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VeeamControlServiceImplTest {

    @Test
    public void startDoesNotCreateServerWhenDisabled() {
        TestVeeamControlService service = new TestVeeamControlService(false);

        assertTrue(service.start());

        assertFalse(service.serverCreated);
        assertFalse(service.serverStarted);
    }

    @Test
    public void startCreatesAndStartsServerWhenEnabled() {
        TestVeeamControlService service = new TestVeeamControlService(true);

        assertTrue(service.start());

        assertTrue(service.serverCreated);
        assertTrue(service.serverStarted);
        assertSame(service.testServer, service.getVeeamControlServer());
    }

    @Test
    public void startReturnsFalseWhenEnabledServerFails() {
        TestVeeamControlService service = new TestVeeamControlService(true);
        service.failOnStart = true;

        assertFalse(service.start());

        assertTrue(service.serverCreated);
        assertTrue(service.serverStarted);
        assertNull(service.getVeeamControlServer());
    }

    private static final class TestVeeamControlService extends VeeamControlServiceImpl {
        private final boolean enabled;
        private final TestVeeamControlServer testServer = new TestVeeamControlServer();
        private boolean serverCreated;
        private boolean serverStarted;
        private boolean failOnStart;

        private TestVeeamControlService(final boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        protected boolean isControlServiceEnabled() {
            return enabled;
        }

        @Override
        protected VeeamControlServer createVeeamControlServer() {
            serverCreated = true;
            return testServer;
        }

        private final class TestVeeamControlServer extends VeeamControlServer {
            private TestVeeamControlServer() {
                super(TestVeeamControlService.this);
            }

            @Override
            public void start() throws Exception {
                serverStarted = true;
                if (failOnStart) {
                    throw new Exception("start failed");
                }
            }
        }
    }
}
