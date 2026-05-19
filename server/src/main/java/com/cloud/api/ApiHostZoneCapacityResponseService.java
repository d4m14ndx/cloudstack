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
import java.util.List;

import org.apache.cloudstack.api.ApiConstants.HostDetails;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.CapacityResponse;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.HostForMigrationResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.PodResponse;
import org.apache.cloudstack.api.response.ZoneResponse;

import com.cloud.dc.DataCenter;
import com.cloud.dc.Pod;
import com.cloud.host.Host;
import com.cloud.org.Cluster;

public interface ApiHostZoneCapacityResponseService {

    HostResponse createHostResponse(Host host);

    HostResponse createHostResponse(Host host, EnumSet<HostDetails> details);

    HostForMigrationResponse createHostForMigrationResponse(Host host);

    HostForMigrationResponse createHostForMigrationResponse(Host host, EnumSet<HostDetails> details);

    PodResponse createMinimalPodResponse(Pod pod);

    PodResponse createPodResponse(Pod pod, Boolean showCapacities);

    ZoneResponse createZoneResponse(ResponseView view, DataCenter dataCenter, Boolean showCapacities, Boolean showResourceIcon);

    List<CapacityResponse> getDataCenterCapacityResponse(Long zoneId);

    ClusterResponse createMinimalClusterResponse(Cluster cluster);

    ClusterResponse createClusterResponse(Cluster cluster, Boolean showCapacities);
}
