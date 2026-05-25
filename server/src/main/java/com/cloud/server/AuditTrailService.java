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

import org.apache.cloudstack.api.command.admin.resource.ArchiveAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.DeleteAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.ListAlertsCmd;
import org.apache.cloudstack.api.command.user.event.ArchiveEventsCmd;
import org.apache.cloudstack.api.command.user.event.DeleteEventsCmd;

import com.cloud.alert.Alert;
import com.cloud.utils.Pair;

/**
 * Audit-trail read and lifecycle operations — searching, archiving, and
 * deleting both user-visible {@link com.cloud.event.EventVO events} and
 * system-emitted {@link com.cloud.alert.AlertVO alerts}, along with the
 * static {@link com.cloud.event.EventTypes} catalogue listing.
 *
 * <p>Extracted from {@link ManagementServerImpl} as part of the Phase 4
 * Spring-component decomposition. The five public {@code ManagementService}
 * entry points covered here ({@code archiveEvents}, {@code deleteEvents},
 * {@code searchForAlerts}, {@code archiveAlerts}, {@code deleteAlerts},
 * and {@code listEventTypes}) remain on the god class as one-line delegating
 * wrappers so that any callers still binding the {@code ManagementService}
 * interface continue to resolve their method calls there.
 */
public interface AuditTrailService {

    /**
     * Mark the events identified by the supplied command's id list and/or
     * type/date range as archived for the caller's permitted account set.
     * Returns {@code false} if explicit ids were supplied but some are not
     * visible to the caller.
     */
    boolean archiveEvents(ArchiveEventsCmd cmd);

    /**
     * Permanently remove the events identified by the supplied command's id
     * list and/or type/date range for the caller's permitted account set.
     * Returns {@code false} if explicit ids were supplied but some are not
     * visible to the caller.
     */
    boolean deleteEvents(DeleteEventsCmd cmd);

    /**
     * Page-aware search for non-archived alerts visible to the caller's
     * authorised zone, narrowing by id, type, name, or keyword as supplied
     * by the command.
     */
    Pair<List<? extends Alert>, Integer> searchForAlerts(ListAlertsCmd cmd);

    /**
     * Mark the alerts identified by the supplied command's id list and/or
     * type/date range as archived within the caller's authorised zone.
     */
    boolean archiveAlerts(ArchiveAlertsCmd cmd);

    /**
     * Permanently remove the alerts identified by the supplied command's id
     * list and/or type/date range within the caller's authorised zone.
     */
    boolean deleteAlerts(DeleteAlertsCmd cmd);

    /**
     * Return the full set of public {@link com.cloud.event.EventTypes}
     * constant values via reflection, or {@code null} on reflective access
     * failure (logged at error level).
     */
    String[] listEventTypes();
}
