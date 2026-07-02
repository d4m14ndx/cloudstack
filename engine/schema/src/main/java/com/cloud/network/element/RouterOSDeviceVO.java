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
package com.cloud.network.element;

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import org.apache.cloudstack.api.Identity;
import org.apache.cloudstack.api.InternalIdentity;

import com.cloud.utils.db.Encrypt;
import com.cloud.utils.db.GenericDao;

/**
 * Tracks a Mikrotik RouterOS CHR appliance deployed by the RouterOS network
 * plugin as the router for an isolated guest network or a VPC.
 */
@Entity
@Table(name = "routeros_devices")
public class RouterOSDeviceVO implements InternalIdentity, Identity {

    public enum State {
        /** VM record allocated, appliance not yet started/reachable */
        Allocated,
        /** Appliance started but the REST API has not been reachable yet; bootstrap config pending */
        RequiresBootstrap,
        /** Base configuration is being pushed */
        Provisioning,
        /** Appliance reachable and fully programmed */
        Active,
        /** Appliance in an error state; operator attention required */
        Error
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "network_id")
    private Long networkId;

    @Column(name = "vpc_id")
    private Long vpcId;

    @Column(name = "vm_instance_id")
    private Long vmInstanceId;

    @Column(name = "api_url")
    private String apiUrl;

    @Column(name = "username")
    private String username;

    @Encrypt
    @Column(name = "password")
    private String password;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "state")
    private State state;

    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;

    @Column(name = GenericDao.REMOVED_COLUMN)
    private Date removed;

    public RouterOSDeviceVO() {
        uuid = UUID.randomUUID().toString();
    }

    public RouterOSDeviceVO(final Long networkId, final Long vpcId, final Long vmInstanceId, final String apiUrl, final String username, final String password) {
        this();
        this.networkId = networkId;
        this.vpcId = vpcId;
        this.vmInstanceId = vmInstanceId;
        this.apiUrl = apiUrl;
        this.username = username;
        this.password = password;
        state = State.Allocated;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    public Long getNetworkId() {
        return networkId;
    }

    public void setNetworkId(final Long networkId) {
        this.networkId = networkId;
    }

    public Long getVpcId() {
        return vpcId;
    }

    public void setVpcId(final Long vpcId) {
        this.vpcId = vpcId;
    }

    public Long getVmInstanceId() {
        return vmInstanceId;
    }

    public void setVmInstanceId(final Long vmInstanceId) {
        this.vmInstanceId = vmInstanceId;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(final String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(final String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(final String password) {
        this.password = password;
    }

    public State getState() {
        return state;
    }

    public void setState(final State state) {
        this.state = state;
    }

    public Date getCreated() {
        return created;
    }

    public Date getRemoved() {
        return removed;
    }

    @Override
    public String toString() {
        return String.format("RouterOSDevice {id: %d, uuid: %s, networkId: %s, vpcId: %s, vmInstanceId: %s, state: %s}", id, uuid, networkId, vpcId, vmInstanceId, state);
    }
}
