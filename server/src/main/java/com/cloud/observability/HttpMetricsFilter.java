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

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.cloud.servlet.MetricsRegistryHolder;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Records HTTP request rate and duration metrics for every request the
 * management server serves. Exposed via the {@code /metrics} endpoint.
 *
 * <p>Emitted metric:
 * <pre>
 *   http_server_request_duration_seconds{method, uri, status} (Timer)
 * </pre>
 *
 * <p>The {@code uri} tag is a coarse route bucket (e.g. {@code /api},
 * {@code /console}, {@code /health}, {@code /metrics}, {@code other}) rather
 * than the raw URI, to keep tag cardinality bounded. The {@code status} tag is
 * a status class string ({@code 2xx}, {@code 3xx}, {@code 4xx}, {@code 5xx})
 * for the same reason.
 */
public class HttpMetricsFilter implements Filter {

    private static final String METRIC_NAME = "http.server.request.duration";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        long startNanos = System.nanoTime();
        Throwable thrown = null;
        try {
            chain.doFilter(request, response);
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            record(req, resp, System.nanoTime() - startNanos, thrown);
        }
    }

    private void record(HttpServletRequest req, HttpServletResponse resp, long elapsedNanos, Throwable thrown) {
        MeterRegistry registry = MetricsRegistryHolder.get();
        String method = req.getMethod() == null ? "UNKNOWN" : req.getMethod();
        String uri = routeBucket(req.getRequestURI());
        String status = statusClass(resp.getStatus(), thrown);

        Timer.builder(METRIC_NAME)
                .description("HTTP request duration in seconds, by method, route, and status class")
                .tag("method", method)
                .tag("uri", uri)
                .tag("status", status)
                .register(registry)
                .record(elapsedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    static String routeBucket(String uri) {
        if (uri == null || uri.isEmpty()) return "other";
        // Strip context path prefix if present (e.g. "/client/api" -> "/api")
        int firstSegmentEnd = uri.indexOf('/', 1);
        String remainder = firstSegmentEnd < 0 ? uri : uri.substring(firstSegmentEnd);
        if (remainder.startsWith("/api")) return "/api";
        if (remainder.startsWith("/console")) return "/console";
        if (remainder.startsWith("/health")) return "/health";
        if (remainder.startsWith("/metrics")) return "/metrics";
        // Top-level handlers fall here (e.g. "/", "/client" itself)
        if (uri.startsWith("/api")) return "/api";
        if (uri.startsWith("/console")) return "/console";
        if (uri.startsWith("/health")) return "/health";
        if (uri.startsWith("/metrics")) return "/metrics";
        return "other";
    }

    static String statusClass(int status, Throwable thrown) {
        if (thrown != null) return "5xx";
        if (status >= 500) return "5xx";
        if (status >= 400) return "4xx";
        if (status >= 300) return "3xx";
        if (status >= 200) return "2xx";
        return "1xx";
    }
}
