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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.command.admin.guest.AddGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.admin.guest.AddGuestOsCmd;
import org.apache.cloudstack.api.command.admin.guest.AddGuestOsMappingCmd;
import org.apache.cloudstack.api.command.admin.guest.DeleteGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.admin.guest.GetHypervisorGuestOsNamesCmd;
import org.apache.cloudstack.api.command.admin.guest.ListGuestOsMappingCmd;
import org.apache.cloudstack.api.command.admin.guest.RemoveGuestOsCmd;
import org.apache.cloudstack.api.command.admin.guest.RemoveGuestOsMappingCmd;
import org.apache.cloudstack.api.command.admin.guest.UpdateGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.admin.guest.UpdateGuestOsCmd;
import org.apache.cloudstack.api.command.admin.guest.UpdateGuestOsMappingCmd;
import org.apache.cloudstack.api.command.user.guest.ListGuestOsCategoriesCmd;
import org.apache.cloudstack.api.command.user.guest.ListGuestOsCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.resourcedetail.dao.GuestOsDetailsDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.CheckGuestOsMappingAnswer;
import com.cloud.agent.api.CheckGuestOsMappingCommand;
import com.cloud.agent.api.GetHypervisorGuestOsNamesAnswer;
import com.cloud.agent.api.GetHypervisorGuestOsNamesCommand;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.MutualExclusiveIdsManagerBase;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.hypervisor.HypervisorCapabilitiesVO;
import com.cloud.storage.GuestOS;
import com.cloud.storage.GuestOSCategoryVO;
import com.cloud.storage.GuestOSHypervisor;
import com.cloud.storage.GuestOSHypervisorVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.GuestOsCategory;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.GuestOSHypervisorDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * @see GuestOsManagementService
 */
@Component
public class GuestOsManagementServiceImpl extends MutualExclusiveIdsManagerBase implements GuestOsManagementService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private GuestOSDao guestOSDao;
    @Inject
    private GuestOSCategoryDao guestOSCategoryDao;
    @Inject
    private GuestOSHypervisorDao guestOSHypervisorDao;
    @Inject
    private HypervisorCapabilitiesDao hypervisorCapabilitiesDao;
    @Inject
    private GuestOsDetailsDao guestOsDetailsDao;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private HostDao hostDao;
    @Inject
    private VMTemplateDao templateDao;

    @Override
    public Pair<List<? extends GuestOS>, Integer> listGuestOSByCriteria(final ListGuestOsCmd cmd) {
        List<Long> ids = getIdsListFromCmd(cmd.getId(), cmd.getIds());
        final Long osCategoryId = cmd.getOsCategoryId();
        final String description = cmd.getDescription();
        final String keyword = cmd.getKeyword();
        final Long startIndex = cmd.getStartIndex();
        final Long pageSize = cmd.getPageSizeVal();
        Boolean forDisplay = cmd.getDisplay();

        return guestOSDao.listGuestOSByCriteria(startIndex, pageSize, ids, osCategoryId, description, keyword, forDisplay);
    }

    @Override
    public Pair<List<? extends GuestOsCategory>, Integer> listGuestOSCategoriesByCriteria(final ListGuestOsCategoriesCmd cmd) {
        final Filter searchFilter = new Filter(GuestOSCategoryVO.class, "sortKey", true,
                cmd.getStartIndex(), cmd.getPageSizeVal());
        searchFilter.addOrderBy(GuestOSCategoryVO.class, "id", true);
        final Long id = cmd.getId();
        final String name = cmd.getName();
        final String keyword = cmd.getKeyword();
        final Boolean featured = cmd.isFeatured();
        final Boolean isIso = cmd.isIso();
        final Boolean isVnf = cmd.isVnf();
        final Long zoneId = cmd.getZoneId();
        final com.cloud.cpu.CPU.CPUArch arch = cmd.getArch();

        final SearchBuilder<GuestOSCategoryVO> sb = guestOSCategoryDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.LIKE);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);
        sb.and("featured", sb.entity().isFeatured(), SearchCriteria.Op.EQ);
        if (ObjectUtils.anyNotNull(zoneId, arch, isIso, isVnf)) {
            final SearchBuilder<GuestOSVO> guestOsSearch = guestOSDao.createSearchBuilder();
            guestOsSearch.and("ids", guestOsSearch.entity().getId(), SearchCriteria.Op.IN);
            sb.join("guestOsSearch", guestOsSearch, guestOsSearch.entity().getCategoryId(), sb.entity().getId(),
                    JoinBuilder.JoinType.INNER);
            guestOsSearch.done();
            sb.groupBy(sb.entity().getId());
        }
        sb.done();
        SearchCriteria<GuestOSCategoryVO> sc = sb.create();
        if (id != null) {
            sc.setParameters("id", id);
        }
        if (name != null) {
            sc.setParameters("name", "%" + name + "%");
        }
        if (keyword != null) {
            sc.setParameters("name", "%" + keyword + "%");
        }
        if (featured != null) {
            sc.setParameters("featured", featured);
        }
        if (ObjectUtils.anyNotNull(zoneId, arch, isIso, isVnf)) {
            List<Long> guestOsIds = templateDao.listTemplateIsoByArchVnfAndZone(zoneId, arch, isIso, isVnf);
            if (CollectionUtils.isEmpty(guestOsIds)) {
                return new Pair<>(Collections.emptyList(), 0);
            }
            sc.setJoinParameters("guestOsSearch", "ids", guestOsIds.toArray());
        }
        final Pair<List<GuestOSCategoryVO>, Integer> result = guestOSCategoryDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_CATEGORY_ADD, eventDescription = "adding OS category")
    public GuestOsCategory addGuestOsCategory(AddGuestOsCategoryCmd cmd) {
        final String name = cmd.getName();
        final boolean featured = cmd.isFeatured();
        final GuestOSCategoryVO guestOSCategory = new GuestOSCategoryVO(name, featured);
        GuestOsCategory guestOsCategory = guestOSCategoryDao.persist(guestOSCategory);
        CallContext.current().setEventResourceId(guestOsCategory.getId());
        CallContext.current().setEventResourceType(ApiCommandResourceType.GuestOsCategory);
        return guestOSCategory;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_CATEGORY_UPDATE, eventDescription = "updating OS category")
    public GuestOsCategory updateGuestOsCategory(UpdateGuestOsCategoryCmd cmd) {
        final long id = cmd.getId();
        final String name = cmd.getName();
        final Boolean featured = cmd.isFeatured();
        Integer sortKey = cmd.getSortKey();
        final GuestOSCategoryVO guestOSCategory = guestOSCategoryDao.findById(id);
        if (guestOSCategory == null) {
            throw new InvalidParameterValueException("Invalid OS category ID specified");
        }
        if (ObjectUtils.allNull(name, featured, sortKey)) {
            return guestOSCategory;
        }
        if (StringUtils.isNotBlank(name)) {
            guestOSCategory.setName(name);
        }
        if (featured != null) {
            guestOSCategory.setFeatured(featured);
        }
        if (sortKey != null) {
            guestOSCategory.setSortKey(sortKey);
        }
        if (!guestOSCategoryDao.update(id, guestOSCategory)) {
            return null;
        }
        return guestOSCategory;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_CATEGORY_DELETE, eventDescription = "deleting OS category")
    public boolean deleteGuestOsCategory(DeleteGuestOsCategoryCmd cmd) {
        final long id = cmd.getId();
        final GuestOSCategoryVO guestOSCategory = guestOSCategoryDao.findById(id);
        if (guestOSCategory == null) {
            throw new InvalidParameterValueException("Invalid OS category ID specified");
        }
        List<Long> guestOses = guestOSDao.listIdsByCategoryId(id);
        if (!guestOses.isEmpty()) {
            throw new InvalidParameterValueException(String.format(
                    "Unable to delete the OS category. %d guest OS exist for it.", guestOses.size()));
        }
        return guestOSCategoryDao.remove(id);
    }

    @Override
    public Pair<List<? extends GuestOSHypervisor>, Integer> listGuestOSMappingByCriteria(final ListGuestOsMappingCmd cmd) {
        final String guestOsId = "guestOsId";
        final Filter searchFilter = new Filter(GuestOSHypervisorVO.class, "hypervisorType", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        searchFilter.addOrderBy(GuestOSHypervisorVO.class, "hypervisorVersion", false);
        searchFilter.addOrderBy(GuestOSHypervisorVO.class, guestOsId, true);
        searchFilter.addOrderBy(GuestOSHypervisorVO.class, "created", false);
        final Long id = cmd.getId();
        final Long osTypeId = cmd.getOsTypeId();
        final String osDisplayName = cmd.getOsDisplayName();
        final String osNameForHypervisor = cmd.getOsNameForHypervisor();
        final String hypervisor = cmd.getHypervisor();
        final String hypervisorVersion = cmd.getHypervisorVersion();

        if (hypervisorVersion != null && (hypervisor == null || hypervisor.isEmpty())) {
            throw new InvalidParameterValueException("Hypervisor version parameter cannot be used without specifying a hypervisor : XenServer, KVM or VMware");
        }

        SearchBuilder<GuestOSHypervisorVO> sb = guestOSHypervisorDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("guestOsName", sb.entity().getGuestOsName(), SearchCriteria.Op.LIKE);
        sb.and("hypervisorType", sb.entity().getHypervisorType(), SearchCriteria.Op.LIKE);
        sb.and("hypervisorVersion", sb.entity().getHypervisorVersion(), SearchCriteria.Op.LIKE);
        sb.and(guestOsId, sb.entity().getGuestOsId(), SearchCriteria.Op.EQ);
        SearchBuilder<GuestOSVO> guestOSSearch = guestOSDao.createSearchBuilder();
        guestOSSearch.and("display", guestOSSearch.entity().isDisplay(), SearchCriteria.Op.LIKE);
        sb.join("guestOSSearch", guestOSSearch, sb.entity().getGuestOsId(), guestOSSearch.entity().getId(), JoinBuilder.JoinType.INNER);

        final SearchCriteria<GuestOSHypervisorVO> sc = sb.create();

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (osTypeId != null) {
            sc.setParameters(guestOsId, osTypeId);
        }

        if (osNameForHypervisor != null) {
            sc.setParameters("guestOsName", "%" + osNameForHypervisor + "%");
        }

        if (hypervisor != null) {
            sc.setParameters("hypervisorType", "%" + hypervisor + "%");
        }

        if (hypervisorVersion != null) {
            sc.setParameters("hypervisorVersion", "%" + hypervisorVersion + "%");
        }

        sc.setJoinParameters("guestOSSearch", "display", true);

        if (osDisplayName != null) {
            List<GuestOSVO> guestOSVOS = guestOSDao.listLikeDisplayName(osDisplayName);
            if (CollectionUtils.isNotEmpty(guestOSVOS)) {
                List<Long> guestOSids = guestOSVOS.stream().map(mo -> mo.getId()).collect(Collectors.toList());
                sc.addAnd(guestOsId, SearchCriteria.Op.IN, guestOSids.toArray());
            }
        }

        final Pair<List<GuestOSHypervisorVO>, Integer> result = guestOSHypervisorDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_MAPPING_ADD, eventDescription = "Adding new guest OS to hypervisor name mapping", create = true)
    public GuestOSHypervisor addGuestOsMapping(final AddGuestOsMappingCmd cmd) {
        final Long osTypeId = cmd.getOsTypeId();
        final String osStdName = cmd.getOsStdName();
        final String hypervisor = cmd.getHypervisor();
        final String hypervisorVersion = cmd.getHypervisorVersion();
        final String osNameForHypervisor = cmd.getOsNameForHypervisor();
        GuestOS guestOs = null;

        if (osTypeId == null && StringUtils.isEmpty(osStdName)) {
            throw new InvalidParameterValueException("Please specify either a guest OS name or UUID");
        }

        final HypervisorType hypervisorType = HypervisorType.getType(hypervisor);

        if (!(hypervisorType == HypervisorType.KVM || hypervisorType == HypervisorType.XenServer || hypervisorType == HypervisorType.VMware)) {
            throw new InvalidParameterValueException("Please specify a valid hypervisor : XenServer, KVM or VMware");
        }

        final HypervisorCapabilitiesVO hypervisorCapabilities = hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(hypervisorType, hypervisorVersion);
        if (hypervisorCapabilities == null) {
            throw new InvalidParameterValueException("Please specify a valid hypervisor and supported version");
        }

        if (osTypeId != null) {
            guestOs = ApiDBUtils.findGuestOSById(osTypeId);
        } else if (osStdName != null) {
            guestOs = ApiDBUtils.findGuestOSByDisplayName(osStdName);
        }

        if (guestOs == null) {
            throw new InvalidParameterValueException("Unable to find the guest OS by name or UUID");
        }

        final GuestOSHypervisorVO duplicate = guestOSHypervisorDao.findByOsIdAndHypervisorAndUserDefined(guestOs.getId(), hypervisorType.toString(), hypervisorVersion, true);

        if (duplicate != null) {
            if (!cmd.isForced()) {
                throw new InvalidParameterValueException(
                        "Mapping from hypervisor : " + hypervisorType + ", version : " + hypervisorVersion + " and guest OS : " + guestOs.getDisplayName() + " already exists!");
            }

            if (Boolean.TRUE.equals(cmd.getOsMappingCheckEnabled())) {
                checkGuestOSHypervisorMapping(hypervisorType, hypervisorVersion, guestOs.getDisplayName(), osNameForHypervisor);
            }

            final long guestOsId = duplicate.getId();
            final GuestOSHypervisorVO guestOsHypervisor = guestOSHypervisorDao.createForUpdate(guestOsId);
            guestOsHypervisor.setGuestOsName(osNameForHypervisor);
            if (guestOSHypervisorDao.update(guestOsId, guestOsHypervisor)) {
                return guestOSHypervisorDao.findById(guestOsId);
            }
            return null;
        }

        if (Boolean.TRUE.equals(cmd.getOsMappingCheckEnabled())) {
            checkGuestOSHypervisorMapping(hypervisorType, hypervisorVersion, guestOs.getDisplayName(), osNameForHypervisor);
        }

        final GuestOSHypervisorVO guestOsMapping = new GuestOSHypervisorVO();
        guestOsMapping.setGuestOsId(guestOs.getId());
        guestOsMapping.setGuestOsName(osNameForHypervisor);
        guestOsMapping.setHypervisorType(hypervisorType.toString());
        guestOsMapping.setHypervisorVersion(hypervisorVersion);
        guestOsMapping.setIsUserDefined(true);
        return guestOSHypervisorDao.persist(guestOsMapping);
    }

    private void checkGuestOSHypervisorMapping(HypervisorType hypervisorType, String hypervisorVersion, String guestOsName, String guestOsNameForHypervisor) {
        if (!canCheckGuestOsNameInHypervisor(hypervisorType)) {
            throw new InvalidParameterValueException(String.format("Guest OS mapping check is not supported for hypervisor: %s, please specify a valid hypervisor : VMware, XenServer", hypervisorType.toString()));
        }
        final HostVO host = hostDao.findHostByHypervisorTypeAndVersion(hypervisorType, hypervisorVersion);
        if (host == null) {
            throw new CloudRuntimeException(String.format("No %s hypervisor with version: %s exists, please specify available hypervisor and version", hypervisorType.toString(), hypervisorVersion));
        }
        CheckGuestOsMappingAnswer answer = (CheckGuestOsMappingAnswer) agentMgr.easySend(host.getId(), new CheckGuestOsMappingCommand(guestOsName, guestOsNameForHypervisor, hypervisorVersion));
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException(String.format("Invalid hypervisor os mapping: %s for guest os: %s, hypervisor: %s and version: %s", guestOsNameForHypervisor, guestOsName, hypervisorType.toString(), hypervisorVersion));
        }
    }

    private boolean canCheckGuestOsNameInHypervisor(HypervisorType hypervisorType) {
        return (hypervisorType == HypervisorType.VMware || hypervisorType == HypervisorType.XenServer);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_MAPPING_ADD, eventDescription = "Adding a new guest OS to hypervisor name mapping", async = true)
    public GuestOSHypervisor getAddedGuestOsMapping(final Long guestOsMappingId) {
        return getGuestOsHypervisor(guestOsMappingId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_HYPERVISOR_NAME_FETCH, eventDescription = "Getting guest OS names from hypervisor", async = true)
    public List<Pair<String, String>> getHypervisorGuestOsNames(GetHypervisorGuestOsNamesCmd getHypervisorGuestOsNamesCmd) {
        final HypervisorType hypervisorType = HypervisorType.getType(getHypervisorGuestOsNamesCmd.getHypervisor());
        if (!canCheckGuestOsNameInHypervisor(hypervisorType)) {
            throw new InvalidParameterValueException(String.format("Guest OS names cannot be fetched for hypervisor: %s, please specify a valid hypervisor : VMware, XenServer", hypervisorType.toString()));
        }

        final HostVO host = hostDao.findHostByHypervisorTypeAndVersion(hypervisorType, getHypervisorGuestOsNamesCmd.getHypervisorVersion());
        if (host == null) {
            throw new CloudRuntimeException(String.format("No %s hypervisor with version: %s exists, please specify available hypervisor and version", hypervisorType.toString(), getHypervisorGuestOsNamesCmd.getHypervisorVersion()));
        }
        GetHypervisorGuestOsNamesAnswer answer = (GetHypervisorGuestOsNamesAnswer) agentMgr.easySend(host.getId(), new GetHypervisorGuestOsNamesCommand(getHypervisorGuestOsNamesCmd.getKeyword()));
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException(String.format("Unable to get guest os names for hypervisor: %s, version: %s", hypervisorType.toString(), getHypervisorGuestOsNamesCmd.getHypervisorVersion()));
        }
        return answer.getHypervisorGuestOsNames();
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_ADD, eventDescription = "Adding new guest OS type", create = true)
    public GuestOS addGuestOs(final AddGuestOsCmd cmd) {
        final Long categoryId = cmd.getOsCategoryId();
        final String displayName = cmd.getOsDisplayName();
        final String name = cmd.getOsName();

        final GuestOSCategoryVO guestOsCategory = ApiDBUtils.findGuestOsCategoryById(categoryId);
        if (guestOsCategory == null) {
            throw new InvalidParameterValueException("Guest OS category not found. Please specify a valid Guest OS category");
        }

        final GuestOS guestOs = ApiDBUtils.findGuestOSByDisplayName(displayName);
        if (guestOs != null) {
            throw new InvalidParameterValueException("The specified Guest OS name : " + displayName + " already exists. Please specify a unique name");
        }

        logger.debug("GuestOSDetails");
        final GuestOSVO guestOsVo = new GuestOSVO();
        guestOsVo.setCategoryId(categoryId);
        guestOsVo.setDisplayName(displayName);
        guestOsVo.setName(name);
        guestOsVo.setIsUserDefined(true);
        guestOsVo.setDisplay(cmd.getForDisplay() == null ? true : cmd.getForDisplay());
        final GuestOS guestOsPersisted = guestOSDao.persist(guestOsVo);

        persistGuestOsDetails(cmd.getDetails(), guestOsPersisted.getId());

        return guestOsPersisted;
    }

    private void persistGuestOsDetails(Map<String, String> details, long guestOsPersistedId) {
        for (String key : details.keySet()) {
            guestOsDetailsDao.addDetail(guestOsPersistedId, key, details.get(key), false);
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_ADD, eventDescription = "Adding a new guest OS type", async = true)
    public GuestOS getAddedGuestOs(final Long guestOsId) {
        return getGuestOs(guestOsId);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_UPDATE, eventDescription = "updating guest OS type", async = true)
    public GuestOS updateGuestOs(final UpdateGuestOsCmd cmd) {
        final Long id = cmd.getId();
        final String displayName = cmd.getOsDisplayName();
        final Long osCategoryId = cmd.getOsCategoryId();
        final Boolean display = cmd.getForDisplay();
        final Map<String, String> details = cmd.getDetails();
        boolean updateNeeded = false;

        final GuestOS guestOsHandle = ApiDBUtils.findGuestOSById(id);
        if (guestOsHandle == null) {
            throw new InvalidParameterValueException("Guest OS not found. Please specify a valid ID for the Guest OS");
        }

        if (StringUtils.isNotBlank(displayName) && !displayName.equals(guestOsHandle.getDisplayName())) {
            final GuestOS duplicate = ApiDBUtils.findGuestOSByDisplayName(displayName);
            if (duplicate != null) {
                throw new InvalidParameterValueException("The specified Guest OS name : " + displayName + " already exists. Please specify a unique guest OS name");
            }
            updateNeeded = true;
        }

        if (osCategoryId != null) {
            if (guestOSCategoryDao.findById(osCategoryId) == null) {
                throw new InvalidParameterValueException("Invalid OS category ID specified");
            }
            updateNeeded = true;
        }

        if (!guestOsHandle.getIsUserDefined() && (StringUtils.isNotBlank(displayName) || MapUtils.isNotEmpty(details)
                || display != null)) {
            throw new InvalidParameterValueException("Unable to modify system defined guest OS");
        }

        if (MapUtils.isNotEmpty(details)) {
            persistGuestOsDetails(details, id);
        }

        if (!updateNeeded) {
            return guestOsHandle;
        }

        final GuestOSVO guestOs = guestOSDao.createForUpdate(id);
        if (StringUtils.isNotBlank(displayName)) {
            guestOs.setDisplayName(displayName);
        }
        if (cmd.getForDisplay() != null) {
            guestOs.setDisplay(cmd.getForDisplay());
        }
        if (osCategoryId != null) {
            guestOs.setCategoryId(osCategoryId);
        }
        if (guestOSDao.update(id, guestOs)) {
            return guestOSDao.findById(id);
        } else {
            return null;
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_REMOVE, eventDescription = "removing guest OS type", async = true)
    public boolean removeGuestOs(final RemoveGuestOsCmd cmd) {
        final Long id = cmd.getId();

        final GuestOS guestOs = ApiDBUtils.findGuestOSById(id);
        if (guestOs == null) {
            throw new InvalidParameterValueException("Guest OS not found. Please specify a valid ID for the Guest OS");
        }

        if (!guestOs.getIsUserDefined()) {
            throw new InvalidParameterValueException("Unable to remove system defined guest OS");
        }

        return guestOSDao.remove(id);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_MAPPING_UPDATE, eventDescription = "updating guest OS mapping", async = true)
    public GuestOSHypervisor updateGuestOsMapping(final UpdateGuestOsMappingCmd cmd) {
        final Long id = cmd.getId();
        final String osNameForHypervisor = cmd.getOsNameForHypervisor();

        final GuestOSHypervisor guestOsHypervisorHandle = guestOSHypervisorDao.findById(id);
        if (guestOsHypervisorHandle == null) {
            throw new InvalidParameterValueException("Guest OS Mapping not found. Please specify a valid ID for the Guest OS Mapping");
        }

        if (!guestOsHypervisorHandle.getIsUserDefined()) {
            throw new InvalidParameterValueException("Unable to modify system defined Guest OS mapping");
        }

        if (Boolean.TRUE.equals(cmd.getOsMappingCheckEnabled())) {
            GuestOS guestOs = ApiDBUtils.findGuestOSById(guestOsHypervisorHandle.getGuestOsId());
            if (guestOs == null) {
                throw new InvalidParameterValueException("Unable to find the guest OS for the mapping");
            }
            checkGuestOSHypervisorMapping(HypervisorType.getType(guestOsHypervisorHandle.getHypervisorType()), guestOsHypervisorHandle.getHypervisorVersion(), guestOs.getDisplayName(), osNameForHypervisor);
        }

        final GuestOSHypervisorVO guestOsHypervisor = guestOSHypervisorDao.createForUpdate(id);
        guestOsHypervisor.setGuestOsName(osNameForHypervisor);
        if (guestOSHypervisorDao.update(id, guestOsHypervisor)) {
            return guestOSHypervisorDao.findById(id);
        } else {
            return null;
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_MAPPING_REMOVE, eventDescription = "removing guest OS mapping", async = true)
    public boolean removeGuestOsMapping(final RemoveGuestOsMappingCmd cmd) {
        final Long id = cmd.getId();

        final GuestOSHypervisor guestOsHypervisorHandle = guestOSHypervisorDao.findById(id);
        if (guestOsHypervisorHandle == null) {
            throw new InvalidParameterValueException("Guest OS Mapping not found. Please specify a valid ID for the Guest OS Mapping");
        }

        if (!guestOsHypervisorHandle.getIsUserDefined()) {
            throw new InvalidParameterValueException("Unable to remove system defined Guest OS mapping");
        }

        return guestOSHypervisorDao.removeGuestOsMapping(id);
    }

    @Override
    public GuestOSVO getGuestOs(final Long guestOsId) {
        return guestOSDao.findById(guestOsId);
    }

    @Override
    public GuestOSHypervisorVO getGuestOsHypervisor(final Long guestOsHypervisorId) {
        return guestOSHypervisorDao.findById(guestOsHypervisorId);
    }
}
