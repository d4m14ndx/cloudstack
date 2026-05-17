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
package com.cloud.server;

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.config.UpdateHypervisorCapabilitiesCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorCapabilities;
import com.cloud.hypervisor.HypervisorCapabilitiesVO;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchCriteria;

/**
 * @see HypervisorCapabilitiesService
 */
@Component
public class HypervisorCapabilitiesServiceImpl implements HypervisorCapabilitiesService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private HypervisorCapabilitiesDao hypervisorCapabilitiesDao;

    @Override
    public Pair<List<? extends HypervisorCapabilities>, Integer> listHypervisorCapabilities(
            final Long id, final HypervisorType hypervisorType, final String keyword,
            final Long startIndex, final Long pageSizeVal) {
        final Filter searchFilter = new Filter(HypervisorCapabilitiesVO.class, "id", true, startIndex, pageSizeVal);
        final SearchCriteria<HypervisorCapabilitiesVO> sc = hypervisorCapabilitiesDao.createSearchCriteria();

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (hypervisorType != null) {
            sc.addAnd("hypervisorType", SearchCriteria.Op.EQ, hypervisorType);
        }

        if (keyword != null) {
            final SearchCriteria<HypervisorCapabilitiesVO> ssc = hypervisorCapabilitiesDao.createSearchCriteria();
            ssc.addOr("hypervisorType", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("hypervisorType", SearchCriteria.Op.SC, ssc);
        }

        final Pair<List<HypervisorCapabilitiesVO>, Integer> result = hypervisorCapabilitiesDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public HypervisorCapabilitiesVO getHypervisorCapabilitiesForUpdate(
            final Long id, final String hypervisorStr, final String hypervisorVersion) {
        if (id == null && StringUtils.isAllEmpty(hypervisorStr, hypervisorVersion)) {
            throw new InvalidParameterValueException("Either ID or hypervisor and hypervisor version must be specified");
        }
        if (id != null) {
            if (!StringUtils.isAllBlank(hypervisorStr, hypervisorVersion)) {
                throw new InvalidParameterValueException("ID can not be specified together with hypervisor and hypervisor version");
            }
            HypervisorCapabilitiesVO hpvCapabilities = hypervisorCapabilitiesDao.findById(id, true);
            if (hpvCapabilities == null) {
                final InvalidParameterValueException ex = new InvalidParameterValueException("unable to find the hypervisor capabilities for specified id");
                ex.addProxyObject(id.toString(), "Id");
                throw ex;
            }
            return hpvCapabilities;
        }
        if (StringUtils.isAnyBlank(hypervisorStr, hypervisorVersion)) {
            throw new InvalidParameterValueException("Hypervisor and hypervisor version must be specified together");
        }
        HypervisorType hypervisorType = HypervisorType.getType(hypervisorStr);
        if (hypervisorType == HypervisorType.None) {
            throw new InvalidParameterValueException("Invalid hypervisor specified");
        }
        HypervisorCapabilitiesVO hpvCapabilities = hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(hypervisorType, hypervisorVersion);
        if (hpvCapabilities == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find the hypervisor capabilities for specified hypervisor and hypervisor version");
            ex.addProxyObject(hypervisorStr, "hypervisor");
            ex.addProxyObject(hypervisorVersion, "hypervisorVersion");
            throw ex;
        }
        return hpvCapabilities;
    }

    @Override
    public HypervisorCapabilities updateHypervisorCapabilities(UpdateHypervisorCapabilitiesCmd cmd) {
        Long id = cmd.getId();
        final String hypervisorStr = cmd.getHypervisor();
        final String hypervisorVersion = cmd.getHypervisorVersion();
        final Boolean securityGroupEnabled = cmd.getSecurityGroupEnabled();
        final Long maxGuestsLimit = cmd.getMaxGuestsLimit();
        final Integer maxDataVolumesLimit = cmd.getMaxDataVolumesLimit();
        final Boolean storageMotionSupported = cmd.getStorageMotionSupported();
        final Integer maxHostsPerClusterLimit = cmd.getMaxHostsPerClusterLimit();
        final Boolean vmSnapshotEnabled = cmd.getVmSnapshotEnabled();
        HypervisorCapabilitiesVO hpvCapabilities = getHypervisorCapabilitiesForUpdate(id, hypervisorStr, hypervisorVersion);

        final boolean updateNeeded = securityGroupEnabled != null || maxGuestsLimit != null ||
                maxDataVolumesLimit != null || storageMotionSupported != null || maxHostsPerClusterLimit != null ||
                vmSnapshotEnabled != null;
        if (!updateNeeded) {
            return hpvCapabilities;
        }
        if (StringUtils.isNotBlank(hypervisorVersion) && !hpvCapabilities.getHypervisorVersion().equals(hypervisorVersion)) {
            logger.debug(String.format("Hypervisor capabilities for hypervisor: %s and version: %s does not exist, creating a copy from the parent version: %s for update.", hypervisorStr, hypervisorVersion, hpvCapabilities.getHypervisorVersion()));
            HypervisorCapabilitiesVO copy = new HypervisorCapabilitiesVO(hpvCapabilities);
            copy.setHypervisorVersion(hypervisorVersion);
            hpvCapabilities = hypervisorCapabilitiesDao.persist(copy);
        }

        id = hpvCapabilities.getId();
        hpvCapabilities = hypervisorCapabilitiesDao.createForUpdate(id);

        if (securityGroupEnabled != null) {
            hpvCapabilities.setSecurityGroupEnabled(securityGroupEnabled);
        }

        if (maxGuestsLimit != null) {
            hpvCapabilities.setMaxGuestsLimit(maxGuestsLimit);
        }

        if (maxDataVolumesLimit != null) {
            hpvCapabilities.setMaxDataVolumesLimit(maxDataVolumesLimit);
        }

        if (storageMotionSupported != null) {
            hpvCapabilities.setStorageMotionSupported(storageMotionSupported);
        }

        if (maxHostsPerClusterLimit != null) {
            hpvCapabilities.setMaxHostsPerCluster(maxHostsPerClusterLimit);
        }

        if (vmSnapshotEnabled != null) {
            hpvCapabilities.setVmSnapshotEnabled(vmSnapshotEnabled);
        }

        if (hypervisorCapabilitiesDao.update(id, hpvCapabilities)) {
            hpvCapabilities = hypervisorCapabilitiesDao.findById(id);
            CallContext.current().setEventDetails("Hypervisor Capabilities ID: " + hpvCapabilities.getUuid());
            return hpvCapabilities;
        } else {
            return null;
        }
    }
}
