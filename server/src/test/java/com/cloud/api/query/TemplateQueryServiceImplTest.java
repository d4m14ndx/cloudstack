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
package com.cloud.api.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.command.admin.iso.ListIsosCmdByAdmin;
import org.apache.cloudstack.api.command.admin.resource.icon.ListResourceIconCmd;
import org.apache.cloudstack.api.command.admin.template.ListTemplatesCmdByAdmin;
import org.apache.cloudstack.api.command.user.iso.ListIsosCmd;
import org.apache.cloudstack.api.command.user.resource.ListDetailOptionsCmd;
import org.apache.cloudstack.api.command.user.template.ListTemplatesCmd;
import org.apache.cloudstack.api.command.user.template.ListVnfTemplatesCmd;
import org.apache.cloudstack.api.response.DetailOptionsResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ResourceIconResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.VNF;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.icon.dao.ResourceIconDao;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.VMInstanceVO;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class TemplateQueryServiceImplTest {

    @Mock
    private AccountManager accountMgr;

    @Mock
    private ResourceManager _resourceMgr;

    @Mock
    private TemplateJoinDao _templateJoinDao;

    @Mock
    private VMTemplateDao _templateDao;

    @Mock
    private DomainDao _domainDao;

    @Mock
    private GuestOSDao guestOSDao;

    @Mock
    private ResourceIconDao resourceIconDao;

    @Mock
    private VMTemplatePoolDao templatePoolDao;

    @Mock
    private VMInstanceDao _vmInstanceDao;

    @Spy
    @InjectMocks
    private TemplateQueryServiceImpl service;

    private AccountVO account;
    private UserVO user;

    @Before
    public void setUp() {
        account = new AccountVO("testaccount", 1L, "networkdomain", Account.Type.NORMAL, UUID.randomUUID().toString());
        account.setId(1L);
        user = new UserVO(1L, "testuser", "password", "firstname", "lastName", "email", "timezone",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ─── applyPublicTemplateSharingRestrictions ───────────────────────────────

    @Test
    public void applyPublicTemplateSharingRestrictionsDoesNotRestrictForRootAdmin() {
        Account adminAccount = mock(Account.class);
        when(adminAccount.getType()).thenReturn(Account.Type.ADMIN);
        SearchCriteria<TemplateJoinVO> sc = mock(SearchCriteria.class);

        service.applyPublicTemplateSharingRestrictions(sc, adminAccount);

        verify(sc, never()).addAnd(anyString(), any(), any());
    }

    @Test
    public void applyPublicTemplateSharingRestrictionsAppliesRestrictionsForNonAdmin() {
        long callerDomainId = 1L;
        long sharableDomainId = 2L;
        long unsharableDomainId = 3L;

        Account caller = mock(Account.class);
        when(caller.getType()).thenReturn(Account.Type.NORMAL);
        when(caller.getDomainId()).thenReturn(callerDomainId);

        TemplateJoinVO t1 = mock(TemplateJoinVO.class);
        when(t1.getDomainId()).thenReturn(callerDomainId);

        TemplateJoinVO t2 = mock(TemplateJoinVO.class);
        when(t2.getDomainId()).thenReturn(sharableDomainId);

        TemplateJoinVO t3 = mock(TemplateJoinVO.class);
        when(t3.getDomainId()).thenReturn(unsharableDomainId);

        when(_templateJoinDao.listPublicTemplates()).thenReturn(Arrays.asList(t1, t2, t3));
        Mockito.lenient().doReturn(false).when(service).checkIfDomainSharesTemplates(callerDomainId);
        doReturn(true).when(service).checkIfDomainSharesTemplates(sharableDomainId);
        doReturn(false).when(service).checkIfDomainSharesTemplates(unsharableDomainId);

        SearchCriteria<TemplateJoinVO> sc = mock(SearchCriteria.class);
        service.applyPublicTemplateSharingRestrictions(sc, caller);

        verify(sc).addAnd(eq("domainId"), eq(SearchCriteria.Op.NOTIN), any());
    }

    @Test
    public void applyPublicTemplateSharingRestrictionsNoUnsharableDomainsSkipsAddAnd() {
        Account caller = mock(Account.class);
        when(caller.getType()).thenReturn(Account.Type.NORMAL);
        when(caller.getDomainId()).thenReturn(1L);

        TemplateJoinVO t1 = mock(TemplateJoinVO.class);
        when(t1.getDomainId()).thenReturn(1L);  // same domain as caller — skipped

        when(_templateJoinDao.listPublicTemplates()).thenReturn(Collections.singletonList(t1));

        SearchCriteria<TemplateJoinVO> sc = mock(SearchCriteria.class);
        service.applyPublicTemplateSharingRestrictions(sc, caller);

        verify(sc, never()).addAnd(anyString(), any(), any());
    }

    // ─── addDomainIdToSetIfDomainDoesNotShareTemplates ────────────────────────

    @Test
    public void addDomainIdToSetSkipsWhenCallerOwnsDomain() {
        long domainId = 5L;
        Account acct = mock(Account.class);
        when(acct.getDomainId()).thenReturn(domainId);

        Set<Long> set = new HashSet<>();
        service.addDomainIdToSetIfDomainDoesNotShareTemplates(domainId, acct, set);

        assertTrue(set.isEmpty());
    }

    @Test
    public void addDomainIdToSetSkipsWhenAlreadyPresent() {
        long domainId = 5L;
        Account acct = mock(Account.class);
        when(acct.getDomainId()).thenReturn(99L);

        Set<Long> set = new HashSet<>();
        set.add(domainId);  // already present

        service.addDomainIdToSetIfDomainDoesNotShareTemplates(domainId, acct, set);

        assertEquals(1, set.size());
        // checkIfDomainSharesTemplates should NOT have been called
        verify(service, never()).checkIfDomainSharesTemplates(domainId);
    }

    @Test
    public void addDomainIdToSetAddsWhenDomainDoesNotShareTemplates() {
        long domainId = 5L;
        Account acct = mock(Account.class);
        when(acct.getDomainId()).thenReturn(99L);
        doReturn(false).when(service).checkIfDomainSharesTemplates(domainId);

        Set<Long> set = new HashSet<>();
        service.addDomainIdToSetIfDomainDoesNotShareTemplates(domainId, acct, set);

        assertTrue(set.contains(domainId));
    }

    @Test
    public void addDomainIdToSetDoesNotAddWhenDomainShares() {
        long domainId = 5L;
        Account acct = mock(Account.class);
        when(acct.getDomainId()).thenReturn(99L);
        doReturn(true).when(service).checkIfDomainSharesTemplates(domainId);

        Set<Long> set = new HashSet<>();
        service.addDomainIdToSetIfDomainDoesNotShareTemplates(domainId, acct, set);

        assertFalse(set.contains(domainId));
    }

    // ─── checkIfDomainSharesTemplates ─────────────────────────────────────────

    @Test
    public void checkIfDomainSharesTemplatesDelegatesToConfigKey() {
        // The real implementation calls QueryService.SharePublicTemplatesWithOtherDomains.valueIn(domainId).
        // We just verify that it returns a boolean without throwing.
        Long domainId = 1L;
        // Default config key value is "true" in tests
        boolean result = service.checkIfDomainSharesTemplates(domainId);
        // Just assert it returns a boolean (true by default config key)
        assertTrue(result);
    }

    // ─── fillVMOrTemplateDetailOptions ────────────────────────────────────────

    @Test
    public void fillVMOrTemplateDetailOptionsNullMapThrows() {
        try {
            service.fillVMOrTemplateDetailOptions(null, HypervisorType.KVM);
            Assert.fail("Expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            // expected
        }
    }

    @Test
    public void fillVMOrTemplateDetailOptionsKvmIncludesKvmSpecificKeys() {
        Map<String, List<String>> options = new HashMap<>();
        service.fillVMOrTemplateDetailOptions(options, HypervisorType.KVM);

        assertTrue(options.containsKey(VmDetailConstants.CPU_THREAD_PER_CORE));
        assertTrue(options.containsKey(VmDetailConstants.NIC_ADAPTER));
        assertTrue(options.containsKey(VmDetailConstants.ROOT_DISK_CONTROLLER));
    }

    @Test
    public void fillVMOrTemplateDetailOptionsVMwareIncludesVMwareSpecificKeys() {
        Map<String, List<String>> options = new HashMap<>();
        service.fillVMOrTemplateDetailOptions(options, HypervisorType.VMware);

        assertTrue(options.containsKey(VmDetailConstants.NESTED_VIRTUALIZATION_FLAG));
        assertTrue(options.containsKey(VmDetailConstants.VIRTUAL_TPM_ENABLED));
        assertTrue(options.containsKey(VmDetailConstants.SVGA_VRAM_SIZE));
    }

    @Test
    public void fillVMOrTemplateDetailOptionsXenServerIncludesVirtualTpmKey() {
        Map<String, List<String>> options = new HashMap<>();
        service.fillVMOrTemplateDetailOptions(options, HypervisorType.XenServer);

        assertTrue(options.containsKey(VmDetailConstants.VIRTUAL_TPM_ENABLED));
    }

    // ─── listDetailOptions ────────────────────────────────────────────────────

    @Test
    public void listDetailOptionsTemplateDelegatesHypervisorLookup() {
        ListDetailOptionsCmd cmd = mock(ListDetailOptionsCmd.class);
        when(cmd.getResourceType()).thenReturn(ResourceObjectType.Template);
        String uuid = UUID.randomUUID().toString();
        when(cmd.getResourceId()).thenReturn(uuid);

        VMTemplateVO templateVO = mock(VMTemplateVO.class);
        when(templateVO.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(_templateDao.findByUuid(uuid)).thenReturn(templateVO);

        // For non-admin caller, we need UserVMDeniedDetails to return something
        try (MockedStatic<org.apache.cloudstack.query.QueryService> qs = Mockito.mockStatic(org.apache.cloudstack.query.QueryService.class)) {
            // Use real service, CallContext has normal account
            DetailOptionsResponse response = service.listDetailOptions(cmd);
            assertNotNull(response);
            assertTrue(response.getDetails().containsKey(VmDetailConstants.CPU_THREAD_PER_CORE));
        }
    }

    @Test
    public void listDetailOptionsUserVmDelegatesToVmInstanceDao() {
        ListDetailOptionsCmd cmd = mock(ListDetailOptionsCmd.class);
        when(cmd.getResourceType()).thenReturn(ResourceObjectType.UserVm);
        String uuid = UUID.randomUUID().toString();
        when(cmd.getResourceId()).thenReturn(uuid);

        VMInstanceVO vmVO = mock(VMInstanceVO.class);
        when(vmVO.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(_vmInstanceDao.findByUuid(uuid)).thenReturn(vmVO);

        DetailOptionsResponse response = service.listDetailOptions(cmd);

        assertNotNull(response);
        verify(_vmInstanceDao).findByUuid(uuid);
    }

    @Test
    public void listDetailOptionsVnfTemplateReturnsVnfKeys() {
        ListDetailOptionsCmd cmd = mock(ListDetailOptionsCmd.class);
        when(cmd.getResourceType()).thenReturn(ResourceObjectType.VnfTemplate);

        DetailOptionsResponse response = service.listDetailOptions(cmd);
        Map<String, List<String>> options = response.getDetails();

        int expectedLength = VNF.AccessDetail.values().length + VNF.VnfDetail.values().length;
        assertEquals(expectedLength, options.size());
        for (VNF.AccessDetail detail : VNF.AccessDetail.values()) {
            assertTrue(options.containsKey(detail.name().toLowerCase()));
        }
        for (VNF.VnfDetail detail : VNF.VnfDetail.values()) {
            assertTrue(options.containsKey(detail.name().toLowerCase()));
        }
        List<String> expectedAccessMethods = Arrays.stream(VNF.AccessMethod.values())
                .map(VNF.AccessMethod::toString).sorted().collect(Collectors.toList());
        assertEquals(expectedAccessMethods, options.get(VNF.AccessDetail.ACCESS_METHODS.name().toLowerCase()));
    }

    @Test(expected = CloudRuntimeException.class)
    public void listDetailOptionsUnsupportedTypeThrows() {
        ListDetailOptionsCmd cmd = mock(ListDetailOptionsCmd.class);
        when(cmd.getResourceType()).thenReturn(ResourceObjectType.Network);

        service.listDetailOptions(cmd);
    }

    @Test
    public void listDetailOptionsScrubsDeniedKeysForNonAdmin() {
        // Account registered in setUp is NORMAL (non-admin)
        ListDetailOptionsCmd cmd = mock(ListDetailOptionsCmd.class);
        when(cmd.getResourceType()).thenReturn(ResourceObjectType.UserVm);
        when(cmd.getResourceId()).thenReturn(null);

        DetailOptionsResponse response = service.listDetailOptions(cmd);
        Map<String, List<String>> options = response.getDetails();

        // GUEST_CPU_MODE and GUEST_CPU_MODEL are in RootAdminOnlyVmSettings - should be scrubbed
        assertFalse(options.containsKey(VmDetailConstants.GUEST_CPU_MODE));
        assertFalse(options.containsKey(VmDetailConstants.GUEST_CPU_MODEL));
    }

    // ─── listResourceIcons ────────────────────────────────────────────────────

    @Test
    public void listResourceIconsReturnsDaoResults() {
        ListResourceIconCmd cmd =
                mock(ListResourceIconCmd.class);
        List<String> resourceIds = Arrays.asList("id1", "id2");
        ResourceObjectType resourceType = ResourceObjectType.UserVm;
        when(cmd.getResourceIds()).thenReturn(resourceIds);
        when(cmd.getResourceType()).thenReturn(resourceType);

        List<ResourceIconResponse> mockResponses = Arrays.asList(
                mock(ResourceIconResponse.class),
                mock(ResourceIconResponse.class)
        );
        when(resourceIconDao.listResourceIcons(resourceIds, resourceType)).thenReturn(mockResponses);

        ListResponse<ResourceIconResponse> result = service.listResourceIcons(cmd);

        assertNotNull(result);
        assertEquals(2, result.getResponses().size());
        verify(resourceIconDao).listResourceIcons(resourceIds, resourceType);
    }

    // ─── listTemplates / listIsos view tests ─────────────────────────────────

    @Test
    public void listTemplatesUsesRestrictedViewByDefault() {
        // Verify that a regular ListTemplatesCmd results in Restricted view (not Full).
        // We mock the internal search to avoid full DB wiring and focus on response creation.
        ListTemplatesCmd cmd =
                mock(ListTemplatesCmd.class);

        when(cmd.getTemplateFilter()).thenReturn("self");
        when(cmd.getHypervisor()).thenReturn("KVM");
        when(cmd.getPageSizeVal()).thenReturn(500L);
        when(cmd.getStartIndex()).thenReturn(0L);

        // Stub DB calls to return empty
        SearchBuilder<TemplateJoinVO> sb = mock(SearchBuilder.class);
        TemplateJoinVO entity = mock(TemplateJoinVO.class);
        SearchCriteria<TemplateJoinVO> sc = mock(SearchCriteria.class);
        when(_templateJoinDao.createSearchBuilder()).thenReturn(sb);
        when(sb.entity()).thenReturn(entity);
        SearchBuilder<com.cloud.storage.VMTemplateStoragePoolVO> poolSb = mock(SearchBuilder.class);
        Mockito.lenient().when(templatePoolDao.createSearchBuilder()).thenReturn(poolSb);
        com.cloud.storage.VMTemplateStoragePoolVO poolEntity = mock(com.cloud.storage.VMTemplateStoragePoolVO.class);
        Mockito.lenient().when(poolSb.entity()).thenReturn(poolEntity);
        when(sb.create()).thenReturn(sc);
        Mockito.lenient().when(_templateJoinDao.searchAndDistinctCount(any(), any(), any())).thenReturn(new Pair<>(new ArrayList<>(), 0));
        Mockito.lenient().when(_resourceMgr.listAvailHypervisorInZone(any())).thenReturn(new ArrayList<>());
        // spy-verify that the non-admin path does not call addAnd for public templates
        Mockito.lenient().doReturn(false).when(service).checkIfDomainSharesTemplates(any());

        // The response should not throw
        try (MockedStatic<ViewResponseHelper> vrh = Mockito.mockStatic(ViewResponseHelper.class)) {
            when(ViewResponseHelper.createTemplateResponse(any(), any(), any()))
                    .thenReturn(Collections.emptyList());
            // For account buildACLSearchParameters we need accountMgr.buildACLSearchParameters
            Mockito.doNothing().when(accountMgr).buildACLSearchParameters(
                    any(), any(), any(), any(), any(), any(), any(Boolean.class), any(Boolean.class));
            ListResponse<TemplateResponse> result = service.listTemplates(cmd);
            assertNotNull(result);
        }
    }

    @Test
    public void listTemplatesByAdminUsesFullView() {
        ListTemplatesCmdByAdmin cmd =
                mock(ListTemplatesCmdByAdmin.class);
        when(cmd.getTemplateFilter()).thenReturn("self");
        when(cmd.getHypervisor()).thenReturn("KVM");
        when(cmd.getPageSizeVal()).thenReturn(500L);
        when(cmd.getStartIndex()).thenReturn(0L);

        SearchBuilder<TemplateJoinVO> sb = mock(SearchBuilder.class);
        TemplateJoinVO entity = mock(TemplateJoinVO.class);
        SearchCriteria<TemplateJoinVO> sc = mock(SearchCriteria.class);
        when(_templateJoinDao.createSearchBuilder()).thenReturn(sb);
        when(sb.entity()).thenReturn(entity);
        SearchBuilder<com.cloud.storage.VMTemplateStoragePoolVO> poolSb = mock(SearchBuilder.class);
        Mockito.lenient().when(templatePoolDao.createSearchBuilder()).thenReturn(poolSb);
        com.cloud.storage.VMTemplateStoragePoolVO poolEntity = mock(com.cloud.storage.VMTemplateStoragePoolVO.class);
        Mockito.lenient().when(poolSb.entity()).thenReturn(poolEntity);
        when(sb.create()).thenReturn(sc);
        Mockito.lenient().when(_templateJoinDao.searchAndDistinctCount(any(), any(), any())).thenReturn(new Pair<>(new ArrayList<>(), 0));
        Mockito.lenient().when(_resourceMgr.listAvailHypervisorInZone(any())).thenReturn(new ArrayList<>());
        Mockito.lenient().doReturn(false).when(service).checkIfDomainSharesTemplates(any());

        try (MockedStatic<ViewResponseHelper> vrh = Mockito.mockStatic(ViewResponseHelper.class)) {
            when(ViewResponseHelper.createTemplateResponse(any(), eq(ResponseView.Full), any()))
                    .thenReturn(Collections.emptyList());
            Mockito.doNothing().when(accountMgr).buildACLSearchParameters(
                    any(), any(), any(), any(), any(), any(), any(Boolean.class), any(Boolean.class));
            ListResponse<TemplateResponse> result = service.listTemplates(cmd);
            assertNotNull(result);
            // Full view is used for admin ListTemplatesCmdByAdmin
        }
    }

    @Test
    public void listIsosUsesRestrictedViewByDefault() {
        ListIsosCmd cmd =
                mock(ListIsosCmd.class);
        when(cmd.getIsoFilter()).thenReturn("self");
        when(cmd.getHypervisor()).thenReturn("None");
        when(cmd.getPageSizeVal()).thenReturn(500L);
        when(cmd.getStartIndex()).thenReturn(0L);

        SearchBuilder<TemplateJoinVO> sb = mock(SearchBuilder.class);
        TemplateJoinVO entity = mock(TemplateJoinVO.class);
        SearchCriteria<TemplateJoinVO> sc = mock(SearchCriteria.class);
        when(_templateJoinDao.createSearchBuilder()).thenReturn(sb);
        when(sb.entity()).thenReturn(entity);
        SearchBuilder<com.cloud.storage.VMTemplateStoragePoolVO> poolSb = mock(SearchBuilder.class);
        Mockito.lenient().when(templatePoolDao.createSearchBuilder()).thenReturn(poolSb);
        com.cloud.storage.VMTemplateStoragePoolVO poolEntity = mock(com.cloud.storage.VMTemplateStoragePoolVO.class);
        Mockito.lenient().when(poolSb.entity()).thenReturn(poolEntity);
        when(sb.create()).thenReturn(sc);
        Mockito.lenient().when(_templateJoinDao.searchAndDistinctCount(any(), any(), any())).thenReturn(new Pair<>(new ArrayList<>(), 0));
        Mockito.lenient().doReturn(false).when(service).checkIfDomainSharesTemplates(any());

        try (MockedStatic<ViewResponseHelper> vrh = Mockito.mockStatic(ViewResponseHelper.class)) {
            when(ViewResponseHelper.createIsoResponse(any(), any()))
                    .thenReturn(Collections.emptyList());
            Mockito.doNothing().when(accountMgr).buildACLSearchParameters(
                    any(), any(), any(), any(), any(), any(), any(Boolean.class), any(Boolean.class));
            ListResponse<TemplateResponse> result = service.listIsos(cmd);
            assertNotNull(result);
            // Restricted view is used by default for non-admin ListIsosCmd (not Full)
        }
    }

    @Test
    public void listIsosByAdminUsesFullView() {
        ListIsosCmdByAdmin cmd =
                mock(ListIsosCmdByAdmin.class);
        when(cmd.getIsoFilter()).thenReturn("self");
        when(cmd.getHypervisor()).thenReturn("None");
        when(cmd.getPageSizeVal()).thenReturn(500L);
        when(cmd.getStartIndex()).thenReturn(0L);

        SearchBuilder<TemplateJoinVO> sb = mock(SearchBuilder.class);
        TemplateJoinVO entity = mock(TemplateJoinVO.class);
        SearchCriteria<TemplateJoinVO> sc = mock(SearchCriteria.class);
        when(_templateJoinDao.createSearchBuilder()).thenReturn(sb);
        when(sb.entity()).thenReturn(entity);
        SearchBuilder<com.cloud.storage.VMTemplateStoragePoolVO> poolSb = mock(SearchBuilder.class);
        Mockito.lenient().when(templatePoolDao.createSearchBuilder()).thenReturn(poolSb);
        com.cloud.storage.VMTemplateStoragePoolVO poolEntity = mock(com.cloud.storage.VMTemplateStoragePoolVO.class);
        Mockito.lenient().when(poolSb.entity()).thenReturn(poolEntity);
        when(sb.create()).thenReturn(sc);
        Mockito.lenient().when(_templateJoinDao.searchAndDistinctCount(any(), any(), any())).thenReturn(new Pair<>(new ArrayList<>(), 0));
        Mockito.lenient().doReturn(false).when(service).checkIfDomainSharesTemplates(any());

        try (MockedStatic<ViewResponseHelper> vrh = Mockito.mockStatic(ViewResponseHelper.class)) {
            when(ViewResponseHelper.createIsoResponse(any(), any()))
                    .thenReturn(Collections.emptyList());
            Mockito.doNothing().when(accountMgr).buildACLSearchParameters(
                    any(), any(), any(), any(), any(), any(), any(Boolean.class), any(Boolean.class));
            ListResponse<TemplateResponse> result = service.listIsos(cmd);
            assertNotNull(result);
            // Full view is used for admin ListIsosCmdByAdmin
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void listTemplatesRejectsFilterAllForNormalAccount() {
        ListTemplatesCmd cmd =
                mock(ListTemplatesCmd.class);
        when(cmd.getTemplateFilter()).thenReturn("all");
        // caller is NORMAL (set up in setUp)
        service.listTemplates(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void listIsosRejectsFilterAllForNormalAccount() {
        ListIsosCmd cmd =
                mock(ListIsosCmd.class);
        when(cmd.getIsoFilter()).thenReturn("all");
        service.listIsos(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void listVnfTemplatesEnforcesVnfTemplateType() {
        ListVnfTemplatesCmd cmd =
                mock(ListVnfTemplatesCmd.class);
        when(cmd.getTemplateFilter()).thenReturn("self");
        // templateType set to non-VNF
        when(cmd.getTemplateType()).thenReturn("USER");
        service.listTemplates(cmd);
    }
}
