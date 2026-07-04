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

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.storage.Volume;

/**
 * Translates a CloudStack {@link VirtualMachineTO} into a Proxmox VE qemu VM
 * create/update configuration map (the parameter set of
 * POST /nodes/{node}/qemu and POST /nodes/{node}/qemu/{vmid}/config).
 */
public class ProxmoxVmConfigBuilder {

    private static final long MIB = 1024L * 1024L;
    private static final int VNC_DISPLAY_MODULO = 20000;

    private ProxmoxVmConfigBuilder() {
    }

    /**
     * Builds the full PVE VM configuration for the given VM spec.
     *
     * @param spec     the CloudStack VM definition
     * @param vmid     the PVE vmid the VM is (or will be) known by
     * @param resource resource used to resolve NIC bridges (traffic labels)
     * @return ordered map of PVE config keys to values
     */
    public static Map<String, Object> build(VirtualMachineTO spec, int vmid, ProxmoxResource resource) {
        Map<String, Object> config = new LinkedHashMap<String, Object>();

        config.put("name", spec.getName());
        config.put("cores", spec.getCpus());
        config.put("sockets", 1);
        config.put("cpu", "host");
        config.put("memory", spec.getMaxRam() / MIB);
        config.put("balloon", spec.getMinRam() / MIB);
        config.put("ostype", isWindows(spec.getOs()) ? "win11" : "l26");
        // Shared controller, not virtio-scsi-single: with one controller per disk a
        // hot-detach becomes a PCI unplug the guest must acknowledge ("error on
        // hot-unplugging device 'virtioscsiN' - still busy in guest?"), while on a
        // shared controller it is a SCSI-bus removal that needs no guest support —
        // matching how libvirt attaches disks on KVM.
        config.put("scsihw", "virtio-scsi-pci");
        config.put("agent", 1);
        config.put("onboot", 0);
        config.put("protection", 0);
        config.put("serial0", "socket");
        config.put("vga", "std");

        if (spec.getDisks() != null) {
            for (DiskTO disk : spec.getDisks()) {
                if (disk.getType() == Volume.Type.ROOT || disk.getType() == Volume.Type.DATADISK) {
                    String volid = getDiskVolid(disk);
                    if (StringUtils.isBlank(volid)) {
                        continue;
                    }
                    long seq = disk.getDiskSeq() != null ? disk.getDiskSeq() : 0L;
                    config.put("scsi" + seq, volid + ",discard=on");
                }
                // ISO disks are intentionally NOT wired into ide2 here. An ISO DiskTO
                // carries an NFS/secondary-storage install path (data.getPath()), not a PVE
                // volid, so emitting "ide2=<nfs-path>,media=cdrom" would make createVm/
                // setVmConfig reject the config and the VM would fail to start. The empty
                // cdrom drive below is a placeholder; the storage processor's attachIso path
                // copies the ISO into an iso-capable PVE storage and then sets the real
                // "<storage>:iso/<file>" volid on ide2 via setVmConfig after the VM exists.
            }
        }
        // Always start with an empty cdrom; attachIso replaces this with the real volid.
        config.put("ide2", "none,media=cdrom");
        config.put("boot", "order=scsi0;ide2;net0");

        if (spec.getNics() != null) {
            for (NicTO nic : spec.getNics()) {
                config.put("net" + nic.getDeviceId(), buildNicSpec(nic, resource.resolveBridge(nic)));
            }
        }

        config.put("args", buildArgs(spec, vmid));

        return config;
    }

    /**
     * Builds the value of a PVE netN property for the given NIC, e.g.
     * {@code virtio=02:00:11:22:33:44,bridge=vmbr0,tag=100,mtu=1450}.
     */
    public static String buildNicSpec(NicTO nic, String bridge) {
        StringBuilder sb = new StringBuilder();
        sb.append("virtio=").append(nic.getMac());
        sb.append(",bridge=").append(bridge);
        String vlan = getVlanTag(nic);
        if (vlan != null) {
            sb.append(",tag=").append(vlan);
        }
        if (nic.getMtu() != null) {
            sb.append(",mtu=").append(nic.getMtu());
        }
        return sb.toString();
    }

    /**
     * Extra qemu arguments: the fixed, password-protected VNC listener the
     * CloudStack console proxy connects to. System VM boot-args patching goes
     * through the qemu guest agent (agent=1), so no extra devices are needed.
     */
    private static String buildArgs(VirtualMachineTO spec, int vmid) {
        StringBuilder args = new StringBuilder();
        args.append("-vnc 0.0.0.0:").append(vmid % VNC_DISPLAY_MODULO).append(",password=on");
        return args.toString();
    }

    private static String getDiskVolid(DiskTO disk) {
        DataTO data = disk.getData();
        if (data != null && StringUtils.isNotBlank(data.getPath())) {
            return data.getPath();
        }
        return disk.getPath();
    }

    /**
     * Returns the numeric VLAN tag of the NIC's broadcast URI, or null when the
     * NIC is untagged (no URI, non-VLAN scheme, vlan://untagged or non-numeric).
     */
    private static String getVlanTag(NicTO nic) {
        URI broadcastUri = nic.getBroadcastUri();
        if (broadcastUri == null) {
            return null;
        }
        try {
            if (BroadcastDomainType.getSchemeValue(broadcastUri) != BroadcastDomainType.Vlan) {
                return null;
            }
            String value = BroadcastDomainType.getValue(broadcastUri);
            if (StringUtils.isBlank(value) || "untagged".equalsIgnoreCase(value)) {
                return null;
            }
            Integer.parseInt(value);
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isWindows(String os) {
        return os != null && os.toLowerCase().contains("windows");
    }
}
