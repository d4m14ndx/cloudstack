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
package com.cloud.hypervisor.proxmox.guru;

import java.util.Collections;
import java.util.List;

import com.cloud.agent.api.Command;
import com.cloud.agent.api.UnregisterVMCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruBase;
import com.cloud.template.VirtualMachineTemplate.BootloaderType;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;

/**
 * Hypervisor guru for Proxmox VE. All Proxmox guests are fully virtualized QEMU/KVM machines,
 * so the bootloader is always HVM. Host changes are tracked because a Proxmox cluster can
 * live-migrate VMs between nodes outside of CloudStack's control.
 */
public class ProxmoxGuru extends HypervisorGuruBase implements HypervisorGuru {

    protected ProxmoxGuru() {
        super();
    }

    @Override
    public HypervisorType getHypervisorType() {
        return HypervisorType.Proxmox;
    }

    @Override
    public VirtualMachineTO implement(VirtualMachineProfile vm) {
        VirtualMachineTO to = toVirtualMachineTO(vm);
        to.setBootloader(BootloaderType.HVM);
        return to;
    }

    @Override
    public boolean trackVmHostChange() {
        return true;
    }

    /**
     * PVE VM definitions are persistent (VMware model), so expunging a CloudStack instance must
     * remove the leftover VM shell from the PVE cluster. This runs at the end of the expunge,
     * after the data disks were detached (handed to the template-holder vmid) and the root disk
     * was freed; ProxmoxResource re-checks that no owned volumes are still referenced before it
     * destroys the VM.
     */
    @Override
    public List<Command> finalizeExpunge(VirtualMachine vm) {
        return Collections.singletonList(new UnregisterVMCommand(vm.getInstanceName()));
    }
}
