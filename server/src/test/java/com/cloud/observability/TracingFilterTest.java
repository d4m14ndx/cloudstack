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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

public class TracingFilterTest {

    private InMemorySpanExporter exporter;

    @Before
    public void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        TracingHolder.setOpenTelemetry(OpenTelemetrySdk.builder().setTracerProvider(tp).build());
    }

    @After
    public void tearDown() {
        TracingHolder.reset();
    }

    @Test
    public void wrapsApiRequestInServerSpan() throws Exception {
        HttpServletRequest req = mockRequest("GET", "/client/api");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getStatus()).thenReturn(200);

        FilterChain chain = mock(FilterChain.class);
        AtomicReference<Span> spanInChain = new AtomicReference<>();
        doAnswer(invocation -> {
            spanInChain.set(Span.current());
            return null;
        }).when(chain).doFilter(any(), any());

        new TracingFilter().doFilter(req, resp, chain);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData s = spans.get(0);
        assertEquals("GET /api", s.getName());
        assertEquals(StatusCode.UNSET, s.getStatus().getStatusCode());
        assertNotNull("filter chain should run inside the span context", spanInChain.get());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    public void marksSpanAsErrorOn5xxResponse() throws Exception {
        HttpServletRequest req = mockRequest("POST", "/client/api");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getStatus()).thenReturn(500);

        new TracingFilter().doFilter(req, resp, mock(FilterChain.class));

        SpanData s = exporter.getFinishedSpanItems().get(0);
        assertEquals(StatusCode.ERROR, s.getStatus().getStatusCode());
    }

    @Test
    public void skipsHealthAndMetricsPaths() throws Exception {
        for (String path : new String[]{"/client/health", "/client/health/live", "/client/health/ready", "/client/metrics"}) {
            HttpServletRequest req = mockRequest("GET", path);
            HttpServletResponse resp = mock(HttpServletResponse.class);
            FilterChain chain = mock(FilterChain.class);

            new TracingFilter().doFilter(req, resp, chain);

            verify(chain, times(1)).doFilter(any(), any());
            verify(resp, never()).getStatus();
        }
        assertTrue("no spans should be created for health/metrics endpoints",
                exporter.getFinishedSpanItems().isEmpty());
    }

    @Test
    public void recordsExceptionAndRethrowsWhenChainFails() throws Exception {
        HttpServletRequest req = mockRequest("GET", "/client/api");
        HttpServletResponse resp = mock(HttpServletResponse.class);

        FilterChain chain = mock(FilterChain.class);
        RuntimeException boom = new RuntimeException("boom");
        doAnswer(invocation -> { throw boom; }).when(chain).doFilter(any(), any());

        RuntimeException thrown = null;
        try {
            new TracingFilter().doFilter(req, resp, chain);
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertEquals("filter must rethrow", boom, thrown);

        SpanData s = exporter.getFinishedSpanItems().get(0);
        assertEquals(StatusCode.ERROR, s.getStatus().getStatusCode());
        assertEquals(1, s.getEvents().size()); // recordException adds an "exception" event
    }

    private static HttpServletRequest mockRequest(String method, String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getScheme()).thenReturn("http");
        when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        return req;
    }
}
