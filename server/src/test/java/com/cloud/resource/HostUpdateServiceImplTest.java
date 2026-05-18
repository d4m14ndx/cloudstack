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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.alert.AlertService;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.host.UpdateHostCmd;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.jsinterpreter.JsInterpreterHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnitRunner;

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

@RunWith(MockitoJUnitRunner.class)
public class HostUpdateServiceImplTest {

    private static final long HOST_ID = 42L;
    private static final long DETAIL_ID = 7L;
    private static final long CLUSTER_ID = 8L;
    private static final long DC_ID = 9L;
    private static final long POD_ID = 10L;

    private HostDao hostDao;
    private HostDetailsDao hostDetailsDao;
    private GuestOSCategoryDao guestOSCategoryDao;
    private HostTagsDao hostTagsDao;
    private VMInstanceDao vmDao;
    private StorageManager storageManager;
    private AlertManager alertManager;
    private AnnotationService annotationService;
    private JsInterpreterHelper jsInterpreterHelper;
    private HostUpdateCallbacks callbacks;

    private HostUpdateServiceImpl service;
    private HostVO host;
    private HostVO updatedHost;
    private UpdateHostCmd cmd;

    private String originalAutoEnableDefaultValue;

    @Before
    public void setUp() throws Exception {
        hostDao = mock(HostDao.class);
        hostDetailsDao = mock(HostDetailsDao.class);
        guestOSCategoryDao = mock(GuestOSCategoryDao.class);
        hostTagsDao = mock(HostTagsDao.class);
        vmDao = mock(VMInstanceDao.class);
        storageManager = mock(StorageManager.class);
        alertManager = mock(AlertManager.class);
        annotationService = mock(AnnotationService.class);
        jsInterpreterHelper = mock(JsInterpreterHelper.class);
        callbacks = mock(HostUpdateCallbacks.class);

        service = new HostUpdateServiceImpl();
        service.hostDao = hostDao;
        service.hostDetailsDao = hostDetailsDao;
        service.guestOSCategoryDao = guestOSCategoryDao;
        service.hostTagsDao = hostTagsDao;
        service.vmDao = vmDao;
        service.storageManager = storageManager;
        service.alertManager = alertManager;
        service.annotationService = annotationService;
        service.jsInterpreterHelper = jsInterpreterHelper;
        service.callbacks = callbacks;

        host = mock(HostVO.class);
        updatedHost = mock(HostVO.class);
        cmd = mock(UpdateHostCmd.class);

        when(cmd.getName()).thenReturn(null);
        when(cmd.getOsCategoryId()).thenReturn(null);
        when(cmd.getAllocationState()).thenReturn(null);
        when(cmd.getUrl()).thenReturn(null);
        when(cmd.getHostTags()).thenReturn(null);
        when(cmd.getIsTagARule()).thenReturn(null);
        when(cmd.getAnnotation()).thenReturn(null);
        when(cmd.getExternalDetails()).thenReturn(null);
        when(cmd.isCleanupExternalDetails()).thenReturn(false);

        when(host.getId()).thenReturn(HOST_ID);
        when(host.getClusterId()).thenReturn(CLUSTER_ID);
        when(host.getDataCenterId()).thenReturn(DC_ID);
        when(host.getPodId()).thenReturn(POD_ID);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(host.getUuid()).thenReturn("host-uuid");
        when(host.getName()).thenReturn("old-host");
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        when(hostDao.findById(HOST_ID)).thenReturn(host, updatedHost);
        when(vmDao.listByHostId(HOST_ID)).thenReturn(Collections.<VMInstanceVO>emptyList());

        doNothing().when(jsInterpreterHelper).ensureInterpreterEnabledIfParameterProvided(any(), anyBoolean());

        originalAutoEnableDefaultValue = String.valueOf(AgentManager.EnableKVMAutoEnableDisable.value());
        setConfigValue(Boolean.FALSE.toString());
    }

    @After
    public void tearDown() throws Exception {
        setConfigValue(originalAutoEnableDefaultValue);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateHost_throwsWhenHostDoesNotExist() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(hostDao.findById(HOST_ID)).thenReturn(null);

        service.updateHost(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateHost_throwsWhenAllocationStateIsInvalid() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getAllocationState()).thenReturn("Bogus");

        service.updateHost(cmd);
    }

    @Test
    public void updateHost_returnsWithoutTransitWhenHostAlreadyEnabled() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getAllocationState()).thenReturn(ResourceState.Event.Enable.toString());
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);

        Host result = service.updateHost(cmd);

        assertSame(updatedHost, result);
        verify(callbacks, never()).resourceStateTransitTo(any(), any());
    }

    @Test
    public void updateHost_returnsWithoutTransitWhenHostAlreadyDisabled() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getAllocationState()).thenReturn(ResourceState.Event.Disable.toString());
        when(host.getResourceState()).thenReturn(ResourceState.Disabled);

        Host result = service.updateHost(cmd);

        assertSame(updatedHost, result);
        verify(callbacks, never()).resourceStateTransitTo(any(), any());
    }

    @Test
    public void updateHost_skipsAutoEnableForHealthCheckWhenHostWasManuallyDisabled() throws Exception {
        setConfigValue(Boolean.TRUE.toString());
        when(host.getResourceState()).thenReturn(ResourceState.Disabled);
        when(hostDetailsDao.findDetail(HOST_ID, ApiConstants.AUTO_ENABLE_KVM_HOST))
                .thenReturn(detail(DETAIL_ID, ApiConstants.AUTO_ENABLE_KVM_HOST, Boolean.FALSE.toString()));

        Host result = service.autoUpdateHostAllocationState(HOST_ID, ResourceState.Event.Enable);

        assertSame(updatedHost, result);
        verify(callbacks, never()).resourceStateTransitTo(any(), any());
        verify(hostDetailsDao, never()).update(anyLong(), any(DetailVO.class));
        verify(annotationService, never()).addAnnotation(any(), any(), any(), anyBoolean());
        verify(alertManager, never()).sendAlert(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    public void updateHost_manualEnableFlipsAutoEnableDetailToTrue() throws Exception {
        setConfigValue(Boolean.TRUE.toString());
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getAllocationState()).thenReturn(ResourceState.Event.Enable.toString());
        when(host.getResourceState()).thenReturn(ResourceState.Disabled);
        DetailVO detail = detail(DETAIL_ID, ApiConstants.AUTO_ENABLE_KVM_HOST, Boolean.FALSE.toString());
        when(hostDetailsDao.findDetail(HOST_ID, ApiConstants.AUTO_ENABLE_KVM_HOST)).thenReturn(detail);

        service.updateHost(cmd);

        assertEquals(Boolean.TRUE.toString(), detail.getValue());
        verify(hostDetailsDao).update(eq(DETAIL_ID), eq(detail));
        verify(callbacks).resourceStateTransitTo(host, ResourceState.Event.Enable);
    }

    @Test
    public void updateHost_manualDisableFlipsAutoEnableDetailToFalse() throws Exception {
        setConfigValue(Boolean.TRUE.toString());
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getAllocationState()).thenReturn(ResourceState.Event.Disable.toString());
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        DetailVO detail = detail(DETAIL_ID, ApiConstants.AUTO_ENABLE_KVM_HOST, Boolean.TRUE.toString());
        when(hostDetailsDao.findDetail(HOST_ID, ApiConstants.AUTO_ENABLE_KVM_HOST)).thenReturn(detail);

        service.updateHost(cmd);

        assertEquals(Boolean.FALSE.toString(), detail.getValue());
        verify(hostDetailsDao).update(eq(DETAIL_ID), eq(detail));
        verify(callbacks).resourceStateTransitTo(host, ResourceState.Event.Disable);
    }

    @Test
    public void updateHost_persistsAutoEnableDetailFalseForManualTransitionWhenMissing() throws Exception {
        setConfigValue(Boolean.TRUE.toString());
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getAllocationState()).thenReturn(ResourceState.Event.Disable.toString());
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);

        service.updateHost(cmd);

        ArgumentCaptor<DetailVO> detailCaptor = ArgumentCaptor.forClass(DetailVO.class);
        verify(hostDetailsDao).persist(detailCaptor.capture());
        assertEquals(Boolean.FALSE.toString(), detailCaptor.getValue().getValue());
    }

    @Test
    public void updateHost_persistsAutoEnableDetailTrueForHealthCheckTransitionWhenMissing() throws Exception {
        setConfigValue(Boolean.TRUE.toString());
        when(host.getResourceState()).thenReturn(ResourceState.Disabled);

        service.autoUpdateHostAllocationState(HOST_ID, ResourceState.Event.Enable);

        ArgumentCaptor<DetailVO> detailCaptor = ArgumentCaptor.forClass(DetailVO.class);
        verify(hostDetailsDao).persist(detailCaptor.capture());
        assertEquals(Boolean.TRUE.toString(), detailCaptor.getValue().getValue());
        verify(callbacks).resourceStateTransitTo(host, ResourceState.Event.Enable);
    }

    @Test
    public void updateHost_updatesHostNameAndDao() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getName()).thenReturn("new-host");

        service.updateHost(cmd);

        verify(host).setName("new-host");
        verify(hostDao).update(HOST_ID, host);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateHost_throwsWhenGuestOsCategoryInvalid() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getOsCategoryId()).thenReturn(99L);
        when(guestOSCategoryDao.findById(99L)).thenReturn(null);

        service.updateHost(cmd);
    }

    @Test
    public void updateHost_updatesExistingGuestOsCategoryDetail() throws Exception {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getOsCategoryId()).thenReturn(12L);
        GuestOSCategoryVO category = category(12L, "Linux");
        when(guestOSCategoryDao.findById(12L)).thenReturn(category);
        DetailVO detail = detail(DETAIL_ID, "guest.os.category.id", "8");
        when(hostDetailsDao.findDetail(HOST_ID, "guest.os.category.id")).thenReturn(detail);

        service.updateHost(cmd);

        assertEquals("12", detail.getValue());
        verify(hostDetailsDao).update(DETAIL_ID, detail);
    }

    @Test
    public void updateHost_removesGuestOsCategoryDetailWhenCategoryNone() throws Exception {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getOsCategoryId()).thenReturn(13L);
        GuestOSCategoryVO category = category(13L, com.cloud.storage.GuestOsCategory.CATEGORY_NONE);
        when(guestOSCategoryDao.findById(13L)).thenReturn(category);
        DetailVO detail = detail(DETAIL_ID, "guest.os.category.id", "8");
        when(hostDetailsDao.findDetail(HOST_ID, "guest.os.category.id")).thenReturn(detail);

        service.updateHost(cmd);

        verify(hostDetailsDao).remove(DETAIL_ID);
    }

    @Test
    public void updateHost_deduplicatesHostTagsBeforePersist() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getHostTags()).thenReturn(Arrays.asList("ssd", "gpu", "ssd"));
        when(cmd.getIsTagARule()).thenReturn(Boolean.TRUE);
        lenient().when(vmDao.listByHostId(HOST_ID)).thenReturn(Collections.<VMInstanceVO>emptyList());

        service.updateHost(cmd);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(hostTagsDao).persist(eq(HOST_ID), captor.capture(), eq(Boolean.TRUE));
        assertEquals(new LinkedHashSet<>(Arrays.asList("ssd", "gpu")), new LinkedHashSet<>(captor.getValue()));
    }

    @Test
    public void updateHost_cleanupExternalDetailsRemovesInsteadOfReplacing() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.isCleanupExternalDetails()).thenReturn(true);
        when(cmd.getExternalDetails()).thenReturn(Collections.singletonMap("ignored", "value"));

        service.updateHost(cmd);

        verify(hostDetailsDao).removeExternalDetails(HOST_ID);
        verify(hostDetailsDao, never()).replaceExternalDetails(anyLong(), anyMap());
    }

    @Test
    public void updateHost_replacesExternalDetailsWhenProvided() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        Map<String, String> details = new HashMap<>();
        details.put("endpoint.url", "https://example");
        when(cmd.getExternalDetails()).thenReturn(details);

        service.updateHost(cmd);

        verify(hostDetailsDao).replaceExternalDetails(HOST_ID, details);
        verify(hostDetailsDao, never()).removeExternalDetails(anyLong());
    }

    @Test
    public void updateHost_updatesSecondaryStorageUrlWhenProvided() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getUrl()).thenReturn("nfs://sec/path");

        service.updateHost(cmd);

        verify(storageManager).updateSecondaryStorage(HOST_ID, "nfs://sec/path");
    }

    @Test
    public void updateHost_sendsHealthCheckAlertAndAnnotationForKvmAutoDisableEnable() throws Exception {
        setConfigValue(Boolean.TRUE.toString());
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);

        service.autoUpdateHostAllocationState(HOST_ID, ResourceState.Event.Disable);

        verify(alertManager).sendAlert(eq(AlertService.AlertType.ALERT_TYPE_HOST), eq(DC_ID), eq(POD_ID), any(), any());
        ArgumentCaptor<String> annotationCaptor = ArgumentCaptor.forClass(String.class);
        verify(annotationService).addAnnotation(annotationCaptor.capture(), eq(AnnotationService.EntityType.HOST), eq("host-uuid"), eq(true));
        assertTrue(annotationCaptor.getValue().contains("auto-disabled"));
    }

    @Test
    public void updateHost_addsPlainAnnotationWhenFeatureDisabled() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getAnnotation()).thenReturn("operator reason");

        service.updateHost(cmd);

        verify(annotationService).addAnnotation("operator reason", AnnotationService.EntityType.HOST, "host-uuid", true);
        verify(alertManager, never()).sendAlert(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    public void autoUpdateHostAllocationState_delegatesToSharedHelperForHealthCheckPath() throws Exception {
        setConfigValue(Boolean.TRUE.toString());
        when(host.getResourceState()).thenReturn(ResourceState.Disabled);

        Host result = service.autoUpdateHostAllocationState(HOST_ID, ResourceState.Event.Enable);

        assertSame(updatedHost, result);
        verify(callbacks).resourceStateTransitTo(host, ResourceState.Event.Enable);
        verify(alertManager).sendAlert(eq(AlertService.AlertType.ALERT_TYPE_HOST), eq(DC_ID), eq(POD_ID), any(), any());
    }

    @Test
    public void updateHost_enableHostSwallowsStorageExceptions() throws NoTransitionException, StorageUnavailableException, StorageConflictException {
        when(cmd.getId()).thenReturn(HOST_ID);
        org.mockito.Mockito.doThrow(new StorageUnavailableException("nope", HOST_ID)).when(storageManager).enableHost(HOST_ID);

        Host result = service.updateHost(cmd);

        assertSame(updatedHost, result);
        verify(storageManager).enableHost(HOST_ID);
    }

    @Test
    public void updateHost_usesJsInterpreterValidationForIsTagRule() throws NoTransitionException {
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getIsTagARule()).thenReturn(Boolean.TRUE);

        service.updateHost(cmd);

        verify(jsInterpreterHelper).ensureInterpreterEnabledIfParameterProvided(ApiConstants.IS_TAG_A_RULE, true);
    }

    private void setConfigValue(String value) throws Exception {
        Field defaultField = ConfigKey.class.getDeclaredField("_defaultValue");
        defaultField.setAccessible(true);
        defaultField.set(AgentManager.EnableKVMAutoEnableDisable, value);
        Field valueField = ConfigKey.class.getDeclaredField("_value");
        valueField.setAccessible(true);
        valueField.set(AgentManager.EnableKVMAutoEnableDisable, null);
    }

    private DetailVO detail(long id, String name, String value) {
        DetailVO detail = new DetailVO(HOST_ID, name, value);
        try {
            Field idField = DetailVO.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(detail, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return detail;
    }

    private GuestOSCategoryVO category(long id, String name) throws Exception {
        GuestOSCategoryVO category = new GuestOSCategoryVO(name, false);
        Field idField = GuestOSCategoryVO.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(category, id);
        return category;
    }
}
