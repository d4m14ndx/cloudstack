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
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.SerialVersionUID;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.TransactionLegacy;

/**
 * Health check endpoints for orchestrators (Kubernetes, load balancers, etc.).
 *
 * <ul>
 *   <li>{@code GET /health/live} — Liveness: process is alive. Always returns 200
 *       unless the JVM is wedged. Use for restart triggers.</li>
 *   <li>{@code GET /health/ready} — Readiness: Spring context is initialized
 *       and the database is reachable. Use for traffic routing decisions.</li>
 *   <li>{@code GET /health} — Aggregate: 200 only if both live and ready.</li>
 * </ul>
 *
 * Responses are plain text; the status code is the source of truth.
 */
public class HealthServlet extends HttpServlet {
    private static final long serialVersionUID = SerialVersionUID.HealthServlet;
    private static final Logger LOG = LogManager.getLogger(HealthServlet.class);

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        final String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        resp.setContentType("text/plain; charset=utf-8");
        resp.setHeader("Cache-Control", "no-store");

        switch (path) {
            case "/live":
                writePlain(resp, HttpServletResponse.SC_OK, "OK");
                return;
            case "/ready":
                checkReadiness(resp);
                return;
            default:
                // Aggregate: liveness is implicit (we're serving), so this reduces to readiness.
                checkReadiness(resp);
        }
    }

    private void checkReadiness(HttpServletResponse resp) throws IOException {
        if (ComponentContext.getApplicationContext() == null) {
            writePlain(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "context-not-initialized");
            return;
        }
        if (!pingDatabase()) {
            writePlain(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "database-unreachable");
            return;
        }
        writePlain(resp, HttpServletResponse.SC_OK, "OK");
    }

    private boolean pingDatabase() {
        try (TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
            Connection conn = txn.getConnection();
            return conn != null && conn.isValid(1);
        } catch (SQLException e) {
            LOG.debug("Readiness DB ping failed", e);
            return false;
        } catch (Exception e) {
            LOG.debug("Readiness DB ping threw unexpected error", e);
            return false;
        }
    }

    private static void writePlain(HttpServletResponse resp, int status, String body) throws IOException {
        resp.setStatus(status);
        try (PrintWriter out = resp.getWriter()) {
            out.write(body);
        }
    }
}
