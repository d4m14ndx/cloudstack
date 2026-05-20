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
package org.apache.cloudstack.bff;

import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.auth.PluggableAPIAuthenticator;
import org.apache.cloudstack.bff.api.CreateUserSessionTokenCmd;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

import com.cloud.utils.component.ManagerBase;

public class BffTrustedSourceAuthManagerImpl extends ManagerBase implements PluggableAPIAuthenticator, Configurable {

    public static final ConfigKey<Boolean> BffTrustedSourceEnabled = new ConfigKey<>("Advanced", Boolean.class,
            "bff.trusted.source.enabled", "false",
            "Enables the trusted BFF source authenticator for createUserSessionToken. The API still requires signed API-key verification and the explicit impersonateUser role permission.",
            true);

    @Override
    public List<Class<?>> getAuthCommands() {
        if (!isBffTrustedSourceEnabled()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(CreateUserSessionTokenCmd.class);
    }

    protected boolean isBffTrustedSourceEnabled() {
        return BffTrustedSourceEnabled.value();
    }

    @Override
    public String getConfigComponentName() {
        return "BFF-TRUSTED-SOURCE-PLUGIN";
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {BffTrustedSourceEnabled};
    }
}
