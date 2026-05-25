// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements.  See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloud.api.commands;

import jakarta.inject.Inject;


import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.NetworkElementApiExecutor;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.api.response.NetscalerLoadBalancerResponse;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.element.NetscalerLoadBalancerElementService;

@APICommand(name = "deleteNetscalerLoadBalancer", responseObject = SuccessResponse.class, description = " delete a netscaler load balancer device",
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class DeleteNetscalerLoadBalancerCmd extends BaseAsyncCmd {

    @Inject
    NetscalerLoadBalancerElementService _netsclarLbService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.LOAD_BALANCER_DEVICE_ID,
               type = CommandType.UUID,
               entityType = NetscalerLoadBalancerResponse.class,
               required = true,
               description = "Netscaler load balancer device ID")
    private Long lbDeviceId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getLoadBalancerDeviceId() {
        return lbDeviceId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException, ConcurrentOperationException,
        ResourceAllocationException {
        NetworkElementApiExecutor.execute(() -> {
            NetworkElementApiExecutor.requireSuccess(_netsclarLbService.deleteNetscalerLoadBalancer(this), "Failed to delete netscaler load balancer.");
            SuccessResponse response = new SuccessResponse(getCommandName());
            response.setResponseName(getCommandName());
            this.setResponseObject(response);
        });
    }

    @Override
    public String getEventDescription() {
        return "Deleting NetScaler load balancer device with ID: " + getResourceUuid(ApiConstants.LOAD_BALANCER_DEVICE_ID);
    }

    @Override
    public String getEventType() {
        return EventTypes.EVENT_EXTERNAL_NCC_DEVICE_DELETE;
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
