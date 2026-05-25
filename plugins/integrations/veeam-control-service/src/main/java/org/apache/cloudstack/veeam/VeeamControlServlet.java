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

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class VeeamControlServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String ROOT_PAYLOAD = "{\"name\":\"CloudStack Veeam Control Service\",\"status\":\"ready\"}";

    private final List<RouteHandler> routeHandlers;

    public VeeamControlServlet(final List<RouteHandler> routeHandlers) {
        this.routeHandlers = routeHandlers == null ? Collections.emptyList() : List.copyOf(routeHandlers);
    }

    @Override
    protected void service(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        String method = request.getMethod();
        String path = normalizePath(request);

        if ("/".equals(path)) {
            handleRoot(method, response);
            return;
        }

        for (RouteHandler routeHandler : routeHandlers) {
            if (routeHandler.canHandle(method, path)) {
                routeHandler.handle(request, response, path);
                return;
            }
        }

        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private static void handleRoot(final String method, final HttpServletResponse response) throws IOException {
        if (!"GET".equals(method) && !"POST".equals(method)) {
            response.setHeader("Allow", "GET, POST");
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(ROOT_PAYLOAD);
    }

    private static String normalizePath(final HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.isBlank()) {
            path = request.getServletPath();
        }
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path;
    }
}
