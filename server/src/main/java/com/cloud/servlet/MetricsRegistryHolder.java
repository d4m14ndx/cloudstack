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

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Lazily-initialized singleton holder for the management server's
 * {@link PrometheusMeterRegistry}. JVM/process metric binders are attached on
 * first access. Designed for use outside Spring (in servlets, filters, etc.)
 * without requiring container wiring.
 *
 * <p>Tests can inject a custom registry via {@link #setRegistry(PrometheusMeterRegistry)}
 * and reset state via {@link #reset()}.
 */
public final class MetricsRegistryHolder {

    private static volatile PrometheusMeterRegistry registry;

    private MetricsRegistryHolder() {
    }

    public static PrometheusMeterRegistry get() {
        PrometheusMeterRegistry local = registry;
        if (local == null) {
            synchronized (MetricsRegistryHolder.class) {
                local = registry;
                if (local == null) {
                    local = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
                    bindDefaultMetrics(local);
                    registry = local;
                }
            }
        }
        return local;
    }

    private static void bindDefaultMetrics(PrometheusMeterRegistry r) {
        new ClassLoaderMetrics().bindTo(r);
        new JvmMemoryMetrics().bindTo(r);
        new JvmGcMetrics().bindTo(r);
        new JvmThreadMetrics().bindTo(r);
        new JvmHeapPressureMetrics().bindTo(r);
        new ProcessorMetrics().bindTo(r);
        new FileDescriptorMetrics().bindTo(r);
        new UptimeMetrics().bindTo(r);
    }

    // Test hooks
    public static void setRegistry(PrometheusMeterRegistry r) {
        registry = r;
    }

    public static void reset() {
        registry = null;
    }
}
