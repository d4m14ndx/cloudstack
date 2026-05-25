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
package com.cloud.network.vpc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.vpc.ListVPCOfferingsCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.query.QueryService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.VpcOfferingJoinDao;
import com.cloud.api.query.vo.VpcOfferingJoinVO;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.vpc.dao.VpcOfferingDao;
import com.cloud.network.vpc.dao.VpcOfferingDetailsDao;
import com.cloud.network.vpc.dao.VpcOfferingServiceMapDao;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;

/**
 * Read-only query operations for {@link VpcOffering} — extracted
 * from {@link VpcManagerImpl}.
 *
 * @see VpcOfferingQueryService
 */
@Component
public class VpcOfferingQueryServiceImpl implements VpcOfferingQueryService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private VpcOfferingDao vpcOfferingDao;
    @Inject
    private VpcOfferingJoinDao vpcOfferingJoinDao;
    @Inject
    private VpcOfferingDetailsDao vpcOfferingDetailsDao;
    @Inject
    private VpcOfferingServiceMapDao vpcOfferingServiceMapDao;
    @Inject
    private DomainDao domainDao;
    @Inject
    private EntityManager entityMgr;

    @Override
    public VpcOffering getVpcOffering(final long vpcOffId) {
        return vpcOfferingDao.findById(vpcOffId);
    }

    @Override
    public Map<Service, Set<Provider>> getVpcOffSvcProvidersMap(final long vpcOffId) {
        final Map<Service, Set<Provider>> serviceProviderMap = new HashMap<>();
        final List<VpcOfferingServiceMapVO> map = vpcOfferingServiceMapDao.listByVpcOffId(vpcOffId);

        for (final VpcOfferingServiceMapVO instance : map) {
            final Service service = Service.getService(instance.getService());
            Set<Provider> providers;
            providers = serviceProviderMap.get(service);
            if (providers == null) {
                providers = new HashSet<>();
            }
            providers.add(Provider.getProvider(instance.getProvider()));
            serviceProviderMap.put(service, providers);
        }

        return serviceProviderMap;
    }

    @Override
    public boolean areServicesSupportedByVpcOffering(final long vpcOffId, final Service... services) {
        return vpcOfferingServiceMapDao.areServicesSupportedByVpcOffering(vpcOffId, services);
    }

    @Override
    public List<Long> getVpcOfferingDomains(Long vpcOfferingId) {
        final VpcOffering offeringHandle = entityMgr.findById(VpcOffering.class, vpcOfferingId);
        if (offeringHandle == null) {
            throw new InvalidParameterValueException("Unable to find VPC offering " + vpcOfferingId);
        }
        return vpcOfferingDetailsDao.findDomainIds(vpcOfferingId);
    }

    @Override
    public List<Long> getVpcOfferingZones(Long vpcOfferingId) {
        final VpcOffering offeringHandle = entityMgr.findById(VpcOffering.class, vpcOfferingId);
        if (offeringHandle == null) {
            throw new InvalidParameterValueException("Unable to find VPC offering " + vpcOfferingId);
        }
        return vpcOfferingDetailsDao.findZoneIds(vpcOfferingId);
    }

    @Override
    public Pair<List<? extends VpcOffering>, Integer> listVpcOfferings(ListVPCOfferingsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        final Long id = cmd.getId();
        final String name = cmd.getVpcOffName();
        final String displayText = cmd.getDisplayText();
        final List<String> supportedServicesStr = cmd.getSupportedServices();
        final Boolean isDefault = cmd.getIsDefault();
        final String keyword = cmd.getKeyword();
        final String state = cmd.getState();
        final Long startIndex = cmd.getStartIndex();
        final Long pageSizeVal = cmd.getPageSizeVal();
        final Long domainId = cmd.getDomainId();
        final Long zoneId = cmd.getZoneId();
        final Filter searchFilter = new Filter(VpcOfferingJoinVO.class, "sortKey", QueryService.SortKeyAscending.value(), null, null);
        searchFilter.addOrderBy(VpcOfferingJoinVO.class, "id", true);
        final SearchCriteria<VpcOfferingJoinVO> sc = vpcOfferingJoinDao.createSearchCriteria();

        verifyDomainId(domainId, caller);

        if (keyword != null) {
            final SearchCriteria<VpcOfferingJoinVO> ssc = vpcOfferingJoinDao.createSearchCriteria();
            ssc.addOr("displayText", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");

            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.LIKE, "%" + name + "%");
        }

        if (displayText != null) {
            sc.addAnd("displayText", SearchCriteria.Op.LIKE, "%" + displayText + "%");
        }

        if (isDefault != null) {
            sc.addAnd("isDefault", SearchCriteria.Op.EQ, isDefault);
        }

        if (state != null) {
            sc.addAnd("state", SearchCriteria.Op.EQ, state);
        }

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (zoneId != null) {
            SearchBuilder<VpcOfferingJoinVO> sb = vpcOfferingJoinDao.createSearchBuilder();
            sb.and("zoneId", sb.entity().getZoneId(), Op.FIND_IN_SET);
            sb.or("zId", sb.entity().getZoneId(), Op.NULL);
            sb.done();
            SearchCriteria<VpcOfferingJoinVO> zoneSC = sb.create();
            zoneSC.setParameters("zoneId", String.valueOf(zoneId));
            sc.addAnd("zoneId", SearchCriteria.Op.SC, zoneSC);
        }

        final List<VpcOfferingJoinVO> offerings = vpcOfferingJoinDao.search(sc, searchFilter);

        // Remove offerings that are not associated with caller's domain or domainId passed
        if ((!Account.Type.ADMIN.equals(caller.getType()) || domainId != null) && CollectionUtils.isNotEmpty(offerings)) {
            ListIterator<VpcOfferingJoinVO> it = offerings.listIterator();
            while (it.hasNext()) {
                VpcOfferingJoinVO offering = it.next();
                if (org.apache.commons.lang3.StringUtils.isEmpty(offering.getDomainId())) {
                    continue;
                }
                if (!domainDao.domainIdListContainsAccessibleDomain(offering.getDomainId(), caller, domainId)) {
                    it.remove();
                }
            }
        }
        // filter by supported services
        final boolean listBySupportedServices = supportedServicesStr != null && !supportedServicesStr.isEmpty() && !offerings.isEmpty();

        if (listBySupportedServices) {
            final List<VpcOfferingJoinVO> supportedOfferings = new ArrayList<>();
            Service[] supportedServices = null;

            if (listBySupportedServices) {
                supportedServices = new Service[supportedServicesStr.size()];
                int i = 0;
                for (final String supportedServiceStr : supportedServicesStr) {
                    final Service service = Service.getService(supportedServiceStr);
                    if (service == null) {
                        throw new InvalidParameterValueException("Invalid service specified " + supportedServiceStr);
                    } else {
                        supportedServices[i] = service;
                    }
                    i++;
                }
            }

            for (final VpcOfferingJoinVO offering : offerings) {
                if (areServicesSupportedByVpcOffering(offering.getId(), supportedServices)) {
                    supportedOfferings.add(offering);
                }
            }

            final List<? extends VpcOffering> wPagination = StringUtils.applyPagination(supportedOfferings, startIndex, pageSizeVal);
            if (wPagination != null) {
                return new Pair<>(wPagination, supportedOfferings.size());
            }
            return new Pair<List<? extends VpcOffering>, Integer>(supportedOfferings, supportedOfferings.size());
        } else {
            final List<? extends VpcOffering> wPagination = StringUtils.applyPagination(offerings, startIndex, pageSizeVal);
            if (wPagination != null) {
                return new Pair<>(wPagination, offerings.size());
            }
            return new Pair<List<? extends VpcOffering>, Integer>(offerings, offerings.size());
        }
    }

    private void verifyDomainId(Long domainId, Account caller) {
        if (domainId == null) {
            return;
        }
        Domain domain = entityMgr.findById(Domain.class, domainId);
        if (domain == null) {
            throw new InvalidParameterValueException("Unable to find the domain by id=" + domainId);
        }
        if (!domainDao.isChildDomain(caller.getDomainId(), domainId)) {
            throw new InvalidParameterValueException(String.format("Unable to list VPC offerings for domain: %s as caller does not have access for it", domain.getUuid()));
        }
    }
}
