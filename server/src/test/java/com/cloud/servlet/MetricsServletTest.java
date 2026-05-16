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
package com.cloud.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

public class MetricsServletTest {

    private PrometheusMeterRegistry registry;

    @Before
    public void setUp() {
        // Inject a clean registry so we don't pull in JVM binders and slow the test.
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsRegistryHolder.setRegistry(registry);
    }

    @After
    public void tearDown() {
        MetricsRegistryHolder.reset();
    }

    @Test
    public void emptyRegistryStillReturns200WithPrometheusContentType() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(body));

        new MetricsServlet().doGet(req, resp);

        verify(resp).setStatus(HttpServletResponse.SC_OK);
        verify(resp).setContentType("text/plain; version=0.0.4; charset=utf-8");
        verify(resp).setHeader("Cache-Control", "no-store");
        // An empty registry produces an empty scrape — that's a valid response.
        assertEquals("", body.toString());
    }

    @Test
    public void registeredMetricsAppearInScrapeOutput() throws Exception {
        Counter counter = registry.counter("test.counter", "tag", "value");
        counter.increment();
        counter.increment();
        counter.increment();

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(body));

        new MetricsServlet().doGet(req, resp);

        String output = body.toString();
        assertTrue("scrape should mention metric name, got:\n" + output,
                output.contains("test_counter_total"));
        assertTrue("scrape should include tag, got:\n" + output,
                output.contains("tag=\"value\""));
        assertTrue("scrape should reflect counter value 3.0, got:\n" + output,
                output.contains("3.0"));
    }
}
