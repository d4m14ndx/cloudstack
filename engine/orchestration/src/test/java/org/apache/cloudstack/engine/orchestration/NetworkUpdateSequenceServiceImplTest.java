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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.cloud.network.Network;
import com.cloud.network.Network.Provider;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.RedundantResource;
import com.cloud.network.router.VirtualRouter;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.dao.DomainRouterDao;

public class NetworkUpdateSequenceServiceImplTest {

    private static final long NETWORK_ID = 11L;

    private NetworkUpdateSequenceServiceImpl service;
    private Network network;
    private NetworkServiceMapDao networkServiceMapDao;
    private DomainRouterDao routerDao;

    @Before
    public void setUp() {
        service = new NetworkUpdateSequenceServiceImpl();
        networkServiceMapDao = mock(NetworkServiceMapDao.class);
        routerDao = mock(DomainRouterDao.class);
        network = mock(Network.class);
        when(network.getId()).thenReturn(NETWORK_ID);
        service.networkServiceMapDao = networkServiceMapDao;
        service.routerDao = routerDao;
        service.networkElements = Collections.emptyList();
    }

    @Test
    public void canUpdateInSequenceAllowsVirtualRouterProviderAndKnownRouterState() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Collections.singletonList(Provider.VirtualRouter.getName()));
        DomainRouterVO router = mockRouter(VirtualRouter.RedundantState.PRIMARY, "r-1");
        when(routerDao.listByNetworkAndRole(NETWORK_ID, VirtualRouter.Role.VIRTUAL_ROUTER)).thenReturn(Collections.singletonList(router));

        assertTrue(service.canUpdateInSequence(network, false));
    }

    @Test
    public void canUpdateInSequenceRejectsNonVirtualRouterProvider() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Arrays.asList(Provider.VirtualRouter.getName(), Provider.Netscaler.getName()));

        try {
            service.canUpdateInSequence(network, false);
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("providers other than virtualrouter"));
        }
    }

    @Test
    public void canUpdateInSequenceRejectsUnknownRedundantStateWithoutForced() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Collections.singletonList(Provider.VirtualRouter.getName()));
        DomainRouterVO router = mockRouter(VirtualRouter.RedundantState.UNKNOWN, "r-unknown");
        when(routerDao.listByNetworkAndRole(NETWORK_ID, VirtualRouter.Role.VIRTUAL_ROUTER)).thenReturn(Collections.singletonList(router));

        try {
            service.canUpdateInSequence(network, false);
            fail("Expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().contains("r-unknown"));
            assertTrue(e.getMessage().contains("forced to true"));
        }
    }

    @Test
    public void canUpdateInSequenceAllowsUnknownRedundantStateWhenForced() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Collections.singletonList(Provider.VirtualRouter.getName()));
        DomainRouterVO router = mockRouter(VirtualRouter.RedundantState.UNKNOWN, "r-forced");
        when(routerDao.listByNetworkAndRole(NETWORK_ID, VirtualRouter.Role.VIRTUAL_ROUTER)).thenReturn(Collections.singletonList(router));

        assertTrue(service.canUpdateInSequence(network, true));
    }

    @Test
    public void configureUpdateInSequenceDispatchesOnlyMatchingRedundantResource() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Collections.singletonList(Provider.VirtualRouter.getName()));
        NetworkElement matching = redundantElement(Provider.VirtualRouter, 0);
        NetworkElement nonMatching = redundantElement(Provider.Netscaler, 0);
        NetworkElement nonRedundant = mock(NetworkElement.class);
        when(nonRedundant.getProvider()).thenReturn(Provider.VirtualRouter);
        service.networkElements = Arrays.asList(nonMatching, nonRedundant, matching);

        service.configureUpdateInSequence(network);

        verify((RedundantResource) matching).configureResource(network);
        verify((RedundantResource) nonMatching, never()).configureResource(network);
    }

    @Test
    public void getResourceCountReturnsFirstMatchingRedundantResourceCount() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Collections.singletonList(Provider.VirtualRouter.getName()));
        NetworkElement first = redundantElement(Provider.VirtualRouter, 3);
        NetworkElement second = redundantElement(Provider.VirtualRouter, 7);
        service.networkElements = Arrays.asList(first, second);

        assertEquals(3, service.getResourceCount(network));
        verify((RedundantResource) second, never()).getResourceCount(network);
    }

    @Test
    public void getResourceCountReturnsZeroWhenNoMatchingRedundantResourceExists() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Collections.singletonList(Provider.VirtualRouter.getName()));
        NetworkElement nonRedundant = mock(NetworkElement.class);
        when(nonRedundant.getProvider()).thenReturn(Provider.VirtualRouter);
        service.networkElements = Collections.singletonList(nonRedundant);

        assertEquals(0, service.getResourceCount(network));
    }

    @Test
    public void finalizeUpdateInSequenceDispatchesFirstMatchingRedundantResource() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Collections.singletonList(Provider.VirtualRouter.getName()));
        NetworkElement first = redundantElement(Provider.VirtualRouter, 1);
        NetworkElement second = redundantElement(Provider.VirtualRouter, 2);
        service.networkElements = Arrays.asList(first, second);

        service.finalizeUpdateInSequence(network, true);

        verify((RedundantResource) first).finalize(network, true);
        verify((RedundantResource) second, never()).finalize(network, true);
    }

    @Test
    public void finalizeUpdateInSequenceIgnoresNonMatchingProvider() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Collections.singletonList(Provider.VirtualRouter.getName()));
        NetworkElement nonMatching = redundantElement(Provider.Netscaler, 1);
        service.networkElements = Collections.singletonList(nonMatching);

        service.finalizeUpdateInSequence(network, false);

        verify((RedundantResource) nonMatching, never()).finalize(network, false);
    }

    @Test
    public void configureUpdateInSequenceDoesNothingWhenProviderListIsEmpty() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Collections.emptyList());
        NetworkElement element = redundantElement(Provider.VirtualRouter, 1);
        service.networkElements = Collections.singletonList(element);

        service.configureUpdateInSequence(network);

        verify((RedundantResource) element, never()).configureResource(network);
    }

    private DomainRouterVO mockRouter(VirtualRouter.RedundantState state, String instanceName) {
        DomainRouterVO router = mock(DomainRouterVO.class);
        when(router.getRedundantState()).thenReturn(state);
        when(router.getInstanceName()).thenReturn(instanceName);
        return router;
    }

    private NetworkElement redundantElement(Provider provider, int resourceCount) {
        NetworkElement element = mock(NetworkElement.class, Mockito.withSettings().extraInterfaces(RedundantResource.class));
        when(element.getProvider()).thenReturn(provider);
        when(((RedundantResource) element).getResourceCount(network)).thenReturn(resourceCount);
        return element;
    }
}
