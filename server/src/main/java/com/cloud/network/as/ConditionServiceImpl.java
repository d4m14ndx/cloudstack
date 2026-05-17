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
package com.cloud.network.as;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.BaseListProjectAndAccountResourcesCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateConditionCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListConditionsCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateConditionCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceInUseException;
import com.cloud.network.as.dao.AutoScalePolicyConditionMapDao;
import com.cloud.network.as.dao.AutoScaleVmGroupDao;
import com.cloud.network.as.dao.AutoScaleVmGroupPolicyMapDao;
import com.cloud.network.as.dao.AutoScaleVmGroupStatisticsDao;
import com.cloud.network.as.dao.ConditionDao;
import com.cloud.network.as.dao.CounterDao;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDao;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;

/**
 * AutoScale condition CRUD — extracted from
 * {@link AutoScaleManagerImpl}.
 *
 * @see ConditionService
 */
@Component
public class ConditionServiceImpl implements ConditionService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private AccountManager accountMgr;
    @Inject
    private CounterDao counterDao;
    @Inject
    private ConditionDao conditionDao;
    @Inject
    private AutoScalePolicyConditionMapDao autoScalePolicyConditionMapDao;
    @Inject
    private AutoScaleVmGroupDao autoScaleVmGroupDao;
    @Inject
    private AutoScaleVmGroupPolicyMapDao autoScaleVmGroupPolicyMapDao;
    @Inject
    private AutoScaleVmGroupStatisticsDao asGroupStatisticsDao;

    @Override
    public Condition createCondition(CreateConditionCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Account owner = accountMgr.finalizeOwner(caller, cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
        accountMgr.checkAccess(caller, null, true, owner);

        String opr = cmd.getRelationalOperator().toUpperCase();
        long cid = cmd.getCounterId();
        long threshold = cmd.getThreshold();
        Condition.Operator op;
        // Validate Relational Operator
        try {
            op = Condition.Operator.valueOf(opr);
        } catch (IllegalArgumentException ex) {
            throw new InvalidParameterValueException("The Operator " + opr + " does not exist; Unable to create Condition.");
        }
        if (threshold < 0) {
            throw new InvalidParameterValueException("The threshold " + threshold + " must be equal to or greater than 0.");
        }

        CounterVO counter = counterDao.findById(cid);

        if (counter == null) {
            throw new InvalidParameterValueException("Unable to find counter");
        }
        ConditionVO condition = null;

        condition = conditionDao.persist(new ConditionVO(cid, threshold, owner.getAccountId(), owner.getDomainId(), op));
        logger.info("Successfully created condition: {}", condition);

        CallContext.current().setEventDetails(" ID: " + condition.getUuid());
        return condition;
    }

    @Override
    public List<? extends Condition> listConditions(ListConditionsCmd cmd) {
        Long id = cmd.getId();
        Long counterId = cmd.getCounterId();
        Long policyId = cmd.getPolicyId();
        SearchWrapper<ConditionVO> searchWrapper = new SearchWrapper<>(conditionDao, ConditionVO.class, cmd, cmd.getId());
        SearchBuilder<ConditionVO> sb = searchWrapper.getSearchBuilder();
        if (policyId != null) {
            SearchBuilder<AutoScalePolicyConditionMapVO> asPolicyConditionSearch = autoScalePolicyConditionMapDao.createSearchBuilder();
            asPolicyConditionSearch.and("policyId", asPolicyConditionSearch.entity().getPolicyId(), SearchCriteria.Op.EQ);
            sb.join("asPolicyConditionSearch", asPolicyConditionSearch, sb.entity().getId(), asPolicyConditionSearch.entity().getConditionId(),
                JoinBuilder.JoinType.INNER);
        }

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("counterId", sb.entity().getCounterId(), SearchCriteria.Op.EQ);

        // now set the SC criteria...
        SearchCriteria<ConditionVO> sc = searchWrapper.buildSearchCriteria();

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (counterId != null) {
            sc.setParameters("counterId", counterId);
        }

        if (policyId != null) {
            sc.setJoinParameters("asPolicyConditionSearch", "policyId", policyId);
        }

        return searchWrapper.search();
    }

    @Override
    public boolean deleteCondition(long conditionId) throws ResourceInUseException {
        /* Check if entity is in database */
        ConditionVO condition = getEntityInDatabase(CallContext.current().getCallingAccount(), "Condition", conditionId, conditionDao);
        if (condition == null) {
            throw new InvalidParameterValueException("Unable to find Condition");
        }

        // Verify if condition is used in any autoscale policy
        if (autoScalePolicyConditionMapDao.isConditionInUse(conditionId)) {
            logger.info("Cannot delete condition {} as it is being used in a condition.", condition);
            throw new ResourceInUseException("Cannot delete Condition when it is in use by one or more AutoScale Policies.");
        }
        boolean success = conditionDao.remove(conditionId);
        if (success) {
            logger.info("Successfully deleted condition {}", condition);
        }
        return success;
    }

    @Override
    public Condition updateCondition(UpdateConditionCmd cmd) throws ResourceInUseException {
        Long conditionId = cmd.getId();
        /* Check if entity is in database */
        ConditionVO condition = getEntityInDatabase(CallContext.current().getCallingAccount(), "Condition", conditionId, conditionDao);

        String operator = cmd.getRelationalOperator().toUpperCase();
        Long threshold = cmd.getThreshold();

        Condition.Operator op;
        // Validate Relational Operator
        try {
            op = Condition.Operator.valueOf(operator);
        } catch (IllegalArgumentException ex) {
            throw new InvalidParameterValueException("The Operator " + operator + " does not exist; Unable to update Condition.");
        }
        if (threshold < 0) {
            throw new InvalidParameterValueException("The threshold " + threshold + " must be equal to or greater than 0.");
        }

        // Verify if condition is used in any autoscale vmgroup
        GenericSearchBuilder<AutoScalePolicyConditionMapVO, Long> conditionSearch = autoScalePolicyConditionMapDao.createSearchBuilder(Long.class);
        conditionSearch.selectFields(conditionSearch.entity().getPolicyId());
        conditionSearch.and("conditionId", conditionSearch.entity().getConditionId(), Op.EQ);
        SearchCriteria<Long> sc = conditionSearch.create();
        sc.setParameters("conditionId", conditionId);
        List<Long> policyIds = autoScalePolicyConditionMapDao.customSearch(sc, null);

        if (CollectionUtils.isNotEmpty(policyIds)) {
            SearchBuilder<AutoScaleVmGroupPolicyMapVO> policySearch = autoScaleVmGroupPolicyMapDao.createSearchBuilder();
            policySearch.and("policyId", policySearch.entity().getPolicyId(), Op.IN);
            SearchBuilder<AutoScaleVmGroupVO> vmGroupSearch = autoScaleVmGroupDao.createSearchBuilder();
            vmGroupSearch.and("stateNEQ", vmGroupSearch.entity().getState(), Op.NEQ);
            vmGroupSearch.join("policySearch", policySearch, vmGroupSearch.entity().getId(), policySearch.entity().getVmGroupId(), JoinBuilder.JoinType.INNER);
            vmGroupSearch.done();

            SearchCriteria<AutoScaleVmGroupVO> sc2 = vmGroupSearch.create();
            sc2.setParameters("stateNEQ", AutoScaleVmGroup.State.DISABLED);
            sc2.setJoinParameters("policySearch", "policyId", policyIds.toArray((new Object[policyIds.size()])));
            List<AutoScaleVmGroupVO> groups = autoScaleVmGroupDao.search(sc2, null);
            if (CollectionUtils.isNotEmpty(groups)) {
                String msg = String.format("Cannot update condition %s as it is being used in %d vm groups NOT in Disabled state.", condition, groups.size());
                logger.info(msg);
                throw new ResourceInUseException(msg);
            }
        }

        condition.setRelationalOperator(op);
        condition.setThreshold(threshold);
        boolean success = conditionDao.update(conditionId, condition);
        if (success) {
            logger.info("Successfully updated condition {}", condition);

            for (Long policyId : policyIds) {
                markStatisticsAsInactive(null, policyId);
            }
        }
        return condition;
    }

    private <VO extends ControlledEntity> VO getEntityInDatabase(Account caller, String paramName, Long id, GenericDao<VO, Long> dao) {

        VO vo = dao.findById(id);

        if (vo == null) {
            throw new InvalidParameterValueException("Unable to find " + paramName);
        }

        accountMgr.checkAccess(caller, null, false, (ControlledEntity)vo);

        return vo;
    }

    private void markStatisticsAsInactive(Long groupId, Long policyId) {
        asGroupStatisticsDao.updateStateByGroup(groupId, policyId, AutoScaleVmGroupStatisticsVO.State.INACTIVE);
    }

    /**
     * Local copy of the ACL-aware search helper used by
     * {@link AutoScaleManagerImpl#listConditions} (and its sibling list
     * APIs). Duplicating the inner class keeps the slice self-contained
     * without exposing the helper or its dependencies on the
     * {@link AccountManager} as public API surface.
     */
    private class SearchWrapper<VO extends ControlledEntity> {
        GenericDao<VO, Long> dao;
        SearchBuilder<VO> searchBuilder;
        SearchCriteria<VO> searchCriteria;
        Long domainId;
        boolean isRecursive;
        List<Long> permittedAccounts = new ArrayList<Long>();
        ListProjectResourcesCriteria listProjectResourcesCriteria;
        Filter searchFilter;

        public SearchWrapper(GenericDao<VO, Long> dao, Class<VO> entityClass, BaseListProjectAndAccountResourcesCmd cmd, Long id)
        {
            this.dao = dao;
            this.searchBuilder = dao.createSearchBuilder();
            domainId = cmd.getDomainId();
            String accountName = cmd.getAccountName();
            isRecursive = cmd.isRecursive();
            boolean listAll = cmd.listAll();
            long startIndex = cmd.getStartIndex();
            long pageSizeVal = cmd.getPageSizeVal();
            Account caller = CallContext.current().getCallingAccount();

            Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<Long, Boolean,
                    ListProjectResourcesCriteria>(domainId, isRecursive, null);
            accountMgr.buildACLSearchParameters(caller, id, accountName, cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject,
                    listAll, false);
            domainId = domainIdRecursiveListProject.first();
            isRecursive = domainIdRecursiveListProject.second();
            listProjectResourcesCriteria = domainIdRecursiveListProject.third();

            accountMgr.buildACLSearchBuilder(searchBuilder, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
            searchFilter = new Filter(entityClass, "id", false, startIndex, pageSizeVal);
        }

        public SearchBuilder<VO> getSearchBuilder() {
            return searchBuilder;
        }

        public SearchCriteria<VO> buildSearchCriteria() {
            searchCriteria = searchBuilder.create();
            accountMgr.buildACLSearchCriteria(searchCriteria, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
            return searchCriteria;
        }

        public List<VO> search() {
            return dao.search(searchCriteria, searchFilter);
        }
    }
}
