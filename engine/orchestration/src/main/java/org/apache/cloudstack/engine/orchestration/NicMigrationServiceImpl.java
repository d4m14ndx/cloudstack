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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.NetworkMigrationResponder;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;

@Component
public class NicMigrationServiceImpl implements NicMigrationService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected NicDao nicDao;
    @Inject
    protected NetworkDao networksDao;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected NetworkServiceMapDao networkServiceMapDao;
    @Inject
    protected IPAddressDao ipAddressDao;
    @Inject
    protected VlanDao vlanDao;
    @Inject
    protected PhysicalNetworkDao physicalNetworkDao;
    @Inject
    protected UserVmManager userVmManager;

    protected List<NetworkGuru> networkGurus;
    protected List<NetworkElement> networkElements;

    public void setNetworkGurus(final List<NetworkGuru> networkGurus) {
        this.networkGurus = networkGurus;
    }

    public void setNetworkElements(final List<NetworkElement> networkElements) {
        this.networkElements = networkElements;
    }

    @Override
    public void prepareNicForMigration(final VirtualMachineProfile vm, final DeployDestination dest) {
        if (vm.getType().equals(VirtualMachine.Type.DomainRouter)
                && (vm.getHypervisorType().equals(HypervisorType.KVM) || vm.getHypervisorType().equals(HypervisorType.VMware))) {
            // Include nics hot plugged and not stored in DB.
            prepareAllNicsForMigration(vm, dest);
            return;
        }
        final List<NicVO> nics = nicDao.listByVmId(vm.getId());
        final ReservationContext context = new ReservationContextImpl(UUID.randomUUID().toString(), null, null);
        for (final NicVO nic : nics) {
            final NetworkVO network = networksDao.findById(nic.getNetworkId());
            final Integer networkRate = networkModel.getNetworkRate(network.getId(), vm.getId());

            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate, networkModel.isSecurityGroupSupportedInNetwork(network),
                    networkModel.getNetworkTag(vm.getHypervisorType(), network));
            if (guru instanceof NetworkMigrationResponder) {
                if (!((NetworkMigrationResponder) guru).prepareMigration(profile, network, vm, dest, context)) {
                    logger.error("NetworkGuru {} prepareForMigration failed.", guru); // XXX: Transaction error
                }
            }

            if (network.getGuestType() == Network.GuestType.L2 && vm.getType() == VirtualMachine.Type.User) {
                userVmManager.setupVmForPvlan(false, vm.getVirtualMachine().getHostId(), profile);
            }

            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!networkModel.isProviderEnabledInPhysicalNetwork(networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                                + network.getPhysicalNetworkId());
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        if (!((NetworkMigrationResponder) element).prepareMigration(profile, network, vm, dest, context)) {
                            logger.error("NetworkElement {} prepareForMigration failed.", element); // XXX: Transaction error
                        }
                    }
                }
            }
            guru.updateNicProfile(profile, network);
            vm.addNic(profile);
        }
    }

    /*
    Prepare All Nics for migration including the nics dynamically created and not stored in DB
    This is a temporary workaround work KVM migration
    Once clean fix is added by stored dynamically nics is DB, this workaround won't be needed
     */
    @Override
    public void prepareAllNicsForMigration(final VirtualMachineProfile vm, final DeployDestination dest) {
        final List<NicVO> nics = nicDao.listByVmId(vm.getId());
        final ReservationContext context = new ReservationContextImpl(UUID.randomUUID().toString(), null, null);
        Long guestNetworkId = null;
        for (final NicVO nic : nics) {
            final NetworkVO network = networksDao.findById(nic.getNetworkId());
            if (network.getTrafficType().equals(TrafficType.Guest) && network.getGuestType().equals(GuestType.Isolated)) {
                guestNetworkId = network.getId();
            }
            final Integer networkRate = networkModel.getNetworkRate(network.getId(), vm.getId());

            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate,
                    networkModel.isSecurityGroupSupportedInNetwork(network), networkModel.getNetworkTag(vm.getHypervisorType(), network));
            if (guru instanceof NetworkMigrationResponder) {
                if (!((NetworkMigrationResponder) guru).prepareMigration(profile, network, vm, dest, context)) {
                    logger.error("NetworkGuru {} prepareForMigration failed.", guru); // XXX: Transaction error
                }
            }
            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!networkModel.isProviderEnabledInPhysicalNetwork(networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException(String.format("Service provider %s either doesn't exist or is not enabled in physical network: %s",
                                element.getProvider().getName(), physicalNetworkDao.findById(network.getPhysicalNetworkId())));
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        if (!((NetworkMigrationResponder) element).prepareMigration(profile, network, vm, dest, context)) {
                            logger.error("NetworkElement {} prepareForMigration failed.", element); // XXX: Transaction error
                        }
                    }
                }
            }
            guru.updateNicProfile(profile, network);
            vm.addNic(profile);
        }

        final List<String> addedURIs = new ArrayList<>();
        if (guestNetworkId != null) {
            final List<IPAddressVO> publicIps = ipAddressDao.listByAssociatedNetwork(guestNetworkId, null);
            for (final IPAddressVO userIp : publicIps) {
                final PublicIp publicIp = PublicIp.createFromAddrAndVlan(userIp, vlanDao.findById(userIp.getVlanId()));
                final URI broadcastUri = BroadcastDomainType.Vlan.toUri(publicIp.getVlanTag());
                final long ntwkId = publicIp.getNetworkId();
                final Nic nic = nicDao.findByNetworkIdInstanceIdAndBroadcastUri(ntwkId, vm.getId(), broadcastUri.toString());
                if (nic == null && !addedURIs.contains(broadcastUri.toString())) {
                    // Nic details are not available in DB. Create nic profile for migration.
                    final NetworkVO network = networksDao.findById(ntwkId);
                    final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
                    final NicProfile profile = new NicProfile();
                    logger.debug("Creating NIC profile for migration. BroadcastUri: {} NetworkId: {} Instance: {}", broadcastUri.toString(), network, vm);
                    profile.setDeviceId(255); // dummyId
                    profile.setIPv4Address(userIp.getAddress().toString());
                    profile.setIPv4Netmask(publicIp.getNetmask());
                    profile.setIPv4Gateway(publicIp.getGateway());
                    profile.setMacAddress(publicIp.getMacAddress());
                    profile.setBroadcastType(network.getBroadcastDomainType());
                    profile.setTrafficType(network.getTrafficType());
                    profile.setBroadcastUri(broadcastUri);
                    profile.setIsolationUri(Networks.IsolationType.Vlan.toUri(publicIp.getVlanTag()));
                    profile.setSecurityGroupEnabled(networkModel.isSecurityGroupSupportedInNetwork(network));
                    profile.setName(networkModel.getNetworkTag(vm.getHypervisorType(), network));
                    profile.setNetworkRate(networkModel.getNetworkRate(network.getId(), vm.getId()));
                    profile.setNetworkId(network.getId());

                    guru.updateNicProfile(profile, network);
                    vm.addNic(profile);
                    addedURIs.add(broadcastUri.toString());
                }
            }
        }
    }

    @Override
    public void commitNicForMigration(final VirtualMachineProfile src, final VirtualMachineProfile dst) {
        for (final NicProfile nicSrc : src.getNics()) {
            final NetworkVO network = networksDao.findById(nicSrc.getNetworkId());
            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile nicDst = findNicProfileById(dst, nicSrc.getId());
            final ReservationContext src_context = new ReservationContextImpl(nicSrc.getReservationId(), null, null);
            final ReservationContext dst_context = new ReservationContextImpl(nicDst.getReservationId(), null, null);

            if (guru instanceof NetworkMigrationResponder) {
                ((NetworkMigrationResponder) guru).commitMigration(nicSrc, network, src, src_context, dst_context);
            }

            if (network.getGuestType() == Network.GuestType.L2 && src.getType() == VirtualMachine.Type.User) {
                userVmManager.setupVmForPvlan(true, src.getVirtualMachine().getHostId(), nicSrc);
            }

            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!networkModel.isProviderEnabledInPhysicalNetwork(networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                                + network.getPhysicalNetworkId());
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        ((NetworkMigrationResponder) element).commitMigration(nicSrc, network, src, src_context, dst_context);
                    }
                }
            }
            // update the reservation id
            final NicVO nicVo = nicDao.findById(nicDst.getId());
            nicVo.setReservationId(nicDst.getReservationId());
            nicDao.persist(nicVo);
        }
    }

    @Override
    public void rollbackNicForMigration(final VirtualMachineProfile src, final VirtualMachineProfile dst) {
        for (final NicProfile nicDst : dst.getNics()) {
            final NetworkVO network = networksDao.findById(nicDst.getNetworkId());
            final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
            final NicProfile nicSrc = findNicProfileById(src, nicDst.getId());
            final ReservationContext src_context = new ReservationContextImpl(nicSrc.getReservationId(), null, null);
            final ReservationContext dst_context = new ReservationContextImpl(nicDst.getReservationId(), null, null);

            if (guru instanceof NetworkMigrationResponder) {
                ((NetworkMigrationResponder) guru).rollbackMigration(nicDst, network, dst, src_context, dst_context);
            }

            if (network.getGuestType() == Network.GuestType.L2 && src.getType() == VirtualMachine.Type.User) {
                userVmManager.setupVmForPvlan(true, dst.getVirtualMachine().getHostId(), nicDst);
            }

            final List<Provider> providersToImplement = getNetworkProviders(network.getId());
            for (final NetworkElement element : networkElements) {
                if (providersToImplement.contains(element.getProvider())) {
                    if (!networkModel.isProviderEnabledInPhysicalNetwork(networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                        throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                                + network.getPhysicalNetworkId());
                    }
                    if (element instanceof NetworkMigrationResponder) {
                        ((NetworkMigrationResponder) element).rollbackMigration(nicDst, network, dst, src_context, dst_context);
                    }
                }
            }
        }
    }

    private NicProfile findNicProfileById(final VirtualMachineProfile vm, final long id) {
        for (final NicProfile nic : vm.getNics()) {
            if (nic.getId() == id) {
                return nic;
            }
        }
        return null;
    }

    private List<Provider> getNetworkProviders(final long networkId) {
        final List<String> providerNames = networkServiceMapDao.getDistinctProviders(networkId);
        final List<Provider> providers = new ArrayList<>();
        for (final String providerName : providerNames) {
            providers.add(Network.Provider.getProvider(providerName));
        }

        return providers;
    }
}
