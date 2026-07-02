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
package com.cloud.hypervisor.proxmox.discoverer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.junit.Before;
import org.junit.Test;

import com.cloud.hypervisor.Hypervisor.HypervisorType;

public class ProxmoxServerDiscovererTest {

    private ProxmoxServerDiscoverer discoverer;

    @Before
    public void setUp() {
        discoverer = new ProxmoxServerDiscoverer();
    }

    // ---- API credential normalization ----

    @Test
    public void testNormalizeUsernameAddsDefaultPamRealm() {
        assertEquals("root@pam", ProxmoxServerDiscoverer.normalizeUsername("root"));
    }

    @Test
    public void testNormalizeUsernameKeepsExplicitRealm() {
        assertEquals("admin@pve", ProxmoxServerDiscoverer.normalizeUsername("admin@pve"));
        assertEquals("root@pam", ProxmoxServerDiscoverer.normalizeUsername("root@pam"));
    }

    @Test
    public void testNormalizeTokenIdInsertsRealmBeforeSeparator() {
        assertEquals("root@pam!cloudstack", ProxmoxServerDiscoverer.normalizeTokenId("root!cloudstack"));
    }

    @Test
    public void testNormalizeTokenIdKeepsExplicitRealm() {
        assertEquals("monitor@pve!mytoken", ProxmoxServerDiscoverer.normalizeTokenId("monitor@pve!mytoken"));
    }

    // ---- hypervisor matching ----

    @Test
    public void testMatchHypervisorNullMatchesAnything() {
        assertTrue(discoverer.matchHypervisor(null));
    }

    @Test
    public void testMatchHypervisorIsCaseInsensitive() {
        assertTrue(discoverer.matchHypervisor("Proxmox"));
        assertTrue(discoverer.matchHypervisor("proxmox"));
        assertTrue(discoverer.matchHypervisor("PROXMOX"));
    }

    @Test
    public void testMatchHypervisorRejectsOtherHypervisors() {
        assertFalse(discoverer.matchHypervisor("KVM"));
        assertFalse(discoverer.matchHypervisor("VMware"));
        assertFalse(discoverer.matchHypervisor(""));
    }

    @Test
    public void testGetHypervisorType() {
        assertEquals(HypervisorType.Proxmox, discoverer.getHypervisorType());
    }

    // ---- find() guard clauses (no pod/cluster: not for this discoverer) ----

    @Test
    public void testFindReturnsNullWithoutPod() throws Exception {
        URI uri = URI.create("https://pve.example.com:8006");
        assertNull(discoverer.find(1L, null, 1L, uri, "root", "password", null));
    }

    @Test
    public void testFindReturnsNullWithoutCluster() throws Exception {
        URI uri = URI.create("https://pve.example.com:8006");
        assertNull(discoverer.find(1L, 1L, null, uri, "root", "password", null));
    }
}
