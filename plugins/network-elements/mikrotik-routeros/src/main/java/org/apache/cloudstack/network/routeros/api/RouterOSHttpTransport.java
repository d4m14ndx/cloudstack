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
package org.apache.cloudstack.network.routeros.api;

import java.io.Closeable;
import java.io.IOException;

/**
 * Thin HTTP transport abstraction so that {@link RouterOSApiClient} request
 * construction and idempotency logic can be unit tested without a network.
 */
public interface RouterOSHttpTransport extends Closeable {

    Response execute(Request request) throws IOException;

    final class Request {
        private final String method;
        private final String url;
        private final String body;

        public Request(final String method, final String url, final String body) {
            this.method = method;
            this.url = url;
            this.body = body;
        }

        public String getMethod() {
            return method;
        }

        public String getUrl() {
            return url;
        }

        public String getBody() {
            return body;
        }

        @Override
        public String toString() {
            return method + " " + url;
        }
    }

    final class Response {
        private final int status;
        private final String body;

        public Response(final int status, final String body) {
            this.status = status;
            this.body = body;
        }

        public int getStatus() {
            return status;
        }

        public String getBody() {
            return body;
        }
    }
}
