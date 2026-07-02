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
package com.cloud.hypervisor.proxmox.api;

import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Exception thrown when a Proxmox VE API request fails. Carries the HTTP status code of the
 * failed request; the message contains the PVE error body (including the "errors" member with
 * per-field validation errors, when present). A status code of 0 indicates a failure that did
 * not produce an HTTP error response (I/O error, task failure, or task poll timeout).
 */
public class ProxmoxApiException extends CloudRuntimeException {

    private static final long serialVersionUID = 4326138183167112397L;

    private final int statusCode;

    public ProxmoxApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ProxmoxApiException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * @return the HTTP status code of the failed request, or 0 if the failure was not an HTTP
     *         error response (transport error, task failure, timeout).
     */
    public int getStatusCode() {
        return statusCode;
    }
}
