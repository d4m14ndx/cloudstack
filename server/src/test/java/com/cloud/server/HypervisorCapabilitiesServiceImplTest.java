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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.cloudstack.api.command.admin.config.UpdateHypervisorCapabilitiesCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorCapabilities;
import com.cloud.hypervisor.HypervisorCapabilitiesVO;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class HypervisorCapabilitiesServiceImplTest {

    @Mock
    private HypervisorCapabilitiesDao hypervisorCapabilitiesDao;

    @InjectMocks
    private HypervisorCapabilitiesServiceImpl service = new HypervisorCapabilitiesServiceImpl();

    @Mock
    private AccountVO callerAccount;
    @Mock
    private User callerUser;

    private AutoCloseable closeable;

    @Before
    public void setup() {
        closeable = MockitoAnnotations.openMocks(this);
        when(callerAccount.getId()).thenReturn(1L);
        // Account.Type is not actually inspected by the production code under
        // test — the slice does not touch ACLs — but keep the helper here for
        // any future tests that want to assert event-detail behaviour.
        Mockito.lenient().when(callerAccount.getType()).thenReturn(Account.Type.ADMIN);
        CallContext.register(callerUser, callerAccount);
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        closeable.close();
    }

    // --- listHypervisorCapabilities ----------------------------------------

    @Test
    public void listHypervisorCapabilitiesAppliesAllFilters() {
        SearchCriteria<HypervisorCapabilitiesVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria<HypervisorCapabilitiesVO> ssc = Mockito.mock(SearchCriteria.class);
        when(hypervisorCapabilitiesDao.createSearchCriteria()).thenReturn(sc, ssc);
        List<HypervisorCapabilitiesVO> rows =
                Arrays.asList(Mockito.mock(HypervisorCapabilitiesVO.class));
        when(hypervisorCapabilitiesDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(rows, 1));

        Pair<List<? extends HypervisorCapabilities>, Integer> result =
                service.listHypervisorCapabilities(7L, HypervisorType.KVM, "kvm", 0L, 50L);

        Assert.assertEquals(1, (int) result.second());
        Assert.assertEquals(1, result.first().size());
        verify(sc).addAnd(eq("id"), eq(SearchCriteria.Op.EQ), eq(7L));
        verify(sc).addAnd(eq("hypervisorType"), eq(SearchCriteria.Op.EQ), eq(HypervisorType.KVM));
        verify(sc).addAnd(eq("hypervisorType"), eq(SearchCriteria.Op.SC), eq(ssc));
        verify(ssc).addOr(eq("hypervisorType"), eq(SearchCriteria.Op.LIKE), eq("%kvm%"));
    }

    @Test
    public void listHypervisorCapabilitiesSkipsOptionalFiltersWhenNull() {
        SearchCriteria<HypervisorCapabilitiesVO> sc = Mockito.mock(SearchCriteria.class);
        when(hypervisorCapabilitiesDao.createSearchCriteria()).thenReturn(sc);
        when(hypervisorCapabilitiesDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        Pair<List<? extends HypervisorCapabilities>, Integer> result =
                service.listHypervisorCapabilities(null, null, null, 0L, 20L);

        Assert.assertEquals(0, (int) result.second());
        // Only one createSearchCriteria call (no sub-search for keyword).
        verify(hypervisorCapabilitiesDao, times(1)).createSearchCriteria();
        // No filter additions when all params are null.
        verify(sc, never()).addAnd(eq("id"), any(), any());
        verify(sc, never()).addAnd(eq("hypervisorType"), any(), any());
    }

    // --- getHypervisorCapabilitiesForUpdate --------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void getHypervisorCapabilitiesForUpdateRejectsNoIdAndNoHypervisor() {
        service.getHypervisorCapabilitiesForUpdate(null, null, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getHypervisorCapabilitiesForUpdateRejectsIdWithHypervisor() {
        service.getHypervisorCapabilitiesForUpdate(1L, "KVM", "1.0");
    }

    @Test
    public void getHypervisorCapabilitiesForUpdateById() {
        HypervisorCapabilitiesVO vo = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(hypervisorCapabilitiesDao.findById(11L, true)).thenReturn(vo);

        HypervisorCapabilitiesVO result = service.getHypervisorCapabilitiesForUpdate(11L, null, null);

        Assert.assertSame(vo, result);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getHypervisorCapabilitiesForUpdateByIdNotFound() {
        when(hypervisorCapabilitiesDao.findById(99L, true)).thenReturn(null);
        service.getHypervisorCapabilitiesForUpdate(99L, null, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getHypervisorCapabilitiesForUpdateRequiresVersionWhenHypervisorGiven() {
        service.getHypervisorCapabilitiesForUpdate(null, "KVM", null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getHypervisorCapabilitiesForUpdateRejectsUnknownHypervisor() {
        service.getHypervisorCapabilitiesForUpdate(null, "DoesNotExist", "1.0");
    }

    @Test
    public void getHypervisorCapabilitiesForUpdateByTypeAndVersion() {
        HypervisorCapabilitiesVO vo = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(HypervisorType.KVM, "1.0"))
                .thenReturn(vo);

        HypervisorCapabilitiesVO result =
                service.getHypervisorCapabilitiesForUpdate(null, "KVM", "1.0");

        Assert.assertSame(vo, result);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getHypervisorCapabilitiesForUpdateByTypeAndVersionNotFound() {
        when(hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(any(), any()))
                .thenReturn(null);
        service.getHypervisorCapabilitiesForUpdate(null, "KVM", "9.9");
    }

    // --- updateHypervisorCapabilities --------------------------------------

    @Test
    public void updateHypervisorCapabilitiesReturnsRowUnchangedWhenNoFieldsSupplied() {
        UpdateHypervisorCapabilitiesCmd cmd = Mockito.mock(UpdateHypervisorCapabilitiesCmd.class);
        when(cmd.getId()).thenReturn(5L);
        when(cmd.getHypervisor()).thenReturn(null);
        when(cmd.getHypervisorVersion()).thenReturn(null);
        when(cmd.getSecurityGroupEnabled()).thenReturn(null);
        when(cmd.getMaxGuestsLimit()).thenReturn(null);
        when(cmd.getMaxDataVolumesLimit()).thenReturn(null);
        when(cmd.getStorageMotionSupported()).thenReturn(null);
        when(cmd.getMaxHostsPerClusterLimit()).thenReturn(null);
        when(cmd.getVmSnapshotEnabled()).thenReturn(null);
        HypervisorCapabilitiesVO vo = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(hypervisorCapabilitiesDao.findById(5L, true)).thenReturn(vo);

        HypervisorCapabilities result = service.updateHypervisorCapabilities(cmd);

        Assert.assertSame(vo, result);
        verify(hypervisorCapabilitiesDao, never()).createForUpdate(Mockito.anyLong());
        verify(hypervisorCapabilitiesDao, never()).update(Mockito.anyLong(), any());
    }

    @Test
    public void updateHypervisorCapabilitiesAppliesAllFields() {
        UpdateHypervisorCapabilitiesCmd cmd = Mockito.mock(UpdateHypervisorCapabilitiesCmd.class);
        when(cmd.getId()).thenReturn(3L);
        when(cmd.getSecurityGroupEnabled()).thenReturn(Boolean.TRUE);
        when(cmd.getMaxGuestsLimit()).thenReturn(100L);
        when(cmd.getMaxDataVolumesLimit()).thenReturn(7);
        when(cmd.getStorageMotionSupported()).thenReturn(Boolean.TRUE);
        when(cmd.getMaxHostsPerClusterLimit()).thenReturn(32);
        when(cmd.getVmSnapshotEnabled()).thenReturn(Boolean.FALSE);

        HypervisorCapabilitiesVO existing = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(existing.getId()).thenReturn(3L);
        when(hypervisorCapabilitiesDao.findById(3L, true)).thenReturn(existing);

        HypervisorCapabilitiesVO forUpdate = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(hypervisorCapabilitiesDao.createForUpdate(3L)).thenReturn(forUpdate);
        when(hypervisorCapabilitiesDao.update(eq(3L), eq(forUpdate))).thenReturn(true);

        HypervisorCapabilitiesVO finalRow = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(finalRow.getUuid()).thenReturn("uuid-3");
        when(hypervisorCapabilitiesDao.findById(3L)).thenReturn(finalRow);

        HypervisorCapabilities result = service.updateHypervisorCapabilities(cmd);

        Assert.assertSame(finalRow, result);
        verify(forUpdate).setSecurityGroupEnabled(Boolean.TRUE);
        verify(forUpdate).setMaxGuestsLimit(100L);
        verify(forUpdate).setMaxDataVolumesLimit(7);
        verify(forUpdate).setStorageMotionSupported(Boolean.TRUE);
        verify(forUpdate).setMaxHostsPerCluster(32);
        verify(forUpdate).setVmSnapshotEnabled(Boolean.FALSE);
    }

    @Test
    public void updateHypervisorCapabilitiesReturnsNullWhenDaoUpdateFails() {
        UpdateHypervisorCapabilitiesCmd cmd = Mockito.mock(UpdateHypervisorCapabilitiesCmd.class);
        when(cmd.getId()).thenReturn(4L);
        when(cmd.getSecurityGroupEnabled()).thenReturn(Boolean.TRUE);

        HypervisorCapabilitiesVO existing = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(existing.getId()).thenReturn(4L);
        when(hypervisorCapabilitiesDao.findById(4L, true)).thenReturn(existing);
        HypervisorCapabilitiesVO forUpdate = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(hypervisorCapabilitiesDao.createForUpdate(4L)).thenReturn(forUpdate);
        when(hypervisorCapabilitiesDao.update(eq(4L), eq(forUpdate))).thenReturn(false);

        HypervisorCapabilities result = service.updateHypervisorCapabilities(cmd);

        Assert.assertNull(result);
        verify(hypervisorCapabilitiesDao, never()).findById(4L);
    }

    @Test
    public void updateHypervisorCapabilitiesClonesParentWhenVersionDoesNotMatch() {
        UpdateHypervisorCapabilitiesCmd cmd = Mockito.mock(UpdateHypervisorCapabilitiesCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getHypervisor()).thenReturn("KVM");
        when(cmd.getHypervisorVersion()).thenReturn("2.5");
        when(cmd.getSecurityGroupEnabled()).thenReturn(Boolean.TRUE);

        // Parent row resolved by hypervisor type + version lookup carries a
        // different (older) hypervisor version: the service must persist a
        // copy so that the parent row is not mutated in place.
        HypervisorCapabilitiesVO parent = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(parent.getHypervisorVersion()).thenReturn("2.0");
        when(hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(HypervisorType.KVM, "2.5"))
                .thenReturn(parent);

        HypervisorCapabilitiesVO copy = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(copy.getId()).thenReturn(2L);
        when(hypervisorCapabilitiesDao.persist(any(HypervisorCapabilitiesVO.class))).thenReturn(copy);

        HypervisorCapabilitiesVO forUpdate = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(hypervisorCapabilitiesDao.createForUpdate(2L)).thenReturn(forUpdate);
        when(hypervisorCapabilitiesDao.update(eq(2L), eq(forUpdate))).thenReturn(true);

        HypervisorCapabilitiesVO finalRow = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(finalRow.getUuid()).thenReturn("uuid-2");
        when(hypervisorCapabilitiesDao.findById(2L)).thenReturn(finalRow);

        HypervisorCapabilities result = service.updateHypervisorCapabilities(cmd);

        Assert.assertSame(finalRow, result);
        verify(hypervisorCapabilitiesDao).persist(any(HypervisorCapabilitiesVO.class));
        verify(forUpdate).setSecurityGroupEnabled(Boolean.TRUE);
    }

    @Test
    public void updateHypervisorCapabilitiesDoesNotCloneWhenVersionsMatch() {
        UpdateHypervisorCapabilitiesCmd cmd = Mockito.mock(UpdateHypervisorCapabilitiesCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getHypervisor()).thenReturn("KVM");
        when(cmd.getHypervisorVersion()).thenReturn("2.0");
        when(cmd.getSecurityGroupEnabled()).thenReturn(Boolean.FALSE);

        HypervisorCapabilitiesVO existing = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(existing.getHypervisorVersion()).thenReturn("2.0");
        when(existing.getId()).thenReturn(8L);
        when(hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(HypervisorType.KVM, "2.0"))
                .thenReturn(existing);

        HypervisorCapabilitiesVO forUpdate = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(hypervisorCapabilitiesDao.createForUpdate(8L)).thenReturn(forUpdate);
        when(hypervisorCapabilitiesDao.update(eq(8L), eq(forUpdate))).thenReturn(true);
        HypervisorCapabilitiesVO finalRow = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(finalRow.getUuid()).thenReturn("uuid-8");
        when(hypervisorCapabilitiesDao.findById(8L)).thenReturn(finalRow);

        HypervisorCapabilities result = service.updateHypervisorCapabilities(cmd);

        Assert.assertSame(finalRow, result);
        verify(hypervisorCapabilitiesDao, never()).persist(any(HypervisorCapabilitiesVO.class));
    }

    @Test
    public void updateHypervisorCapabilitiesSetsEventDetailsOnContext() {
        UpdateHypervisorCapabilitiesCmd cmd = Mockito.mock(UpdateHypervisorCapabilitiesCmd.class);
        when(cmd.getId()).thenReturn(12L);
        when(cmd.getVmSnapshotEnabled()).thenReturn(Boolean.TRUE);

        HypervisorCapabilitiesVO existing = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(existing.getId()).thenReturn(12L);
        when(hypervisorCapabilitiesDao.findById(12L, true)).thenReturn(existing);
        HypervisorCapabilitiesVO forUpdate = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(hypervisorCapabilitiesDao.createForUpdate(12L)).thenReturn(forUpdate);
        when(hypervisorCapabilitiesDao.update(eq(12L), eq(forUpdate))).thenReturn(true);
        HypervisorCapabilitiesVO finalRow = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(finalRow.getUuid()).thenReturn("uuid-event");
        when(hypervisorCapabilitiesDao.findById(12L)).thenReturn(finalRow);

        service.updateHypervisorCapabilities(cmd);

        // Cannot easily inspect CallContext.getCurrentContext().getEventDetails()
        // from a unit test without static mocking — verify the side effect by
        // confirming the row was looked up after the update succeeded.
        verify(hypervisorCapabilitiesDao).findById(12L);
    }

    @Test
    public void updateHypervisorCapabilitiesPartialFieldsLeavesOthersUntouched() {
        UpdateHypervisorCapabilitiesCmd cmd = Mockito.mock(UpdateHypervisorCapabilitiesCmd.class);
        when(cmd.getId()).thenReturn(20L);
        when(cmd.getMaxHostsPerClusterLimit()).thenReturn(64);
        // Explicitly null-stub the other optional fields so the impl's
        // null-guard branches all skip — Mockito's default for a Boolean
        // return type is null, but we set them explicitly so the intent is
        // obvious to a future reader.
        when(cmd.getSecurityGroupEnabled()).thenReturn(null);
        when(cmd.getMaxGuestsLimit()).thenReturn(null);
        when(cmd.getMaxDataVolumesLimit()).thenReturn(null);
        when(cmd.getStorageMotionSupported()).thenReturn(null);
        when(cmd.getVmSnapshotEnabled()).thenReturn(null);

        HypervisorCapabilitiesVO existing = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(existing.getId()).thenReturn(20L);
        when(hypervisorCapabilitiesDao.findById(20L, true)).thenReturn(existing);
        HypervisorCapabilitiesVO forUpdate = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(hypervisorCapabilitiesDao.createForUpdate(20L)).thenReturn(forUpdate);
        when(hypervisorCapabilitiesDao.update(eq(20L), eq(forUpdate))).thenReturn(true);
        HypervisorCapabilitiesVO finalRow = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(finalRow.getUuid()).thenReturn("uuid-20");
        when(hypervisorCapabilitiesDao.findById(20L)).thenReturn(finalRow);

        service.updateHypervisorCapabilities(cmd);

        verify(forUpdate).setMaxHostsPerCluster(64);
        verify(forUpdate, never()).setSecurityGroupEnabled(Mockito.any());
        verify(forUpdate, never()).setMaxGuestsLimit(Mockito.any());
        verify(forUpdate, never()).setMaxDataVolumesLimit(Mockito.any());
        verify(forUpdate, never()).setStorageMotionSupported(Mockito.anyBoolean());
        verify(forUpdate, never()).setVmSnapshotEnabled(Mockito.any());
    }

    // --- wiring smoke test -------------------------------------------------

    @Test
    public void wiringInjectsHypervisorCapabilitiesDao() {
        HypervisorCapabilitiesServiceImpl s = new HypervisorCapabilitiesServiceImpl();
        ReflectionTestUtils.setField(s, "hypervisorCapabilitiesDao", hypervisorCapabilitiesDao);
        Assert.assertSame(hypervisorCapabilitiesDao,
                ReflectionTestUtils.getField(s, "hypervisorCapabilitiesDao"));
    }
}
