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

import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.lb.LoadBalancingRule.LbSslCert;

/**
 * Load balancer SSL certificate binding management — extracted from
 * {@link LoadBalancingRulesManagerImpl} as part of the Phase 4
 * Spring-component decomposition.
 *
 * <p>This component owns the SSL certificate lifecycle for individual
 * load balancer rules: looking up the bound certificate, assigning a
 * certificate to an LB rule (with capability + protocol validation,
 * optional forced replacement, and rollback on provider failure),
 * removing the binding, and removing a stale cert-map row when the LB
 * protocol is being changed away from SSL.
 *
 * <p>{@code LoadBalancingRulesManagerImpl} keeps its public-API methods
 * ({@code getLbSslCert}, {@code assignCertToLoadBalancer},
 * {@code removeCertFromLoadBalancer},
 * {@code assignSSLCertToLoadBalancerRule}) as thin delegating wrappers
 * so the {@link LoadBalancingRulesService} and
 * {@link LoadBalancingRulesManager} contracts and downstream callers
 * (Tungsten, Netscaler, ElasticLoadBalancer, VirtualNetworkAppliance,
 * existing test spies) keep working.
 */
public interface LoadBalancerCertService {

    /**
     * Look up the SSL certificate currently bound to the given LB rule.
     * Returns {@code null} when no cert is bound or when the referenced
     * certificate row has been removed.
     */
    LbSslCert getLbSslCert(long lbRuleId);

    /**
     * Bind a certificate to a load balancer rule. Verifies caller
     * access, that LB + cert belong to the same account, that the
     * network supports {@code SslTermination}, and that the LB protocol
     * is {@code ssl}. When {@code forced} is true any previously bound
     * certificate is removed first. Pushes the new config to the LB
     * provider and rolls back the DB state if the provider rejects it
     * (and rollback is supported by that provider).
     *
     * @return {@code true} when the provider accepted the new config.
     */
    boolean assignCertToLoadBalancer(long lbRuleId, Long certId, boolean forced);

    /**
     * Remove the certificate binding from a load balancer rule. Flips
     * the cert-map row to {@code revoke=true}, pushes the change to the
     * provider, and rolls back if the provider rejects it (and rollback
     * is supported).
     *
     * @return {@code true} when the provider accepted the new config.
     */
    boolean removeCertFromLoadBalancer(long lbRuleId);

    /**
     * Legacy entry point that loads the LB rule and currently returns
     * {@code false}. Retained for interface compatibility.
     */
    boolean assignSSLCertToLoadBalancerRule(Long lbId, String certName, String publicCert, String privateKey);

    /**
     * Remove the cert-map row for an LB rule (without touching provider
     * state) — used when an LB's protocol is changed away from {@code ssl}
     * so the stale binding does not linger.
     */
    void removeCertMapIfExists(LoadBalancerVO lb);
}
