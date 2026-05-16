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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.cloud.servlet.MetricsRegistryHolder;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

public class HttpMetricsFilterTest {

    private PrometheusMeterRegistry registry;

    @Before
    public void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsRegistryHolder.setRegistry(registry);
    }

    @After
    public void tearDown() {
        MetricsRegistryHolder.reset();
    }

    @Test
    public void recordsTimerForOkApiRequest() throws Exception {
        runFilter("GET", "/client/api", 200, null);
        Timer t = registry.find("http.server.request.duration")
                .tag("method", "GET")
                .tag("uri", "/api")
                .tag("status", "2xx")
                .timer();
        assertNotNull("timer should be registered with expected tags", t);
        assertEquals(1, t.count());
    }

    @Test
    public void recordsStatusClassAcrossFamilies() throws Exception {
        runFilter("GET", "/client/api", 200, null);
        runFilter("GET", "/client/api", 302, null);
        runFilter("POST", "/client/api", 401, null);
        runFilter("POST", "/client/api", 500, null);

        assertEquals(1, registry.find("http.server.request.duration").tag("status", "2xx").timer().count());
        assertEquals(1, registry.find("http.server.request.duration").tag("status", "3xx").timer().count());
        assertEquals(1, registry.find("http.server.request.duration").tag("status", "4xx").timer().count());
        assertEquals(1, registry.find("http.server.request.duration").tag("status", "5xx").timer().count());
    }

    @Test
    public void exceptionInChainIsRecordedAs5xxAndRethrown() throws Exception {
        HttpServletRequest req = mockRequest("POST", "/client/api");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getStatus()).thenReturn(200);

        FilterChain chain = mock(FilterChain.class);
        RuntimeException boom = new RuntimeException("boom");
        doThrow(boom).when(chain).doFilter(any(), any());

        try {
            new HttpMetricsFilter().doFilter(req, resp, chain);
            fail("filter must rethrow chain exceptions");
        } catch (RuntimeException expected) {
            assertEquals(boom, expected);
        }

        Timer t = registry.find("http.server.request.duration").tag("status", "5xx").timer();
        assertNotNull(t);
        assertEquals(1, t.count());
    }

    @Test
    public void routeBucketCollapsesUrisByPrefix() {
        assertEquals("/api", HttpMetricsFilter.routeBucket("/client/api"));
        assertEquals("/api", HttpMetricsFilter.routeBucket("/api"));
        assertEquals("/health", HttpMetricsFilter.routeBucket("/client/health/ready"));
        assertEquals("/metrics", HttpMetricsFilter.routeBucket("/client/metrics"));
        assertEquals("/console", HttpMetricsFilter.routeBucket("/client/console"));
        assertEquals("other", HttpMetricsFilter.routeBucket("/client/anything-else"));
        assertEquals("other", HttpMetricsFilter.routeBucket(null));
    }

    private void runFilter(String method, String uri, int status, Throwable thrown) throws Exception {
        HttpServletRequest req = mockRequest(method, uri);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getStatus()).thenReturn(status);
        FilterChain chain = mock(FilterChain.class);
        if (thrown != null) {
            doThrow(thrown).when(chain).doFilter(any(), any());
        }
        new HttpMetricsFilter().doFilter(req, resp, chain);
    }

    private static HttpServletRequest mockRequest(String method, String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(uri);
        return req;
    }
}
