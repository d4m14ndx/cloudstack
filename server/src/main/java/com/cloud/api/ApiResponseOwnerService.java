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

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.response.ControlledEntityResponse;
import org.apache.cloudstack.api.response.ControlledViewEntityResponse;
import org.apache.cloudstack.api.response.DomainResponse;

import com.cloud.api.query.vo.ControlledViewEntity;

public interface ApiResponseOwnerService {

    String getPrettyDomainPath(String path);

    void populateOwner(ControlledEntityResponse response, ControlledEntity object);

    void populateOwner(ControlledViewEntityResponse response, ControlledEntity object);

    void populateOwner(ControlledViewEntityResponse response, ControlledViewEntity object);

    void populateDomainTags(String domainUuid, DomainResponse domainResponse);

    void populateAccount(ControlledEntityResponse response, long accountId);

    void populateDomain(ControlledEntityResponse response, long domainId);

    void populateDomain(ControlledViewEntityResponse response, long domainId);
}
