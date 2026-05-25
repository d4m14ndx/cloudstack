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
package com.cloud.server;

import java.util.Map;

import org.apache.cloudstack.api.command.user.config.ListCapabilitiesCmd;

/**
 * Top-level deployment introspection — the {@code listCapabilities} flag
 * dictionary that the UI and downstream clients consume to feature-flag
 * their behaviour, plus the {@code getVersion} read of the running
 * management server's package implementation version.
 *
 * <p>The capabilities map is a flat snapshot of global toggles (security
 * groups enabled, elastic load balancer support, KVM snapshot support,
 * region-wide secondary storage, custom disk size bounds, API throttling
 * parameters, Kubernetes service availability, per-user view/expunge
 * permissions, and so on) plus the per-domain VPN customer-gateway
 * algorithm exclusion lists. None of the values are mutated by this slice;
 * every entry is read from a {@link com.cloud.framework.config.ConfigKey},
 * a {@link com.cloud.configuration.Config} value, or a DAO probe at the
 * moment of the call.
 *
 * <p>Extracted from {@link ManagementServerImpl} as part of the Phase 4
 * Spring-component decomposition. The two public {@code ManagementService}
 * entry points covered here ({@code listCapabilities} and {@code getVersion})
 * remain on the god class as one-line delegating wrappers so that callers
 * still binding the {@code ManagementService} interface continue to resolve
 * their method calls there.
 */
public interface CapabilitiesService {

    /**
     * Build the deployment-wide capabilities map for the caller's account
     * (and optionally the supplied domain, with access checks applied).
     * The returned map is a freshly allocated {@link java.util.HashMap}
     * containing the full snapshot of feature flags, throttling limits,
     * and per-user/per-domain entitlement booleans the UI surfaces.
     */
    Map<String, Object> listCapabilities(ListCapabilitiesCmd cmd);

    /**
     * Read the running management server's package implementation version
     * from {@link ManagementServer}'s manifest. Returns {@code "unknown"}
     * when the manifest is missing the {@code Implementation-Version}
     * attribute (most commonly when running from an exploded classpath
     * during local development).
     */
    String getVersion();
}
