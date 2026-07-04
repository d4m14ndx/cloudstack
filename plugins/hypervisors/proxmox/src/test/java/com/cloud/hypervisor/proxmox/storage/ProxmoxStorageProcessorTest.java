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
package com.cloud.hypervisor.proxmox.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.apache.cloudstack.storage.command.AttachAnswer;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.DettachAnswer;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import com.cloud.agent.api.to.DiskTO;
import com.cloud.hypervisor.proxmox.api.ProxmoxApiClient;
import com.cloud.hypervisor.proxmox.resource.ProxmoxResource;
import com.cloud.storage.Volume;
import com.cloud.utils.Pair;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Unit tests for the volume attach/detach lifecycle of {@link ProxmoxStorageProcessor}. The
 * PVE-critical property under test: detaching must never remove an unusedN config entry while
 * the volume it references still exists, because PVE physically destroys owned volumes when
 * their unused entry is deleted ("unlink of unused[n] always cause physical removal"). Owned
 * volumes are instead renamed to the template-holder vmid, and attach never renames at all
 * (CloudStack does not persist path changes from attach answers).
 */
public class ProxmoxStorageProcessorTest {

    private static final String NODE = "pve1";
    private static final String VM_NAME = "i-2-5-VM";
    private static final int VMID = 10005;
    private static final int HOLDER_VMID = 9999;

    private ProxmoxResource resource;
    private ProxmoxApiClient api;
    private ProxmoxStorageProcessor processor;

    @Before
    public void setUp() {
        resource = mock(ProxmoxResource.class);
        api = mock(ProxmoxApiClient.class);
        when(resource.getApiClient()).thenReturn(api);
        when(resource.getNodeName()).thenReturn(NODE);
        when(resource.getVmidBase()).thenReturn(10000);
        when(resource.findVmid(VM_NAME)).thenReturn(VMID);
        when(resource.executeOnNode(anyString(), anyInt())).thenReturn(new Pair<>(true, ""));
        when(resource.getStorageType("nfs-prim")).thenReturn("nfs");
        when(resource.getStorageType("ceph-prim")).thenReturn("rbd");
        processor = new ProxmoxStorageProcessor(resource);
    }

    private static JsonObject config(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static DiskTO dataDisk(String volid, String uuid, Long diskSeq) {
        VolumeObjectTO volume = new VolumeObjectTO();
        volume.setPath(volid);
        volume.setUuid(uuid);
        return new DiskTO(volume, diskSeq, volid, Volume.Type.DATADISK);
    }

    // ---- detach ----

    @Test
    public void testDetachParksOwnedFileVolumeInsteadOfDestroyingIt() {
        String volid = "nfs-prim:10005/vm-10005-disk-u1.qcow2";
        DiskTO disk = dataDisk(volid, "u1", 1L);

        when(api.findNodeOfVm(VMID)).thenReturn(NODE);
        when(api.getVmConfig(NODE, VMID)).thenReturn(
                config("{\"scsi1\":\"" + volid + ",iothread=1\"}"),
                config("{\"unused0\":\"" + volid + "\"}"));
        when(api.getVolumePath(NODE, volid)).thenReturn("/mnt/pve/nfs-prim/images/10005/vm-10005-disk-u1.qcow2");

        DettachAnswer answer = (DettachAnswer) processor.dettachVolume(new DettachCommand(disk, VM_NAME));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(api).unlinkDisk(NODE, VMID, "scsi1", false);

        // the volume is renamed to the template holder and the new volid is reported back
        String newVolid = "nfs-prim:" + HOLDER_VMID + "/vm-" + HOLDER_VMID + "-disk-u1.qcow2";
        assertEquals(newVolid, answer.getContextParam("volumePath"));
        assertEquals(newVolid, ((VolumeObjectTO) answer.getDisk().getData()).getPath());

        // the rename must happen BEFORE the (now dangling) unused0 entry is dropped; deleting
        // unused0 while the volume still exists would make PVE destroy the volume
        InOrder order = inOrder(resource, api);
        order.verify(resource).executeOnNode(contains("mv '/mnt/pve/nfs-prim/images/10005/vm-10005-disk-u1.qcow2' "
                + "'/mnt/pve/nfs-prim/images/" + HOLDER_VMID + "/vm-" + HOLDER_VMID + "-disk-u1.qcow2'"), anyInt());
        order.verify(api).setVmConfig(eq(NODE), eq(VMID), argThat((Map<String, Object> params) -> "unused0".equals(params.get("delete"))));
    }

    @Test
    public void testDetachParksOwnedRbdVolumeViaRename() {
        String volid = "ceph-prim:vm-10005-disk-u2";
        DiskTO disk = dataDisk(volid, "u2u2u2u2u2", 1L);

        when(api.findNodeOfVm(VMID)).thenReturn(NODE);
        when(api.getVmConfig(NODE, VMID)).thenReturn(
                config("{\"scsi1\":\"" + volid + ",iothread=1\"}"),
                config("{\"unused0\":\"" + volid + "\"}"));
        when(api.getVolumePath(NODE, volid)).thenReturn(
                "rbd:cloud/vm-10005-disk-u2:conf=/etc/pve/ceph.conf:id=admin:keyring=/etc/pve/priv/ceph/ceph-prim.keyring");

        DettachAnswer answer = (DettachAnswer) processor.dettachVolume(new DettachCommand(disk, VM_NAME));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(resource).executeOnNode(contains("rename 'cloud/vm-10005-disk-u2' 'cloud/vm-" + HOLDER_VMID + "-disk-u2u2u2u2'"), anyInt());
        assertEquals("ceph-prim:vm-" + HOLDER_VMID + "-disk-u2u2u2u2", answer.getContextParam("volumePath"));
    }

    @Test
    public void testDetachOfUnownedVolumeOnlyRemovesReference() {
        // holder-owned volume attached to the VM: unlinking removes the reference without
        // creating an unusedN entry (PVE only parks volumes the VM owns), nothing to rename
        String volid = "nfs-prim:9999/vm-9999-disk-u3.qcow2";
        DiskTO disk = dataDisk(volid, "u3", 2L);

        when(api.findNodeOfVm(VMID)).thenReturn(NODE);
        when(api.getVmConfig(NODE, VMID)).thenReturn(
                config("{\"scsi2\":\"" + volid + "\"}"),
                config("{}"));

        DettachAnswer answer = (DettachAnswer) processor.dettachVolume(new DettachCommand(disk, VM_NAME));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(api).unlinkDisk(NODE, VMID, "scsi2", false);
        assertNull(answer.getContextParam("volumePath"));
        assertEquals(volid, ((VolumeObjectTO) answer.getDisk().getData()).getPath());
        verify(resource, never()).executeOnNode(anyString(), anyInt());
        verify(api, never()).setVmConfig(anyString(), anyInt(), any());
    }

    @Test
    public void testDetachTreatsMissingVmAsAlreadyDetached() {
        DiskTO disk = dataDisk("nfs-prim:9999/vm-9999-disk-u4.qcow2", "u4", 1L);
        when(api.findNodeOfVm(VMID)).thenReturn(null);

        DettachAnswer answer = (DettachAnswer) processor.dettachVolume(new DettachCommand(disk, VM_NAME));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(api, never()).getVmConfig(anyString(), anyInt());
    }

    @Test
    public void testDetachFailsWhenUnlinkStaysPending() {
        // hotplug not possible: the disk stays attached (pending change); the detach must fail
        // instead of renaming the backing volume away underneath the running guest
        String volid = "nfs-prim:10005/vm-10005-disk-u5.qcow2";
        DiskTO disk = dataDisk(volid, "u5", 1L);

        when(api.findNodeOfVm(VMID)).thenReturn(NODE);
        when(api.getVmConfig(NODE, VMID)).thenReturn(config("{\"scsi1\":\"" + volid + "\"}"));

        DettachAnswer answer = (DettachAnswer) processor.dettachVolume(new DettachCommand(disk, VM_NAME));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("pending"));
        verify(resource, never()).executeOnNode(anyString(), anyInt());
    }

    // ---- attach ----

    @Test
    public void testAttachUsesExistingVolidWithoutRenaming() {
        String volid = "nfs-prim:9999/vm-9999-disk-u6.qcow2";
        DiskTO disk = dataDisk(volid, "u6", 1L);

        when(api.findNodeOfVm(VMID)).thenReturn(NODE);
        when(api.getVmConfig(NODE, VMID)).thenReturn(config("{\"scsi0\":\"nfs-prim:10005/vm-10005-disk-root.qcow2\"}"));

        AttachAnswer answer = (AttachAnswer) processor.attachVolume(new AttachCommand(disk, VM_NAME));

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(Long.valueOf(1L), answer.getDisk().getDiskSeq());
        // the volid stays exactly as CloudStack knows it: attach answers cannot change paths
        assertEquals(volid, ((VolumeObjectTO) answer.getDisk().getData()).getPath());
        verify(api).setVmConfig(eq(NODE), eq(VMID), argThat((Map<String, Object> params) -> volid.equals(params.get("scsi1"))));
        verify(resource, never()).executeOnNode(anyString(), anyInt());
    }

    @Test
    public void testAttachIsIdempotentWhenVolumeAlreadyAttached() {
        String volid = "nfs-prim:9999/vm-9999-disk-u7.qcow2";
        DiskTO disk = dataDisk(volid, "u7", 1L);

        when(api.findNodeOfVm(VMID)).thenReturn(NODE);
        when(api.getVmConfig(NODE, VMID)).thenReturn(config("{\"scsi3\":\"" + volid + ",iothread=1\"}"));

        AttachAnswer answer = (AttachAnswer) processor.attachVolume(new AttachCommand(disk, VM_NAME));

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(Long.valueOf(3L), answer.getDisk().getDiskSeq());
        verify(api, never()).setVmConfig(anyString(), anyInt(), any());
    }

    @Test
    public void testAttachRefusesVolumeStillAttachedToItsOwnerVm() {
        String volid = "nfs-prim:10007/vm-10007-disk-u8.qcow2";
        DiskTO disk = dataDisk(volid, "u8", 1L);

        when(api.findNodeOfVm(VMID)).thenReturn(NODE);
        when(api.findNodeOfVm(10007)).thenReturn("pve2");
        when(api.getVmConfig("pve2", 10007)).thenReturn(config("{\"scsi1\":\"" + volid + "\"}"));

        AttachAnswer answer = (AttachAnswer) processor.attachVolume(new AttachCommand(disk, VM_NAME));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("still attached"));
        verify(api, never()).setVmConfig(anyString(), anyInt(), any());
    }

    @Test
    public void testAttachRefusesVolumeParkedOnItsOwnerVm() {
        // an owned volume parked as unusedN would be destroyed together with its owner VM,
        // so it must not be wired into another VM while that reference exists
        String volid = "nfs-prim:10007/vm-10007-disk-u9.qcow2";
        DiskTO disk = dataDisk(volid, "u9", 1L);

        when(api.findNodeOfVm(VMID)).thenReturn(NODE);
        when(api.findNodeOfVm(10007)).thenReturn("pve2");
        when(api.getVmConfig("pve2", 10007)).thenReturn(config("{\"unused0\":\"" + volid + "\"}"));

        AttachAnswer answer = (AttachAnswer) processor.attachVolume(new AttachCommand(disk, VM_NAME));

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("parked"));
        verify(api, never()).setVmConfig(anyString(), anyInt(), any());
    }

    @Test
    public void testAttachAllowsVolumeOfExpungedOwnerVm() {
        // the owner VM named by the volid no longer exists: vmids are never reused, attach as-is
        String volid = "nfs-prim:10007/vm-10007-disk-u10.qcow2";
        DiskTO disk = dataDisk(volid, "u10", 1L);

        when(api.findNodeOfVm(VMID)).thenReturn(NODE);
        when(api.findNodeOfVm(10007)).thenReturn(null);
        when(api.getVmConfig(NODE, VMID)).thenReturn(config("{}"));

        AttachAnswer answer = (AttachAnswer) processor.attachVolume(new AttachCommand(disk, VM_NAME));

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(volid, ((VolumeObjectTO) answer.getDisk().getData()).getPath());
        verify(api).setVmConfig(eq(NODE), eq(VMID), argThat((Map<String, Object> params) -> volid.equals(params.get("scsi1"))));
    }

    @Test
    public void testAttachToVmNotYetCreatedSucceedsWithoutConfigChange() {
        // stopped instance that never started: the next StartCommand wires the disk in
        DiskTO disk = dataDisk("nfs-prim:9999/vm-9999-disk-u11.qcow2", "u11", 1L);
        when(api.findNodeOfVm(VMID)).thenReturn(null);

        AttachAnswer answer = (AttachAnswer) processor.attachVolume(new AttachCommand(disk, VM_NAME));

        assertTrue(answer.getDetails(), answer.getResult());
        verify(api, never()).getVmConfig(anyString(), anyInt());
        verify(api, never()).setVmConfig(anyString(), anyInt(), any());
    }
}
