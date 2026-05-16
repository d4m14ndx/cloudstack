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
import java.util.Collections;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.UrlAttributes;

/**
 * Servlet filter that wraps every incoming HTTP request in an OpenTelemetry
 * {@link Span}. Trace context propagated by upstream callers (via W3C
 * {@code traceparent} header) is honored.
 *
 * <p>Excludes the {@code /health/*} and {@code /metrics} endpoints — those are
 * scraped by orchestrators and Prometheus respectively, and including them
 * floods the trace store with low-value spans.
 */
public class TracingFilter implements Filter {

    private static final TextMapGetter<HttpServletRequest> HEADER_GETTER =
            new TextMapGetter<HttpServletRequest>() {
                @Override
                public Iterable<String> keys(HttpServletRequest req) {
                    return req.getHeaderNames() == null ? Collections.emptyList() :
                            Collections.list(req.getHeaderNames());
                }

                @Override
                public String get(HttpServletRequest req, String key) {
                    return req == null ? null : req.getHeader(key);
                }
            };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        if (shouldSkip(req)) {
            chain.doFilter(request, response);
            return;
        }

        OpenTelemetry otel = TracingHolder.openTelemetry();
        Tracer tracer = otel.getTracer(TracingHolder.INSTRUMENTATION_NAME);

        Context parent = otel.getPropagators().getTextMapPropagator()
                .extract(Context.current(), req, HEADER_GETTER);

        Span span = tracer.spanBuilder(spanName(req))
                .setParent(parent)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, req.getMethod())
                .setAttribute(UrlAttributes.URL_PATH, req.getRequestURI())
                .setAttribute(UrlAttributes.URL_SCHEME, req.getScheme())
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            chain.doFilter(request, response);
            span.setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, resp.getStatus());
            if (resp.getStatus() >= 500) {
                span.setStatus(StatusCode.ERROR, "HTTP " + resp.getStatus());
            }
        } catch (Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR, t.getClass().getSimpleName());
            throw t;
        } finally {
            span.end();
        }
    }

    private static boolean shouldSkip(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (path == null) return false;
        return path.endsWith("/metrics")
                || path.contains("/health/")
                || path.endsWith("/health");
    }

    private static String spanName(HttpServletRequest req) {
        // Use "METHOD /api" for API requests (the specific command lives in a query
        // string parameter — we don't add it to the span name to keep cardinality
        // bounded). Specific command names can be added as span attributes by
        // ApiServlet if/when desired.
        String path = req.getRequestURI();
        if (path != null && path.contains("/api")) {
            return req.getMethod() + " /api";
        }
        return req.getMethod() + " " + (path == null ? "/" : path);
    }
}
