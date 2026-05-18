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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import org.apache.cloudstack.api.command.admin.guest.AddGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.admin.guest.DeleteGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.admin.guest.UpdateGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.user.guest.ListGuestOsCategoriesCmd;
import org.apache.cloudstack.resourcedetail.dao.GuestOsDetailsDao;

import com.cloud.api.ApiDBUtils;
import com.cloud.cpu.CPU;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.storage.GuestOSCategoryVO;
import com.cloud.storage.GuestOSHypervisorVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.GuestOsCategory;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.GuestOSHypervisorDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

/**
 * Focused unit tests for {@link GuestOsManagementServiceImpl}.
 * Migrated from {@code ManagementServerImplTest} and extended for full coverage.
 */
@RunWith(MockitoJUnitRunner.class)
public class GuestOsManagementServiceImplTest {

    @Mock
    GuestOSCategoryDao guestOSCategoryDao;

    @Mock
    GuestOSDao guestOSDao;

    @Mock
    GuestOSHypervisorDao guestOSHypervisorDao;

    @Mock
    HypervisorCapabilitiesDao hypervisorCapabilitiesDao;

    @Mock
    GuestOsDetailsDao guestOsDetailsDao;

    @Mock
    VMTemplateDao templateDao;

    @Spy
    @InjectMocks
    GuestOsManagementServiceImpl service = new GuestOsManagementServiceImpl();

    private AutoCloseable closeable;
    private MockedStatic<ApiDBUtils> apiDBUtilsMock;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        apiDBUtilsMock = Mockito.mockStatic(ApiDBUtils.class);
    }

    @After
    public void tearDown() throws Exception {
        if (apiDBUtilsMock != null) {
            apiDBUtilsMock.close();
        }
        closeable.close();
    }

    // -------------------------------------------------------------------------
    // addGuestOsCategory
    // -------------------------------------------------------------------------

    @Test
    public void testAddGuestOsCategory() {
        AddGuestOsCategoryCmd addCmd = Mockito.mock(AddGuestOsCategoryCmd.class);
        String name = "Ubuntu";
        boolean featured = true;
        Mockito.when(addCmd.getName()).thenReturn(name);
        Mockito.when(addCmd.isFeatured()).thenReturn(featured);
        Mockito.doAnswer((Answer<GuestOSCategoryVO>) invocation ->
                (GuestOSCategoryVO) invocation.getArguments()[0])
                .when(guestOSCategoryDao).persist(any(GuestOSCategoryVO.class));

        GuestOsCategory result = service.addGuestOsCategory(addCmd);

        Assert.assertNotNull(result);
        Assert.assertEquals(name, result.getName());
        Assert.assertEquals(featured, result.isFeatured());
        verify(guestOSCategoryDao, times(1)).persist(any(GuestOSCategoryVO.class));
    }

    // -------------------------------------------------------------------------
    // updateGuestOsCategory
    // -------------------------------------------------------------------------

    @Test
    public void testUpdateGuestOsCategory() {
        UpdateGuestOsCategoryCmd updateCmd = Mockito.mock(UpdateGuestOsCategoryCmd.class);
        GuestOSCategoryVO guestOSCategory = new GuestOSCategoryVO("Old name", false);
        long id = 1L;
        String name = "Updated Name";
        Boolean featured = true;
        Integer sortKey = 10;
        Mockito.when(updateCmd.getId()).thenReturn(id);
        Mockito.when(updateCmd.getName()).thenReturn(name);
        Mockito.when(updateCmd.isFeatured()).thenReturn(featured);
        Mockito.when(updateCmd.getSortKey()).thenReturn(sortKey);
        Mockito.when(guestOSCategoryDao.findById(id)).thenReturn(guestOSCategory);
        Mockito.when(guestOSCategoryDao.update(eq(id), any(GuestOSCategoryVO.class))).thenReturn(true);

        GuestOsCategory result = service.updateGuestOsCategory(updateCmd);

        Assert.assertNotNull(result);
        Assert.assertEquals(name, result.getName());
        Assert.assertEquals(featured, result.isFeatured());
        verify(guestOSCategoryDao, times(1)).findById(id);
        verify(guestOSCategoryDao, times(1)).update(eq(id), any(GuestOSCategoryVO.class));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testUpdateGuestOsCategory_ThrowsExceptionWhenCategoryNotFound() {
        UpdateGuestOsCategoryCmd updateCmd = Mockito.mock(UpdateGuestOsCategoryCmd.class);
        long id = 1L;
        when(updateCmd.getId()).thenReturn(id);
        when(guestOSCategoryDao.findById(id)).thenReturn(null);
        service.updateGuestOsCategory(updateCmd);
    }

    @Test
    public void testUpdateGuestOsCategory_NoChanges() {
        UpdateGuestOsCategoryCmd updateCmd = Mockito.mock(UpdateGuestOsCategoryCmd.class);
        GuestOSCategoryVO guestOSCategory = new GuestOSCategoryVO("Old name", false);
        long id = 1L;
        when(updateCmd.getId()).thenReturn(id);
        when(updateCmd.getName()).thenReturn(null);
        when(updateCmd.isFeatured()).thenReturn(null);
        when(updateCmd.getSortKey()).thenReturn(null);
        when(guestOSCategoryDao.findById(id)).thenReturn(guestOSCategory);

        GuestOsCategory result = service.updateGuestOsCategory(updateCmd);

        Assert.assertNotNull(result);
        Assert.assertNotNull(result.getName());
        Assert.assertFalse(result.isFeatured());
        verify(guestOSCategoryDao, times(1)).findById(id);
        verify(guestOSCategoryDao, Mockito.never()).update(eq(id), any(GuestOSCategoryVO.class));
    }

    @Test
    public void testUpdateGuestOsCategory_UpdateNameOnly() {
        UpdateGuestOsCategoryCmd updateCmd = Mockito.mock(UpdateGuestOsCategoryCmd.class);
        GuestOSCategoryVO guestOSCategory = new GuestOSCategoryVO("Old name", false);
        long id = 1L;
        String name = "Updated Name";
        Mockito.when(updateCmd.getId()).thenReturn(id);
        Mockito.when(updateCmd.getName()).thenReturn(name);
        Mockito.when(updateCmd.isFeatured()).thenReturn(null);
        Mockito.when(updateCmd.getSortKey()).thenReturn(null);
        Mockito.when(guestOSCategoryDao.findById(id)).thenReturn(guestOSCategory);
        Mockito.when(guestOSCategoryDao.update(eq(id), any(GuestOSCategoryVO.class))).thenReturn(true);

        GuestOsCategory result = service.updateGuestOsCategory(updateCmd);

        Assert.assertNotNull(result);
        Assert.assertEquals(name, result.getName());
        Assert.assertFalse(result.isFeatured());
        verify(guestOSCategoryDao, times(1)).findById(id);
        verify(guestOSCategoryDao, times(1)).update(eq(id), any(GuestOSCategoryVO.class));
    }

    // -------------------------------------------------------------------------
    // deleteGuestOsCategory
    // -------------------------------------------------------------------------

    @Test
    public void testDeleteGuestOsCategory_Successful() {
        DeleteGuestOsCategoryCmd deleteCmd = Mockito.mock(DeleteGuestOsCategoryCmd.class);
        GuestOSCategoryVO guestOSCategory = Mockito.mock(GuestOSCategoryVO.class);
        long id = 1L;
        Mockito.when(deleteCmd.getId()).thenReturn(id);
        Mockito.when(guestOSCategoryDao.findById(id)).thenReturn(guestOSCategory);
        Mockito.when(guestOSDao.listIdsByCategoryId(id)).thenReturn(Arrays.asList());
        Mockito.when(guestOSCategoryDao.remove(id)).thenReturn(true);

        boolean result = service.deleteGuestOsCategory(deleteCmd);

        Assert.assertTrue(result);
        verify(guestOSCategoryDao, times(1)).findById(id);
        verify(guestOSDao, times(1)).listIdsByCategoryId(id);
        verify(guestOSCategoryDao, times(1)).remove(id);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDeleteGuestOsCategory_ThrowsExceptionWhenCategoryNotFound() {
        DeleteGuestOsCategoryCmd deleteCmd = Mockito.mock(DeleteGuestOsCategoryCmd.class);
        long id = 1L;
        Mockito.when(deleteCmd.getId()).thenReturn(id);
        Mockito.when(guestOSCategoryDao.findById(id)).thenReturn(null);
        service.deleteGuestOsCategory(deleteCmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDeleteGuestOsCategory_ThrowsExceptionWhenGuestOsExists() {
        DeleteGuestOsCategoryCmd deleteCmd = Mockito.mock(DeleteGuestOsCategoryCmd.class);
        GuestOSCategoryVO guestOSCategory = Mockito.mock(GuestOSCategoryVO.class);
        long id = 1L;
        Mockito.when(deleteCmd.getId()).thenReturn(id);
        Mockito.when(guestOSCategoryDao.findById(id)).thenReturn(guestOSCategory);
        Mockito.when(guestOSDao.listIdsByCategoryId(id)).thenReturn(Arrays.asList(1L));
        service.deleteGuestOsCategory(deleteCmd);
    }

    // -------------------------------------------------------------------------
    // listGuestOSCategoriesByCriteria
    // -------------------------------------------------------------------------

    private void mockGuestOsJoin() {
        GuestOSVO vo = mock(GuestOSVO.class);
        SearchBuilder<GuestOSVO> sb = mock(SearchBuilder.class);
        when(sb.entity()).thenReturn(vo);
        when(guestOSDao.createSearchBuilder()).thenReturn(sb);
    }

    @Test
    public void testListGuestOSCategoriesByCriteria_Success() {
        ListGuestOsCategoriesCmd listCmd = Mockito.mock(ListGuestOsCategoriesCmd.class);
        GuestOSCategoryVO guestOSCategory = Mockito.mock(GuestOSCategoryVO.class);
        Long id = 1L;
        String name = "Ubuntu";
        String keyword = "Linux";
        Boolean featured = true;
        Long zoneId = 1L;
        CPU.CPUArch arch = CPU.CPUArch.getDefault();
        Boolean isIso = true;
        Boolean isVnf = false;
        Mockito.when(listCmd.getId()).thenReturn(id);
        Mockito.when(listCmd.getName()).thenReturn(name);
        Mockito.when(listCmd.getKeyword()).thenReturn(keyword);
        Mockito.when(listCmd.isFeatured()).thenReturn(featured);
        Mockito.when(listCmd.getZoneId()).thenReturn(zoneId);
        Mockito.when(listCmd.getArch()).thenReturn(arch);
        Mockito.when(listCmd.isIso()).thenReturn(isIso);
        Mockito.when(listCmd.isVnf()).thenReturn(isVnf);
        SearchBuilder<GuestOSCategoryVO> searchBuilder = Mockito.mock(SearchBuilder.class);
        Mockito.when(searchBuilder.entity()).thenReturn(guestOSCategory);
        SearchCriteria<GuestOSCategoryVO> searchCriteria = Mockito.mock(SearchCriteria.class);
        Mockito.when(guestOSCategoryDao.createSearchBuilder()).thenReturn(searchBuilder);
        Mockito.when(searchBuilder.create()).thenReturn(searchCriteria);
        Mockito.when(templateDao.listTemplateIsoByArchVnfAndZone(zoneId, arch, isIso, isVnf))
                .thenReturn(Arrays.asList(1L, 2L));
        Pair<List<GuestOSCategoryVO>, Integer> mockResult = new Pair<>(Arrays.asList(guestOSCategory), 1);
        mockGuestOsJoin();
        Mockito.when(guestOSCategoryDao.searchAndCount(eq(searchCriteria), any())).thenReturn(mockResult);

        Pair<List<? extends GuestOsCategory>, Integer> result = service.listGuestOSCategoriesByCriteria(listCmd);

        Assert.assertNotNull(result);
        Assert.assertEquals(1, result.second().intValue());
        Assert.assertEquals(1, result.first().size());
        verify(guestOSCategoryDao, times(1)).createSearchBuilder();
        verify(templateDao, times(1)).listTemplateIsoByArchVnfAndZone(zoneId, arch, isIso, isVnf);
        verify(guestOSCategoryDao, times(1)).searchAndCount(eq(searchCriteria), any());
    }

    @Test
    public void testListGuestOSCategoriesByCriteria_NoResults() {
        ListGuestOsCategoriesCmd listCmd = Mockito.mock(ListGuestOsCategoriesCmd.class);
        GuestOSCategoryVO guestOSCategory = Mockito.mock(GuestOSCategoryVO.class);
        Long id = 1L;
        String name = "CentOS";
        String keyword = "Linux";
        Boolean featured = false;
        Long zoneId = 1L;
        CPU.CPUArch arch = CPU.CPUArch.getDefault();
        Boolean isIso = false;
        Boolean isVnf = false;
        Mockito.when(listCmd.getId()).thenReturn(id);
        Mockito.when(listCmd.getName()).thenReturn(name);
        Mockito.when(listCmd.getKeyword()).thenReturn(keyword);
        Mockito.when(listCmd.isFeatured()).thenReturn(featured);
        Mockito.when(listCmd.getZoneId()).thenReturn(zoneId);
        Mockito.when(listCmd.getArch()).thenReturn(arch);
        Mockito.when(listCmd.isIso()).thenReturn(isIso);
        Mockito.when(listCmd.isVnf()).thenReturn(isVnf);
        SearchBuilder<GuestOSCategoryVO> searchBuilder = Mockito.mock(SearchBuilder.class);
        Mockito.when(searchBuilder.entity()).thenReturn(guestOSCategory);
        SearchCriteria<GuestOSCategoryVO> searchCriteria = Mockito.mock(SearchCriteria.class);
        Mockito.when(guestOSCategoryDao.createSearchBuilder()).thenReturn(searchBuilder);
        Mockito.when(searchBuilder.create()).thenReturn(searchCriteria);
        Mockito.when(templateDao.listTemplateIsoByArchVnfAndZone(zoneId, arch, isIso, isVnf))
                .thenReturn(Arrays.asList(1L, 2L));
        Pair<List<GuestOSCategoryVO>, Integer> mockResult = new Pair<>(Arrays.asList(), 0);
        Mockito.when(guestOSCategoryDao.searchAndCount(eq(searchCriteria), any())).thenReturn(mockResult);
        mockGuestOsJoin();

        Pair<List<? extends GuestOsCategory>, Integer> result = service.listGuestOSCategoriesByCriteria(listCmd);

        Assert.assertNotNull(result);
        Assert.assertEquals(0, result.second().intValue());
        Assert.assertEquals(0, result.first().size());
        verify(guestOSCategoryDao, times(1)).createSearchBuilder();
        verify(templateDao, times(1)).listTemplateIsoByArchVnfAndZone(zoneId, arch, isIso, isVnf);
        verify(guestOSCategoryDao, times(1)).searchAndCount(eq(searchCriteria), any());
    }

    @Test
    public void testListGuestOSCategoriesByCriteria_NoGuestOsIdsFound() {
        ListGuestOsCategoriesCmd listCmd = Mockito.mock(ListGuestOsCategoriesCmd.class);
        GuestOSCategoryVO guestOSCategory = Mockito.mock(GuestOSCategoryVO.class);
        Long id = 1L;
        String name = "Ubuntu";
        String keyword = "Linux";
        Boolean featured = true;
        Long zoneId = 1L;
        CPU.CPUArch arch = CPU.CPUArch.getDefault();
        Boolean isIso = true;
        Boolean isVnf = false;
        Mockito.when(listCmd.getId()).thenReturn(id);
        Mockito.when(listCmd.getName()).thenReturn(name);
        Mockito.when(listCmd.getKeyword()).thenReturn(keyword);
        Mockito.when(listCmd.isFeatured()).thenReturn(featured);
        Mockito.when(listCmd.getZoneId()).thenReturn(zoneId);
        Mockito.when(listCmd.getArch()).thenReturn(arch);
        Mockito.when(listCmd.isIso()).thenReturn(isIso);
        Mockito.when(listCmd.isVnf()).thenReturn(isVnf);
        SearchBuilder<GuestOSCategoryVO> searchBuilder = Mockito.mock(SearchBuilder.class);
        Mockito.when(searchBuilder.entity()).thenReturn(guestOSCategory);
        SearchCriteria<GuestOSCategoryVO> searchCriteria = Mockito.mock(SearchCriteria.class);
        Mockito.when(guestOSCategoryDao.createSearchBuilder()).thenReturn(searchBuilder);
        Mockito.when(searchBuilder.create()).thenReturn(searchCriteria);
        // Return empty list to trigger early-exit path
        Mockito.when(templateDao.listTemplateIsoByArchVnfAndZone(zoneId, arch, isIso, isVnf))
                .thenReturn(Collections.emptyList());
        mockGuestOsJoin();

        Pair<List<? extends GuestOsCategory>, Integer> result = service.listGuestOSCategoriesByCriteria(listCmd);

        Assert.assertNotNull(result);
        Assert.assertEquals(0, result.second().intValue());
        Assert.assertEquals(0, result.first().size());
        verify(templateDao, times(1)).listTemplateIsoByArchVnfAndZone(zoneId, arch, isIso, isVnf);
        // Early exit: searchAndCount should NOT be called when ids list is empty
        verify(guestOSCategoryDao, Mockito.never()).searchAndCount(any(), any());
    }

    @Test
    public void testListGuestOSCategoriesByCriteria_FilterById() {
        ListGuestOsCategoriesCmd listCmd = Mockito.mock(ListGuestOsCategoriesCmd.class);
        GuestOSCategoryVO guestOSCategory = Mockito.mock(GuestOSCategoryVO.class);
        Long id = 1L;
        Mockito.when(listCmd.getId()).thenReturn(id);
        Mockito.when(listCmd.getZoneId()).thenReturn(null);
        Mockito.when(listCmd.isIso()).thenReturn(null);
        Mockito.when(listCmd.isVnf()).thenReturn(null);
        SearchBuilder<GuestOSCategoryVO> searchBuilder = Mockito.mock(SearchBuilder.class);
        Mockito.when(searchBuilder.entity()).thenReturn(guestOSCategory);
        SearchCriteria<GuestOSCategoryVO> searchCriteria = Mockito.mock(SearchCriteria.class);
        Mockito.when(guestOSCategoryDao.createSearchBuilder()).thenReturn(searchBuilder);
        Mockito.when(searchBuilder.create()).thenReturn(searchCriteria);
        Pair<List<GuestOSCategoryVO>, Integer> mockResult = new Pair<>(Arrays.asList(guestOSCategory), 1);
        Mockito.when(guestOSCategoryDao.searchAndCount(eq(searchCriteria), any())).thenReturn(mockResult);
        mockGuestOsJoin();

        Pair<List<? extends GuestOsCategory>, Integer> result = service.listGuestOSCategoriesByCriteria(listCmd);

        Assert.assertNotNull(result);
        Assert.assertEquals(1, result.second().intValue());
        Assert.assertEquals(1, result.first().size());
        verify(guestOSCategoryDao, times(1)).createSearchBuilder();
        verify(searchCriteria, times(1)).setParameters("id", id);
        verify(guestOSCategoryDao, times(1)).searchAndCount(eq(searchCriteria), any());
    }

    // -------------------------------------------------------------------------
    // getGuestOs / getGuestOsHypervisor
    // -------------------------------------------------------------------------

    @Test
    public void testGetGuestOs_ReturnsVO() {
        GuestOSVO vo = Mockito.mock(GuestOSVO.class);
        long id = 42L;
        when(guestOSDao.findById(id)).thenReturn(vo);

        GuestOSVO result = service.getGuestOs(id);

        Assert.assertSame(vo, result);
        verify(guestOSDao, times(1)).findById(id);
    }

    @Test
    public void testGetGuestOs_ReturnsNullWhenNotFound() {
        long id = 99L;
        when(guestOSDao.findById(id)).thenReturn(null);

        GuestOSVO result = service.getGuestOs(id);

        Assert.assertNull(result);
    }

    @Test
    public void testGetGuestOsHypervisor_ReturnsVO() {
        GuestOSHypervisorVO vo = Mockito.mock(GuestOSHypervisorVO.class);
        long id = 7L;
        when(guestOSHypervisorDao.findById(id)).thenReturn(vo);

        GuestOSHypervisorVO result = service.getGuestOsHypervisor(id);

        Assert.assertSame(vo, result);
        verify(guestOSHypervisorDao, times(1)).findById(id);
    }
}
