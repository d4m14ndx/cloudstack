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

package com.cloud.vm;

import java.util.Map;

import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.offering.ServiceOffering;

public interface VmScaleReconfigurationService {

    void findHostAndMigrate(String vmUuid, Long newSvcOfferingId, Map<String, String> customParameters, DeploymentPlanner.ExcludeList excludes)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException;

    void migrateForScale(String vmUuid, long srcHostId, DeployDestination dest, Long oldSvcOfferingId)
            throws ResourceUnavailableException, ConcurrentOperationException;

    void orchestrateMigrateForScale(String vmUuid, long srcHostId, DeployDestination dest, Long oldSvcOfferingId)
            throws ResourceUnavailableException, ConcurrentOperationException;

    VMInstanceVO reConfigureVm(String vmUuid, ServiceOffering oldServiceOffering, ServiceOffering newServiceOffering,
            Map<String, String> customParameters, boolean reconfiguringOnExistingHost)
                    throws ResourceUnavailableException, InsufficientServerCapacityException, ConcurrentOperationException;

    VMInstanceVO orchestrateReConfigureVm(String vmUuid, ServiceOffering oldServiceOffering, ServiceOffering newServiceOffering,
            boolean reconfiguringOnExistingHost) throws ResourceUnavailableException, ConcurrentOperationException;
}
