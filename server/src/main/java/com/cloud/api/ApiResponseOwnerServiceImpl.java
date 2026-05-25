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
package com.cloud.api;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.response.ControlledEntityResponse;
import org.apache.cloudstack.api.response.ControlledViewEntityResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.query.vo.ControlledViewEntity;
import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.domain.Domain;
import com.cloud.projects.Project;
import com.cloud.server.ResourceTag;
import com.cloud.user.Account;

@Component
public class ApiResponseOwnerServiceImpl implements ApiResponseOwnerService {

    protected Logger logger = LogManager.getLogger(ApiResponseOwnerServiceImpl.class);

    @Override
    public String getPrettyDomainPath(String path) {
        if (path == null) {
            return null;
        }
        StringBuilder domainPath = new StringBuilder("ROOT");
        (domainPath.append(path)).deleteCharAt(domainPath.length() - 1);
        return domainPath.toString();
    }

    @Override
    public void populateOwner(ControlledEntityResponse response, ControlledEntity object) {
        Account account = ApiDBUtils.findAccountById(object.getAccountId());

        if (account.getType() == Account.Type.PROJECT) {
            Project project = ApiDBUtils.findProjectByProjectAccountId(account.getId());
            response.setProjectId(project.getUuid());
            response.setProjectName(project.getName());
        } else {
            response.setAccountName(account.getAccountName());
        }
        populateDomain(response, object.getDomainId());
    }

    @Override
    public void populateOwner(ControlledViewEntityResponse response, ControlledEntity object) {
        Account account = ApiDBUtils.findAccountById(object.getAccountId());

        if (account.getType() == Account.Type.PROJECT) {
            Project project = ApiDBUtils.findProjectByProjectAccountId(account.getId());
            response.setProjectId(project.getUuid());
            response.setProjectName(project.getName());
        } else {
            response.setAccountName(account.getAccountName());
        }

        populateDomain(response, object.getDomainId());
    }

    @Override
    public void populateOwner(ControlledViewEntityResponse response, ControlledViewEntity object) {

        if (object.getAccountType() == Account.Type.PROJECT) {
            response.setProjectId(object.getProjectUuid());
            response.setProjectName(object.getProjectName());
        } else {
            response.setAccountName(object.getAccountName());
        }

        response.setDomainId(object.getDomainUuid());
        response.setDomainName(object.getDomainName());
        response.setDomainPath(getPrettyDomainPath(object.getDomainPath()));
    }

    @Override
    public void populateDomainTags(String domainUuid, DomainResponse domainResponse) {
        List<ResourceTagJoinVO> tags = ApiDBUtils.listResourceTagViewByResourceUUID(domainUuid,
                ResourceTag.ResourceObjectType.Domain);
        if (CollectionUtils.isEmpty(tags)) {
            return;
        }
        Set<ResourceTagResponse> tagResponses = new HashSet<>();
        for (ResourceTagJoinVO tag : tags) {
            ResourceTagResponse tagResponse = ApiDBUtils.newResourceTagResponse(tag, true);
            tagResponses.add(tagResponse);
        }
        domainResponse.setTags(tagResponses);
    }

    @Override
    public void populateAccount(ControlledEntityResponse response, long accountId) {
        Account account = ApiDBUtils.findAccountById(accountId);
        if (account == null) {
            logger.debug("Unable to find account with id: " + accountId);
        } else if (account.getType() == Account.Type.PROJECT) {
            Project project = ApiDBUtils.findProjectByProjectAccountId(account.getId());
            if (project != null) {
                response.setProjectId(project.getUuid());
                response.setProjectName(project.getName());
                response.setAccountName(account.getAccountName());
            } else {
                logger.debug("Unable to find project with id: " + account.getId());
            }
        } else {
            response.setAccountName(account.getAccountName());
        }
    }

    @Override
    public void populateDomain(ControlledEntityResponse response, long domainId) {
        Domain domain = ApiDBUtils.findDomainById(domainId);
        if (domain == null) {
            return;
        }
        response.setDomainId(domain.getUuid());
        response.setDomainName(domain.getName());
        response.setDomainPath(getPrettyDomainPath(domain.getPath()));
    }

    @Override
    public void populateDomain(ControlledViewEntityResponse response, long domainId) {
        Domain domain = ApiDBUtils.findDomainById(domainId);
        if (domain == null) {
            return;
        }
        response.setDomainId(domain.getUuid());
        response.setDomainName(domain.getName());
        response.setDomainPath(getPrettyDomainPath(domain.getPath()));
    }
}
