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
package com.cloud.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Unit tests for {@link VmHostNameUniquenessServiceImpl} — the
 * hostname/DHCP-option validation helpers extracted from
 * {@code UserVmManagerImpl} as slice 13 of the Phase 4
 * Spring-component decomposition.
 */
@RunWith(MockitoJUnitRunner.class)
public class VmHostNameUniquenessServiceImplTest {

    @Mock private DomainDao domainDao;
    @Mock private NetworkDao networkDao;
    @Mock private NetworkModel networkModel;
    @Mock private VMInstanceDao vmInstanceDao;

    private VmHostNameUniquenessServiceImpl service;

    @Before
    public void setUp() {
        // Spy so we can stub getVmDistinctHostNameScope() without
        // touching the static ConfigKey.
        service = Mockito.spy(new VmHostNameUniquenessServiceImpl());
        ReflectionTestUtils.setField(service, "domainDao", domainDao);
        ReflectionTestUtils.setField(service, "networkDao", networkDao);
        ReflectionTestUtils.setField(service, "networkModel", networkModel);
        ReflectionTestUtils.setField(service, "vmInstanceDao", vmInstanceDao);
    }

    private NetworkVO mockNetwork(long id, String uuid, String domain, Long accountId, Long vpcId, Long ntDomainId) {
        NetworkVO n = mock(NetworkVO.class);
        when(n.getId()).thenReturn(id);
        when(n.getUuid()).thenReturn(uuid);
        when(n.getNetworkDomain()).thenReturn(domain);
        when(n.getAccountId()).thenReturn(accountId == null ? 0L : accountId);
        when(n.getVpcId()).thenReturn(vpcId);
        when(n.getDomainId()).thenReturn(ntDomainId == null ? 0L : ntDomainId);
        when(n.getName()).thenReturn("net-" + id);
        return n;
    }

    // ---- verifyExtraDhcpOptionsNetwork ----

    @Test
    public void verifyExtraDhcpOptionsNetworkAcceptsNullMap() {
        // No exception when the map is null.
        service.verifyExtraDhcpOptionsNetwork(null, Collections.emptyList());
    }

    @Test
    public void verifyExtraDhcpOptionsNetworkAcceptsWhenAllUuidsMatch() {
        NetworkVO n1 = mockNetwork(1L, "uuid-1", "ex.com", 100L, null, 1L);
        Map<String, Map<Integer, String>> dhcpMap = new HashMap<>();
        dhcpMap.put("uuid-1", Collections.singletonMap(42, "value"));

        service.verifyExtraDhcpOptionsNetwork(dhcpMap, Collections.singletonList(n1));
    }

    @Test
    public void verifyExtraDhcpOptionsNetworkRejectsUnknownUuid() {
        NetworkVO n1 = mockNetwork(1L, "uuid-1", "ex.com", 100L, null, 1L);
        Map<String, Map<Integer, String>> dhcpMap = new HashMap<>();
        dhcpMap.put("uuid-missing", Collections.singletonMap(42, "value"));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.verifyExtraDhcpOptionsNetwork(dhcpMap, Collections.singletonList(n1)));
        assertTrue(ex.getMessage().contains("uuid-missing"));
    }

    @Test
    public void verifyExtraDhcpOptionsNetworkRejectsAnyMissingAmongMany() {
        NetworkVO n1 = mockNetwork(1L, "uuid-1", "ex.com", 100L, null, 1L);
        NetworkVO n2 = mockNetwork(2L, "uuid-2", "ex.com", 100L, null, 1L);
        Map<String, Map<Integer, String>> dhcpMap = new HashMap<>();
        dhcpMap.put("uuid-1", Collections.emptyMap());
        dhcpMap.put("uuid-not-there", Collections.emptyMap());

        assertThrows(InvalidParameterValueException.class,
                () -> service.verifyExtraDhcpOptionsNetwork(dhcpMap, Arrays.asList(n1, n2)));
    }

    @Test
    public void verifyExtraDhcpOptionsNetworkAcceptsEmptyMap() {
        // Empty map iterates zero times; should not throw.
        service.verifyExtraDhcpOptionsNetwork(new HashMap<>(), Collections.emptyList());
    }

    // ---- getNetworksForCheckUniqueHostName ----

    @Test
    public void getNetworksForCheckUniqueHostNameDefaultsToInputPlusVpcMembers() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "d", 100L, 50L, 1L);
        NetworkVO n2 = mockNetwork(2L, "u-2", "d", 100L, null, 1L);
        NetworkVO vpcMember = mockNetwork(3L, "u-3", "d", 100L, 50L, 1L);
        doReturn("network").when(service).getVmDistinctHostNameScope();
        when(networkDao.listByVpc(50L)).thenReturn(Collections.singletonList(vpcMember));

        List<NetworkVO> out = service.getNetworksForCheckUniqueHostName(Arrays.asList(n1, n2));

        assertEquals(3, out.size());
        assertTrue(out.contains(n1));
        assertTrue(out.contains(n2));
        assertTrue(out.contains(vpcMember));
    }

    @Test
    public void getNetworksForCheckUniqueHostNameGlobalScopeUsesNetworkDao() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "ex.com", 100L, null, 1L);
        NetworkVO siblingInGlobalDomain = mockNetwork(99L, "u-99", "ex.com", 200L, null, 2L);
        doReturn("global").when(service).getVmDistinctHostNameScope();
        when(networkDao.listByNetworkDomains(anySet()))
                .thenReturn(Collections.singletonList(siblingInGlobalDomain));

        List<NetworkVO> out = service.getNetworksForCheckUniqueHostName(Collections.singletonList(n1));

        assertEquals(1, out.size());
        assertEquals(99L, out.get(0).getId());
    }

    @Test
    public void getNetworksForCheckUniqueHostNameAccountScopeFiltersByAccount() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "ex.com", 100L, null, 1L);
        NetworkVO sibling = mockNetwork(2L, "u-2", "ex.com", 100L, null, 1L);
        doReturn("account").when(service).getVmDistinctHostNameScope();
        when(networkDao.listByNetworkDomainsAndAccountIds(anySet(), anySet()))
                .thenReturn(Arrays.asList(n1, sibling));

        List<NetworkVO> out = service.getNetworksForCheckUniqueHostName(Collections.singletonList(n1));

        assertEquals(2, out.size());
        verify(networkDao).listByNetworkDomainsAndAccountIds(anySet(), anySet());
    }

    @Test
    public void getNetworksForCheckUniqueHostNameDomainScopeDelegatesWithoutSubdomains() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "ex.com", 100L, null, 5L);
        doReturn("domain").when(service).getVmDistinctHostNameScope();
        when(networkDao.listByNetworkDomainsAndDomainIds(anySet(), anySet()))
                .thenReturn(Collections.singletonList(n1));

        List<NetworkVO> out = service.getNetworksForCheckUniqueHostName(Collections.singletonList(n1));

        // domain scope must NOT consult the domain DAO for child IDs.
        verify(domainDao, never()).getDomainChildrenIds(anyString());
        assertEquals(1, out.size());
    }

    @Test
    public void getNetworksForCheckUniqueHostNameSubdomainScopeExpandsToChildDomains() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "ex.com", 100L, null, 5L);
        DomainVO parent = mock(DomainVO.class);
        when(parent.getPath()).thenReturn("/root/dept/");
        when(domainDao.findById(5L)).thenReturn(parent);
        when(domainDao.getDomainChildrenIds("/root/dept/")).thenReturn(Arrays.asList(6L, 7L));
        doReturn("subdomain").when(service).getVmDistinctHostNameScope();
        when(networkDao.listByNetworkDomainsAndDomainIds(anySet(), anySet()))
                .thenReturn(Collections.singletonList(n1));

        service.getNetworksForCheckUniqueHostName(Collections.singletonList(n1));

        verify(domainDao, times(1)).getDomainChildrenIds("/root/dept/");
    }

    // ---- getNetworksWithSameNetworkDomainInDomains ----

    @Test
    public void getNetworksWithSameNetworkDomainInDomainsNoSubdomainsSkipsChildLookup() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "ex.com", 100L, null, 5L);
        when(networkDao.listByNetworkDomainsAndDomainIds(anySet(), anySet()))
                .thenReturn(Collections.singletonList(n1));

        service.getNetworksWithSameNetworkDomainInDomains(Collections.singletonList(n1), false);

        verify(domainDao, never()).findById(any(Long.class));
        verify(domainDao, never()).getDomainChildrenIds(anyString());
    }

    @Test
    public void getNetworksWithSameNetworkDomainInDomainsAddsChildIdsWhenRequested() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "ex.com", 100L, null, 5L);
        DomainVO parent = mock(DomainVO.class);
        when(parent.getPath()).thenReturn("/root/");
        when(domainDao.findById(5L)).thenReturn(parent);
        when(domainDao.getDomainChildrenIds("/root/")).thenReturn(Arrays.asList(6L));
        when(networkDao.listByNetworkDomainsAndDomainIds(anySet(), anySet()))
                .thenReturn(Collections.singletonList(n1));

        service.getNetworksWithSameNetworkDomainInDomains(Collections.singletonList(n1), true);

        verify(domainDao).findById(5L);
        verify(domainDao).getDomainChildrenIds("/root/");
    }

    // ---- getNetworkIdPerNetworkDomain ----

    @Test
    public void getNetworkIdPerNetworkDomainBucketsByDomain() {
        NetworkVO a1 = mockNetwork(1L, "u-1", "alpha.com", 100L, null, 1L);
        NetworkVO a2 = mockNetwork(2L, "u-2", "alpha.com", 100L, null, 1L);
        NetworkVO b1 = mockNetwork(3L, "u-3", "beta.com", 100L, null, 1L);
        doReturn(Arrays.asList(a1, a2, b1)).when(service).getNetworksForCheckUniqueHostName(any());

        Map<String, Set<Long>> out = service.getNetworkIdPerNetworkDomain(new ArrayList<>());

        assertEquals(2, out.size());
        assertEquals(Set.of(1L, 2L), out.get("alpha.com"));
        assertEquals(Set.of(3L), out.get("beta.com"));
    }

    @Test
    public void getNetworkIdPerNetworkDomainCallsExpansionHelper() {
        doReturn(Collections.emptyList()).when(service).getNetworksForCheckUniqueHostName(any());

        Map<String, Set<Long>> out = service.getNetworkIdPerNetworkDomain(Collections.emptyList());

        assertNotNull(out);
        assertTrue(out.isEmpty());
        verify(service, times(1)).getNetworksForCheckUniqueHostName(any());
    }

    // ---- checkIfHostNameUniqueInNtwkDomain ----

    @Test
    public void checkIfHostNameUniqueInNtwkDomainPassesWhenNoVmsShareName() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "ex.com", 100L, null, 1L);
        doReturn(Map.of("ex.com", Set.of(1L))).when(service).getNetworkIdPerNetworkDomain(any());
        when(vmInstanceDao.listDistinctHostNames(1L)).thenReturn(Arrays.asList("other-vm-1", "other-vm-2"));

        service.checkIfHostNameUniqueInNtwkDomain("my-vm", Collections.singletonList(n1));
    }

    @Test
    public void checkIfHostNameUniqueInNtwkDomainRejectsOnCollision() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "ex.com", 100L, null, 1L);
        doReturn(Map.of("ex.com", Set.of(1L))).when(service).getNetworkIdPerNetworkDomain(any());
        when(vmInstanceDao.listDistinctHostNames(1L)).thenReturn(Arrays.asList("my-vm"));
        when(networkModel.getNetwork(1L)).thenReturn(n1);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.checkIfHostNameUniqueInNtwkDomain("my-vm", Collections.singletonList(n1)));
        assertTrue(ex.getMessage().contains("my-vm"));
        assertTrue(ex.getMessage().contains("ex.com"));
    }

    @Test
    public void checkIfHostNameUniqueInNtwkDomainHandlesUnknownNetworkInErrorMsg() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "ex.com", 100L, null, 1L);
        doReturn(Map.of("ex.com", Set.of(1L))).when(service).getNetworkIdPerNetworkDomain(any());
        when(vmInstanceDao.listDistinctHostNames(1L)).thenReturn(Arrays.asList("my-vm"));
        // networkModel.getNetwork returns null — error message should fall back to "<unknown>".
        when(networkModel.getNetwork(1L)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.checkIfHostNameUniqueInNtwkDomain("my-vm", Collections.singletonList(n1)));
        assertTrue(ex.getMessage().contains("<unknown>"));
    }

    @Test
    public void checkIfHostNameUniqueInNtwkDomainChecksAllNetworksInEachDomain() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "ex.com", 100L, null, 1L);
        NetworkVO n2 = mockNetwork(2L, "u-2", "ex.com", 100L, null, 1L);
        // LinkedHashSet pins iteration order so we can assert that the FIRST
        // network checked has no collision and the SECOND triggers the throw.
        java.util.LinkedHashSet<Long> orderedIds = new java.util.LinkedHashSet<>();
        orderedIds.add(1L);
        orderedIds.add(2L);
        doReturn(Map.of("ex.com", (Set<Long>) orderedIds)).when(service).getNetworkIdPerNetworkDomain(any());
        when(vmInstanceDao.listDistinctHostNames(1L)).thenReturn(Collections.emptyList());
        when(vmInstanceDao.listDistinctHostNames(2L)).thenReturn(Arrays.asList("my-vm"));
        when(networkModel.getNetwork(2L)).thenReturn(n2);

        assertThrows(InvalidParameterValueException.class,
                () -> service.checkIfHostNameUniqueInNtwkDomain("my-vm", Arrays.asList(n1, n2)));
        verify(vmInstanceDao).listDistinctHostNames(1L);
        verify(vmInstanceDao).listDistinctHostNames(2L);
    }

    @Test
    public void checkIfHostNameUniqueInNtwkDomainSkipsLookupWhenNoDomains() {
        doReturn(new HashMap<String, Set<Long>>()).when(service).getNetworkIdPerNetworkDomain(any());

        // No exception, no VM hostname lookups needed.
        service.checkIfHostNameUniqueInNtwkDomain("anything", Collections.emptyList());
        verify(vmInstanceDao, never()).listDistinctHostNames(any(Long.class));
    }

    // ---- getVmDistinctHostNameScope ----

    @Test
    public void getVmDistinctHostNameScopeIsOverridableForTests() {
        doReturn("custom").when(service).getVmDistinctHostNameScope();
        assertEquals("custom", service.getVmDistinctHostNameScope());
    }

    @Test
    public void getNetworksForCheckUniqueHostNameWithGlobalSetPassesUniqueDomains() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "alpha.com", 100L, null, 1L);
        NetworkVO n2 = mockNetwork(2L, "u-2", "alpha.com", 100L, null, 1L);
        NetworkVO n3 = mockNetwork(3L, "u-3", "beta.com", 100L, null, 1L);
        doReturn("global").when(service).getVmDistinctHostNameScope();
        when(networkDao.listByNetworkDomains(eq(Set.of("alpha.com", "beta.com")))).thenReturn(Arrays.asList(n1, n2, n3));

        List<NetworkVO> out = service.getNetworksForCheckUniqueHostName(Arrays.asList(n1, n2, n3));

        assertEquals(3, out.size());
        verify(networkDao).listByNetworkDomains(eq(Set.of("alpha.com", "beta.com")));
    }

    @Test
    public void getNetworksForCheckUniqueHostNameDefaultScopeNoVpcReturnsInputCopy() {
        NetworkVO n1 = mockNetwork(1L, "u-1", "ex.com", 100L, null, 1L);
        doReturn("network").when(service).getVmDistinctHostNameScope();
        // No vpc -> no listByVpc call expected.

        List<NetworkVO> out = service.getNetworksForCheckUniqueHostName(Collections.singletonList(n1));

        assertEquals(1, out.size());
        assertEquals(n1, out.get(0));
        verify(networkDao, never()).listByVpc(any(Long.class));
    }

    @Test
    public void getNetworkIdPerNetworkDomainHandlesNullDomainKey() {
        // A network with a null networkDomain — confirms HashMap is fine
        // with a null key and the helper does not blow up.
        NetworkVO n1 = mock(NetworkVO.class);
        when(n1.getId()).thenReturn(7L);
        when(n1.getNetworkDomain()).thenReturn(null);
        doReturn(Collections.singletonList(n1)).when(service).getNetworksForCheckUniqueHostName(any());

        Map<String, Set<Long>> out = service.getNetworkIdPerNetworkDomain(Collections.emptyList());
        assertEquals(Set.of(7L), out.get(null));
    }
}
