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
package org.apache.cloudstack.engine.orchestration;

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.element.ConfigDriveNetworkElement;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.DnsServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;

@Component
public class NicElementPreparationServiceImpl implements NicElementPreparationService {

    @Inject
    protected NetworkModel networkModel;

    @Inject
    protected NicDhcpCleanupService nicDhcpCleanupService;

    @Override
    public boolean prepareElement(final NetworkElement element, final Network network, final NicProfile profile, final VirtualMachineProfile vmProfile,
                                  final DeployDestination dest, final ReservationContext context)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {
        element.prepare(network, profile, vmProfile, dest, context);
        if (vmProfile.getType() == VirtualMachine.Type.User && element.getProvider() != null) {
            if (isServiceProvidedByElement(network, Service.Dhcp, element) && element instanceof DhcpServiceProvider) {
                final DhcpServiceProvider sp = (DhcpServiceProvider) element;
                if (nicDhcpCleanupService.isDhcpAccrossMultipleSubnetsSupported(sp)) {
                    if (!sp.configDhcpSupportForSubnet(network, profile, vmProfile, dest, context)) {
                        return false;
                    }
                }
                if (!sp.addDhcpEntry(network, profile, vmProfile, dest, context)) {
                    return false;
                }
            }
            if (isServiceProvidedByElement(network, Service.Dns, element) && element instanceof DnsServiceProvider) {
                final DnsServiceProvider sp = (DnsServiceProvider) element;
                if (profile.getIPv6Address() == null) {
                    if (!sp.configDnsSupportForSubnet(network, profile, vmProfile, dest, context)) {
                        return false;
                    }
                }
                if (!sp.addDnsEntry(network, profile, vmProfile, dest, context)) {
                    return false;
                }
            }
            if (isServiceProvidedByElement(network, Service.UserData, element) && element instanceof UserDataServiceProvider) {
                final UserDataServiceProvider sp = (UserDataServiceProvider) element;
                if (!sp.addPasswordAndUserdata(network, profile, vmProfile, dest, context)) {
                    return false;
                }
            }
            if (element instanceof ConfigDriveNetworkElement && (
                    isServiceProvidedByElement(network, Service.Dhcp, element) ||
                            isServiceProvidedByElement(network, Service.Dns, element) ||
                            isServiceProvidedByElement(network, Service.UserData, element))) {
                final ConfigDriveNetworkElement sp = (ConfigDriveNetworkElement) element;
                return sp.createConfigDriveIso(profile, vmProfile, dest, null);
            }
        }
        return true;
    }

    protected boolean isServiceProvidedByElement(final Network network, final Service service, final NetworkElement element) {
        return networkModel.areServicesSupportedInNetwork(network.getId(), service)
                && networkModel.isProviderSupportServiceInNetwork(network.getId(), service, element.getProvider());
    }
}
