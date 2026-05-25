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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.dao.LoadBalancerCertMapDao;
import com.cloud.network.dao.LoadBalancerCertMapVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.SslCertVO;
import com.cloud.network.lb.LoadBalancingRule.LbSslCert;
import com.cloud.network.rules.FirewallRule;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;

/**
 * Load balancer SSL certificate binding management — extracted from
 * {@link LoadBalancingRulesManagerImpl}.
 *
 * @see LoadBalancerCertService
 */
@Component
public class LoadBalancerCertServiceImpl implements LoadBalancerCertService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private LoadBalancerDao lbDao;
    @Inject
    private LoadBalancerCertMapDao lbCertMapDao;
    @Inject
    private EntityManager entityMgr;
    @Inject
    private AccountManager accountMgr;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private NetworkOrchestrationService networkMgr;
    @Inject
    private LoadBalancingRulesManager lbRulesManager;
    @Inject
    private LoadBalancingRulesService lbRulesService;

    @Override
    public LbSslCert getLbSslCert(long lbRuleId) {
        LoadBalancerCertMapVO lbCertMap = lbCertMapDao.findByLbRuleId(lbRuleId);

        if (lbCertMap == null) {
            return null;
        }

        SslCertVO certVO = entityMgr.findById(SslCertVO.class, lbCertMap.getCertId());
        if (certVO == null) {
            logger.warn("Cert rule with cert ID " + lbCertMap.getCertId() + " but Cert is not found");
            return null;
        }

        return new LbSslCert(certVO.getCertificate(), certVO.getKey(), certVO.getPassword(), certVO.getChain(), certVO.getFingerPrint(), lbCertMap.isRevoke());
    }

    @Override
    public boolean assignCertToLoadBalancer(long lbRuleId, Long certId, boolean forced) {
        CallContext caller = CallContext.current();

        LoadBalancerVO loadBalancer = lbDao.findById(lbRuleId);
        if (loadBalancer == null) {
            throw new InvalidParameterValueException("Invalid load balancer id: " + lbRuleId);
        }

        SslCertVO certVO = entityMgr.findById(SslCertVO.class, certId);
        if (certVO == null) {
            throw new InvalidParameterValueException("Invalid certificate id: " + certId);
        }

        accountMgr.checkAccess(caller.getCallingAccount(), null, true, loadBalancer);

        // check if LB and Cert belong to the same account
        if (loadBalancer.getAccountId() != certVO.getAccountId()) {
            throw new InvalidParameterValueException("Access denied for Account " + certVO.getAccountId());
        }

        String capability = lbRulesManager.getLBCapability(loadBalancer.getNetworkId(), Capability.SslTermination.getName());
        if (capability == null) {
            throw new InvalidParameterValueException("Ssl termination not supported by the loadbalancer");
        }

        validateCertMapRule(lbRuleId, forced);

        //check for correct port
        if (loadBalancer.getLbProtocol() == null || !(loadBalancer.getLbProtocol().equals(NetUtils.SSL_PROTO))) {
            throw new InvalidParameterValueException("Bad LB protocol: Expected ssl got " + loadBalancer.getLbProtocol());
        }

        boolean success = false;
        FirewallRule.State backupState = loadBalancer.getState();

        try {

            loadBalancer.setState(FirewallRule.State.Add);
            lbDao.persist(loadBalancer);
            LoadBalancerCertMapVO certMap = new LoadBalancerCertMapVO(lbRuleId, certId, false);
            lbCertMapDao.persist(certMap);
            lbRulesService.applyLoadBalancerConfig(loadBalancer.getId());
            success = true;
        } catch (ResourceUnavailableException e) {
            if (isRollBackAllowedForProvider(loadBalancer)) {

                loadBalancer.setState(backupState);
                lbDao.persist(loadBalancer);
                LoadBalancerCertMapVO certMap = lbCertMapDao.findByLbRuleId(lbRuleId);
                lbCertMapDao.remove(certMap.getId());
                logger.debug("LB Rollback rule: {} while adding cert", loadBalancer);
            }
            logger.warn("Unable to apply the load balancer config because resource is unavailable.", e);
        }
        return success;
    }

    @Override
    public boolean removeCertFromLoadBalancer(long lbRuleId) {
        CallContext caller = CallContext.current();

        LoadBalancerVO loadBalancer = lbDao.findById(lbRuleId);
        LoadBalancerCertMapVO lbCertMap = lbCertMapDao.findByLbRuleId(lbRuleId);

        if (loadBalancer == null) {
            throw new InvalidParameterValueException("Invalid load balancer value: " + lbRuleId);
        }

        if (lbCertMap == null) {
            throw new InvalidParameterValueException("No certificate is bound to lb with id: " + lbRuleId);
        }

        accountMgr.checkAccess(caller.getCallingAccount(), null, true, loadBalancer);

        boolean success = false;
        FirewallRule.State backupState = loadBalancer.getState();
        try {

            loadBalancer.setState(FirewallRule.State.Add);
            lbDao.persist(loadBalancer);
            lbCertMap.setRevoke(true);
            lbCertMapDao.persist(lbCertMap);

            if (!lbRulesService.applyLoadBalancerConfig(lbRuleId)) {
                logger.warn("Failed to remove cert from load balancer rule {}", loadBalancer);
                CloudRuntimeException ex = new CloudRuntimeException(String.format("Failed to remove certificate load balancer rule %s", loadBalancer));
                ex.addProxyObject(loadBalancer.getUuid(), "loadBalancerId");
                throw ex;
            }
            success = true;
        } catch (ResourceUnavailableException e) {
            if (isRollBackAllowedForProvider(loadBalancer)) {
                lbCertMap.setRevoke(false);
                lbCertMapDao.persist(lbCertMap);
                loadBalancer.setState(backupState);
                lbDao.persist(loadBalancer);
                logger.debug(String.format("Rolled back certificate removal lb %s", loadBalancer));
            }
            logger.warn("Unable to apply the load balancer config because resource is unavailable.", e);
            if (!success) {
                CloudRuntimeException ex = new CloudRuntimeException(String.format("Failed to remove certificate from load balancer rule %s", loadBalancer));
                ex.addProxyObject(loadBalancer.getUuid(), "loadBalancerId");
                throw ex;
            }
        }
        return success;
    }

    @Override
    public boolean assignSSLCertToLoadBalancerRule(Long lbId, String certName, String publicCert, String privateKey) {
        logger.error("Calling the manager for LB");
        lbDao.findById(lbId);

        return false;  //TODO
    }

    @Override
    public void removeCertMapIfExists(LoadBalancerVO lb) {
        LoadBalancerCertMapVO loadBalancerCertMapVO = lbCertMapDao.findByLbRuleId(lb.getId());
        if (loadBalancerCertMapVO != null) {
            logger.debug("Removing SSL cert for load balancer %s as the new protocol is not ssl but %s", lb, lb.getLbProtocol());
            lbCertMapDao.remove(loadBalancerCertMapVO.getId());
        }
    }

    /**
     * If a certificate is already bound to {@code lbRuleId}, either
     * throw (when {@code forced} is false) or remove the existing
     * binding to make way for the new one.
     */
    protected void validateCertMapRule(long lbRuleId, boolean forced) {
        //check if the lb is already bound
        LoadBalancerCertMapVO certMapRule = lbCertMapDao.findByLbRuleId(lbRuleId);
        if (certMapRule != null) {
            if (!forced) {
                throw new InvalidParameterValueException("Another certificate is already bound to the LB");
            }
            logger.debug("Another certificate is already bound to the LB, removing it");
            removeCertFromLoadBalancer(lbRuleId);
        }
    }

    /**
     * True for providers that support DB rollback on apply failure
     * (Netscaler, F5, Netris, VirtualRouter, VPCVirtualRouter).
     */
    protected boolean isRollBackAllowedForProvider(LoadBalancerVO loadBalancer) {
        Network network = networkDao.findById(loadBalancer.getNetworkId());
        List<Provider> provider = networkMgr.getProvidersForServiceInNetwork(network, Service.Lb);
        if (provider == null || provider.size() == 0) {
            return false;
        }
        if (provider.get(0) == Provider.Netscaler || provider.get(0) == Provider.F5BigIp ||
            provider.get(0) == Provider.Netris ||
            provider.get(0) == Provider.VirtualRouter || provider.get(0) == Provider.VPCVirtualRouter) {
            return true;
        }
        return false;
    }
}
