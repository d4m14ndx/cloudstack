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

import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.framework.config.ConfigKey;

import com.cloud.utils.component.ManagerBase;

public class VeeamControlServiceImpl extends ManagerBase implements VeeamControlService {

    private List<RouteHandler> routeHandlers = Collections.emptyList();
    private VeeamControlServer veeamControlServer;

    @Override
    public boolean start() {
        if (!isControlServiceEnabled()) {
            logger.info("Veeam Control Service is disabled");
            return true;
        }

        veeamControlServer = createVeeamControlServer();
        try {
            veeamControlServer.start();
        } catch (Exception e) {
            logger.error("Failed to start Veeam Control Service", e);
            veeamControlServer = null;
            return false;
        }
        return true;
    }

    @Override
    public boolean stop() {
        if (veeamControlServer == null) {
            return true;
        }

        try {
            veeamControlServer.stop();
        } catch (Exception e) {
            logger.error("Failed to stop Veeam Control Service", e);
        } finally {
            veeamControlServer = null;
        }
        return true;
    }

    @Override
    public List<Class<?>> getCommands() {
        return Collections.emptyList();
    }

    @Override
    public String getConfigComponentName() {
        return VeeamControlService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                Enabled,
                BindAddress,
                Port,
                ContextPath
        };
    }

    @Override
    public List<RouteHandler> getRouteHandlers() {
        return routeHandlers;
    }

    public void setRouteHandlers(final List<RouteHandler> routeHandlers) {
        this.routeHandlers = routeHandlers == null ? Collections.emptyList() : List.copyOf(routeHandlers);
    }

    protected boolean isControlServiceEnabled() {
        return Enabled.value();
    }

    protected VeeamControlServer createVeeamControlServer() {
        return new VeeamControlServer(this);
    }

    VeeamControlServer getVeeamControlServer() {
        return veeamControlServer;
    }
}
