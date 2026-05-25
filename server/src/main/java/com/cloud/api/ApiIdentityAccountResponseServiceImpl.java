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

import java.util.EnumSet;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants.DomainDetails;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.UserResponse;
import org.springframework.stereotype.Component;

import com.cloud.api.query.vo.AccountJoinVO;
import com.cloud.api.query.vo.UserAccountJoinVO;
import com.cloud.domain.Domain;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.UserAccount;

@Component
public class ApiIdentityAccountResponseServiceImpl implements ApiIdentityAccountResponseService {

    @Inject
    private ApiResponseOwnerService apiResponseOwnerService;

    @Override
    public UserResponse createUserResponse(User user) {
        UserAccountJoinVO vUser = ApiDBUtils.newUserView(user);
        return ApiDBUtils.newUserResponse(vUser);
    }

    // this method is used for response generation via createAccount (which creates an account + user)
    @Override
    public AccountResponse createUserAccountResponse(ResponseView view, UserAccount user) {
        return ApiDBUtils.newAccountResponse(view, EnumSet.of(DomainDetails.all), ApiDBUtils.findAccountViewById(user.getAccountId()));
    }

    @Override
    public AccountResponse createAccountResponse(ResponseView view, Account account) {
        AccountJoinVO vUser = ApiDBUtils.newAccountView(account);
        return ApiDBUtils.newAccountResponse(view, EnumSet.of(DomainDetails.all), vUser);
    }

    @Override
    public UserResponse createUserResponse(UserAccount user) {
        UserAccountJoinVO vUser = ApiDBUtils.newUserView(user);
        return ApiDBUtils.newUserResponse(vUser);
    }

    @Override
    public DomainResponse createDomainResponse(Domain domain) {
        DomainResponse domainResponse = new DomainResponse();
        domainResponse.setDomainName(domain.getName());
        domainResponse.setId(domain.getUuid());
        domainResponse.setLevel(domain.getLevel());
        domainResponse.setCreated(domain.getCreated());
        domainResponse.setNetworkDomain(domain.getNetworkDomain());
        Domain parentDomain = ApiDBUtils.findDomainById(domain.getParent());
        if (parentDomain != null) {
            domainResponse.setParentDomainId(parentDomain.getUuid());
        }
        domainResponse.setPath(apiResponseOwnerService.getPrettyDomainPath(domain.getPath()));
        if (domain.getParent() != null) {
            domainResponse.setParentDomainName(ApiDBUtils.findDomainById(domain.getParent()).getName());
        }
        if (domain.getChildCount() > 0) {
            domainResponse.setHasChild(true);
        }
        apiResponseOwnerService.populateDomainTags(domain.getUuid(), domainResponse);
        domainResponse.setObjectName("domain");
        return domainResponse;
    }

    @Override
    public User findUserById(Long userId) {
        return ApiDBUtils.findUserById(userId);
    }

    @Override
    public Account findAccountByNameDomain(String accountName, Long domainId) {
        return ApiDBUtils.findAccountByNameDomain(accountName, domainId);
    }

    @Override
    public VirtualMachineTemplate findTemplateById(Long templateId) {
        return ApiDBUtils.findTemplateById(templateId);
    }

    @Override
    public DiskOfferingVO findDiskOfferingById(Long diskOfferingId) {
        return ApiDBUtils.findDiskOfferingById(diskOfferingId);
    }
}
