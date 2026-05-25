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
package com.cloud.resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.alert.AlertService;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.host.UpdateHostCmd;
import org.apache.cloudstack.jsinterpreter.JsInterpreterHelper;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.alert.AlertManager;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.StorageConflictException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.GuestOSCategoryVO;
import com.cloud.storage.StorageManager;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class HostUpdateServiceImpl implements HostUpdateService {
    private static final Logger LOG = LogManager.getLogger(HostUpdateServiceImpl.class);

    @Inject
    protected HostDao hostDao;
    @Inject
    protected HostDetailsDao hostDetailsDao;
    @Inject
    protected GuestOSCategoryDao guestOSCategoryDao;
    @Inject
    protected HostTagsDao hostTagsDao;
    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected StorageManager storageManager;
    @Inject
    protected AlertManager alertManager;
    @Inject
    protected AnnotationService annotationService;
    @Inject
    protected JsInterpreterHelper jsInterpreterHelper;
    @Inject
    protected HostUpdateCallbacks callbacks;

    @Override
    public Host updateHost(final UpdateHostCmd cmd) throws NoTransitionException {
        return updateHost(cmd.getId(), cmd.getName(), cmd.getOsCategoryId(),
                cmd.getAllocationState(), cmd.getUrl(), cmd.getHostTags(), cmd.getIsTagARule(), cmd.getAnnotation(), false,
                cmd.getExternalDetails(), cmd.isCleanupExternalDetails());
    }

    @Override
    public Host autoUpdateHostAllocationState(final Long hostId, final ResourceState.Event resourceEvent) throws NoTransitionException {
        return updateHost(hostId, null, null, resourceEvent.toString(), null, null, null, null, true, null, false);
    }

    private ResourceState.Event getResourceEventFromAllocationStateString(final String allocationState) {
        final ResourceState.Event resourceEvent = ResourceState.Event.toEvent(allocationState);
        if (resourceEvent != ResourceState.Event.Enable && resourceEvent != ResourceState.Event.Disable) {
            throw new InvalidParameterValueException(String.format("Invalid allocation state: %s, only Enable/Disable are allowed", allocationState));
        }
        return resourceEvent;
    }

    private void handleAutoEnableDisableKVMHost(final boolean autoEnableDisableKVMSetting,
            final boolean isUpdateFromHostHealthCheck,
            final HostVO host, DetailVO hostDetail,
            final ResourceState.Event resourceEvent) {
        if (!autoEnableDisableKVMSetting) {
            return;
        }

        if (!isUpdateFromHostHealthCheck && hostDetail != null
                && !Boolean.parseBoolean(hostDetail.getValue()) && resourceEvent == ResourceState.Event.Enable) {
            hostDetail.setValue(Boolean.TRUE.toString());
            hostDetailsDao.update(hostDetail.getId(), hostDetail);
        } else if (!isUpdateFromHostHealthCheck && hostDetail != null
                && Boolean.parseBoolean(hostDetail.getValue()) && resourceEvent == ResourceState.Event.Disable) {
            LOG.info("The setting {} is enabled but {} is manually set into {} state,ignoring future auto enabling of the host based on health check results",
                    AgentManager.EnableKVMAutoEnableDisable.key(), host, resourceEvent);
            hostDetail.setValue(Boolean.FALSE.toString());
            hostDetailsDao.update(hostDetail.getId(), hostDetail);
        } else if (hostDetail == null) {
            final String autoEnableValue = !isUpdateFromHostHealthCheck ? Boolean.FALSE.toString() : Boolean.TRUE.toString();
            hostDetail = new DetailVO(host.getId(), ApiConstants.AUTO_ENABLE_KVM_HOST, autoEnableValue);
            hostDetailsDao.persist(hostDetail);
        }
    }

    private boolean updateHostAllocationState(final HostVO host, final String allocationState,
            final boolean isUpdateFromHostHealthCheck) throws NoTransitionException {
        final boolean autoEnableDisableKVMSetting = AgentManager.EnableKVMAutoEnableDisable.valueIn(host.getClusterId())
                && host.getHypervisorType() == HypervisorType.KVM;
        final ResourceState.Event resourceEvent = getResourceEventFromAllocationStateString(allocationState);
        final DetailVO hostDetail = hostDetailsDao.findDetail(host.getId(), ApiConstants.AUTO_ENABLE_KVM_HOST);

        if ((host.getResourceState() == ResourceState.Enabled && resourceEvent == ResourceState.Event.Enable)
                || (host.getResourceState() == ResourceState.Disabled && resourceEvent == ResourceState.Event.Disable)) {
            LOG.info("The host {} is already on the allocated state", host.getName());
            return false;
        }

        if (isAutoEnableAttemptForADisabledHost(autoEnableDisableKVMSetting, isUpdateFromHostHealthCheck, hostDetail, resourceEvent)) {
            LOG.debug("The setting '{}' is enabled and the health check succeeds on the host, but the host has been manually disabled previously, ignoring auto enabling",
                    AgentManager.EnableKVMAutoEnableDisable.key());
            return false;
        }

        handleAutoEnableDisableKVMHost(autoEnableDisableKVMSetting, isUpdateFromHostHealthCheck, host, hostDetail, resourceEvent);
        callbacks.resourceStateTransitTo(host, resourceEvent);
        return true;
    }

    private boolean isAutoEnableAttemptForADisabledHost(final boolean autoEnableDisableKVMSetting,
            final boolean isUpdateFromHostHealthCheck,
            final DetailVO hostDetail, final ResourceState.Event resourceEvent) {
        return autoEnableDisableKVMSetting && isUpdateFromHostHealthCheck && hostDetail != null
                && !Boolean.parseBoolean(hostDetail.getValue()) && resourceEvent == ResourceState.Event.Enable;
    }

    private void updateHostName(final HostVO host, final String name) {
        LOG.debug("Updating Host name to: {}", name);
        host.setName(name);
        hostDao.update(host.getId(), host);
    }

    private void updateHostGuestOSCategory(final Long hostId, final Long guestOSCategoryId) {
        if (!(guestOSCategoryId > 0) || guestOSCategoryDao.findById(guestOSCategoryId) == null) {
            throw new InvalidParameterValueException("Please specify a valid guest OS category.");
        }

        final GuestOSCategoryVO guestOSCategory = guestOSCategoryDao.findById(guestOSCategoryId);
        final DetailVO guestOSDetail = hostDetailsDao.findDetail(hostId, "guest.os.category.id");

        if (guestOSCategory != null && !com.cloud.storage.GuestOsCategory.CATEGORY_NONE.equalsIgnoreCase(guestOSCategory.getName())) {
            if (guestOSDetail != null) {
                guestOSDetail.setValue(String.valueOf(guestOSCategory.getId()));
                hostDetailsDao.update(guestOSDetail.getId(), guestOSDetail);
            } else {
                final Map<String, String> detail = new HashMap<>();
                detail.put("guest.os.category.id", String.valueOf(guestOSCategory.getId()));
                hostDetailsDao.persist(hostId, detail);
            }
        } else if (guestOSDetail != null) {
            hostDetailsDao.remove(guestOSDetail.getId());
        }
    }

    private void updateHostTags(final HostVO host, final Long hostId, final List<String> hostTags, final Boolean isTagARule) {
        final List<VMInstanceVO> activeVMs = vmDao.listByHostId(hostId);
        LOG.warn("The following active VMs [{}] are using the host [{}]. Updating the host tags will not affect them.", activeVMs, host);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Updating Host Tags to :{}", hostTags);
        }
        hostTagsDao.persist(hostId, new ArrayList<>(new HashSet<>(hostTags)), isTagARule);
    }

    private Host updateHost(final Long hostId, final String name, final Long guestOSCategoryId, final String allocationState,
            final String url, final List<String> hostTags, final Boolean isTagARule, final String annotation,
            final boolean isUpdateFromHostHealthCheck, final Map<String, String> externalDetails,
            final boolean cleanupExternalDetails) throws NoTransitionException {
        jsInterpreterHelper.ensureInterpreterEnabledIfParameterProvided(ApiConstants.IS_TAG_A_RULE, Boolean.TRUE.equals(isTagARule));

        final HostVO host = hostDao.findById(hostId);
        if (host == null) {
            throw new InvalidParameterValueException("Host with id " + hostId + " doesn't exist");
        }

        boolean isUpdateHostAllocation = false;
        if (StringUtils.isNotBlank(allocationState)) {
            isUpdateHostAllocation = updateHostAllocationState(host, allocationState, isUpdateFromHostHealthCheck);
        }

        if (StringUtils.isNotBlank(name)) {
            updateHostName(host, name);
        }

        if (guestOSCategoryId != null) {
            updateHostGuestOSCategory(hostId, guestOSCategoryId);
        }

        if (hostTags != null) {
            updateHostTags(host, hostId, hostTags, isTagARule);
        }

        if (cleanupExternalDetails) {
            hostDetailsDao.removeExternalDetails(hostId);
        } else if (MapUtils.isNotEmpty(externalDetails)) {
            hostDetailsDao.replaceExternalDetails(hostId, externalDetails);
        }

        if (url != null) {
            storageManager.updateSecondaryStorage(hostId, url);
        }
        try {
            storageManager.enableHost(hostId);
        } catch (final StorageUnavailableException | StorageConflictException e) {
            LOG.error("Failed to setup host {} when enabled", host);
        }

        final HostVO updatedHost = hostDao.findById(hostId);
        sendAlertAndAnnotationForAutoEnableDisableKVMHostFeature(host, allocationState, isUpdateFromHostHealthCheck, isUpdateHostAllocation, annotation);
        return updatedHost;
    }

    private void sendAlertAndAnnotationForAutoEnableDisableKVMHostFeature(final HostVO host, final String allocationState,
            final boolean isUpdateFromHostHealthCheck,
            final boolean isUpdateHostAllocation, final String annotation) {
        final boolean isAutoEnableDisableKVMSettingEnabled = host.getHypervisorType() == HypervisorType.KVM
                && AgentManager.EnableKVMAutoEnableDisable.valueIn(host.getClusterId());
        if (!isAutoEnableDisableKVMSettingEnabled) {
            if (StringUtils.isNotBlank(annotation)) {
                annotationService.addAnnotation(annotation, AnnotationService.EntityType.HOST, host.getUuid(), true);
            }
            return;
        }

        if (!isUpdateHostAllocation) {
            return;
        }

        String msg = String.format("The host %s (%s) ", host.getName(), host.getUuid());
        final ResourceState.Event resourceEvent = getResourceEventFromAllocationStateString(allocationState);
        final boolean isEventEnable = resourceEvent == ResourceState.Event.Enable;

        if (isUpdateFromHostHealthCheck) {
            msg += String.format("is auto-%s after %s health check results",
                    isEventEnable ? "enabled" : "disabled",
                    isEventEnable ? "successful" : "failed");
            alertManager.sendAlert(AlertService.AlertType.ALERT_TYPE_HOST, host.getDataCenterId(), host.getPodId(), msg, msg);
        } else {
            msg += String.format("is %s despite the setting '%s' is enabled for the cluster %s",
                    isEventEnable ? "enabled" : "disabled", AgentManager.EnableKVMAutoEnableDisable.key(), host.getClusterId());
            if (StringUtils.isNotBlank(annotation)) {
                msg += String.format(", reason: %s", annotation);
            }
        }
        annotationService.addAnnotation(msg, AnnotationService.EntityType.HOST, host.getUuid(), true);
    }
}
