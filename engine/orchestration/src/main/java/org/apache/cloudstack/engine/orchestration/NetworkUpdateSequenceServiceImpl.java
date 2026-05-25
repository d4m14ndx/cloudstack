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
package org.apache.cloudstack.engine.orchestration;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import com.cloud.network.Network;
import com.cloud.network.Network.Provider;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.RedundantResource;
import com.cloud.network.router.VirtualRouter;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.dao.DomainRouterDao;

@Component
public class NetworkUpdateSequenceServiceImpl implements NetworkUpdateSequenceService {

    @Inject
    protected NetworkServiceMapDao networkServiceMapDao;
    @Inject
    protected DomainRouterDao routerDao;

    protected List<NetworkElement> networkElements;

    public void setNetworkElements(final List<NetworkElement> networkElements) {
        this.networkElements = networkElements;
    }

    @Override
    public boolean canUpdateInSequence(final Network network, final boolean forced) {
        final List<Provider> providers = getNetworkProviders(network.getId());

        for (final Provider provider : providers) {
            if (provider != Provider.VirtualRouter) {
                throw new UnsupportedOperationException("Cannot update the network resources in sequence when providers other than virtualrouter are used");
            }
        }

        final List<DomainRouterVO> routers = routerDao.listByNetworkAndRole(network.getId(), VirtualRouter.Role.VIRTUAL_ROUTER);
        for (final DomainRouterVO router : routers) {
            if (router.getRedundantState() == VirtualRouter.RedundantState.UNKNOWN && !forced) {
                throw new CloudRuntimeException("Domain router: " + router.getInstanceName()
                        + " is in unknown state, Cannot update network. set parameter forced to true for forcing an update");
            }
        }
        return true;
    }

    @Override
    public void configureUpdateInSequence(final Network network) {
        final List<Provider> providers = getNetworkProviders(network.getId());
        for (final NetworkElement element : networkElements) {
            if (providers.contains(element.getProvider()) && element instanceof RedundantResource) {
                ((RedundantResource) element).configureResource(network);
            }
        }
    }

    @Override
    public int getResourceCount(final Network network) {
        final List<Provider> providers = getNetworkProviders(network.getId());
        int resourceCount = 0;
        for (final NetworkElement element : networkElements) {
            if (providers.contains(element.getProvider()) && element instanceof RedundantResource) {
                resourceCount = ((RedundantResource) element).getResourceCount(network);
                break;
            }
        }
        return resourceCount;
    }

    @Override
    public void finalizeUpdateInSequence(final Network network, final boolean success) {
        final List<Provider> providers = getNetworkProviders(network.getId());
        for (final NetworkElement element : networkElements) {
            if (providers.contains(element.getProvider()) && element instanceof RedundantResource) {
                ((RedundantResource) element).finalize(network, success);
                break;
            }
        }
    }

    private List<Provider> getNetworkProviders(final long networkId) {
        final List<String> providerNames = networkServiceMapDao.getDistinctProviders(networkId);
        final List<Provider> providers = new ArrayList<>();
        for (final String providerName : providerNames) {
            providers.add(Network.Provider.getProvider(providerName));
        }
        return providers;
    }
}
