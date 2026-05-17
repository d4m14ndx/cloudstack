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
package com.cloud.configuration;

import org.apache.cloudstack.api.command.admin.config.ResetCfgCmd;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigurationVO;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.Pair;

/**
 * Configuration-reset orchestration — handles {@code resetConfiguration}
 * for the {@link com.cloud.configuration.ConfigurationService} contract.
 * Extracted from {@link ConfigurationManagerImpl} as slice 6 of the
 * Phase 4 Spring-component decomposition.
 *
 * <p>Resets a {@code ConfigurationVO} to its default value (for global
 * scope) or removes the scope-specific detail row (for Zone, Cluster,
 * StoragePool, Domain, Account, ImageStore scopes), then invalidates
 * the {@code ConfigDepot} cache so subsequent reads see the reset value.
 *
 * <p>{@code ConfigurationManagerImpl} keeps a one-line delegating wrapper
 * so the {@code ConfigurationService} contract stays unchanged.
 */
public interface ConfigurationResetService {

    /**
     * Reset a configuration value. Per-scope behaviour:
     * <ul>
     *   <li><b>Zone / StoragePool / ImageStore</b> &mdash; remove the detail row
     *       in the corresponding {@code *DetailsDao}.</li>
     *   <li><b>Cluster</b> &mdash; remove the cluster detail row, unless the
     *       config is one of the over-provisioning factors (cpu / mem), which
     *       are re-persisted with the default value rather than removed.</li>
     *   <li><b>Domain / Account</b> &mdash; remove the detail row if present.</li>
     *   <li><b>Global (no scope id)</b> &mdash; update the {@code configuration}
     *       table itself to the default value.</li>
     * </ul>
     * The returned pair is the (possibly refreshed) {@code ConfigurationVO}
     * and the new effective value in the resolved scope.
     *
     * @throws InvalidParameterValueException if the config name is unknown,
     *     more than one scope id was supplied, the scope id refers to a
     *     non-existent entity, or the scope is not valid for that config.
     */
    Pair<Configuration, String> resetConfiguration(ResetCfgCmd cmd)
            throws InvalidParameterValueException;

    /**
     * Read the effective value of a configuration in the given scope —
     * for Global, the row's value; for any other scope, the {@code ConfigKey}'s
     * {@code valueInScope}. Exposed for callers (and tests) that need to
     * inspect the pre- or post-reset value without going through reset.
     */
    String getConfigurationValueInScope(ConfigurationVO config, String name, ConfigKey.Scope scope, Long id);
}
