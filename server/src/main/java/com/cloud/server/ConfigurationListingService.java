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

import java.util.List;

import org.apache.cloudstack.api.command.admin.config.ListCfgGroupsByCmd;
import org.apache.cloudstack.api.command.admin.config.ListCfgsByCmd;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.config.ConfigurationGroup;

import com.cloud.utils.Pair;

/**
 * Read-only listing of global, scoped, and grouped CloudStack configuration
 * parameters — the {@code listConfigurations} and {@code listConfigurationGroups}
 * API entry points. This is a pure search layer: it builds
 * {@link com.cloud.utils.db.SearchCriteria} from the command's optional
 * filters (name, category, keyword, group/subgroup/parent, zone, cluster,
 * account, domain, storage pool, image store), runs them against the
 * configuration DAO, and — when the caller has narrowed the result to a
 * single scope — re-populates each row's {@code value} field from the
 * {@link org.apache.cloudstack.framework.config.ConfigDepot} so the response
 * reflects the value visible in that scope rather than the global default.
 *
 * <p>Access control is enforced before the search runs: domain admins are
 * defaulted to their own domain when neither account nor domain id is
 * provided, normal users are defaulted to their own account, and any
 * explicit account or domain id is checked through {@code AccountManager}.
 * Hidden configurations are always excluded.
 *
 * <p>Extracted from {@link ManagementServerImpl} as part of the Phase 4
 * Spring-component decomposition. The two public {@code ManagementService}
 * entry points covered here ({@code searchForConfigurations} and
 * {@code listConfigurationGroups}) remain on the god class as one-line
 * delegating wrappers so callers binding {@code ManagementService} still
 * resolve their method calls there.
 */
public interface ConfigurationListingService {

    /**
     * Search global or scoped configuration parameters that match the
     * filters carried by {@link ListCfgsByCmd}. Only one of zone, cluster,
     * account, domain, storage pool, or image store may be supplied; if more
     * than one is provided the call throws
     * {@link com.cloud.exception.InvalidParameterValueException}. When a
     * scope is supplied the returned rows have their {@code value} field
     * overwritten with the scope-local value resolved through
     * {@link org.apache.cloudstack.framework.config.ConfigDepot}.
     */
    Pair<List<? extends Configuration>, Integer> searchForConfigurations(ListCfgsByCmd cmd);

    /**
     * Return the configuration-parameter groups in deployment-defined
     * precedence order, optionally filtered to a single group by name. The
     * group records describe how parameters are bucketed for display in the
     * admin UI; this call performs no scoping or access checks beyond what
     * the DAO itself enforces.
     */
    Pair<List<? extends ConfigurationGroup>, Integer> listConfigurationGroups(ListCfgGroupsByCmd cmd);
}
