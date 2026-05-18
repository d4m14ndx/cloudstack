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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.command.admin.iso.ListIsosCmdByAdmin;
import org.apache.cloudstack.api.command.admin.resource.icon.ListResourceIconCmd;
import org.apache.cloudstack.api.command.admin.template.ListTemplatesCmdByAdmin;
import org.apache.cloudstack.api.command.user.iso.ListIsosCmd;
import org.apache.cloudstack.api.command.user.template.ListTemplatesCmd;
import org.apache.cloudstack.api.command.user.template.ListVnfTemplatesCmd;
import org.apache.cloudstack.api.command.user.resource.ListDetailOptionsCmd;
import org.apache.cloudstack.api.response.DetailOptionsResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ResourceIconResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateState;
import org.apache.cloudstack.query.QueryService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.api.query.vo.BaseViewWithTagInformationVO;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.cpu.CPU;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.VNF;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.icon.dao.ResourceIconDao;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.ScopeType;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.template.VirtualMachineTemplate.State;
import com.cloud.template.VirtualMachineTemplate.TemplateFilter;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Implementation of template and ISO query helpers extracted from
 * {@link QueryManagerImpl} as part of the Phase 4 Spring-component
 * decomposition (slice 8).
 */
@Component
public class TemplateQueryServiceImpl implements TemplateQueryService {

    protected static final Logger logger = LogManager.getLogger(TemplateQueryServiceImpl.class);

    @Inject
    protected AccountManager accountMgr;

    @Inject
    protected ResourceManager _resourceMgr;

    @Inject
    protected TemplateJoinDao _templateJoinDao;

    @Inject
    protected VMTemplateDao _templateDao;

    @Inject
    protected DomainDao _domainDao;

    @Inject
    protected GuestOSDao guestOSDao;

    @Inject
    protected ResourceIconDao resourceIconDao;

    @Inject
    protected VMTemplatePoolDao templatePoolDao;

    @Inject
    protected VMInstanceDao _vmInstanceDao;

    @Override
    public ListResponse<TemplateResponse> listTemplates(ListTemplatesCmd cmd) {
        Pair<List<TemplateJoinVO>, Integer> result = searchForTemplatesInternal(cmd);
        ListResponse<TemplateResponse> response = new ListResponse<>();

        ResponseView respView = ResponseView.Restricted;
        if (cmd instanceof ListTemplatesCmdByAdmin) {
            respView = ResponseView.Full;
        }

        List<TemplateResponse> templateResponses = ViewResponseHelper.createTemplateResponse(cmd.getDetails(), respView, result.first().toArray(new TemplateJoinVO[0]));
        response.setResponses(templateResponses, result.second());
        return response;
    }

    private Pair<List<TemplateJoinVO>, Integer> searchForTemplatesInternal(ListTemplatesCmd cmd) {
        TemplateFilter templateFilter = TemplateFilter.valueOf(cmd.getTemplateFilter());
        Long id = cmd.getId();
        Map<String, String> tags = cmd.getTags();
        boolean showRemovedTmpl = cmd.getShowRemoved();
        Account caller = CallContext.current().getCallingAccount();
        Long parentTemplateId = cmd.getParentTemplateId();
        Long domainId = cmd.getDomainId();
        boolean isRecursive = cmd.isRecursive();

        boolean listAll = false;
        if (templateFilter != null && templateFilter == TemplateFilter.all) {
            if (caller.getType() == Account.Type.NORMAL) {
                throw new InvalidParameterValueException("Filter " + TemplateFilter.all + " can be specified by admin only");
            }
            listAll = true;
        }

        List<Long> permittedAccountIds = new ArrayList<>();
        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(domainId, isRecursive, null);
        accountMgr.buildACLSearchParameters(
                caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccountIds,
                domainIdRecursiveListProject, listAll, false
        );
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        List<Account> permittedAccounts = new ArrayList<>();
        for (Long accountId : permittedAccountIds) {
            permittedAccounts.add(accountMgr.getAccount(accountId));
        }

        boolean showDomr = ((templateFilter != TemplateFilter.selfexecutable) && (templateFilter != TemplateFilter.featured));
        HypervisorType hypervisorType = HypervisorType.getType(cmd.getHypervisor());

        String templateType = cmd.getTemplateType();
        if (cmd instanceof ListVnfTemplatesCmd) {
            if (templateType == null) {
                templateType = com.cloud.storage.Storage.TemplateType.VNF.name();
            } else if (!com.cloud.storage.Storage.TemplateType.VNF.name().equals(templateType)) {
                throw new InvalidParameterValueException("Template type must be VNF when list VNF templates");
            }
        }
        Boolean isVnf = cmd.getVnf();
        Boolean forCks = cmd.getForCks();

        return searchForTemplatesInternal(id, cmd.getTemplateName(), cmd.getKeyword(), templateFilter, false,
                null, cmd.getPageSizeVal(), cmd.getStartIndex(), cmd.getZoneId(), cmd.getStoragePoolId(),
                cmd.getImageStoreId(), hypervisorType, showDomr, cmd.listInReadyState(), permittedAccounts, caller,
                listProjectResourcesCriteria, tags, showRemovedTmpl, cmd.getIds(), parentTemplateId, cmd.getShowUnique(),
                templateType, isVnf, domainId, isRecursive, cmd.getArch(), cmd.getOsCategoryId(), forCks, cmd.getExtensionId());
    }

    private Pair<List<TemplateJoinVO>, Integer> searchForTemplatesInternal(Long templateId, String name, String keyword,
            TemplateFilter templateFilter, boolean isIso, Boolean bootable, Long pageSize,
            Long startIndex, Long zoneId, Long storagePoolId, Long imageStoreId, HypervisorType hyperType,
            boolean showDomr, boolean onlyReady, List<Account> permittedAccounts, Account caller,
            ListProjectResourcesCriteria listProjectResourcesCriteria, Map<String, String> tags,
            boolean showRemovedTmpl, List<Long> ids, Long parentTemplateId, Boolean showUnique, String templateType,
            Boolean isVnf, Long domainId, boolean isRecursive, CPU.CPUArch arch, Long osCategoryId, Boolean forCks, Long extensionId) {

        // check if zone is configured, if not, just return empty list
        List<HypervisorType> hypers = null;
        if (!isIso) {
            hypers = _resourceMgr.listAvailHypervisorInZone(null);
            hypers.add(HypervisorType.External);
        }

        VMTemplateVO template;

        Filter searchFilter = new Filter(TemplateJoinVO.class, "sortKey", QueryService.SortKeyAscending.value(), startIndex, pageSize);
        searchFilter.addOrderBy(TemplateJoinVO.class, "tempZonePair", QueryService.SortKeyAscending.value());

        SearchBuilder<TemplateJoinVO> sb = _templateJoinDao.createSearchBuilder();
        if (showUnique) {
            sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct templateId
        } else {
            sb.select(null, Func.DISTINCT, sb.entity().getTempZonePair()); // select distinct (templateId, zoneId) pair
        }
        if (ids != null && !ids.isEmpty()) {
            sb.and("idIN", sb.entity().getId(), SearchCriteria.Op.IN);
        }

        if (storagePoolId != null) {
            SearchBuilder<VMTemplateStoragePoolVO> storagePoolSb = templatePoolDao.createSearchBuilder();
            sb.join("storagePool", storagePoolSb, storagePoolSb.entity().getTemplateId(), sb.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        if (osCategoryId != null) {
            sb.and("guestOsIdIN", sb.entity().getGuestOSId(), Op.IN);
        }

        if (extensionId != null) {
            sb.and("extensionId", sb.entity().getExtensionId(), Op.IN);
        }

        SearchCriteria<TemplateJoinVO> sc = sb.create();

        if (imageStoreId != null) {
            sc.addAnd("dataStoreId", SearchCriteria.Op.EQ, imageStoreId);
        }

        if (arch != null) {
            sc.addAnd("arch", SearchCriteria.Op.EQ, arch);
        }

        if (storagePoolId != null) {
            sc.setJoinParameters("storagePool", "pool_id", storagePoolId);
        }

        if (osCategoryId != null) {
            List<Long> guestOsIds = guestOSDao.listIdsByCategoryId(osCategoryId);
            if (org.apache.commons.collections.CollectionUtils.isNotEmpty(guestOsIds)) {
                sc.setParameters("guestOsIdIN", guestOsIds.toArray());
            } else {
                return new Pair<>(new ArrayList<>(), 0);
            }
        }

        if (extensionId != null) {
            sc.setParameters("extensionId", extensionId);
        }

        // verify templateId parameter and specially handle it
        if (templateId != null) {
            template = _templateDao.findByIdIncludingRemoved(templateId); // Done for backward compatibility - Bug-5221
            if (template == null) {
                throw new InvalidParameterValueException("Please specify a valid template ID.");
            }// If ISO requested then it should be ISO.
            if (isIso && template.getFormat() != ImageFormat.ISO) {
                logger.error("Template {} is not an ISO", template);
                InvalidParameterValueException ex = new InvalidParameterValueException("Specified Template Id is not an ISO");
                ex.addProxyObject(template.getUuid(), "templateId");
                throw ex;
            }// If ISO not requested then it shouldn't be an ISO.
            if (!isIso && template.getFormat() == ImageFormat.ISO) {
                logger.error("Incorrect format of the template: {}", template);
                InvalidParameterValueException ex = new InvalidParameterValueException("Incorrect format " + template.getFormat() + " of the specified template id");
                ex.addProxyObject(template.getUuid(), "templateId");
                throw ex;
            }

            if (!template.isPublicTemplate() && caller.getType() == Account.Type.DOMAIN_ADMIN) {
                Account template_acc = accountMgr.getAccount(template.getAccountId());
                DomainVO domain = _domainDao.findById(template_acc.getDomainId());
                accountMgr.checkAccess(caller, domain);
            } else if (template.isPublicTemplate() || caller.getType() != Account.Type.ADMIN) {
                // if template is not public or non-admin caller, perform permission check here
                accountMgr.checkAccess(caller, null, false, template);
            }

            // if templateId is specified, then we will just use the id to
            // search and ignore other query parameters
            sc.addAnd("id", SearchCriteria.Op.EQ, templateId);
        } else {

            DomainVO domain;
            if (!permittedAccounts.isEmpty()) {
                domain = _domainDao.findById(permittedAccounts.get(0).getDomainId());
            } else {
                domain = _domainDao.findById(Objects.requireNonNullElse(domainId, caller.getDomainId()));
            }

            setIdsListToSearchCriteria(sc, ids);

            // add criteria for project or not
            if (listProjectResourcesCriteria == ListProjectResourcesCriteria.SkipProjectResources) {
                sc.addAnd("accountType", SearchCriteria.Op.NEQ, Account.Type.PROJECT);
            } else if (listProjectResourcesCriteria == ListProjectResourcesCriteria.ListProjectResourcesOnly) {
                sc.addAnd("accountType", SearchCriteria.Op.EQ, Account.Type.PROJECT);
            }

            // add criteria for domain path in case of admins
            if ((templateFilter == TemplateFilter.self || templateFilter == TemplateFilter.selfexecutable)
                    && (accountMgr.isAdmin(caller.getAccountId()))) {
                if (isRecursive) {
                    sc.addAnd("domainPath", SearchCriteria.Op.LIKE, domain.getPath() + "%");
                } else {
                    sc.addAnd("domainPath", SearchCriteria.Op.EQ, domain.getPath());
                }
            }

            List<Long> relatedDomainIds = new ArrayList<>();
            List<Long> permittedAccountIds = new ArrayList<>();
            if (!permittedAccounts.isEmpty()) {
                for (Account account : permittedAccounts) {
                    permittedAccountIds.add(account.getId());
                    boolean publicTemplates = (templateFilter == TemplateFilter.featured || templateFilter == TemplateFilter.community);

                    // get all parent domain ID's all the way till root domain
                    DomainVO domainTreeNode;
                    //if template filter is featured, or community, all child domains should be included in search
                    if (publicTemplates) {
                        domainTreeNode = _domainDao.findById(Domain.ROOT_DOMAIN);

                    } else {
                        domainTreeNode = _domainDao.findById(account.getDomainId());
                    }
                    relatedDomainIds.add(domainTreeNode.getId());
                    while (domainTreeNode.getParent() != null) {
                        domainTreeNode = _domainDao.findById(domainTreeNode.getParent());
                        relatedDomainIds.add(domainTreeNode.getId());
                    }

                    // get all child domain ID's
                    if (accountMgr.isAdmin(account.getId()) || publicTemplates) {
                        List<DomainVO> allChildDomains = _domainDao.findAllChildren(domainTreeNode.getPath(), domainTreeNode.getId());
                        for (DomainVO childDomain : allChildDomains) {
                            relatedDomainIds.add(childDomain.getId());
                        }
                    }
                }
            }

            // control different template filters
            if (templateFilter == TemplateFilter.featured || templateFilter == TemplateFilter.community) {
                sc.addAnd("publicTemplate", SearchCriteria.Op.EQ, true);
                if (templateFilter == TemplateFilter.featured) {
                    sc.addAnd("featured", SearchCriteria.Op.EQ, true);
                } else {
                    sc.addAnd("featured", SearchCriteria.Op.EQ, false);
                }
                if (!permittedAccounts.isEmpty()) {
                    SearchCriteria<TemplateJoinVO> scc = _templateJoinDao.createSearchCriteria();
                    scc.addOr("domainId", SearchCriteria.Op.IN, relatedDomainIds.toArray());
                    scc.addOr("domainId", SearchCriteria.Op.NULL);
                    sc.addAnd("domainId", SearchCriteria.Op.SC, scc);
                }
            } else if (templateFilter == TemplateFilter.self || templateFilter == TemplateFilter.selfexecutable) {
                if (!permittedAccounts.isEmpty()) {
                    sc.addAnd("accountId", SearchCriteria.Op.IN, permittedAccountIds.toArray());
                }
            } else if (templateFilter == TemplateFilter.sharedexecutable || templateFilter == TemplateFilter.shared) {
                // only show templates shared by others
                if (permittedAccounts.isEmpty()) {
                    return new Pair<>(new ArrayList<>(), 0);
                }
                sc.addAnd("sharedAccountId", SearchCriteria.Op.IN, permittedAccountIds.toArray());
            } else if (templateFilter == TemplateFilter.executable) {
                SearchCriteria<TemplateJoinVO> scc = _templateJoinDao.createSearchCriteria();
                scc.addOr("publicTemplate", SearchCriteria.Op.EQ, true);
                if (!permittedAccounts.isEmpty()) {
                    scc.addOr("accountId", SearchCriteria.Op.IN, permittedAccountIds.toArray());
                }
                sc.addAnd("publicTemplate", SearchCriteria.Op.SC, scc);
            } else if (templateFilter == TemplateFilter.all && caller.getType() != Account.Type.ADMIN) {
                SearchCriteria<TemplateJoinVO> scc = _templateJoinDao.createSearchCriteria();
                scc.addOr("publicTemplate", SearchCriteria.Op.EQ, true);

                if (listProjectResourcesCriteria == ListProjectResourcesCriteria.SkipProjectResources) {
                    scc.addOr("domainPath", SearchCriteria.Op.LIKE, _domainDao.findById(caller.getDomainId()).getPath() + "%");
                } else {
                    if (!permittedAccounts.isEmpty()) {
                        scc.addOr("accountId", SearchCriteria.Op.IN, permittedAccountIds.toArray());
                        scc.addOr("sharedAccountId", SearchCriteria.Op.IN, permittedAccountIds.toArray());
                    }
                }
                sc.addAnd("publicTemplate", SearchCriteria.Op.SC, scc);
            }
        }

        applyPublicTemplateSharingRestrictions(sc, caller);

        return templateChecks(isIso, hypers, tags, name, keyword, hyperType, onlyReady, bootable, zoneId, showDomr, caller,
                showRemovedTmpl, parentTemplateId, showUnique, templateType, isVnf, forCks, searchFilter, sc);
    }

    /**
     * If the caller is not a root admin, restricts the search to return only public templates from the domain which
     * the caller belongs to and domains with the setting 'share.public.templates.with.other.domains' enabled.
     */
    @Override
    public void applyPublicTemplateSharingRestrictions(SearchCriteria<TemplateJoinVO> sc, Account caller) {
        if (caller.getType() == Account.Type.ADMIN) {
            logger.debug(String.format("Account [%s] is a root admin. Therefore, it has access to all public templates.", caller));
            return;
        }

        List<TemplateJoinVO> publicTemplates = _templateJoinDao.listPublicTemplates();

        Set<Long> unsharableDomainIds = new HashSet<>();
        for (TemplateJoinVO template : publicTemplates) {
            addDomainIdToSetIfDomainDoesNotShareTemplates(template.getDomainId(), caller, unsharableDomainIds);
        }

        if (!unsharableDomainIds.isEmpty()) {
            logger.info(String.format("The public templates belonging to the domains [%s] will not be listed to account [%s] as they have the configuration [%s] marked as 'false'.", unsharableDomainIds, caller, QueryService.SharePublicTemplatesWithOtherDomains.key()));
            sc.addAnd("domainId", SearchCriteria.Op.NOTIN, unsharableDomainIds.toArray());
        }
    }

    /**
     * Adds the provided domain ID to the set if the domain does not share templates with the account. That is, if:
     * (1) the template does not belong to the domain of the account AND
     * (2) the domain of the template has the setting 'share.public.templates.with.other.domains' disabled.
     */
    @Override
    public void addDomainIdToSetIfDomainDoesNotShareTemplates(long domainId, Account account, Set<Long> unsharableDomainIds) {
        if (domainId == account.getDomainId()) {
            logger.trace(String.format("Domain [%s] will not be added to the set of domains with unshared templates since the account [%s] belongs to it.", domainId, account));
            return;
        }

        if (unsharableDomainIds.contains(domainId)) {
            logger.trace(String.format("Domain [%s] is already on the set of domains with unshared templates.", domainId));
            return;
        }

        if (!checkIfDomainSharesTemplates(domainId)) {
            logger.debug(String.format("Domain [%s] will be added to the set of domains with unshared templates as configuration [%s] is false.", domainId, QueryService.SharePublicTemplatesWithOtherDomains.key()));
            unsharableDomainIds.add(domainId);
        }
    }

    @Override
    public boolean checkIfDomainSharesTemplates(Long domainId) {
        return QueryService.SharePublicTemplatesWithOtherDomains.valueIn(domainId);
    }

    private Pair<List<TemplateJoinVO>, Integer> templateChecks(boolean isIso, List<HypervisorType> hypers, Map<String, String> tags, String name, String keyword,
                                                               HypervisorType hyperType, boolean onlyReady, Boolean bootable, Long zoneId, boolean showDomr, Account caller,
                                                               boolean showRemovedTmpl, Long parentTemplateId, Boolean showUnique, String templateType, Boolean isVnf, Boolean forCks,
                                                               Filter searchFilter, SearchCriteria<TemplateJoinVO> sc) {
        if (!isIso) {
            // add hypervisor criteria for template case
            if (hypers != null && !hypers.isEmpty()) {
                String[] relatedHypers = new String[hypers.size()];
                for (int i = 0; i < hypers.size(); i++) {
                    relatedHypers[i] = hypers.get(i).toString();
                }
                sc.addAnd("hypervisorType", SearchCriteria.Op.IN, relatedHypers);
            }
        }

        // add tags criteria
        if (tags != null && !tags.isEmpty()) {
            SearchCriteria<TemplateJoinVO> scc = _templateJoinDao.createSearchCriteria();
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                SearchCriteria<TemplateJoinVO> scTag = _templateJoinDao.createSearchCriteria();
                scTag.addAnd("tagKey", SearchCriteria.Op.EQ, entry.getKey());
                scTag.addAnd("tagValue", SearchCriteria.Op.EQ, entry.getValue());
                if (isIso) {
                    scTag.addAnd("tagResourceType", SearchCriteria.Op.EQ, ResourceObjectType.ISO);
                } else {
                    scTag.addAnd("tagResourceType", SearchCriteria.Op.EQ, ResourceObjectType.Template);
                }
                scc.addOr("tagKey", SearchCriteria.Op.SC, scTag);
            }
            sc.addAnd("tagKey", SearchCriteria.Op.SC, scc);
        }

        // other criteria

        if (keyword != null) {
            SearchCriteria<TemplateJoinVO> scc = _templateJoinDao.createSearchCriteria();
            scc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            scc.addOr("displayText", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, scc);
        }
        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.EQ, name);
        }

        SearchCriteria.Op op = isIso ? Op.EQ : Op.NEQ;
        sc.addAnd("format", op, "ISO");

        if (!hyperType.equals(HypervisorType.None)) {
            sc.addAnd("hypervisorType", SearchCriteria.Op.EQ, hyperType);
        }

        if (bootable != null) {
            sc.addAnd("bootable", SearchCriteria.Op.EQ, bootable);
        }

        if (onlyReady) {
            SearchCriteria<TemplateJoinVO> readySc = _templateJoinDao.createSearchCriteria();
            readySc.addOr("state", SearchCriteria.Op.EQ, TemplateState.Ready);
            readySc.addOr("format", SearchCriteria.Op.IN, ImageFormat.BAREMETAL, ImageFormat.EXTERNAL);
            SearchCriteria<TemplateJoinVO> isoPerhostSc = _templateJoinDao.createSearchCriteria();
            isoPerhostSc.addAnd("format", SearchCriteria.Op.EQ, ImageFormat.ISO);
            isoPerhostSc.addAnd("templateType", SearchCriteria.Op.EQ, com.cloud.storage.Storage.TemplateType.PERHOST);
            readySc.addOr("templateType", SearchCriteria.Op.SC, isoPerhostSc);
            sc.addAnd("state", SearchCriteria.Op.SC, readySc);
        }

        if (!showDomr) {
            // excluding system template
            sc.addAnd("templateType", SearchCriteria.Op.NEQ, Storage.TemplateType.SYSTEM);
        }

        if (zoneId != null) {
            SearchCriteria<TemplateJoinVO> zoneSc = _templateJoinDao.createSearchCriteria();
            zoneSc.addOr("dataCenterId", SearchCriteria.Op.EQ, zoneId);
            zoneSc.addOr("dataStoreScope", SearchCriteria.Op.EQ, ScopeType.REGION);
            // handle the case where TemplateManager.VMWARE_TOOLS_ISO and TemplateManager.VMWARE_TOOLS_ISO do not
            // have data_center information in template_view
            SearchCriteria<TemplateJoinVO> isoPerhostSc = _templateJoinDao.createSearchCriteria();
            isoPerhostSc.addAnd("format", SearchCriteria.Op.EQ, ImageFormat.ISO);
            isoPerhostSc.addAnd("templateType", SearchCriteria.Op.EQ, com.cloud.storage.Storage.TemplateType.PERHOST);
            zoneSc.addOr("templateType", SearchCriteria.Op.SC, isoPerhostSc);
            sc.addAnd("dataCenterId", SearchCriteria.Op.SC, zoneSc);
        }

        if (parentTemplateId != null) {
            sc.addAnd("parentTemplateId", SearchCriteria.Op.EQ, parentTemplateId);
        }

        if (templateType != null) {
            sc.addAnd("templateType", SearchCriteria.Op.EQ, templateType);
        }

        if (isVnf != null) {
            if (isVnf) {
                sc.addAnd("templateType", SearchCriteria.Op.EQ, com.cloud.storage.Storage.TemplateType.VNF);
            } else {
                sc.addAnd("templateType", SearchCriteria.Op.NEQ, com.cloud.storage.Storage.TemplateType.VNF);
            }
        }

        if (forCks != null) {
            sc.addAnd("forCks", SearchCriteria.Op.EQ, forCks);
        }

        // don't return removed template, this should not be needed since we
        // changed annotation for removed field in TemplateJoinVO.
        // sc.addAnd("removed", SearchCriteria.Op.NULL);

        // search unique templates and find details by Ids
        Pair<List<TemplateJoinVO>, Integer> uniqueTmplPair;
        if (showRemovedTmpl) {
            uniqueTmplPair = _templateJoinDao.searchIncludingRemovedAndCount(sc, searchFilter);
        } else {
            sc.addAnd("templateState", SearchCriteria.Op.IN, State.Active, State.UploadAbandoned, State.UploadError, State.NotUploaded, State.UploadInProgress);
            if (showUnique) {
                final String[] distinctColumns = {"template_view.id"};
                uniqueTmplPair = _templateJoinDao.searchAndDistinctCount(sc, searchFilter, distinctColumns);
            } else {
                final String[] distinctColumns = {"temp_zone_pair"};
                uniqueTmplPair = _templateJoinDao.searchAndDistinctCount(sc, searchFilter, distinctColumns);
            }
        }

        return findTemplatesByIdOrTempZonePair(uniqueTmplPair, showRemovedTmpl, showUnique, caller);
    }

    // findTemplatesByIdOrTempZonePair returns the templates with the given ids if showUnique is true, or else by the TempZonePair
    private Pair<List<TemplateJoinVO>, Integer> findTemplatesByIdOrTempZonePair(Pair<List<TemplateJoinVO>, Integer> templateDataPair,
                                                                                boolean showRemoved, boolean showUnique, Account caller) {
        Integer count = templateDataPair.second();
        if (count == 0) {
            // empty result
            return templateDataPair;
        }
        List<TemplateJoinVO> templateData = templateDataPair.first();
        List<TemplateJoinVO> templates;
        if (showUnique) {
            Long[] templateIds = templateData.stream().map(BaseViewWithTagInformationVO::getId).toArray(Long[]::new);
            templates = _templateJoinDao.findByDistinctIds(templateIds);
        } else {
            String[] templateZonePairs = templateData.stream().map(TemplateJoinVO::getTempZonePair).toArray(String[]::new);
            templates = _templateJoinDao.searchByTemplateZonePair(showRemoved, templateZonePairs);
        }

        return new Pair<>(templates, count);
    }

    @Override
    public ListResponse<TemplateResponse> listIsos(ListIsosCmd cmd) {
        Pair<List<TemplateJoinVO>, Integer> result = searchForIsosInternal(cmd);
        ListResponse<TemplateResponse> response = new ListResponse<>();

        ResponseView respView = ResponseView.Restricted;
        if (cmd instanceof ListIsosCmdByAdmin) {
            respView = ResponseView.Full;
        }

        List<TemplateResponse> templateResponses = ViewResponseHelper.createIsoResponse(respView, result.first().toArray(new TemplateJoinVO[0]));
        response.setResponses(templateResponses, result.second());
        return response;
    }

    private Pair<List<TemplateJoinVO>, Integer> searchForIsosInternal(ListIsosCmd cmd) {
        TemplateFilter isoFilter = TemplateFilter.valueOf(cmd.getIsoFilter());
        Long id = cmd.getId();
        Map<String, String> tags = cmd.getTags();
        boolean showRemovedISO = cmd.getShowRemoved();
        Account caller = CallContext.current().getCallingAccount();
        Long domainId = cmd.getDomainId();
        boolean isRecursive = cmd.isRecursive();

        boolean listAll = false;
        if (isoFilter != null && isoFilter == TemplateFilter.all) {
            if (caller.getType() == Account.Type.NORMAL) {
                throw new InvalidParameterValueException("Filter " + TemplateFilter.all + " can be specified by admin only");
            }
            listAll = true;
        }


        List<Long> permittedAccountIds = new ArrayList<>();
        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(domainId, isRecursive, null);
        accountMgr.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccountIds, domainIdRecursiveListProject, listAll, false);
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        List<Account> permittedAccounts = new ArrayList<>();
        for (Long accountId : permittedAccountIds) {
            permittedAccounts.add(accountMgr.getAccount(accountId));
        }

        HypervisorType hypervisorType = HypervisorType.getType(cmd.getHypervisor());

        return searchForTemplatesInternal(cmd.getId(), cmd.getIsoName(), cmd.getKeyword(), isoFilter, true, cmd.isBootable(),
                cmd.getPageSizeVal(), cmd.getStartIndex(), cmd.getZoneId(), cmd.getStoragePoolId(), cmd.getImageStoreId(),
                hypervisorType, true, cmd.listInReadyState(), permittedAccounts, caller, listProjectResourcesCriteria,
                tags, showRemovedISO, null, null, cmd.getShowUnique(), null, null,
                domainId, isRecursive, cmd.getArch(), cmd.getOsCategoryId(), null, null);
    }

    @Override
    public DetailOptionsResponse listDetailOptions(final ListDetailOptionsCmd cmd) {
        final ResourceObjectType type = cmd.getResourceType();
        final String resourceUuid = cmd.getResourceId();
        final Map<String, List<String>> options = new HashMap<>();
        switch (type) {
            case Template:
            case UserVm:
                HypervisorType hypervisorType = HypervisorType.None;
                if (StringUtils.isNotEmpty(resourceUuid) && ResourceObjectType.Template.equals(type)) {
                    hypervisorType = _templateDao.findByUuid(resourceUuid).getHypervisorType();
                }
                if (StringUtils.isNotEmpty(resourceUuid) && ResourceObjectType.UserVm.equals(type)) {
                    hypervisorType = _vmInstanceDao.findByUuid(resourceUuid).getHypervisorType();
                }
                fillVMOrTemplateDetailOptions(options, hypervisorType);
                break;
            case VnfTemplate:
                fillVnfTemplateDetailOptions(options);
                return new DetailOptionsResponse(options);
            default:
                throw new CloudRuntimeException("Resource type not supported.");
        }
        if (CallContext.current().getCallingAccount().getType() != Account.Type.ADMIN) {
            final List<String> userDenyListedSettings = Stream.of(QueryService.UserVMDeniedDetails.value().split(","))
                    .map(String::trim)
                    .collect(Collectors.toList());
            userDenyListedSettings.addAll(QueryService.RootAdminOnlyVmSettings);
            for (final String detail : userDenyListedSettings) {
                options.remove(detail);
            }
        }
        return new DetailOptionsResponse(options);
    }

    @Override
    public ListResponse<ResourceIconResponse> listResourceIcons(ListResourceIconCmd cmd) {
        ListResponse<ResourceIconResponse> responses = new ListResponse<>();
        responses.setResponses(resourceIconDao.listResourceIcons(cmd.getResourceIds(), cmd.getResourceType()));
        return responses;
    }

    private void fillVnfTemplateDetailOptions(final Map<String, List<String>> options) {
        for (VNF.AccessDetail detail : VNF.AccessDetail.values()) {
            if (VNF.AccessDetail.ACCESS_METHODS.equals(detail)) {
                options.put(detail.name().toLowerCase(), Arrays.stream(VNF.AccessMethod.values()).map(VNF.AccessMethod::toString).sorted().collect(Collectors.toList()));
            } else {
                options.put(detail.name().toLowerCase(), Collections.emptyList());
            }
        }
        for (VNF.VnfDetail detail : VNF.VnfDetail.values()) {
            options.put(detail.name().toLowerCase(), Collections.emptyList());
        }
    }

    @Override
    public void fillVMOrTemplateDetailOptions(final Map<String, List<String>> options, final HypervisorType hypervisorType) {
        if (options == null) {
            throw new CloudRuntimeException("Invalid/null detail-options response object passed");
        }

        options.put(ApiConstants.BootType.UEFI.toString(), Arrays.asList(ApiConstants.BootMode.LEGACY.toString(),
            ApiConstants.BootMode.SECURE.toString()));
        options.put(VmDetailConstants.KEYBOARD, Arrays.asList("uk", "us", "jp", "fr", "es-latam"));
        options.put(VmDetailConstants.CPU_CORE_PER_SOCKET, Collections.emptyList());
        options.put(VmDetailConstants.ROOT_DISK_SIZE, Collections.emptyList());

        if (HypervisorType.KVM.equals(hypervisorType)) {
            options.put(VmDetailConstants.CPU_THREAD_PER_CORE, Collections.emptyList());
            options.put(VmDetailConstants.NIC_ADAPTER, Arrays.asList("e1000", "virtio", "rtl8139", "vmxnet3", "ne2k_pci"));
            options.put(VmDetailConstants.ROOT_DISK_CONTROLLER, Arrays.asList("osdefault", "ide", "scsi", "virtio", "virtio-blk"));
            options.put(VmDetailConstants.DATA_DISK_CONTROLLER, Arrays.asList("osdefault", "ide", "scsi", "virtio", "virtio-blk"));
            options.put(VmDetailConstants.VIDEO_HARDWARE, Arrays.asList("cirrus", "vga", "qxl", "virtio"));
            options.put(VmDetailConstants.VIDEO_RAM, Collections.emptyList());
            options.put(VmDetailConstants.IO_POLICY, Arrays.asList("threads", "native", "io_uring", "storage_specific"));
            options.put(VmDetailConstants.IOTHREADS, List.of("enabled"));
            options.put(VmDetailConstants.NIC_MULTIQUEUE_NUMBER, Collections.emptyList());
            options.put(VmDetailConstants.NIC_PACKED_VIRTQUEUES_ENABLED, Arrays.asList("true", "false"));
            options.put(VmDetailConstants.VIRTUAL_TPM_MODEL, Arrays.asList("tpm-tis", "tpm-crb"));
            options.put(VmDetailConstants.VIRTUAL_TPM_VERSION, Arrays.asList("1.2", "2.0"));
            options.put(VmDetailConstants.GUEST_CPU_MODE, Arrays.asList("custom", "host-model", "host-passthrough"));
            options.put(VmDetailConstants.GUEST_CPU_MODEL, Collections.emptyList());
            options.put(VmDetailConstants.KVM_GUEST_OS_MACHINE_TYPE, Collections.emptyList());
            options.put(VmDetailConstants.KVM_SKIP_FORCE_DISK_CONTROLLER, Arrays.asList("true", "false"));
        }

        if (HypervisorType.VMware.equals(hypervisorType)) {
            options.put(VmDetailConstants.NIC_ADAPTER, Arrays.asList("E1000", "PCNet32", "Vmxnet2", "Vmxnet3"));
            options.put(VmDetailConstants.ROOT_DISK_CONTROLLER, Arrays.asList("osdefault", "ide", "scsi", "lsilogic", "lsisas1068", "buslogic", "pvscsi"));
            options.put(VmDetailConstants.DATA_DISK_CONTROLLER, Arrays.asList("osdefault", "ide", "scsi", "lsilogic", "lsisas1068", "buslogic", "pvscsi"));
            options.put(VmDetailConstants.NESTED_VIRTUALIZATION_FLAG, Arrays.asList("true", "false"));
            options.put(VmDetailConstants.SVGA_VRAM_SIZE, Collections.emptyList());
            options.put(VmDetailConstants.RAM_RESERVATION, Collections.emptyList());
            options.put(VmDetailConstants.VIRTUAL_TPM_ENABLED, Arrays.asList("true", "false"));
        }

        if (HypervisorType.XenServer.equals(hypervisorType)) {
            options.put(VmDetailConstants.VIRTUAL_TPM_ENABLED, Arrays.asList("true", "false"));
        }
    }

    /**
     * Sets a list of IDs into the search criteria.
     */
    protected <Z, T> void setIdsListToSearchCriteria(SearchCriteria<Z> sc, List<T> ids) {
        if (ids != null && !ids.isEmpty()) {
            sc.setParameters("idIN", ids.toArray());
        }
    }
}
