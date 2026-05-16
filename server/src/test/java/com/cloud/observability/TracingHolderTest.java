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
package com.cloud.observability;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Test;

import io.opentelemetry.api.OpenTelemetry;

public class TracingHolderTest {

    @After
    public void tearDown() {
        TracingHolder.reset();
    }

    @Test
    public void returnsInjectedInstanceWhenSet() {
        OpenTelemetry injected = OpenTelemetry.noop();
        TracingHolder.setOpenTelemetry(injected);
        assertSame(injected, TracingHolder.openTelemetry());
        assertNotNull(TracingHolder.tracer());
    }

    @Test
    public void resetClearsInstance() {
        TracingHolder.setOpenTelemetry(OpenTelemetry.noop());
        TracingHolder.reset();
        // After reset, the lazy init runs — never returns null even if the SDK
        // can't configure an exporter.
        assertNotNull(TracingHolder.openTelemetry());
    }
}
