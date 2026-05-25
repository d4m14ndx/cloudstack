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
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.Test;

public class HealthServletTest {

    @Test
    public void liveReturns200() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();

        when(req.getPathInfo()).thenReturn("/live");
        when(resp.getWriter()).thenReturn(new PrintWriter(body));

        new HealthServlet().doGet(req, resp);

        assertResponse(resp, HttpServletResponse.SC_OK);
        assertEquals("OK", body.toString());
    }

    @Test
    public void readyReturns503WhenContextNotInitialized() throws Exception {
        // ComponentContext is a static singleton; if no ApplicationContext is set,
        // readiness must report unavailable.
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();

        when(req.getPathInfo()).thenReturn("/ready");
        when(resp.getWriter()).thenReturn(new PrintWriter(body));

        new HealthServlet().doGet(req, resp);

        assertResponse(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue("body should describe failure reason, got: " + body,
                body.toString().contains("context-not-initialized")
                        || body.toString().contains("database-unreachable"));
    }

    @Test
    public void rootDelegatesToReadiness() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();

        when(req.getPathInfo()).thenReturn(null);
        when(resp.getWriter()).thenReturn(new PrintWriter(body));

        new HealthServlet().doGet(req, resp);

        // Same outcome as /ready in a no-context test environment.
        assertResponse(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    private static void assertResponse(HttpServletResponse resp, int expected) {
        org.mockito.ArgumentCaptor<Integer> status = org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(resp).setStatus(status.capture());
        assertEquals(expected, status.getValue().intValue());
    }
}
