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
package com.cloud.hypervisor.proxmox.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.hypervisor.proxmox.api.ProxmoxApiClient;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Tests the guest-agent first-boot provisioning path used to close the appliance bootstrap gap
 * (a RouterOS CHR boots without its management-reachable IP): the resource must write the
 * provisioning script to the guest's file store and then execute it as a RouterOS config import.
 */
public class ProxmoxResourceProvisionTest {

    private ProxmoxResource resource;
    private ProxmoxApiClient api;

    @Before
    public void setUp() {
        resource = spy(new ProxmoxResource());
        api = mock(ProxmoxApiClient.class);
        doReturn(api).when(resource).getApiClient();
        doReturn(0L).when(resource).guestProvisionRetryMs(); // don't actually wait between attempts
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRunGuestProvisionScriptWritesFileAndExecutesIt() {
        VirtualMachineTO spec = mock(VirtualMachineTO.class);
        when(spec.getName()).thenReturn("r-chr-1");
        doReturn(new Pair<>(Boolean.TRUE, "")).when(resource).executeOnNode(anyString());

        resource.runGuestProvisionScript(spec, "pve-a1", 10555,
                "/ip address add address=192.168.3.20/24 interface=ether2 comment=cs-bootstrap\n");

        // The script is written to the guest's file store under a fixed name (retried, so >=1 call).
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(api, atLeastOnce()).post(eq("/nodes/pve-a1/qemu/10555/agent/file-write"), params.capture());
        assertEquals("cs-provision.rsc", params.getValue().get("file"));
        assertTrue(((String) params.getValue().get("content")).contains("interface=ether2"));

        // Then executed as a config import (guest-agent exec of that file) on the node.
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(resource, atLeastOnce()).executeOnNode(cmd.capture());
        assertTrue(cmd.getValue().contains("/nodes/pve-a1/qemu/10555/agent/exec --command cs-provision.rsc"));
    }

    @Test
    public void testRunGuestProvisionScriptExecutesEvenIfFileWriteReportsError() {
        VirtualMachineTO spec = mock(VirtualMachineTO.class);
        when(spec.getName()).thenReturn("r-chr-2");
        // The guest-file-close phase can report an error even when the bytes landed; that must not
        // stop the exec, which is what actually applies the configuration.
        doThrow(new CloudRuntimeException("guest-file-close timed out"))
                .when(api).post(eq("/nodes/pve-a1/qemu/10556/agent/file-write"), anyMap());
        doReturn(new Pair<>(Boolean.TRUE, "")).when(resource).executeOnNode(anyString());

        resource.runGuestProvisionScript(spec, "pve-a1", 10556, "/ip service set www-ssl disabled=no\n");

        verify(resource, atLeastOnce()).executeOnNode(contains("agent/exec --command cs-provision.rsc"));
    }
}
