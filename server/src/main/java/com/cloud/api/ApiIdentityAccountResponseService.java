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

import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.UserResponse;

import com.cloud.domain.Domain;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.UserAccount;

public interface ApiIdentityAccountResponseService {

    UserResponse createUserResponse(User user);

    AccountResponse createUserAccountResponse(ResponseView view, UserAccount user);

    AccountResponse createAccountResponse(ResponseView view, Account account);

    UserResponse createUserResponse(UserAccount user);

    DomainResponse createDomainResponse(Domain domain);

    User findUserById(Long userId);

    Account findAccountByNameDomain(String accountName, Long domainId);

    VirtualMachineTemplate findTemplateById(Long templateId);

    DiskOfferingVO findDiskOfferingById(Long diskOfferingId);
}
