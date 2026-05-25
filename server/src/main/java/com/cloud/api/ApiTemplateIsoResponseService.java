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

import java.util.List;

import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.ExtractResponse;
import org.apache.cloudstack.api.response.TemplatePermissionsResponse;
import org.apache.cloudstack.api.response.TemplateResponse;

import com.cloud.template.VirtualMachineTemplate;

public interface ApiTemplateIsoResponseService {

    TemplateResponse createTemplateUpdateResponse(ResponseView view, VirtualMachineTemplate result);

    List<TemplateResponse> createTemplateResponses(ResponseView view, VirtualMachineTemplate result, Long zoneId, boolean readyOnly);

    List<TemplateResponse> createTemplateResponses(ResponseView view, VirtualMachineTemplate result, List<Long> zoneIds, boolean readyOnly);

    List<TemplateResponse> createTemplateResponses(ResponseView view, long templateId, Long zoneId, boolean readyOnly);

    List<TemplateResponse> createIsoResponses(ResponseView view, VirtualMachineTemplate result, Long zoneId, boolean readyOnly);

    ExtractResponse createVolumeExtractResponse(Long id, Long zoneId, Long accountId, String mode, String url);

    ExtractResponse createSnapshotExtractResponse(Long id, Long zoneId, Long accountId, String url);

    ExtractResponse createImageExtractResponse(Long id, Long zoneId, Long accountId, String mode, String url);

    List<TemplateResponse> createTemplateResponses(ResponseView view, long templateId, Long snapshotId, Long volumeId, boolean readyOnly);

    List<TemplateResponse> createTemplateResponses(ResponseView view, long templateId, Long vmId);

    TemplatePermissionsResponse createTemplatePermissionsResponse(ResponseView view, List<String> accountNames, Long id);
}
