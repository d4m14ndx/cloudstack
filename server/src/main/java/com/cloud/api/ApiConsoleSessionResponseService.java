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
import org.apache.cloudstack.api.response.ConsoleSessionResponse;
import org.apache.cloudstack.consoleproxy.ConsoleSession;
import org.springframework.stereotype.Component;

import com.cloud.domain.Domain;
import com.cloud.host.Host;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.vm.VMInstanceVO;

@Component
public class ApiConsoleSessionResponseService {

    protected void populateDomainFieldsOnConsoleSessionResponse(ConsoleSession consoleSession, ConsoleSessionResponse consoleSessionResponse) {
        Domain domain = findDomainById(consoleSession.getDomainId());
        if (domain != null) {
            consoleSessionResponse.setDomain(domain.getName());
            consoleSessionResponse.setDomainPath(domain.getPath());
            consoleSessionResponse.setDomainId(domain.getUuid());
        }
    }

    protected void populateUserFieldsOnConsoleSessionResponse(ConsoleSession consoleSession, ConsoleSessionResponse consoleSessionResponse) {
        User user = findUserById(consoleSession.getUserId());
        if (user != null) {
            consoleSessionResponse.setUser(user.getUsername());
            consoleSessionResponse.setUserId(user.getUuid());
        }
    }

    protected void populateAccountFieldsOnConsoleSessionResponse(ConsoleSession consoleSession, ConsoleSessionResponse consoleSessionResponse) {
        Account account = findAccountById(consoleSession.getAccountId());
        if (account != null) {
            consoleSessionResponse.setAccount(account.getAccountName());
            consoleSessionResponse.setAccountId(account.getUuid());
        }
    }

    protected void populateHostFieldsOnConsoleSessionResponse(ConsoleSession consoleSession, ConsoleSessionResponse consoleSessionResponse) {
        Host host = findHostById(consoleSession.getHostId());
        if (host != null) {
            consoleSessionResponse.setHostId(host.getUuid());
            consoleSessionResponse.setHostName(host.getName());
        }
    }

    protected void populateInstanceFieldsOnConsoleSessionResponse(ConsoleSession consoleSession, ConsoleSessionResponse consoleSessionResponse) {
        VMInstanceVO instance = findVMInstanceById(consoleSession.getInstanceId());
        if (instance != null) {
            consoleSessionResponse.setVmId(instance.getUuid());
            consoleSessionResponse.setVmName(instance.getInstanceName());
        }
    }

    public ConsoleSessionResponse createConsoleSessionResponse(ConsoleSession consoleSession, ResponseView responseView) {
        ConsoleSessionResponse consoleSessionResponse = new ConsoleSessionResponse();
        if (consoleSession == null) {
            return consoleSessionResponse;
        }

        consoleSessionResponse.setId(consoleSession.getUuid());
        consoleSessionResponse.setCreated(consoleSession.getCreated());
        consoleSessionResponse.setAcquired(consoleSession.getAcquired());
        consoleSessionResponse.setRemoved(consoleSession.getRemoved());
        consoleSessionResponse.setConsoleEndpointCreatorAddress(consoleSession.getConsoleEndpointCreatorAddress());
        consoleSessionResponse.setClientAddress(consoleSession.getClientAddress());

        populateDomainFieldsOnConsoleSessionResponse(consoleSession, consoleSessionResponse);
        populateUserFieldsOnConsoleSessionResponse(consoleSession, consoleSessionResponse);
        populateAccountFieldsOnConsoleSessionResponse(consoleSession, consoleSessionResponse);
        populateInstanceFieldsOnConsoleSessionResponse(consoleSession, consoleSessionResponse);
        if (responseView == ResponseView.Full) {
            populateHostFieldsOnConsoleSessionResponse(consoleSession, consoleSessionResponse);
        }

        consoleSessionResponse.setObjectName("consolesession");
        return consoleSessionResponse;
    }

    protected Domain findDomainById(Long domainId) {
        return ApiDBUtils.findDomainById(domainId);
    }

    protected User findUserById(Long userId) {
        return ApiDBUtils.findUserById(userId);
    }

    protected Account findAccountById(Long accountId) {
        return ApiDBUtils.findAccountById(accountId);
    }

    protected Host findHostById(Long hostId) {
        return ApiDBUtils.findHostById(hostId);
    }

    protected VMInstanceVO findVMInstanceById(Long instanceId) {
        return ApiDBUtils.findVMInstanceById(instanceId);
    }
}
