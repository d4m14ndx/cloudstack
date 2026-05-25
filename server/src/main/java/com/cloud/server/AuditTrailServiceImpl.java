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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.resource.ArchiveAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.DeleteAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.ListAlertsCmd;
import org.apache.cloudstack.api.command.user.event.ArchiveEventsCmd;
import org.apache.cloudstack.api.command.user.event.DeleteEventsCmd;
import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.context.CallContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;


import com.cloud.alert.Alert;
import com.cloud.alert.AlertVO;
import com.cloud.alert.dao.AlertDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.EventTypes;
import com.cloud.event.EventVO;
import com.cloud.event.dao.EventDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchCriteria;

/**
 * @see AuditTrailService
 */
@Component
public class AuditTrailServiceImpl implements AuditTrailService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private EventDao eventDao;
    @Inject
    private AlertDao alertDao;
    @Inject
    private DomainDao domainDao;
    @Inject
    private AccountDao accountDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private AccountService accountService;

    @Override
    public boolean archiveEvents(final ArchiveEventsCmd cmd) {
        final Account caller = CallContext.current().getCallingAccount();
        final List<Long> ids = cmd.getIds();
        boolean result = true;
        List<Long> permittedAccountIds = new ArrayList<>();

        if (accountService.isNormalUser(caller.getId()) || caller.getType() == Account.Type.PROJECT) {
            permittedAccountIds.add(caller.getId());
        } else {
            final DomainVO domain = domainDao.findById(caller.getDomainId());
            final List<Long> permittedDomainIds = domainDao.getDomainChildrenIds(domain.getPath());
            permittedAccountIds = accountDao.getAccountIdsForDomains(permittedDomainIds);
        }

        final List<EventVO> events = eventDao.listToArchiveOrDeleteEvents(ids, cmd.getType(), cmd.getStartDate(), cmd.getEndDate(), permittedAccountIds);
        final ControlledEntity[] sameOwnerEvents = events.toArray(new ControlledEntity[events.size()]);
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, false, sameOwnerEvents);

        if (ids != null && events.size() < ids.size()) {
            return false;
        }
        eventDao.archiveEvents(events);
        return result;
    }

    @Override
    public boolean deleteEvents(final DeleteEventsCmd cmd) {
        final Account caller = CallContext.current().getCallingAccount();
        final List<Long> ids = cmd.getIds();
        boolean result = true;
        List<Long> permittedAccountIds = new ArrayList<>();

        if (accountManager.isNormalUser(caller.getId()) || caller.getType() == Account.Type.PROJECT) {
            permittedAccountIds.add(caller.getId());
        } else {
            final DomainVO domain = domainDao.findById(caller.getDomainId());
            final List<Long> permittedDomainIds = domainDao.getDomainChildrenIds(domain.getPath());
            permittedAccountIds = accountDao.getAccountIdsForDomains(permittedDomainIds);
        }

        final List<EventVO> events = eventDao.listToArchiveOrDeleteEvents(ids, cmd.getType(), cmd.getStartDate(), cmd.getEndDate(), permittedAccountIds);
        final ControlledEntity[] sameOwnerEvents = events.toArray(new ControlledEntity[events.size()]);
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, false, sameOwnerEvents);

        if (ids != null && events.size() < ids.size()) {
            return false;
        }
        for (final EventVO event : events) {
            eventDao.remove(event.getId());
        }
        return result;
    }

    @Override
    public Pair<List<? extends Alert>, Integer> searchForAlerts(final ListAlertsCmd cmd) {
        final Filter searchFilter = new Filter(AlertVO.class, "lastSent", false, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchCriteria<AlertVO> sc = alertDao.createSearchCriteria();

        final Object id = cmd.getId();
        final Object type = cmd.getType();
        final Object keyword = cmd.getKeyword();
        final Object name = cmd.getName();

        final Long zoneId = accountManager.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), null);
        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }
        if (zoneId != null) {
            sc.addAnd("data_center_id", SearchCriteria.Op.EQ, zoneId);
        }

        if (keyword != null) {
            final SearchCriteria<AlertVO> ssc = alertDao.createSearchCriteria();
            ssc.addOr("subject", SearchCriteria.Op.LIKE, "%" + keyword + "%");

            sc.addAnd("subject", SearchCriteria.Op.SC, ssc);
        }

        if (type != null) {
            sc.addAnd("type", SearchCriteria.Op.EQ, type);
        }

        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.EQ, name);
        }

        sc.addAnd("archived", SearchCriteria.Op.EQ, false);
        final Pair<List<AlertVO>, Integer> result = alertDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public boolean archiveAlerts(final ArchiveAlertsCmd cmd) {
        final Long zoneId = accountManager.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), null);
        return alertDao.archiveAlert(cmd.getIds(), cmd.getType(), cmd.getStartDate(), cmd.getEndDate(), zoneId);
    }

    @Override
    public boolean deleteAlerts(final DeleteAlertsCmd cmd) {
        final Long zoneId = accountManager.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), null);
        return alertDao.deleteAlert(cmd.getIds(), cmd.getType(), cmd.getStartDate(), cmd.getEndDate(), zoneId);
    }

    @Override
    public String[] listEventTypes() {
        final Object eventObj = new EventTypes();
        final Class<EventTypes> c = EventTypes.class;
        final Field[] fields = c.getFields();
        final String[] eventTypes = new String[fields.length];
        try {
            int i = 0;
            for (final Field field : fields) {
                eventTypes[i++] = field.get(eventObj).toString();
            }
            return eventTypes;
        } catch (final IllegalArgumentException | IllegalAccessException e) {
            logger.error("Error while listing Event Types", e);
        }
        return null;
    }
}
