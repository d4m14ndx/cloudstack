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
package org.apache.cloudstack.veeam;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class VeeamControlServer {
    private static final Logger LOGGER = LogManager.getLogger(VeeamControlServer.class);

    private final VeeamControlService veeamControlService;
    private Server server;

    public VeeamControlServer(final VeeamControlService veeamControlService) {
        this.veeamControlService = veeamControlService;
    }

    public void start() throws Exception {
        if (server != null && server.isStarted()) {
            return;
        }

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        String bindAddress = normalizeBindAddress(VeeamControlService.BindAddress.value());
        if (bindAddress != null) {
            connector.setHost(bindAddress);
        }
        connector.setPort(VeeamControlService.Port.value());
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath(normalizeContextPath(VeeamControlService.ContextPath.value()));
        contextHandler.addServlet(new ServletHolder(new VeeamControlServlet(veeamControlService.getRouteHandlers())), "/*");
        server.setHandler(contextHandler);

        server.start();
        LOGGER.info("Started Veeam Control Service on {}:{}{}",
                bindAddress == null ? "0.0.0.0" : bindAddress,
                VeeamControlService.Port.value(),
                contextHandler.getContextPath());
    }

    public void stop() throws Exception {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private static String normalizeBindAddress(final String bindAddress) {
        if (bindAddress == null || bindAddress.isBlank()) {
            return null;
        }
        return bindAddress.strip();
    }

    private static String normalizeContextPath(final String contextPath) {
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath.strip())) {
            return "/";
        }
        String normalized = contextPath.strip();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
