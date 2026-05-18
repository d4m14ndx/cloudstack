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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.api.command.admin.resource.icon.ListResourceIconCmd;
import org.apache.cloudstack.api.command.user.iso.ListIsosCmd;
import org.apache.cloudstack.api.command.user.template.ListTemplatesCmd;
import org.apache.cloudstack.api.command.user.resource.ListDetailOptionsCmd;
import org.apache.cloudstack.api.response.DetailOptionsResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ResourceIconResponse;
import org.apache.cloudstack.api.response.TemplateResponse;

import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.Account;
import com.cloud.utils.db.SearchCriteria;

public interface TemplateQueryService {

    ListResponse<TemplateResponse> listTemplates(ListTemplatesCmd cmd);

    ListResponse<TemplateResponse> listIsos(ListIsosCmd cmd);

    DetailOptionsResponse listDetailOptions(ListDetailOptionsCmd cmd);

    ListResponse<ResourceIconResponse> listResourceIcons(ListResourceIconCmd cmd);

    /**
     * Spy-verified seam (mirrored from QueryManagerImpl). Applies domain-based
     * public-template sharing restrictions to the supplied search criteria.
     */
    void applyPublicTemplateSharingRestrictions(SearchCriteria<TemplateJoinVO> sc, Account caller);

    /**
     * Spy-verified seam. Conditionally adds {@code domainId} to the supplied
     * unsharable-set when the domain does not share public templates with the
     * caller's domain.
     */
    void addDomainIdToSetIfDomainDoesNotShareTemplates(long domainId, Account account, Set<Long> unsharableDomainIds);

    /**
     * Spy-verified seam. Returns {@code true} when the domain has the
     * {@code share.public.templates.with.other.domains} configuration enabled.
     */
    boolean checkIfDomainSharesTemplates(Long domainId);

    /**
     * Fills the provided options map with hypervisor-specific detail keys for
     * VM/Template detail rendering. Public so callers (e.g. detail-options API
     * handlers) can reuse the dispatch logic.
     */
    void fillVMOrTemplateDetailOptions(Map<String, List<String>> options, HypervisorType hypervisorType);
}
