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

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.cloud.utils.SerialVersionUID;

/**
 * Exposes JVM and process metrics in Prometheus text format at {@code /metrics}.
 *
 * <p>Scrape with Prometheus:
 * <pre>
 *   - job_name: cloudstack-management
 *     metrics_path: /client/metrics
 *     static_configs:
 *       - targets: ['mgmt:8080']
 * </pre>
 *
 * <p>This is JVM/process-level instrumentation only (heap, GC, threads, file
 * descriptors, uptime, etc.). Business-level metrics (VMs, hosts, storage)
 * are still served by the separate prometheus integration plugin on its
 * dedicated port.
 */
public class MetricsServlet extends HttpServlet {
    private static final long serialVersionUID = SerialVersionUID.MetricsServlet;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain; version=0.0.4; charset=utf-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(MetricsRegistryHolder.get().scrape());
    }
}
