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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;

import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.storage.Volume;
import com.cloud.template.VirtualMachineTemplate.BootloaderType;
import com.cloud.vm.VirtualMachine;

public class ProxmoxVmConfigBuilderTest {

    private static final long GIB = 1024L * 1024L * 1024L;
    private static final String DEFAULT_BRIDGE = "vmbr0";

    private ProxmoxResource resource;

    @Before
    public void setUp() {
        resource = mock(ProxmoxResource.class);
        // mimic the real contract: traffic label (nic name) wins, else the default bridge
        when(resource.resolveBridge(any(NicTO.class))).thenAnswer(invocation -> {
            NicTO nic = invocation.getArgument(0);
            return nic.getName() != null ? nic.getName() : DEFAULT_BRIDGE;
        });
    }

    private VirtualMachineTO userVm(String os) {
        return new VirtualMachineTO(5L, "i-2-5-VM", VirtualMachine.Type.User, 2, 1000,
                GIB, 2L * GIB, BootloaderType.HVM, os, true, false, "vncsecret");
    }

    private DiskTO disk(Volume.Type type, String dataPath, Long diskSeq) {
        VolumeObjectTO volume = new VolumeObjectTO();
        volume.setPath(dataPath);
        return new DiskTO(volume, diskSeq, dataPath, type);
    }

    private DiskTO isoDisk(String dataPath) {
        TemplateObjectTO iso = new TemplateObjectTO();
        iso.setPath(dataPath);
        return new DiskTO(iso, 3L, dataPath, Volume.Type.ISO);
    }

    private NicTO nic(int deviceId, String mac, String broadcastUri, Integer mtu, String name) {
        NicTO nic = new NicTO();
        nic.setDeviceId(deviceId);
        nic.setMac(mac);
        if (broadcastUri != null) {
            nic.setBroadcastUri(URI.create(broadcastUri));
        }
        nic.setMtu(mtu);
        nic.setName(name);
        return nic;
    }

    @Test
    public void testBasicUserVmConfig() {
        VirtualMachineTO spec = userVm("Ubuntu 22.04 (64-bit)");

        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);

        assertEquals("i-2-5-VM", config.get("name"));
        assertEquals(Integer.valueOf(2), config.get("cores"));
        assertEquals(Integer.valueOf(1), config.get("sockets"));
        assertEquals("host", config.get("cpu"));
        assertEquals(Long.valueOf(2048L), config.get("memory"));
        assertEquals(Long.valueOf(1024L), config.get("balloon"));
        assertEquals("l26", config.get("ostype"));
        assertEquals("virtio-scsi-pci", config.get("scsihw"));
        assertEquals(Integer.valueOf(1), config.get("agent"));
        assertEquals(Integer.valueOf(0), config.get("onboot"));
        assertEquals(Integer.valueOf(0), config.get("protection"));
        assertEquals("socket", config.get("serial0"));
        assertEquals("std", config.get("vga"));
        assertEquals("order=scsi0;ide2;net0", config.get("boot"));
        assertEquals("-vnc 0.0.0.0:10005,password=on", config.get("args"));
        // no ISO attached: empty cdrom drive
        assertEquals("none,media=cdrom", config.get("ide2"));
    }

    @Test
    public void testWindowsGuestUsesWin11OsType() {
        VirtualMachineTO spec = userVm("Windows Server 2022 (64-bit)");
        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);
        assertEquals("win11", config.get("ostype"));
    }

    @Test
    public void testWindowsOsTypeDetectionIsCaseInsensitive() {
        VirtualMachineTO spec = userVm("microsoft windows 10");
        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);
        assertEquals("win11", config.get("ostype"));
    }

    @Test
    public void testNullOsDefaultsToLinuxOsType() {
        VirtualMachineTO spec = userVm(null);
        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);
        assertEquals("l26", config.get("ostype"));
    }

    @Test
    public void testRootAndDataDisksMapToScsiSlots() {
        VirtualMachineTO spec = userVm("CentOS 8");
        spec.setDisks(new DiskTO[] {
                disk(Volume.Type.ROOT, "local-lvm:vm-10005-disk-0", 0L),
                disk(Volume.Type.DATADISK, "local-lvm:vm-10005-disk-1", 1L),
        });

        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);

        assertEquals("local-lvm:vm-10005-disk-0,discard=on", config.get("scsi0"));
        assertEquals("local-lvm:vm-10005-disk-1,discard=on", config.get("scsi1"));
    }

    @Test
    public void testDiskVolidComesFromDataToPath() {
        // the DataTO path (volid) must win over the DiskTO path
        VolumeObjectTO volume = new VolumeObjectTO();
        volume.setPath("local-lvm:vm-10005-disk-0");
        DiskTO root = new DiskTO(volume, 0L, "stale-disk-to-path", Volume.Type.ROOT);
        VirtualMachineTO spec = userVm("CentOS 8");
        spec.setDisks(new DiskTO[] {root});

        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);

        assertEquals("local-lvm:vm-10005-disk-0,discard=on", config.get("scsi0"));
    }

    @Test
    public void testDiskVolidFallsBackToDiskToPath() {
        VolumeObjectTO volume = new VolumeObjectTO();
        volume.setPath(null);
        DiskTO root = new DiskTO(volume, 0L, "local-lvm:vm-10005-disk-0", Volume.Type.ROOT);
        VirtualMachineTO spec = userVm("CentOS 8");
        spec.setDisks(new DiskTO[] {root});

        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);

        assertEquals("local-lvm:vm-10005-disk-0,discard=on", config.get("scsi0"));
    }

    @Test
    public void testDiskWithoutVolidIsSkipped() {
        VolumeObjectTO volume = new VolumeObjectTO();
        DiskTO root = new DiskTO(volume, 0L, null, Volume.Type.ROOT);
        VirtualMachineTO spec = userVm("CentOS 8");
        spec.setDisks(new DiskTO[] {root});

        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);

        assertFalse(config.containsKey("scsi0"));
    }

    @Test
    public void testNullDiskSeqDefaultsToScsi0() {
        VirtualMachineTO spec = userVm("CentOS 8");
        spec.setDisks(new DiskTO[] {disk(Volume.Type.ROOT, "local-lvm:vm-10005-disk-0", null)});

        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);

        assertEquals("local-lvm:vm-10005-disk-0,discard=on", config.get("scsi0"));
    }

    @Test
    public void testIsoDiskDoesNotWireIntoIde2() {
        // The builder must never place an ISO DiskTO path (an NFS/secondary-storage install
        // path, not a PVE volid) into ide2. It always emits an empty cdrom drive; the storage
        // processor's attachIso sets the real "<storage>:iso/<file>" volid after the VM exists.
        VirtualMachineTO spec = userVm("CentOS 8");
        spec.setDisks(new DiskTO[] {
                disk(Volume.Type.ROOT, "local-lvm:vm-10005-disk-0", 0L),
                isoDisk("/mnt/secondary/template/tmpl/1/3/systemvm.iso"),
        });

        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);

        assertEquals("none,media=cdrom", config.get("ide2"));
        assertEquals("local-lvm:vm-10005-disk-0,discard=on", config.get("scsi0"));
    }

    @Test
    public void testNicsMapToNetEntries() {
        VirtualMachineTO spec = userVm("CentOS 8");
        spec.setNics(new NicTO[] {
                nic(0, "02:00:11:22:33:44", "vlan://100", null, null),
                nic(1, "02:00:aa:bb:cc:dd", null, 1450, "cloudbr1"),
        });

        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);

        assertEquals("virtio=02:00:11:22:33:44,bridge=vmbr0,tag=100", config.get("net0"));
        assertEquals("virtio=02:00:aa:bb:cc:dd,bridge=cloudbr1,mtu=1450", config.get("net1"));
    }

    @Test
    public void testVncArgsUsesDisplayModulo() {
        VirtualMachineTO spec = userVm("CentOS 8");
        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 30001, resource);
        assertEquals("-vnc 0.0.0.0:10001,password=on", config.get("args"));
    }

    @Test
    public void testBuildNicSpecWithVlanTag() {
        NicTO nic = nic(0, "02:00:11:22:33:44", "vlan://100", null, null);
        assertEquals("virtio=02:00:11:22:33:44,bridge=vmbr0,tag=100",
                ProxmoxVmConfigBuilder.buildNicSpec(nic, "vmbr0"));
    }

    @Test
    public void testBuildNicSpecUntaggedVlanHasNoTag() {
        NicTO nic = nic(0, "02:00:11:22:33:44", "vlan://untagged", null, null);
        assertEquals("virtio=02:00:11:22:33:44,bridge=vmbr0",
                ProxmoxVmConfigBuilder.buildNicSpec(nic, "vmbr0"));
    }

    @Test
    public void testBuildNicSpecNullBroadcastUriHasNoTag() {
        NicTO nic = nic(0, "02:00:11:22:33:44", null, null, null);
        assertEquals("virtio=02:00:11:22:33:44,bridge=vmbr0",
                ProxmoxVmConfigBuilder.buildNicSpec(nic, "vmbr0"));
    }

    @Test
    public void testBuildNicSpecNonVlanSchemeHasNoTag() {
        NicTO nic = nic(0, "02:00:11:22:33:44", "vxlan://200", null, null);
        assertEquals("virtio=02:00:11:22:33:44,bridge=vmbr0",
                ProxmoxVmConfigBuilder.buildNicSpec(nic, "vmbr0"));
    }

    @Test
    public void testBuildNicSpecNonNumericVlanHasNoTag() {
        NicTO nic = nic(0, "02:00:11:22:33:44", "vlan://abc", null, null);
        assertEquals("virtio=02:00:11:22:33:44,bridge=vmbr0",
                ProxmoxVmConfigBuilder.buildNicSpec(nic, "vmbr0"));
    }

    @Test
    public void testBuildNicSpecWithVlanAndMtu() {
        NicTO nic = nic(0, "02:00:11:22:33:44", "vlan://100", 9000, null);
        assertEquals("virtio=02:00:11:22:33:44,bridge=cloudbr0,tag=100,mtu=9000",
                ProxmoxVmConfigBuilder.buildNicSpec(nic, "cloudbr0"));
    }

    @Test
    public void testNullDisksAndNicsAreTolerated() {
        VirtualMachineTO spec = userVm("CentOS 8");
        spec.setDisks(null);
        spec.setNics(null);

        Map<String, Object> config = ProxmoxVmConfigBuilder.build(spec, 10005, resource);

        assertEquals("none,media=cdrom", config.get("ide2"));
        assertNull(config.get("net0"));
        assertNull(config.get("scsi0"));
    }
}
