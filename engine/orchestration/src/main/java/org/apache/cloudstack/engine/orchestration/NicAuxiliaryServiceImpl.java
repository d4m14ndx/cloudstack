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

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.guru.NetworkGuruAdditionalFunctions;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.vm.Nic;
import com.cloud.vm.Nic.ReservationStrategy;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;

/**
 * Auxiliary NIC lookups and placeholder persistence extracted from
 * {@link NetworkOrchestrator}.
 */
@Component
public class NicAuxiliaryServiceImpl implements NicAuxiliaryService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected NicDao nicDao;

    @Inject
    protected NicSecondaryIpDao nicSecondaryIpDao;

    @Inject
    protected NetworkDao networksDao;

    @Inject
    protected NetworkModel networkModel;

    @Override
    public List<? extends Nic> listVmNics(final long vmId, final Long nicId, final Long networkId, String keyword, List<NetworkGuru> networkGurus) {
        List<NicVO> result;

        if (keyword == null || keyword.isEmpty()) {
            if (nicId == null && networkId == null) {
                result = nicDao.listByVmId(vmId);
            } else {
                result = nicDao.listByVmIdAndNicIdAndNtwkId(vmId, nicId, networkId);
            }
        } else {
            result = nicDao.listByVmIdAndKeyword(vmId, keyword);
        }

        for (final NicVO nic : result) {
            if (networkModel.isProviderForNetwork(Network.Provider.Nsx, nic.getNetworkId())) {
                logger.info("Listing NSX logical switch and logical switch por for each nic");
                final NetworkVO network = networksDao.findById(nic.getNetworkId());
                final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
                final NetworkGuruAdditionalFunctions guruFunctions = (NetworkGuruAdditionalFunctions) guru;

                final Map<String, ? extends Object> nsxParams = guruFunctions.listAdditionalNicParams(nic.getUuid());
                if (nsxParams != null) {
                    final String lswitchUuuid = nsxParams.containsKey(NetworkGuruAdditionalFunctions.NSX_LSWITCH_UUID)
                            ? (String) nsxParams.get(NetworkGuruAdditionalFunctions.NSX_LSWITCH_UUID) : null;
                    final String lswitchPortUuuid = nsxParams.containsKey(NetworkGuruAdditionalFunctions.NSX_LSWITCHPORT_UUID)
                            ? (String) nsxParams.get(NetworkGuruAdditionalFunctions.NSX_LSWITCHPORT_UUID) : null;
                    nic.setNsxLogicalSwitchUuid(lswitchUuuid);
                    nic.setNsxLogicalSwitchPortUuid(lswitchPortUuuid);
                }
            }
        }

        return result;
    }

    @Override
    public boolean isSecondaryIpSetForNic(final long nicId) {
        final NicVO nic = nicDao.findById(nicId);
        return nic.getSecondaryIp();
    }

    @Override
    public boolean removeVmSecondaryIpsOfNic(final long nicId) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                final List<NicSecondaryIpVO> ipList = nicSecondaryIpDao.listByNicId(nicId);
                if (ipList != null) {
                    for (final NicSecondaryIpVO ip : ipList) {
                        nicSecondaryIpDao.remove(ip.getId());
                    }
                    logger.debug("Revoving nic secondary ip entry ...");
                }
            }
        });

        return true;
    }

    @Override
    public NicVO savePlaceholderNic(final Network network, final String ip4Address, final String ip6Address, final Type vmType) {
        return savePlaceholderNic(network, ip4Address, ip6Address, null, null, null, vmType);
    }

    @Override
    public NicVO savePlaceholderNic(final Network network, final String ip4Address, final String ip6Address, final String ip6Cidr, final String ip6Gateway, final String reserver, final Type vmType) {
        final NicVO nic = new NicVO(null, null, network.getId(), null);
        nic.setIPv4Address(ip4Address);
        nic.setIPv6Address(ip6Address);
        nic.setIPv6Cidr(ip6Cidr);
        nic.setIPv6Gateway(ip6Gateway);
        nic.setReservationStrategy(ReservationStrategy.PlaceHolder);
        if (reserver != null) {
            nic.setReserver(reserver);
        }
        nic.setState(Nic.State.Reserved);
        nic.setVmType(vmType);
        return nicDao.persist(nic);
    }

    @Override
    public void unmanageNics(VirtualMachineProfile vm, BiConsumer<VirtualMachineProfile, NicVO> removeNic) {
        logger.debug("Unmanaging NICs for VM: {}", vm);

        VirtualMachine virtualMachine = vm.getVirtualMachine();
        final List<NicVO> nics = nicDao.listByVmId(vm.getId());
        for (final NicVO nic : nics) {
            removeNic.accept(vm, nic);
            NetworkVO network = networksDao.findById(nic.getNetworkId());
            if (virtualMachine.getState() != VirtualMachine.State.Stopped) {
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_REMOVE, virtualMachine.getAccountId(), virtualMachine.getDataCenterId(), virtualMachine.getId(),
                        Long.toString(nic.getId()), network.getNetworkOfferingId(), null, 0L, virtualMachine.getClass().getName(), virtualMachine.getUuid(), virtualMachine.isDisplay());
            }
        }
    }

    @Override
    public void expungeLbVmRefs(List<NetworkElement> networkElements, List<Long> vmIds, Long batchSize) {
        if (CollectionUtils.isEmpty(networkElements) || CollectionUtils.isEmpty(vmIds)) {
            return;
        }
        for (NetworkElement element : networkElements) {
            if (element instanceof LoadBalancingServiceProvider) {
                LoadBalancingServiceProvider lbProvider = (LoadBalancingServiceProvider)element;
                lbProvider.expungeLbVmRefs(vmIds, batchSize);
            }
        }
    }
}
