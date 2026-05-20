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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;

public interface VmImportFacade {

    UserVm importVM(DataCenter zone, Host host, VirtualMachineTemplate template, String instanceNameInternal, String displayName,
                    Account owner, String userData, Boolean isDisplayVm, String keyboard, long accountId, long userId,
                    ServiceOffering serviceOffering, String sshPublicKeys, Long guestOsId, String hostName, HypervisorType hypervisorType,
                    Map<String, String> customParameters, VirtualMachine.PowerState powerState,
                    LinkedHashMap<String, List<NicProfile>> networkNicMap, ManagerOperations operations) throws InsufficientCapacityException;

    void setVmRequiredFieldsForImport(boolean isImport, UserVmVO vm, DataCenter zone, HypervisorType hypervisorType,
                                      Host host, Host lastHost, VirtualMachine.PowerState powerState);

    interface ManagerOperations {
        void checkNameForRFCCompliance(String hostName);

        Boolean checkIfDynamicScalingCanBeEnabled(VirtualMachine vm, ServiceOffering offering, VirtualMachineTemplate template, Long zoneId);

        UserVmVO commitUserVm(Allocation allocation) throws InsufficientCapacityException;
    }

    class Allocation {
        private final DataCenter zone;
        private final Host host;
        private final Host lastHost;
        private final VirtualMachineTemplate template;
        private final String hostName;
        private final String displayName;
        private final Account owner;
        private final String userData;
        private final Boolean isDisplayVm;
        private final String keyboard;
        private final long accountId;
        private final long userId;
        private final ServiceOffering serviceOffering;
        private final boolean iso;
        private final Long guestOsId;
        private final String sshPublicKeys;
        private final LinkedHashMap<String, List<NicProfile>> networkNicMap;
        private final long id;
        private final String instanceName;
        private final String uuidName;
        private final HypervisorType hypervisorType;
        private final Map<String, String> customParameters;
        private final VirtualMachine.PowerState powerState;
        private final Boolean dynamicScalingEnabled;

        Allocation(DataCenter zone, Host host, Host lastHost, VirtualMachineTemplate template, String hostName, String displayName,
                   Account owner, String userData, Boolean isDisplayVm, String keyboard, long accountId, long userId,
                   ServiceOffering serviceOffering, Long guestOsId, String sshPublicKeys,
                   LinkedHashMap<String, List<NicProfile>> networkNicMap, long id, String instanceName, String uuidName,
                   HypervisorType hypervisorType, Map<String, String> customParameters, VirtualMachine.PowerState powerState,
                   Boolean dynamicScalingEnabled) {
            this.zone = zone;
            this.host = host;
            this.lastHost = lastHost;
            this.template = template;
            this.hostName = hostName;
            this.displayName = displayName;
            this.owner = owner;
            this.userData = userData;
            this.isDisplayVm = isDisplayVm;
            this.keyboard = keyboard;
            this.accountId = accountId;
            this.userId = userId;
            this.serviceOffering = serviceOffering;
            this.iso = template.getFormat().equals(ImageFormat.ISO);
            this.guestOsId = guestOsId;
            this.sshPublicKeys = sshPublicKeys;
            this.networkNicMap = networkNicMap;
            this.id = id;
            this.instanceName = instanceName;
            this.uuidName = uuidName;
            this.hypervisorType = hypervisorType;
            this.customParameters = customParameters;
            this.powerState = powerState;
            this.dynamicScalingEnabled = dynamicScalingEnabled;
        }

        public DataCenter getZone() {
            return zone;
        }

        public Host getHost() {
            return host;
        }

        public Host getLastHost() {
            return lastHost;
        }

        public VirtualMachineTemplate getTemplate() {
            return template;
        }

        public String getHostName() {
            return hostName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Account getOwner() {
            return owner;
        }

        public String getUserData() {
            return userData;
        }

        public Boolean getDisplayVm() {
            return isDisplayVm;
        }

        public String getKeyboard() {
            return keyboard;
        }

        public long getAccountId() {
            return accountId;
        }

        public long getUserId() {
            return userId;
        }

        public ServiceOffering getServiceOffering() {
            return serviceOffering;
        }

        public boolean isIso() {
            return iso;
        }

        public Long getGuestOsId() {
            return guestOsId;
        }

        public String getSshPublicKeys() {
            return sshPublicKeys;
        }

        public LinkedHashMap<String, List<NicProfile>> getNetworkNicMap() {
            return networkNicMap;
        }

        public long getId() {
            return id;
        }

        public String getInstanceName() {
            return instanceName;
        }

        public String getUuidName() {
            return uuidName;
        }

        public HypervisorType getHypervisorType() {
            return hypervisorType;
        }

        public Map<String, String> getCustomParameters() {
            return customParameters;
        }

        public VirtualMachine.PowerState getPowerState() {
            return powerState;
        }

        public Boolean getDynamicScalingEnabled() {
            return dynamicScalingEnabled;
        }
    }
}
