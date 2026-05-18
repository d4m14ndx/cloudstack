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
package com.cloud.storage;

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.storage.heuristics.CreateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.RemoveSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.UpdateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.jsinterpreter.JsInterpreterHelper;
import org.apache.cloudstack.secstorage.HeuristicVO;
import org.apache.cloudstack.secstorage.dao.SecondaryStorageHeuristicDao;
import org.apache.cloudstack.secstorage.heuristics.HeuristicType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class SecondaryStorageHeuristicServiceImplTest {

    @InjectMocks
    private SecondaryStorageHeuristicServiceImpl service;

    @Mock
    private SecondaryStorageHeuristicDao secondaryStorageHeuristicDao;

    @Mock
    private DataCenterDao dcDao;

    @Mock
    private JsInterpreterHelper jsInterpreterHelper;

    // ---- create tests ----

    @Test
    public void create_valid_persistsHeuristic() {
        CreateSecondaryStorageSelectorCmd cmd = mockCreateCmd("TestHeuristic", "desc", 1L, "TEMPLATE", "function() { return true; }");
        HeuristicVO persisted = new HeuristicVO();
        when(secondaryStorageHeuristicDao.findByZoneIdAndType(1L, HeuristicType.TEMPLATE)).thenReturn(null);
        when(secondaryStorageHeuristicDao.persist(any(HeuristicVO.class))).thenReturn(persisted);

        Object result = service.createSecondaryStorageHeuristic(cmd);

        assertSame(persisted, result);
        verify(secondaryStorageHeuristicDao, times(1)).persist(any(HeuristicVO.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void create_invalidType_throwsIllegalArgumentException() {
        CreateSecondaryStorageSelectorCmd cmd = mockCreateCmd("name", "desc", 1L, "BOGUS", "function() {}");

        service.createSecondaryStorageHeuristic(cmd);
    }

    @Test(expected = CloudRuntimeException.class)
    public void create_typeAlreadyExists_throwsCloudRuntimeException() {
        CreateSecondaryStorageSelectorCmd cmd = mockCreateCmd("name", "desc", 1L, "TEMPLATE", "function() {}");
        when(secondaryStorageHeuristicDao.findByZoneIdAndType(1L, HeuristicType.TEMPLATE)).thenReturn(new HeuristicVO());
        DataCenterVO dc = org.mockito.Mockito.mock(DataCenterVO.class);
        when(dcDao.findById(1L)).thenReturn(dc);

        service.createSecondaryStorageHeuristic(cmd);
    }

    @Test(expected = IllegalArgumentException.class)
    public void create_blankRule_throwsIllegalArgumentException() {
        CreateSecondaryStorageSelectorCmd cmd = mockCreateCmd("name", "desc", 1L, "TEMPLATE", "");
        when(secondaryStorageHeuristicDao.findByZoneIdAndType(1L, HeuristicType.TEMPLATE)).thenReturn(null);

        service.createSecondaryStorageHeuristic(cmd);
    }

    @Test(expected = IllegalArgumentException.class)
    public void create_nullRule_throwsIllegalArgumentException() {
        CreateSecondaryStorageSelectorCmd cmd = mockCreateCmd("name", "desc", 1L, "TEMPLATE", null);
        when(secondaryStorageHeuristicDao.findByZoneIdAndType(1L, HeuristicType.TEMPLATE)).thenReturn(null);

        service.createSecondaryStorageHeuristic(cmd);
    }

    @Test(expected = CloudRuntimeException.class)
    public void create_jsInterpreterDisabled_propagatesException() {
        CreateSecondaryStorageSelectorCmd cmd = mockCreateCmd("name", "desc", 1L, "TEMPLATE", "function() {}");
        when(secondaryStorageHeuristicDao.findByZoneIdAndType(1L, HeuristicType.TEMPLATE)).thenReturn(null);
        org.mockito.Mockito.doThrow(new CloudRuntimeException("interpreter not enabled"))
                .when(jsInterpreterHelper).ensureInterpreterEnabledIfParameterProvided(any(), eq(true));

        service.createSecondaryStorageHeuristic(cmd);

        verify(secondaryStorageHeuristicDao, never()).persist(any());
    }

    @Test
    public void create_typeMatchedCaseInsensitively() {
        CreateSecondaryStorageSelectorCmd cmd = mockCreateCmd("name", "desc", 1L, "template", "function() {}");
        HeuristicVO persisted = new HeuristicVO();
        when(secondaryStorageHeuristicDao.findByZoneIdAndType(1L, HeuristicType.TEMPLATE)).thenReturn(null);
        when(secondaryStorageHeuristicDao.persist(any(HeuristicVO.class))).thenReturn(persisted);

        Object result = service.createSecondaryStorageHeuristic(cmd);

        assertSame(persisted, result);
        verify(secondaryStorageHeuristicDao, times(1)).persist(any(HeuristicVO.class));
    }

    // ---- update tests ----

    @Test
    public void update_valid_replacesRuleAndPersists() {
        UpdateSecondaryStorageSelectorCmd cmd = mockUpdateCmd(42L, "function() { return 'store1'; }");
        HeuristicVO existing = new HeuristicVO();
        HeuristicVO persisted = new HeuristicVO();
        when(secondaryStorageHeuristicDao.findById(42L)).thenReturn(existing);
        when(secondaryStorageHeuristicDao.persist(existing)).thenReturn(persisted);

        Object result = service.updateSecondaryStorageHeuristic(cmd);

        assertSame(persisted, result);
        verify(secondaryStorageHeuristicDao, times(1)).persist(existing);
    }

    @Test(expected = IllegalArgumentException.class)
    public void update_blankRule_throwsIllegalArgumentException() {
        UpdateSecondaryStorageSelectorCmd cmd = mockUpdateCmd(42L, "   ");
        when(secondaryStorageHeuristicDao.findById(42L)).thenReturn(new HeuristicVO());

        service.updateSecondaryStorageHeuristic(cmd);

        verify(secondaryStorageHeuristicDao, never()).persist(any());
    }

    // ---- remove tests ----

    @Test
    public void remove_existing_callsDaoRemove() {
        RemoveSecondaryStorageSelectorCmd cmd = mockRemoveCmd(99L);
        when(secondaryStorageHeuristicDao.findById(99L)).thenReturn(new HeuristicVO());

        service.removeSecondaryStorageHeuristic(cmd);

        verify(secondaryStorageHeuristicDao, times(1)).remove(99L);
    }

    @Test(expected = CloudRuntimeException.class)
    public void remove_missing_throwsCloudRuntimeException() {
        RemoveSecondaryStorageSelectorCmd cmd = mockRemoveCmd(99L);
        when(secondaryStorageHeuristicDao.findById(99L)).thenReturn(null);

        service.removeSecondaryStorageHeuristic(cmd);

        verify(secondaryStorageHeuristicDao, never()).remove(anyLong());
    }

    // ---- validateHeuristicRule tests ----

    @Test
    public void validateHeuristicRule_validRule_invokesInterpreterHelperCheck() {
        service.validateHeuristicRule("function() {}");

        verify(jsInterpreterHelper, times(1))
                .ensureInterpreterEnabledIfParameterProvided(ApiConstants.HEURISTIC_RULE, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void validateHeuristicRule_whitespaceOnly_throwsIllegalArgumentException() {
        service.validateHeuristicRule("   ");

        verify(jsInterpreterHelper, never()).ensureInterpreterEnabledIfParameterProvided(any(), eq(true));
    }

    // ---- helpers ----

    private CreateSecondaryStorageSelectorCmd mockCreateCmd(String name, String description, long zoneId, String type, String rule) {
        CreateSecondaryStorageSelectorCmd cmd = org.mockito.Mockito.mock(CreateSecondaryStorageSelectorCmd.class);
        when(cmd.getName()).thenReturn(name);
        when(cmd.getDescription()).thenReturn(description);
        when(cmd.getZoneId()).thenReturn(zoneId);
        when(cmd.getType()).thenReturn(type);
        when(cmd.getHeuristicRule()).thenReturn(rule);
        return cmd;
    }

    private UpdateSecondaryStorageSelectorCmd mockUpdateCmd(long id, String rule) {
        UpdateSecondaryStorageSelectorCmd cmd = org.mockito.Mockito.mock(UpdateSecondaryStorageSelectorCmd.class);
        when(cmd.getId()).thenReturn(id);
        when(cmd.getHeuristicRule()).thenReturn(rule);
        return cmd;
    }

    private RemoveSecondaryStorageSelectorCmd mockRemoveCmd(long id) {
        RemoveSecondaryStorageSelectorCmd cmd = org.mockito.Mockito.mock(RemoveSecondaryStorageSelectorCmd.class);
        when(cmd.getId()).thenReturn(id);
        return cmd;
    }
}
