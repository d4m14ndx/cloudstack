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
package com.cloud.network;

import java.util.Date;

import jakarta.inject.Inject;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.api.command.user.address.RemoveQuarantinedIpCmd;
import org.apache.cloudstack.api.command.user.address.UpdateQuarantinedIpCmd;
import org.apache.cloudstack.context.CallContext;

import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.PublicIpQuarantineDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Public-IP quarantine lifecycle operations — extracted from
 * {@link NetworkServiceImpl}.
 *
 * @see PublicIpQuarantineManager
 */
@Component
public class PublicIpQuarantineManagerImpl implements PublicIpQuarantineManager {
    private static final Logger LOG = LogManager.getLogger(PublicIpQuarantineManagerImpl.class);

    @Inject
    PublicIpQuarantineDao publicIpQuarantineDao;
    @Inject
    IPAddressDao ipAddressDao;
    @Inject
    AccountDao accountDao;
    @Inject
    DomainDao domainDao;
    @Inject
    AccountManager accountManager;
    @Inject
    IpAddressManager ipAddrMgr;

    @Override
    public PublicIpQuarantine updatePublicIpAddressInQuarantine(UpdateQuarantinedIpCmd cmd) throws CloudRuntimeException {
        Long ipId = cmd.getId();
        String ipAddress = cmd.getIpAddress();
        Date newEndDate = cmd.getEndDate();

        if (new Date().after(newEndDate)) {
            throw new InvalidParameterValueException(String.format("The given end date [%s] is invalid as it is before the current date.", newEndDate));
        }

        PublicIpQuarantine publicIpQuarantine = retrievePublicIpQuarantine(ipId, ipAddress);
        checkCallerForPublicIpQuarantineAccess(publicIpQuarantine);

        String publicIpQuarantineAddress = ipAddressDao.findById(publicIpQuarantine.getPublicIpAddressId()).getAddress().toString();
        Date currentEndDate = publicIpQuarantine.getEndDate();

        if (new Date().after(currentEndDate)) {
            throw new CloudRuntimeException(String.format("The quarantine for the public IP address [%s] is no longer active; thus, it cannot be updated.", publicIpQuarantineAddress));
        }

        return ipAddrMgr.updatePublicIpAddressInQuarantine(publicIpQuarantine.getId(), newEndDate);
    }

    @Override
    public void removePublicIpAddressFromQuarantine(RemoveQuarantinedIpCmd cmd) throws CloudRuntimeException {
        Long ipId = cmd.getId();
        String ipAddress = cmd.getIpAddress();
        PublicIpQuarantine publicIpQuarantine = retrievePublicIpQuarantine(ipId, ipAddress);

        String removalReason = cmd.getRemovalReason();
        if (StringUtils.isBlank(removalReason)) {
            LOG.error("The removalReason parameter cannot be blank.");
            ipAddress = ObjectUtils.defaultIfNull(ipAddress, ipAddressDao.findById(publicIpQuarantine.getPublicIpAddressId()).getAddress().toString());
            throw new CloudRuntimeException(String.format("The given reason for removing the public IP address [%s] from quarantine is blank.", ipAddress));
        }

        checkCallerForPublicIpQuarantineAccess(publicIpQuarantine);

        ipAddrMgr.removePublicIpAddressFromQuarantine(publicIpQuarantine.getId(), removalReason);
    }

    /**
     * Retrieves the active quarantine for the given public IP address. It can find by the ID of the quarantine or the address of the public IP.
     * @throws CloudRuntimeException if it does not find an active quarantine for the given public IP.
     */
    @Override
    public PublicIpQuarantine retrievePublicIpQuarantine(Long ipId, String ipAddress) throws CloudRuntimeException {
        PublicIpQuarantine publicIpQuarantine;
        if (ipId != null) {
            LOG.debug("The ID of the IP in quarantine was informed; therefore, the `ipAddress` parameter will be ignored.");
            publicIpQuarantine = publicIpQuarantineDao.findById(ipId);
        } else if (ipAddress != null) {
            LOG.debug("The address of the IP in quarantine was informed, it will be used to fetch its metadata.");
            publicIpQuarantine = publicIpQuarantineDao.findByIpAddress(ipAddress);
        } else {
            throw new CloudRuntimeException("Either the ID or the address of the IP in quarantine must be informed.");
        }

        if (publicIpQuarantine == null) {
            throw new CloudRuntimeException("There is no active quarantine for the specified IP address.");
        }

        return publicIpQuarantine;
    }

    protected void checkCallerForPublicIpQuarantineAccess(PublicIpQuarantine publicIpQuarantine) {
        Account callingAccount = CallContext.current().getCallingAccount();
        DomainVO domainOfThePreviousOwner = domainDao.findById(accountDao.findById(publicIpQuarantine.getPreviousOwnerId()).getDomainId());

        accountManager.checkAccess(callingAccount, domainOfThePreviousOwner);
    }
}
