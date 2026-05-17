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
package com.cloud.api.query;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.InternalIdentity;
import org.apache.cloudstack.api.command.user.event.ListEventsCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.query.vo.EventJoinVO;
import com.cloud.event.Event;
import com.cloud.event.EventVO;
import com.cloud.event.dao.EventDao;
import com.cloud.event.dao.EventJoinDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;

/**
 * Implementation of the event listing helpers extracted from
 * {@link QueryManagerImpl}.
 *
 * @see EventQueryService
 */
@Component
public class EventQueryServiceImpl implements EventQueryService {

    @Inject
    private AccountManager accountMgr;
    @Inject
    private EntityManager entityManager;
    @Inject
    private EventDao eventDao;
    @Inject
    private EventJoinDao eventJoinDao;

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public AccountManager getAccountManager() {
        return accountMgr;
    }

    @Override
    public Pair<List<EventJoinVO>, Integer> searchForEventsInternal(ListEventsCmd cmd) {
        Pair<List<Long>, Integer> eventIdPage = searchForEventIdsAndCount(cmd);

        Integer count = eventIdPage.second();
        Long[] idArray = eventIdPage.first().toArray(new Long[0]);

        /**
         * Need to check array empty, because {@link com.cloud.utils.db.GenericDaoBase#searchAndCount(SearchCriteria, Filter, boolean)}
         * makes two calls: first to get objects and second to get count.
         * List events has start date filter, there is highly possible cause where no objects loaded
         * and next millisecond new event added and finally we ended up with count = 1 and no ids.
         */
        if (count == 0 || idArray.length < 1) {
            count = 0;
        }

        List<EventJoinVO> events = eventJoinDao.searchByIds(idArray);
        return new Pair<>(events, count);
    }

    protected Pair<List<Long>, Integer> searchForEventIdsAndCount(ListEventsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        boolean isRootAdmin = accountMgr.isRootAdmin(caller.getId());
        List<Long> permittedAccounts = new ArrayList<>();

        Long id = cmd.getId();
        String type = cmd.getType();
        String level = cmd.getLevel();
        Date startDate = cmd.getStartDate();
        Date endDate = cmd.getEndDate();
        String keyword = cmd.getKeyword();
        Integer entryTime = cmd.getEntryTime();
        Integer duration = cmd.getDuration();
        Long startId = cmd.getStartId();
        final String resourceUuid = getResourceUuid(cmd.getResourceId());
        final ApiCommandResourceType resourceType = getResourceType(cmd.getResourceType());
        final String stateStr = cmd.getState();
        Long resourceId = null;
        if (resourceUuid != null) {
            if (resourceType == null) {
                throw new InvalidParameterValueException(String.format("%s parameter must be used with %s parameter", ApiConstants.RESOURCE_ID, ApiConstants.RESOURCE_TYPE));
            }
            Object object = entityManager.findByUuidIncludingRemoved(resourceType.getAssociatedClass(), resourceUuid);
            if (object instanceof InternalIdentity) {
                resourceId = ((InternalIdentity)object).getId();
            }
            if (resourceId == null) {
                throw new InvalidParameterValueException(String.format("Invalid %s", ApiConstants.RESOURCE_ID));
            }
            if (!isRootAdmin && object instanceof ControlledEntity) {
                ControlledEntity entity = (ControlledEntity)object;
                accountMgr.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.ListEntry, entity.getAccountId() == caller.getId(), entity);
            }
        }
        Event.State state = null;
        if (StringUtils.isNotBlank(stateStr)) {
            state = EnumUtils.getEnum(Event.State.class, stateStr);
            if (state == null) {
                throw new InvalidParameterValueException(String.format("Invalid %s specified: %s", ApiConstants.STATE, stateStr));
            }
        }

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountMgr.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        Filter searchFilter = new Filter(EventVO.class, "createDate", false, cmd.getStartIndex(), cmd.getPageSizeVal());
        // additional order by since createdDate does not have milliseconds
        // and two events, created within one second can be incorrectly ordered (for example VM.CREATE Completed before Scheduled)
        searchFilter.addOrderBy(EventVO.class, "id", false);

        SearchBuilder<EventVO> eventSearchBuilder = eventDao.createSearchBuilder();
        eventSearchBuilder.select(null, Func.DISTINCT, eventSearchBuilder.entity().getId());
        accountMgr.buildACLSearchBuilder(eventSearchBuilder, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        eventSearchBuilder.and("id", eventSearchBuilder.entity().getId(), SearchCriteria.Op.EQ);
        eventSearchBuilder.and("levelL", eventSearchBuilder.entity().getLevel(), SearchCriteria.Op.LIKE);
        eventSearchBuilder.and("levelEQ", eventSearchBuilder.entity().getLevel(), SearchCriteria.Op.EQ);
        eventSearchBuilder.and("type", eventSearchBuilder.entity().getType(), SearchCriteria.Op.EQ);
        eventSearchBuilder.and("createDateB", eventSearchBuilder.entity().getCreateDate(), SearchCriteria.Op.BETWEEN);
        eventSearchBuilder.and("createDateG", eventSearchBuilder.entity().getCreateDate(), SearchCriteria.Op.GTEQ);
        eventSearchBuilder.and("createDateL", eventSearchBuilder.entity().getCreateDate(), SearchCriteria.Op.LTEQ);
        eventSearchBuilder.and("state", eventSearchBuilder.entity().getState(), SearchCriteria.Op.EQ);
        eventSearchBuilder.or("startId", eventSearchBuilder.entity().getStartId(), SearchCriteria.Op.EQ);
        eventSearchBuilder.and("createDate", eventSearchBuilder.entity().getCreateDate(), SearchCriteria.Op.BETWEEN);
        eventSearchBuilder.and("displayEvent", eventSearchBuilder.entity().isDisplay(), SearchCriteria.Op.EQ);
        eventSearchBuilder.and("archived", eventSearchBuilder.entity().getArchived(), SearchCriteria.Op.EQ);
        eventSearchBuilder.and("resourceId", eventSearchBuilder.entity().getResourceId(), SearchCriteria.Op.EQ);
        eventSearchBuilder.and("resourceType", eventSearchBuilder.entity().getResourceType(), SearchCriteria.Op.EQ);

        if (keyword != null) {
            eventSearchBuilder.and().op("keywordType", eventSearchBuilder.entity().getType(), SearchCriteria.Op.LIKE);
            eventSearchBuilder.or("keywordDescription", eventSearchBuilder.entity().getDescription(), SearchCriteria.Op.LIKE);
            eventSearchBuilder.or("keywordLevel", eventSearchBuilder.entity().getLevel(), SearchCriteria.Op.LIKE);
            eventSearchBuilder.cp();
        }

        SearchCriteria<EventVO> sc = eventSearchBuilder.create();
        // building ACL condition
        accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        // For end users display only enabled events
        if (!accountMgr.isRootAdmin(caller.getId())) {
            sc.setParameters("displayEvent", true);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (startId != null) {
            sc.setParameters("startId", startId);
            if (id == null) {
                sc.setParameters("id", startId);
            }
        }

        if (keyword != null) {
            sc.setParameters("keywordType", "%" + keyword + "%");
            sc.setParameters("keywordDescription", "%" + keyword + "%");
            sc.setParameters("keywordLevel", "%" + keyword + "%");
        }

        if (level != null) {
            sc.setParameters("levelEQ", level);
        }

        if (type != null) {
            sc.setParameters("type", type);
        }

        if (startDate != null && endDate != null) {
            sc.setParameters("createDateB", startDate, endDate);
        } else if (startDate != null) {
            sc.setParameters("createDateG", startDate);
        } else if (endDate != null) {
            sc.setParameters("createDateL", endDate);
        }

        if (resourceId != null) {
            sc.setParameters("resourceId", resourceId);
        }

        if (resourceType != null) {
            sc.setParameters("resourceType", resourceType.toString());
        }

        if (id == null) {
            sc.setParameters("archived", cmd.getArchived());
        }

        if (state != null) {
            sc.setParameters("state", state);
        }

        Pair<List<Long>, Integer> eventPair;
        // event_view will not have duplicate rows for each event, so
        // searchAndCount should be good enough.
        if ((entryTime != null) && (duration != null)) {
            // TODO: waiting for response from dev list, logic is mystery to
            // me!!
            /*
             * if (entryTime <= duration) { throw new
             * InvalidParameterValueException
             * ("Entry time must be greater than duration"); } Calendar calMin =
             * Calendar.getInstance(); Calendar calMax = Calendar.getInstance();
             * calMin.add(Calendar.SECOND, -entryTime);
             * calMax.add(Calendar.SECOND, -duration); Date minTime =
             * calMin.getTime(); Date maxTime = calMax.getTime();
             *
             * sc.setParameters("state", com.cloud.event.Event.State.Completed);
             * sc.setParameters("startId", 0); sc.setParameters("createDate",
             * minTime, maxTime); List<EventJoinVO> startedEvents =
             * eventJoinDao.searchAllEvents(sc, searchFilter);
             * List<EventJoinVO> pendingEvents = new ArrayList<EventJoinVO>();
             * for (EventVO event : startedEvents) { EventVO completedEvent =
             * eventDao.findCompletedEvent(event.getId()); if (completedEvent
             * == null) { pendingEvents.add(event); } } return pendingEvents;
             */
            eventPair = new Pair<>(new ArrayList<>(), 0);
        } else {
            Pair<List<EventVO>, Integer> uniqueEventPair = eventDao.searchAndCount(sc, searchFilter);
            Integer count = uniqueEventPair.second();
            List<Long> eventIds = uniqueEventPair.first().stream().map(EventVO::getId).collect(Collectors.toList());
            eventPair = new Pair<>(eventIds, count);
        }
        return eventPair;
    }
}
