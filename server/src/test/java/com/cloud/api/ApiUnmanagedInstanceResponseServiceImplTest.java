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
package com.cloud.api;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import org.apache.cloudstack.api.response.NicResponse;
import org.apache.cloudstack.api.response.UnmanagedInstanceDiskResponse;
import org.apache.cloudstack.api.response.UnmanagedInstanceResponse;
import org.apache.cloudstack.vm.UnmanagedInstanceTO;

import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.org.Cluster;

public class ApiUnmanagedInstanceResponseServiceImplTest {

    private final ApiUnmanagedInstanceResponseService service = new ApiUnmanagedInstanceResponseServiceImpl();

    @Test
    public void createUnmanagedInstanceResponseUsesClusterAndHostWhenProvided() {
        UnmanagedInstanceTO instance = createUnmanagedInstance();
        Cluster cluster = Mockito.mock(Cluster.class);
        Mockito.when(cluster.getUuid()).thenReturn("cluster-uuid");
        Mockito.when(cluster.getName()).thenReturn("cluster-name");
        Host host = Mockito.mock(Host.class);
        Mockito.when(host.getUuid()).thenReturn("host-uuid");
        Mockito.when(host.getName()).thenReturn("host-name");
        Mockito.when(host.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        Mockito.when(host.getHypervisorVersion()).thenReturn("8.2.0");

        UnmanagedInstanceResponse response = service.createUnmanagedInstanceResponse(instance, cluster, host);

        Assert.assertEquals("instance-1", response.getName());
        Assert.assertEquals("cluster-uuid", response.getClusterId());
        Assert.assertEquals("cluster-name", response.getClusterName());
        Assert.assertEquals("host-uuid", response.getHostId());
        Assert.assertEquals("host-name", response.getHostName());
        Assert.assertEquals("KVM", response.getHypervisor());
        Assert.assertEquals("8.2.0", response.getHypervisorVersion());
        Assert.assertEquals(UnmanagedInstanceTO.PowerState.PowerOff.toString(), response.getPowerState());
        Assert.assertEquals(Integer.valueOf(4), response.getCpuCores());
        Assert.assertEquals(Integer.valueOf(2500), response.getCpuSpeed());
        Assert.assertEquals(Integer.valueOf(2), response.getCpuCoresPerSocket());
        Assert.assertEquals(Integer.valueOf(8192), response.getMemory());
        Assert.assertEquals("os-uuid", response.getOperatingSystemId());
        Assert.assertEquals("Ubuntu 22.04", response.getOperatingSystem());
        Assert.assertEquals("UEFI", response.getBootMode());
        Assert.assertEquals("HD", response.getBootType());
        Assert.assertEquals("unmanagedinstance", response.getObjectName());

        UnmanagedInstanceDiskResponse diskResponse = response.getDisks().iterator().next();
        Assert.assertEquals("disk-1", diskResponse.getDiskId());
        Assert.assertEquals("Hard disk 1", diskResponse.getLabel());
        Assert.assertEquals(Long.valueOf(17179869184L), diskResponse.getCapacity());
        Assert.assertEquals("SCSI", diskResponse.getController());
        Assert.assertEquals(Integer.valueOf(0), diskResponse.getControllerUnit());
        Assert.assertEquals(Integer.valueOf(1), diskResponse.getPosition());
        Assert.assertEquals("[datastore] instance/disk.vmdk", diskResponse.getImagePath());
        Assert.assertEquals("datastore-1", diskResponse.getDatastoreName());
        Assert.assertEquals("nfs.example.test", diskResponse.getDatastoreHost());
        Assert.assertEquals("/exports/datastore", diskResponse.getDatastorePath());
        Assert.assertEquals("NFS", diskResponse.getDatastoreType());

        NicResponse nicResponse = response.getNics().iterator().next();
        Assert.assertEquals("nic-1", nicResponse.getId());
        Assert.assertEquals("guest-network", nicResponse.getNetworkName());
        Assert.assertEquals("aa:bb:cc:dd:ee:ff", nicResponse.getMacAddress());
        Assert.assertEquals("Vmxnet3", nicResponse.getAdapterType());
        Assert.assertEquals(List.of("10.1.1.20", "10.1.1.21"), nicResponse.getIpAddresses());
        Assert.assertEquals(Integer.valueOf(101), nicResponse.getVlanId());
        Assert.assertEquals(Integer.valueOf(201), nicResponse.getIsolatedPvlanId());
        Assert.assertEquals("promiscuous", nicResponse.getIsolatedPvlanType());
    }

    @Test
    public void createUnmanagedInstanceResponseUsesExternalMetadataAndUnknownPowerStateFallback() {
        UnmanagedInstanceTO instance = new UnmanagedInstanceTO();
        instance.setName("external-instance");
        instance.setClusterName("external-cluster");
        instance.setHostName("external-host");
        instance.setHypervisorType("External");
        instance.setHostHypervisorVersion("7.0");

        UnmanagedInstanceResponse response = service.createUnmanagedInstanceResponse(instance, null, null);

        Assert.assertEquals("external-cluster", response.getClusterName());
        Assert.assertEquals("external-host", response.getHostName());
        Assert.assertEquals("External", response.getHypervisor());
        Assert.assertEquals("7.0", response.getHypervisorVersion());
        Assert.assertEquals(UnmanagedInstanceTO.PowerState.PowerUnknown.toString(), response.getPowerState());
        Assert.assertTrue(response.getDisks().isEmpty());
        Assert.assertTrue(response.getNics().isEmpty());
    }

    private UnmanagedInstanceTO createUnmanagedInstance() {
        UnmanagedInstanceTO instance = new UnmanagedInstanceTO();
        instance.setName("instance-1");
        instance.setClusterName("instance-cluster");
        instance.setHostName("instance-host");
        instance.setHypervisorType("VMware");
        instance.setHostHypervisorVersion("ignored");
        instance.setPowerState(UnmanagedInstanceTO.PowerState.PowerOff);
        instance.setCpuCores(4);
        instance.setCpuSpeed(2500);
        instance.setCpuCoresPerSocket(2);
        instance.setMemory(8192);
        instance.setOperatingSystemId("os-uuid");
        instance.setOperatingSystem("Ubuntu 22.04");
        instance.setBootMode("UEFI");
        instance.setBootType("HD");
        instance.setDisks(List.of(createDisk()));
        instance.setNics(List.of(createNic()));
        return instance;
    }

    private UnmanagedInstanceTO.Disk createDisk() {
        UnmanagedInstanceTO.Disk disk = new UnmanagedInstanceTO.Disk();
        disk.setDiskId("disk-1");
        disk.setLabel("Hard disk 1");
        disk.setCapacity(17179869184L);
        disk.setController("SCSI");
        disk.setControllerUnit(0);
        disk.setPosition(1);
        disk.setImagePath("[datastore] instance/disk.vmdk");
        disk.setDatastoreName("datastore-1");
        disk.setDatastoreHost("nfs.example.test");
        disk.setDatastorePath("/exports/datastore");
        disk.setDatastoreType("NFS");
        return disk;
    }

    private UnmanagedInstanceTO.Nic createNic() {
        UnmanagedInstanceTO.Nic nic = new UnmanagedInstanceTO.Nic();
        nic.setNicId("nic-1");
        nic.setNetwork("guest-network");
        nic.setMacAddress("aa:bb:cc:dd:ee:ff");
        nic.setAdapterType("Vmxnet3");
        nic.setIpAddress(List.of("10.1.1.20", "10.1.1.21"));
        nic.setVlan(101);
        nic.setPvlan(201);
        nic.setPvlanType("promiscuous");
        return nic;
    }
}
