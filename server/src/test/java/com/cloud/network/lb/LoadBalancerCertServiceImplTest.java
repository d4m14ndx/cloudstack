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
package com.cloud.network.lb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.dao.LoadBalancerCertMapDao;
import com.cloud.network.dao.LoadBalancerCertMapVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.SslCertVO;
import com.cloud.network.lb.LoadBalancingRule.LbSslCert;
import com.cloud.network.rules.FirewallRule;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.user.AccountVO;
import com.cloud.user.UserVO;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;

@RunWith(MockitoJUnitRunner.class)
public class LoadBalancerCertServiceImplTest {

    @Mock private LoadBalancerDao lbDao;
    @Mock private LoadBalancerCertMapDao lbCertMapDao;
    @Mock private EntityManager entityMgr;
    @Mock private AccountManager accountMgr;
    @Mock private NetworkDao networkDao;
    @Mock private NetworkOrchestrationService networkMgr;
    @Mock private LoadBalancingRulesManager lbRulesManager;
    @Mock private LoadBalancingRulesService lbRulesService;

    @InjectMocks
    private LoadBalancerCertServiceImpl service;

    private static final long LB_RULE_ID = 2L;
    private static final long CERT_ID = 3L;
    private static final long NETWORK_ID = 4L;
    private static final long ACCOUNT_ID = 10L;

    @Before
    public void setUp() {
        AccountVO account = new AccountVO("testaccount", 1L, "networkdomain", Account.Type.NORMAL, "uuid");
        account.setId(ACCOUNT_ID);
        UserVO user = new UserVO(1, "testuser", "password", "firstname", "lastName", "email", "timezone",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ---- getLbSslCert ----

    @Test
    public void getLbSslCertReturnsNullWhenNoMapping() {
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(null);

        LbSslCert result = service.getLbSslCert(LB_RULE_ID);

        assertNull(result);
    }

    @Test
    public void getLbSslCertReturnsNullWhenCertMissing() {
        LoadBalancerCertMapVO certMap = mock(LoadBalancerCertMapVO.class);
        when(certMap.getCertId()).thenReturn(CERT_ID);
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(certMap);
        when(entityMgr.findById(SslCertVO.class, CERT_ID)).thenReturn(null);

        LbSslCert result = service.getLbSslCert(LB_RULE_ID);

        assertNull(result);
    }

    @Test
    public void getLbSslCertBuildsLbSslCert() {
        LoadBalancerCertMapVO certMap = mock(LoadBalancerCertMapVO.class);
        when(certMap.getCertId()).thenReturn(CERT_ID);
        when(certMap.isRevoke()).thenReturn(true);
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(certMap);

        SslCertVO certVO = mock(SslCertVO.class);
        when(certVO.getCertificate()).thenReturn("pem");
        when(certVO.getKey()).thenReturn("key");
        when(certVO.getPassword()).thenReturn("pass");
        when(certVO.getChain()).thenReturn("chain");
        when(certVO.getFingerPrint()).thenReturn("fp");
        when(entityMgr.findById(SslCertVO.class, CERT_ID)).thenReturn(certVO);

        LbSslCert result = service.getLbSslCert(LB_RULE_ID);

        assertNotNull(result);
        assertEquals("pem", result.getCert());
        assertEquals("key", result.getKey());
        assertEquals("pass", result.getPassword());
        assertEquals("chain", result.getChain());
        assertEquals("fp", result.getFingerprint());
        assertTrue(result.isRevoked());
    }

    // ---- assignCertToLoadBalancer ----

    @Test
    public void assignCertRejectsUnknownLb() {
        when(lbDao.findById(LB_RULE_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.assignCertToLoadBalancer(LB_RULE_ID, CERT_ID, false));
    }

    @Test
    public void assignCertRejectsUnknownCert() {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lbDao.findById(LB_RULE_ID)).thenReturn(lb);
        when(entityMgr.findById(SslCertVO.class, CERT_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.assignCertToLoadBalancer(LB_RULE_ID, CERT_ID, false));
    }

    @Test
    public void assignCertRejectsCrossAccount() {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lbDao.findById(LB_RULE_ID)).thenReturn(lb);
        when(lb.getAccountId()).thenReturn(ACCOUNT_ID);

        SslCertVO certVO = mock(SslCertVO.class);
        when(certVO.getAccountId()).thenReturn(ACCOUNT_ID + 1);
        when(entityMgr.findById(SslCertVO.class, CERT_ID)).thenReturn(certVO);

        assertThrows(InvalidParameterValueException.class,
                () -> service.assignCertToLoadBalancer(LB_RULE_ID, CERT_ID, false));
    }

    @Test
    public void assignCertRejectsWhenSslTerminationUnsupported() {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lbDao.findById(LB_RULE_ID)).thenReturn(lb);
        when(lb.getAccountId()).thenReturn(ACCOUNT_ID);
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);

        SslCertVO certVO = mock(SslCertVO.class);
        when(certVO.getAccountId()).thenReturn(ACCOUNT_ID);
        when(entityMgr.findById(SslCertVO.class, CERT_ID)).thenReturn(certVO);

        when(lbRulesManager.getLBCapability(NETWORK_ID, Capability.SslTermination.getName())).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.assignCertToLoadBalancer(LB_RULE_ID, CERT_ID, false));
    }

    @Test
    public void assignCertRejectsWhenAnotherCertBoundWithoutForced() {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lbDao.findById(LB_RULE_ID)).thenReturn(lb);
        when(lb.getAccountId()).thenReturn(ACCOUNT_ID);
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);

        SslCertVO certVO = mock(SslCertVO.class);
        when(certVO.getAccountId()).thenReturn(ACCOUNT_ID);
        when(entityMgr.findById(SslCertVO.class, CERT_ID)).thenReturn(certVO);

        when(lbRulesManager.getLBCapability(NETWORK_ID, Capability.SslTermination.getName())).thenReturn("LB");

        LoadBalancerCertMapVO existing = mock(LoadBalancerCertMapVO.class);
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(existing);

        assertThrows(InvalidParameterValueException.class,
                () -> service.assignCertToLoadBalancer(LB_RULE_ID, CERT_ID, false));
    }

    @Test
    public void assignCertRejectsNonSslProtocol() {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lbDao.findById(LB_RULE_ID)).thenReturn(lb);
        when(lb.getAccountId()).thenReturn(ACCOUNT_ID);
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);
        when(lb.getLbProtocol()).thenReturn(NetUtils.TCP_PROTO);

        SslCertVO certVO = mock(SslCertVO.class);
        when(certVO.getAccountId()).thenReturn(ACCOUNT_ID);
        when(entityMgr.findById(SslCertVO.class, CERT_ID)).thenReturn(certVO);

        when(lbRulesManager.getLBCapability(NETWORK_ID, Capability.SslTermination.getName())).thenReturn("LB");
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.assignCertToLoadBalancer(LB_RULE_ID, CERT_ID, false));
    }

    @Test
    public void assignCertHappyPathPersistsAndPushesConfig() throws ResourceUnavailableException {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lbDao.findById(LB_RULE_ID)).thenReturn(lb);
        when(lb.getId()).thenReturn(LB_RULE_ID);
        when(lb.getAccountId()).thenReturn(ACCOUNT_ID);
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);
        when(lb.getLbProtocol()).thenReturn(NetUtils.SSL_PROTO);
        when(lb.getState()).thenReturn(FirewallRule.State.Active);

        SslCertVO certVO = mock(SslCertVO.class);
        when(certVO.getAccountId()).thenReturn(ACCOUNT_ID);
        when(entityMgr.findById(SslCertVO.class, CERT_ID)).thenReturn(certVO);

        when(lbRulesManager.getLBCapability(NETWORK_ID, Capability.SslTermination.getName())).thenReturn("LB");
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(null);
        when(lbRulesService.applyLoadBalancerConfig(LB_RULE_ID)).thenReturn(true);

        boolean result = service.assignCertToLoadBalancer(LB_RULE_ID, CERT_ID, false);

        assertTrue(result);
        verify(lb).setState(FirewallRule.State.Add);
        verify(lbDao).persist(lb);
        verify(lbCertMapDao).persist(any(LoadBalancerCertMapVO.class));
        verify(lbRulesService).applyLoadBalancerConfig(LB_RULE_ID);
    }

    @Test
    public void assignCertForcedReplacesExisting() throws ResourceUnavailableException {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lbDao.findById(LB_RULE_ID)).thenReturn(lb);
        when(lb.getId()).thenReturn(LB_RULE_ID);
        when(lb.getAccountId()).thenReturn(ACCOUNT_ID);
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);
        when(lb.getLbProtocol()).thenReturn(NetUtils.SSL_PROTO);
        when(lb.getState()).thenReturn(FirewallRule.State.Active);

        SslCertVO certVO = mock(SslCertVO.class);
        when(certVO.getAccountId()).thenReturn(ACCOUNT_ID);
        when(entityMgr.findById(SslCertVO.class, CERT_ID)).thenReturn(certVO);

        when(lbRulesManager.getLBCapability(NETWORK_ID, Capability.SslTermination.getName())).thenReturn("LB");

        // existing cert map present so forced=true should trigger removeCertFromLoadBalancer first
        LoadBalancerCertMapVO existing = mock(LoadBalancerCertMapVO.class);
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(existing);
        when(lbRulesService.applyLoadBalancerConfig(LB_RULE_ID)).thenReturn(true);

        boolean result = service.assignCertToLoadBalancer(LB_RULE_ID, CERT_ID, true);

        assertTrue(result);
        // applyLoadBalancerConfig should have been called twice (once for remove, once for assign)
        verify(lbRulesService, times(2)).applyLoadBalancerConfig(LB_RULE_ID);
    }

    @Test
    public void assignCertRollsBackWhenProviderUnavailable() throws ResourceUnavailableException {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lbDao.findById(LB_RULE_ID)).thenReturn(lb);
        when(lb.getId()).thenReturn(LB_RULE_ID);
        when(lb.getAccountId()).thenReturn(ACCOUNT_ID);
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);
        when(lb.getLbProtocol()).thenReturn(NetUtils.SSL_PROTO);
        when(lb.getState()).thenReturn(FirewallRule.State.Active);

        SslCertVO certVO = mock(SslCertVO.class);
        when(certVO.getAccountId()).thenReturn(ACCOUNT_ID);
        when(entityMgr.findById(SslCertVO.class, CERT_ID)).thenReturn(certVO);

        when(lbRulesManager.getLBCapability(NETWORK_ID, Capability.SslTermination.getName())).thenReturn("LB");
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(null).thenReturn(mock(LoadBalancerCertMapVO.class));
        doThrow(new ResourceUnavailableException("offline", DataCenterStub.class, 1L))
                .when(lbRulesService).applyLoadBalancerConfig(LB_RULE_ID);

        NetworkVO network = mock(NetworkVO.class);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(networkMgr.getProvidersForServiceInNetwork(network, Network.Service.Lb))
                .thenReturn(Collections.singletonList(Network.Provider.VirtualRouter));

        boolean result = service.assignCertToLoadBalancer(LB_RULE_ID, CERT_ID, false);

        assertFalse(result);
        verify(lbCertMapDao).remove(anyLong());
    }

    // ---- removeCertFromLoadBalancer ----

    @Test
    public void removeCertRejectsUnknownLb() {
        when(lbDao.findById(LB_RULE_ID)).thenReturn(null);
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(mock(LoadBalancerCertMapVO.class));

        assertThrows(InvalidParameterValueException.class, () -> service.removeCertFromLoadBalancer(LB_RULE_ID));
    }

    @Test
    public void removeCertRejectsWhenNoCertBound() {
        when(lbDao.findById(LB_RULE_ID)).thenReturn(mock(LoadBalancerVO.class));
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class, () -> service.removeCertFromLoadBalancer(LB_RULE_ID));
    }

    @Test
    public void removeCertHappyPath() throws ResourceUnavailableException {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lbDao.findById(LB_RULE_ID)).thenReturn(lb);
        when(lb.getState()).thenReturn(FirewallRule.State.Active);

        LoadBalancerCertMapVO certMap = mock(LoadBalancerCertMapVO.class);
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(certMap);
        when(lbRulesService.applyLoadBalancerConfig(LB_RULE_ID)).thenReturn(true);

        boolean result = service.removeCertFromLoadBalancer(LB_RULE_ID);

        assertTrue(result);
        verify(lb).setState(FirewallRule.State.Add);
        verify(certMap).setRevoke(true);
        verify(lbRulesService).applyLoadBalancerConfig(LB_RULE_ID);
    }

    @Test
    public void removeCertThrowsWhenApplyReturnsFalse() throws ResourceUnavailableException {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lbDao.findById(LB_RULE_ID)).thenReturn(lb);
        when(lb.getState()).thenReturn(FirewallRule.State.Active);
        when(lb.getUuid()).thenReturn("lb-uuid");

        LoadBalancerCertMapVO certMap = mock(LoadBalancerCertMapVO.class);
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(certMap);
        when(lbRulesService.applyLoadBalancerConfig(LB_RULE_ID)).thenReturn(false);

        assertThrows(CloudRuntimeException.class, () -> service.removeCertFromLoadBalancer(LB_RULE_ID));
    }

    @Test
    public void removeCertRollsBackOnResourceUnavailable() throws ResourceUnavailableException {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lbDao.findById(LB_RULE_ID)).thenReturn(lb);
        when(lb.getState()).thenReturn(FirewallRule.State.Active);
        when(lb.getUuid()).thenReturn("lb-uuid");
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);

        LoadBalancerCertMapVO certMap = mock(LoadBalancerCertMapVO.class);
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(certMap);
        doThrow(new ResourceUnavailableException("offline", DataCenterStub.class, 1L))
                .when(lbRulesService).applyLoadBalancerConfig(LB_RULE_ID);

        NetworkVO network = mock(NetworkVO.class);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(networkMgr.getProvidersForServiceInNetwork(network, Network.Service.Lb))
                .thenReturn(Collections.singletonList(Network.Provider.VirtualRouter));

        assertThrows(CloudRuntimeException.class, () -> service.removeCertFromLoadBalancer(LB_RULE_ID));
        verify(certMap).setRevoke(false);
    }

    // ---- removeCertMapIfExists ----

    @Test
    public void removeCertMapIfExistsNoopWhenNoCertMap() {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lb.getId()).thenReturn(LB_RULE_ID);
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(null);

        service.removeCertMapIfExists(lb);

        verify(lbCertMapDao, never()).remove(anyLong());
    }

    @Test
    public void removeCertMapIfExistsRemovesWhenPresent() {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lb.getId()).thenReturn(LB_RULE_ID);
        LoadBalancerCertMapVO certMap = mock(LoadBalancerCertMapVO.class);
        when(certMap.getId()).thenReturn(99L);
        when(lbCertMapDao.findByLbRuleId(LB_RULE_ID)).thenReturn(certMap);

        service.removeCertMapIfExists(lb);

        verify(lbCertMapDao).remove(99L);
    }

    // ---- assignSSLCertToLoadBalancerRule (legacy stub) ----

    @Test
    public void assignSslCertToLoadBalancerRuleAlwaysReturnsFalse() {
        boolean result = service.assignSSLCertToLoadBalancerRule(LB_RULE_ID, "name", "cert", "key");

        assertFalse(result);
        verify(lbDao).findById(LB_RULE_ID);
    }

    // ---- isRollBackAllowedForProvider ----

    @Test
    public void isRollBackAllowedForVirtualRouter() {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = mock(NetworkVO.class);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(networkMgr.getProvidersForServiceInNetwork(network, Network.Service.Lb))
                .thenReturn(Collections.singletonList(Network.Provider.VirtualRouter));

        assertTrue(service.isRollBackAllowedForProvider(lb));
    }

    @Test
    public void isRollBackAllowedForNetscaler() {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = mock(NetworkVO.class);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(networkMgr.getProvidersForServiceInNetwork(network, Network.Service.Lb))
                .thenReturn(Arrays.asList(Network.Provider.Netscaler));

        assertTrue(service.isRollBackAllowedForProvider(lb));
    }

    @Test
    public void isRollBackNotAllowedWhenNoProvider() {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = mock(NetworkVO.class);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(networkMgr.getProvidersForServiceInNetwork(network, Network.Service.Lb))
                .thenReturn(Collections.emptyList());

        assertFalse(service.isRollBackAllowedForProvider(lb));
    }

    @Test
    public void isRollBackNotAllowedForUnknownProvider() {
        LoadBalancerVO lb = mock(LoadBalancerVO.class);
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = mock(NetworkVO.class);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(networkMgr.getProvidersForServiceInNetwork(network, Network.Service.Lb))
                .thenReturn(Collections.singletonList(Network.Provider.Opendaylight));

        assertFalse(service.isRollBackAllowedForProvider(lb));
    }

    /** Marker stub for ResourceUnavailableException's resource-class parameter. */
    private static class DataCenterStub {
    }
}
