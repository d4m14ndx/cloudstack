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
package com.cloud.configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.network.CreateGuestNetworkIpv6PrefixCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteGuestNetworkIpv6PrefixCmd;
import org.apache.cloudstack.api.command.admin.network.ListGuestNetworkIpv6PrefixesCmd;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenterGuestIpv6Prefix;
import com.cloud.dc.DataCenterGuestIpv6PrefixVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterGuestIpv6PrefixDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Ipv6GuestPrefixSubnetNetworkMapVO;
import com.cloud.network.Ipv6Service;
import com.cloud.network.dao.Ipv6GuestPrefixSubnetNetworkMapDao;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.googlecode.ipv6.IPv6Network;

@Component
public class GuestIpv6PrefixServiceImpl implements GuestIpv6PrefixService {

    protected static final Logger logger = LogManager.getLogger(GuestIpv6PrefixServiceImpl.class);

    @Inject
    protected DataCenterDao _zoneDao;
    @Inject
    protected DataCenterGuestIpv6PrefixDao dataCenterGuestIpv6PrefixDao;
    @Inject
    protected Ipv6GuestPrefixSubnetNetworkMapDao ipv6GuestPrefixSubnetNetworkMapDao;

    @Override
    @DB
    public DataCenterGuestIpv6Prefix createDataCenterGuestIpv6Prefix(final CreateGuestNetworkIpv6PrefixCmd cmd)
            throws ConcurrentOperationException {
        final long zoneId = cmd.getZoneId();
        final DataCenterVO zone = _zoneDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id: " + zoneId);
        }
        final String prefix = cmd.getPrefix();
        IPv6Network prefixNet = IPv6Network.fromString(prefix);
        if (prefixNet.getNetmask().asPrefixLength() > Ipv6Service.IPV6_SLAAC_CIDR_NETMASK) {
            throw new InvalidParameterValueException(String.format("IPv6 prefix must be /%d or less", Ipv6Service.IPV6_SLAAC_CIDR_NETMASK));
        }
        List<DataCenterGuestIpv6PrefixVO> existingPrefixes = dataCenterGuestIpv6PrefixDao.listByDataCenterId(zoneId);
        for (DataCenterGuestIpv6PrefixVO existingPrefix : existingPrefixes) {
            IPv6Network existingPrefixNet = IPv6Network.fromString(existingPrefix.getPrefix());
            if (NetUtils.ipv6NetworksOverlap(existingPrefixNet, prefixNet)) {
                throw new InvalidParameterValueException(String.format("IPv6 prefix %s overlaps with the existing IPv6 prefix %s", prefixNet, existingPrefixNet));
            }
        }
        DataCenterGuestIpv6Prefix dataCenterGuestIpv6Prefix = null;
        try {
            dataCenterGuestIpv6Prefix = Transaction.execute(new TransactionCallback<>() {
                @Override
                public DataCenterGuestIpv6Prefix doInTransaction(TransactionStatus status) {
                    DataCenterGuestIpv6PrefixVO dataCenterGuestIpv6PrefixVO = new DataCenterGuestIpv6PrefixVO(zoneId, prefix);
                    dataCenterGuestIpv6PrefixDao.persist(dataCenterGuestIpv6PrefixVO);
                    return dataCenterGuestIpv6PrefixVO;
                }
            });
        } catch (final Exception e) {
            logger.error("Unable to add IPv6 prefix for zone: {} due to {}", zone, e.getMessage(), e);
            throw new CloudRuntimeException(String.format("Unable to add IPv6 prefix for zone ID: %s. Please contact Cloud Support.", zone));
        }
        return dataCenterGuestIpv6Prefix;
    }

    @Override
    public List<? extends DataCenterGuestIpv6Prefix> listDataCenterGuestIpv6Prefixes(final ListGuestNetworkIpv6PrefixesCmd cmd)
            throws ConcurrentOperationException {
        final Long id = cmd.getId();
        final Long zoneId = cmd.getZoneId();
        if (id != null) {
            DataCenterGuestIpv6PrefixVO prefix = dataCenterGuestIpv6PrefixDao.findById(id);
            List<DataCenterGuestIpv6PrefixVO> prefixes = new ArrayList<>();
            if (prefix != null) {
                prefixes.add(prefix);
            }
            return prefixes;
        }
        if (zoneId != null) {
            final DataCenterVO zone = _zoneDao.findById(zoneId);
            if (zone == null) {
                throw new InvalidParameterValueException("Unable to find zone by id: " + zoneId);
            }
            return dataCenterGuestIpv6PrefixDao.listByDataCenterId(zoneId);
        }
        return dataCenterGuestIpv6PrefixDao.listAll();
    }

    @Override
    public boolean deleteDataCenterGuestIpv6Prefix(DeleteGuestNetworkIpv6PrefixCmd cmd) {
        final long prefixId = cmd.getId();
        final DataCenterGuestIpv6PrefixVO prefix = dataCenterGuestIpv6PrefixDao.findById(prefixId);
        if (prefix == null) {
            throw new InvalidParameterValueException("Unable to find guest network IPv6 prefix by id: " + prefixId);
        }
        List<Ipv6GuestPrefixSubnetNetworkMapVO> prefixSubnets = ipv6GuestPrefixSubnetNetworkMapDao.listUsedByPrefix(prefixId);
        if (CollectionUtils.isNotEmpty(prefixSubnets)) {
            List<String> usedSubnets = prefixSubnets.stream().map(Ipv6GuestPrefixSubnetNetworkMapVO::getSubnet).collect(Collectors.toList());
            logger.error(String.format("Subnets for guest IPv6 prefix {ID: %s, %s} are in use: %s", prefix.getUuid(), prefix.getPrefix(), String.join(", ", usedSubnets)));
            throw new CloudRuntimeException(String.format("Unable to delete guest network IPv6 prefix ID: %s. Prefix subnets are in use.", prefix.getUuid()));
        }
        ipv6GuestPrefixSubnetNetworkMapDao.deleteByPrefixId(prefixId);
        dataCenterGuestIpv6PrefixDao.remove(prefixId);
        return true;
    }
}
