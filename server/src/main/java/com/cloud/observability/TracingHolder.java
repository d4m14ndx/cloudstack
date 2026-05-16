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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

/**
 * Lazily-initialized singleton holder for the management server's
 * {@link OpenTelemetry} SDK instance.
 *
 * <p>Initialization is fully driven by standard OpenTelemetry environment
 * variables (the autoconfigure module reads them). Examples:
 *
 * <ul>
 *   <li>{@code OTEL_SERVICE_NAME=cloudstack-management}</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4318}</li>
 *   <li>{@code OTEL_TRACES_EXPORTER=otlp} (or {@code none} to disable)</li>
 *   <li>{@code OTEL_TRACES_SAMPLER=parentbased_traceidratio}</li>
 *   <li>{@code OTEL_TRACES_SAMPLER_ARG=0.1}</li>
 * </ul>
 *
 * <p>If no exporter is configured the SDK still runs in-process — spans are
 * created and propagated, just not exported — so any code calling
 * {@link #tracer()} is safe regardless of deployment configuration.
 *
 * <p>Tests can inject a custom OpenTelemetry instance via
 * {@link #setOpenTelemetry(OpenTelemetry)} and reset state via {@link #reset()}.
 *
 * @see <a href="https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/">OTel autoconfigure docs</a>
 */
public final class TracingHolder {
    private static final Logger LOG = LogManager.getLogger(TracingHolder.class);

    public static final String INSTRUMENTATION_NAME = "org.apache.cloudstack.management";

    private static volatile OpenTelemetry openTelemetry;

    private TracingHolder() {
    }

    public static OpenTelemetry openTelemetry() {
        OpenTelemetry local = openTelemetry;
        if (local == null) {
            synchronized (TracingHolder.class) {
                local = openTelemetry;
                if (local == null) {
                    local = initialize();
                    openTelemetry = local;
                }
            }
        }
        return local;
    }

    public static Tracer tracer() {
        return openTelemetry().getTracer(INSTRUMENTATION_NAME);
    }

    private static OpenTelemetry initialize() {
        try {
            // Default sampler to 10% if not set, so production deploys don't accidentally
            // get full-rate sampling. Operators override with OTEL_TRACES_SAMPLER_ARG.
            if (System.getenv("OTEL_TRACES_SAMPLER") == null
                    && System.getProperty("otel.traces.sampler") == null) {
                System.setProperty("otel.traces.sampler", "parentbased_traceidratio");
                System.setProperty("otel.traces.sampler.arg", "0.1");
            }
            if (System.getenv("OTEL_SERVICE_NAME") == null
                    && System.getProperty("otel.service.name") == null) {
                System.setProperty("otel.service.name", "cloudstack-management");
            }
            return AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk();
        } catch (Throwable t) {
            // Never let a tracing init failure break the management server.
            LOG.warn("OpenTelemetry initialization failed; falling back to no-op tracing.", t);
            return OpenTelemetry.noop();
        }
    }

    // Test hooks
    public static void setOpenTelemetry(OpenTelemetry sdk) {
        openTelemetry = sdk;
    }

    public static void reset() {
        openTelemetry = null;
    }
}
