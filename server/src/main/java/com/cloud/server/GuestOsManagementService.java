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

import java.util.List;

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

import com.cloud.storage.GuestOS;
import com.cloud.storage.GuestOSHypervisor;
import com.cloud.storage.GuestOSHypervisorVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.GuestOsCategory;
import com.cloud.utils.Pair;

/**
 * CRUD operations for GuestOS types, categories, and GuestOS-to-hypervisor mappings.
 *
 * <p>Extracted from {@link ManagementServerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 10). All public methods remain on the
 * god class as one-line delegating wrappers so existing callers bound to the
 * {@code ManagementService} and {@code ManagementServer} interfaces continue to
 * resolve normally.
 */
public interface GuestOsManagementService {

    Pair<List<? extends GuestOS>, Integer> listGuestOSByCriteria(ListGuestOsCmd cmd);

    Pair<List<? extends GuestOsCategory>, Integer> listGuestOSCategoriesByCriteria(ListGuestOsCategoriesCmd cmd);

    GuestOsCategory addGuestOsCategory(AddGuestOsCategoryCmd cmd);

    GuestOsCategory updateGuestOsCategory(UpdateGuestOsCategoryCmd cmd);

    boolean deleteGuestOsCategory(DeleteGuestOsCategoryCmd cmd);

    Pair<List<? extends GuestOSHypervisor>, Integer> listGuestOSMappingByCriteria(ListGuestOsMappingCmd cmd);

    GuestOSHypervisor addGuestOsMapping(AddGuestOsMappingCmd cmd);

    GuestOSHypervisor getAddedGuestOsMapping(Long guestOsMappingId);

    List<Pair<String, String>> getHypervisorGuestOsNames(GetHypervisorGuestOsNamesCmd cmd);

    GuestOS addGuestOs(AddGuestOsCmd cmd);

    GuestOS getAddedGuestOs(Long guestOsId);

    GuestOS updateGuestOs(UpdateGuestOsCmd cmd);

    boolean removeGuestOs(RemoveGuestOsCmd cmd);

    GuestOSHypervisor updateGuestOsMapping(UpdateGuestOsMappingCmd cmd);

    boolean removeGuestOsMapping(RemoveGuestOsMappingCmd cmd);

    GuestOSVO getGuestOs(Long guestOsId);

    GuestOSHypervisorVO getGuestOsHypervisor(Long guestOsHypervisorId);
}
