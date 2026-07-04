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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

import org.apache.cloudstack.vm.UnmanagedInstanceTO;

import com.cloud.agent.api.GetUnmanagedInstancesAnswer;
import com.cloud.agent.api.GetUnmanagedInstancesCommand;
import com.cloud.agent.api.PrepareUnmanageVMInstanceCommand;
import com.cloud.agent.api.PrepareUnmanageVMInstanceAnswer;
import com.cloud.hypervisor.proxmox.api.ProxmoxApiClient;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for the Import-Export Instances support: unmanaged instance discovery, and the vmid
 * resolution that must stay safe once imported VMs with arbitrary names and vmids exist next
 * to convention-named ones. The critical property: an imported name that happens to contain
 * digits (web-01) must never be misread as the vmid of an unrelated CloudStack VM.
 */
public class ProxmoxResourceImportTest {

    private ProxmoxResource resource;
    private ProxmoxApiClient api;

    @Before
    public void setUp() {
        resource = spy(new ProxmoxResource());
        api = mock(ProxmoxApiClient.class);
        doReturn(api).when(resource).getApiClient();
    }

    private static JsonArray array(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private void withClusterInventory() {
        when(api.getClusterResources("vm")).thenReturn(array("["
                + "{\"vmid\": 10005, \"name\": \"i-2-5-VM\", \"type\": \"qemu\", \"node\": \"pve-a1\"},"
                + "{\"vmid\": 100, \"name\": \"web-01\", \"type\": \"qemu\", \"node\": \"pve-a2\"},"
                + "{\"vmid\": 10007, \"type\": \"qemu\", \"node\": \"pve-a1\"},"
                + "{\"vmid\": 300, \"name\": \"dup\", \"type\": \"qemu\", \"node\": \"pve-a1\"},"
                + "{\"vmid\": 301, \"name\": \"dup\", \"type\": \"qemu\", \"node\": \"pve-a3\"},"
                + "{\"vmid\": 400, \"name\": \"i-2-5-VM\", \"type\": \"lxc\", \"node\": \"pve-a1\"}"
                + "]"));
    }

    @Test
    public void findVmidResolvesByNameFirst() {
        withClusterInventory();
        assertEquals(Integer.valueOf(10005), resource.findVmid("i-2-5-VM"));
    }

    @Test
    public void findVmidNeverMisreadsImportedNameAsConventionVmid() {
        withClusterInventory();
        // "web-01" parses to 10001 by convention; the VM really is vmid 100 and 10001 must not match
        assertEquals(Integer.valueOf(100), resource.findVmid("web-01"));
    }

    @Test
    public void findVmidFallsBackToConventionForNamelessVm() {
        withClusterInventory();
        assertEquals(Integer.valueOf(10007), resource.findVmid("i-2-7-VM"));
    }

    @Test(expected = CloudRuntimeException.class)
    public void findVmidRefusesAmbiguousName() {
        withClusterInventory();
        resource.findVmid("dup");
    }

    @Test
    public void findVmidReturnsNullWhenNowhere() {
        withClusterInventory();
        assertNull(resource.findVmid("i-9-999-VM"));
    }

    @Test
    public void getUnmanagedInstancesDescribesForeignVmAndSkipsTheRest() {
        when(api.listNodeVms(any())).thenReturn(array("["
                + "{\"vmid\": 150, \"name\": \"legacy-vm\", \"status\": \"running\"},"
                + "{\"vmid\": 151, \"name\": \"a-template\", \"status\": \"stopped\", \"template\": 1},"
                + "{\"vmid\": 152, \"status\": \"stopped\"},"
                + "{\"vmid\": 10005, \"name\": \"i-2-5-VM\", \"status\": \"running\"}"
                + "]"));
        when(api.getVmConfig(any(), eq(150))).thenReturn(object("{"
                + "\"cores\": 2, \"sockets\": 2, \"memory\": \"4096\", \"ostype\": \"l26\","
                + "\"scsi0\": \"cs-rbd:vm-150-disk-0,iothread=1,size=32G\","
                + "\"virtio1\": \"cs-nfs-primary:150/vm-150-disk-1.qcow2,size=1536M\","
                + "\"ide2\": \"cs-cephfs:iso/x.iso,media=cdrom\","
                + "\"net0\": \"virtio=BC:24:11:00:00:01,bridge=vmbr0,tag=105,firewall=1\""
                + "}"));
        when(api.getClusterName()).thenReturn("cs-lab-a");

        GetUnmanagedInstancesCommand cmd = new GetUnmanagedInstancesCommand();
        cmd.setManagedInstancesNames(Arrays.asList("i-2-5-VM"));
        GetUnmanagedInstancesAnswer answer = (GetUnmanagedInstancesAnswer) resource.executeRequest(cmd);

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(1, answer.getUnmanagedInstances().size());
        UnmanagedInstanceTO vm = answer.getUnmanagedInstances().get("legacy-vm");
        assertEquals("legacy-vm", vm.getName());
        assertEquals("150", vm.getPath());
        assertEquals(UnmanagedInstanceTO.PowerState.PowerOn, vm.getPowerState());
        assertEquals(Integer.valueOf(4), vm.getCpuCores());
        assertEquals(Integer.valueOf(2), vm.getCpuCoresPerSocket());
        assertEquals(Integer.valueOf(4096), vm.getMemory());
        assertEquals("Proxmox", vm.getHypervisorType());
        assertEquals("cs-lab-a", vm.getClusterName());
        assertEquals("BIOS", vm.getBootType());

        assertEquals(2, vm.getDisks().size());
        UnmanagedInstanceTO.Disk root = vm.getDisks().get(0);
        assertEquals("scsi0", root.getDiskId());
        assertEquals("scsi", root.getController());
        assertEquals(Long.valueOf(32L * 1024 * 1024 * 1024), root.getCapacity());
        assertEquals("cs-rbd:vm-150-disk-0", root.getImagePath());
        assertEquals("cs-rbd:vm-150-disk-0", root.getFileBaseName());
        assertEquals("cs-rbd", root.getDatastoreName());
        assertEquals("localhost", root.getDatastoreHost());
        assertEquals("/cs-rbd", root.getDatastorePath());
        UnmanagedInstanceTO.Disk data = vm.getDisks().get(1);
        assertEquals("virtio1", data.getDiskId());
        assertEquals(Long.valueOf(1536L * 1024 * 1024), data.getCapacity());

        assertEquals(1, vm.getNics().size());
        UnmanagedInstanceTO.Nic nic = vm.getNics().get(0);
        assertEquals("net0", nic.getNicId());
        assertEquals("virtio", nic.getAdapterType());
        assertEquals("BC:24:11:00:00:01", nic.getMacAddress());
        assertEquals("vmbr0", nic.getNetwork());
        assertEquals(Integer.valueOf(105), nic.getVlan());
    }

    @Test
    public void getUnmanagedInstancesFiltersByRequestedName() {
        when(api.listNodeVms(any())).thenReturn(array("["
                + "{\"vmid\": 150, \"name\": \"legacy-vm\", \"status\": \"running\"},"
                + "{\"vmid\": 160, \"name\": \"other-vm\", \"status\": \"stopped\"}"
                + "]"));
        when(api.getVmConfig(any(), eq(160))).thenReturn(object("{\"scsi0\": \"cs-rbd:vm-160-disk-0,size=8G\"}"));
        when(api.getClusterName()).thenReturn("cs-lab-a");

        GetUnmanagedInstancesCommand cmd = new GetUnmanagedInstancesCommand();
        cmd.setInstanceName("other-vm");
        GetUnmanagedInstancesAnswer answer = (GetUnmanagedInstancesAnswer) resource.executeRequest(cmd);

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(1, answer.getUnmanagedInstances().size());
        assertEquals("160", answer.getUnmanagedInstances().get("other-vm").getPath());
    }

    @Test
    public void prepareUnmanageSucceedsWhenVmExists() {
        withClusterInventory();
        PrepareUnmanageVMInstanceCommand cmd = new PrepareUnmanageVMInstanceCommand();
        cmd.setInstanceName("web-01");
        PrepareUnmanageVMInstanceAnswer answer = (PrepareUnmanageVMInstanceAnswer) resource.executeRequest(cmd);
        assertTrue(answer.getDetails(), answer.getResult());
    }

    @Test
    public void prepareUnmanageFailsWhenVmIsGone() {
        withClusterInventory();
        PrepareUnmanageVMInstanceCommand cmd = new PrepareUnmanageVMInstanceCommand();
        cmd.setInstanceName("no-such-vm");
        PrepareUnmanageVMInstanceAnswer answer = (PrepareUnmanageVMInstanceAnswer) resource.executeRequest(cmd);
        assertFalse(answer.getResult());
    }
}
