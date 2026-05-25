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

import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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

import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.userdata.DeleteCniConfigurationCmd;
import org.apache.cloudstack.api.command.user.userdata.DeleteUserDataCmd;
import org.apache.cloudstack.api.command.user.userdata.ListUserDataCmd;
import org.apache.cloudstack.api.command.user.userdata.RegisterCniConfigurationCmd;
import org.apache.cloudstack.api.command.user.userdata.RegisterUserDataCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.userdata.UserDataManager;

import com.cloud.api.ApiDBUtils;
import com.cloud.domain.DomainVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.user.UserData;
import com.cloud.user.UserDataVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDataDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class UserDataRegistryServiceImplTest {

    @Mock
    Account account;

    @Mock
    AccountManager accountManager;

    @Mock
    AccountDao accountDao;

    @Mock
    UserDataDao userDataDao;

    @Mock
    VMTemplateDao templateDao;

    @Mock
    UserVmDao userVmDao;

    @Mock
    AnnotationDao annotationDao;

    @Mock
    UserDataManager userDataManager;

    @Spy
    @InjectMocks
    UserDataRegistryServiceImpl service = new UserDataRegistryServiceImpl();

    private AutoCloseable closeable;

    @Before
    public void setup() {
        closeable = MockitoAnnotations.openMocks(this);
        CallContext.register(Mockito.mock(User.class), Mockito.mock(Account.class));
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        closeable.close();
    }

    @Test
    public void testSuccessfulRegisterUserdata() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(account.getAccountId()).thenReturn(1L);
            when(account.getDomainId()).thenReturn(2L);
            when(callContextMock.getCallingAccount()).thenReturn(account);
            when(accountManager.finalizeOwner(nullable(Account.class), nullable(String.class), nullable(Long.class), nullable(Long.class))).thenReturn(account);

            String testUserData = "testUserdata";
            RegisterUserDataCmd cmd = Mockito.mock(RegisterUserDataCmd.class);
            when(cmd.getUserData()).thenReturn(testUserData);
            when(cmd.getName()).thenReturn("testName");
            when(cmd.getHttpMethod()).thenReturn(BaseCmd.HTTPMethod.GET);

            when(userDataDao.findByName(account.getAccountId(), account.getDomainId(), "testName")).thenReturn(null);
            when(userDataDao.findByUserData(account.getAccountId(), account.getDomainId(), testUserData)).thenReturn(null);
            when(userDataManager.validateUserData(testUserData, BaseCmd.HTTPMethod.GET)).thenReturn(testUserData);

            UserData userData = service.registerUserData(cmd);
            Assert.assertEquals("testName", userData.getName());
            Assert.assertEquals("testUserdata", userData.getUserData());
            Assert.assertEquals(1L, userData.getAccountId());
            Assert.assertEquals(2L, userData.getDomainId());
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testRegisterExistingUserdata() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(account.getAccountId()).thenReturn(1L);
            when(account.getDomainId()).thenReturn(2L);
            when(callContextMock.getCallingAccount()).thenReturn(account);
            when(accountManager.finalizeOwner(nullable(Account.class), nullable(String.class), nullable(Long.class), nullable(Long.class))).thenReturn(account);

            RegisterUserDataCmd cmd = Mockito.mock(RegisterUserDataCmd.class);
            when(cmd.getUserData()).thenReturn("testUserdata");
            when(cmd.getName()).thenReturn("testName");

            UserDataVO userData = Mockito.mock(UserDataVO.class);
            when(userDataDao.findByName(account.getAccountId(), account.getDomainId(), "testName")).thenReturn(null);
            when(userDataDao.findByUserData(account.getAccountId(), account.getDomainId(), "testUserdata")).thenReturn(userData);

            service.registerUserData(cmd);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testRegisterExistingUserdataByName() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(account.getAccountId()).thenReturn(1L);
            when(account.getDomainId()).thenReturn(2L);
            Mockito.when(callContextMock.getCallingAccount()).thenReturn(account);
            when(accountManager.finalizeOwner(nullable(Account.class), nullable(String.class), nullable(Long.class), nullable(Long.class))).thenReturn(account);

            RegisterUserDataCmd cmd = Mockito.mock(RegisterUserDataCmd.class);
            when(cmd.getName()).thenReturn("testName");

            UserDataVO userData = Mockito.mock(UserDataVO.class);
            when(userDataDao.findByName(account.getAccountId(), account.getDomainId(), "testName")).thenReturn(userData);

            service.registerUserData(cmd);
        }
    }

    @Test
    public void testSuccessfulDeleteUserdata() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(callContextMock.getCallingAccount()).thenReturn(account);
            when(accountManager.finalizeOwner(nullable(Account.class), nullable(String.class), nullable(Long.class), nullable(Long.class))).thenReturn(account);

            DeleteUserDataCmd cmd = Mockito.mock(DeleteUserDataCmd.class);
            when(cmd.getAccountName()).thenReturn("testAccountName");
            when(cmd.getDomainId()).thenReturn(1L);
            when(cmd.getProjectId()).thenReturn(2L);
            when(cmd.getId()).thenReturn(1L);
            UserDataVO userData = Mockito.mock(UserDataVO.class);

            Mockito.when(userData.getId()).thenReturn(1L);
            when(userDataDao.findById(1L)).thenReturn(userData);
            when(templateDao.findTemplatesLinkedToUserdata(1L)).thenReturn(new ArrayList<VMTemplateVO>());
            when(userVmDao.findByUserDataId(1L)).thenReturn(new ArrayList<UserVmVO>());
            when(userDataDao.remove(1L)).thenReturn(true);

            boolean result = service.deleteUserData(cmd);
            Assert.assertEquals(true, result);
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void testDeleteUserdataLinkedToTemplate() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(callContextMock.getCallingAccount()).thenReturn(account);
            when(accountManager.finalizeOwner(nullable(Account.class), nullable(String.class), nullable(Long.class), nullable(Long.class))).thenReturn(account);

            DeleteUserDataCmd cmd = Mockito.mock(DeleteUserDataCmd.class);
            when(cmd.getAccountName()).thenReturn("testAccountName");
            when(cmd.getDomainId()).thenReturn(1L);
            when(cmd.getProjectId()).thenReturn(2L);
            when(cmd.getId()).thenReturn(1L);

            UserDataVO userData = Mockito.mock(UserDataVO.class);
            Mockito.when(userData.getId()).thenReturn(1L);
            when(userDataDao.findById(1L)).thenReturn(userData);

            VMTemplateVO vmTemplateVO = Mockito.mock(VMTemplateVO.class);
            List<VMTemplateVO> linkedTemplates = new ArrayList<>();
            linkedTemplates.add(vmTemplateVO);
            when(templateDao.findTemplatesLinkedToUserdata(1L)).thenReturn(linkedTemplates);

            service.deleteUserData(cmd);
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void testDeleteUserdataUsedByVM() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(callContextMock.getCallingAccount()).thenReturn(account);
            when(accountManager.finalizeOwner(nullable(Account.class), nullable(String.class), nullable(Long.class), nullable(Long.class))).thenReturn(account);

            DeleteUserDataCmd cmd = Mockito.mock(DeleteUserDataCmd.class);
            when(cmd.getAccountName()).thenReturn("testAccountName");
            when(cmd.getDomainId()).thenReturn(1L);
            when(cmd.getProjectId()).thenReturn(2L);
            when(cmd.getId()).thenReturn(1L);

            UserDataVO userData = Mockito.mock(UserDataVO.class);
            Mockito.when(userData.getId()).thenReturn(1L);
            when(userDataDao.findById(1L)).thenReturn(userData);

            when(templateDao.findTemplatesLinkedToUserdata(1L)).thenReturn(new ArrayList<VMTemplateVO>());

            UserVmVO userVmVO = Mockito.mock(UserVmVO.class);
            List<UserVmVO> vms = new ArrayList<>();
            vms.add(userVmVO);
            when(userVmDao.findByUserDataId(1L)).thenReturn(vms);

            service.deleteUserData(cmd);
        }
    }

    @Test
    public void testListUserDataById() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);

            ListUserDataCmd cmd = Mockito.mock(ListUserDataCmd.class);
            when(cmd.getAccountName()).thenReturn("testAccountName");
            when(cmd.getDomainId()).thenReturn(1L);
            when(cmd.getProjectId()).thenReturn(2L);
            when(cmd.getId()).thenReturn(1L);
            when(cmd.isRecursive()).thenReturn(false);
            UserDataVO userData = Mockito.mock(UserDataVO.class);

            SearchBuilder<UserDataVO> sb = Mockito.mock(SearchBuilder.class);
            when(userDataDao.createSearchBuilder()).thenReturn(sb);
            when(sb.entity()).thenReturn(userData);

            SearchCriteria<UserDataVO> sc = Mockito.mock(SearchCriteria.class);
            when(sb.create()).thenReturn(sc);

            List<UserDataVO> userDataList = new ArrayList<UserDataVO>();
            userDataList.add(userData);
            Pair<List<UserDataVO>, Integer> result = new Pair(userDataList, 1);
            when(userDataDao.searchAndCount(nullable(SearchCriteria.class), nullable(Filter.class))).thenReturn(result);

            Pair<List<? extends UserData>, Integer> userdataResultList = service.listUserDatas(cmd, false);

            Assert.assertEquals(userdataResultList.first().get(0), userDataList.get(0));
        }
    }

    @Test
    public void testListUserDataByName() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(callContextMock.getCallingAccount()).thenReturn(account);

            ListUserDataCmd cmd = Mockito.mock(ListUserDataCmd.class);
            when(cmd.getAccountName()).thenReturn("testAccountName");
            when(cmd.getDomainId()).thenReturn(1L);
            when(cmd.getProjectId()).thenReturn(2L);
            when(cmd.getName()).thenReturn("testSearchUserdataName");
            when(cmd.isRecursive()).thenReturn(false);
            UserDataVO userData = Mockito.mock(UserDataVO.class);

            SearchBuilder<UserDataVO> sb = Mockito.mock(SearchBuilder.class);
            when(userDataDao.createSearchBuilder()).thenReturn(sb);
            when(sb.entity()).thenReturn(userData);

            SearchCriteria<UserDataVO> sc = Mockito.mock(SearchCriteria.class);
            when(sb.create()).thenReturn(sc);

            List<UserDataVO> userDataList = new ArrayList<UserDataVO>();
            userDataList.add(userData);
            Pair<List<UserDataVO>, Integer> result = new Pair(userDataList, 1);
            when(userDataDao.searchAndCount(nullable(SearchCriteria.class), nullable(Filter.class))).thenReturn(result);

            Pair<List<? extends UserData>, Integer> userdataResultList = service.listUserDatas(cmd, false);

            Assert.assertEquals(userdataResultList.first().get(0), userDataList.get(0));
        }
    }

    @Test
    public void testListUserDataByKeyword() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(callContextMock.getCallingAccount()).thenReturn(account);

            ListUserDataCmd cmd = Mockito.mock(ListUserDataCmd.class);
            when(cmd.getAccountName()).thenReturn("testAccountName");
            when(cmd.getDomainId()).thenReturn(1L);
            when(cmd.getProjectId()).thenReturn(2L);
            when(cmd.getKeyword()).thenReturn("testSearchUserdataKeyword");
            when(cmd.isRecursive()).thenReturn(false);
            UserDataVO userData = Mockito.mock(UserDataVO.class);

            SearchBuilder<UserDataVO> sb = Mockito.mock(SearchBuilder.class);
            when(userDataDao.createSearchBuilder()).thenReturn(sb);
            when(sb.entity()).thenReturn(userData);

            SearchCriteria<UserDataVO> sc = Mockito.mock(SearchCriteria.class);
            when(sb.create()).thenReturn(sc);

            List<UserDataVO> userDataList = new ArrayList<UserDataVO>();
            userDataList.add(userData);
            Pair<List<UserDataVO>, Integer> result = new Pair(userDataList, 1);
            when(userDataDao.searchAndCount(nullable(SearchCriteria.class), nullable(Filter.class))).thenReturn(result);

            Pair<List<? extends UserData>, Integer> userdataResultList = service.listUserDatas(cmd, false);

            Assert.assertEquals(userdataResultList.first().get(0), userDataList.get(0));
        }
    }

    @Test
    public void testRegisterCniConfiguration_persists() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(account.getAccountId()).thenReturn(1L);
            when(account.getDomainId()).thenReturn(2L);
            when(callContextMock.getCallingAccount()).thenReturn(account);
            when(accountManager.finalizeOwner(nullable(Account.class), nullable(String.class), nullable(Long.class), nullable(Long.class))).thenReturn(account);

            String cniConfig = "testCniConfig";
            RegisterCniConfigurationCmd cmd = Mockito.mock(RegisterCniConfigurationCmd.class);
            when(cmd.getCniConfig()).thenReturn(cniConfig);
            when(cmd.getName()).thenReturn("testCniName");
            when(cmd.getHttpMethod()).thenReturn(BaseCmd.HTTPMethod.GET);

            when(userDataDao.findByName(account.getAccountId(), account.getDomainId(), "testCniName")).thenReturn(null);
            when(userDataManager.validateUserData(cniConfig, BaseCmd.HTTPMethod.GET)).thenReturn(cniConfig);

            UserData userData = service.registerCniConfiguration(cmd);
            Assert.assertEquals("testCniName", userData.getName());
            Assert.assertEquals(cniConfig, userData.getUserData());
            Assert.assertEquals(1L, userData.getAccountId());
            Assert.assertEquals(2L, userData.getDomainId());
            Assert.assertTrue(((UserDataVO) userData).isForCks());
        }
    }

    @Test
    public void testDeleteCniConfiguration_delegatesToDeleteUserData() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(callContextMock.getCallingAccount()).thenReturn(account);
            when(accountManager.finalizeOwner(nullable(Account.class), nullable(String.class), nullable(Long.class), nullable(Long.class))).thenReturn(account);

            DeleteCniConfigurationCmd cmd = Mockito.mock(DeleteCniConfigurationCmd.class);
            when(cmd.getAccountName()).thenReturn("testAccountName");
            when(cmd.getDomainId()).thenReturn(1L);
            when(cmd.getProjectId()).thenReturn(2L);
            when(cmd.getId()).thenReturn(1L);

            UserDataVO userData = Mockito.mock(UserDataVO.class);
            Mockito.when(userData.getId()).thenReturn(1L);
            when(userDataDao.findById(1L)).thenReturn(userData);
            when(templateDao.findTemplatesLinkedToUserdata(1L)).thenReturn(new ArrayList<VMTemplateVO>());
            when(userVmDao.findByUserDataId(1L)).thenReturn(new ArrayList<UserVmVO>());
            when(userDataDao.remove(1L)).thenReturn(true);

            boolean result = service.deleteCniConfiguration(cmd);
            Assert.assertTrue(result);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDeleteUserdata_unknownId_throwsInvalidParameter() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class);
             MockedStatic<ApiDBUtils> apiDBUtilsMock = Mockito.mockStatic(ApiDBUtils.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(callContextMock.getCallingAccount()).thenReturn(account);
            when(accountManager.finalizeOwner(nullable(Account.class), nullable(String.class), nullable(Long.class), nullable(Long.class))).thenReturn(account);
            when(account.getAccountName()).thenReturn("testAccount");
            when(account.getDomainId()).thenReturn(1L);

            DomainVO domain = Mockito.mock(DomainVO.class);
            when(domain.getUuid()).thenReturn("domain-uuid");
            apiDBUtilsMock.when(() -> ApiDBUtils.findDomainById(1L)).thenReturn(domain);

            DeleteUserDataCmd cmd = Mockito.mock(DeleteUserDataCmd.class);
            when(cmd.getId()).thenReturn(999L);
            when(userDataDao.findById(999L)).thenReturn(null);

            service.deleteUserData(cmd);
        }
    }

    @Test
    public void testDeleteUserdata_adminFallbackOwnerLookup() {
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            when(CallContext.current()).thenReturn(callContextMock);
            when(callContextMock.getCallingAccount()).thenReturn(account);
            when(account.getType()).thenReturn(Account.Type.ADMIN);

            when(accountManager.finalizeOwner(nullable(Account.class), nullable(String.class), nullable(Long.class), nullable(Long.class)))
                    .thenThrow(new InvalidParameterValueException("not found"));

            Account fallbackOwner = Mockito.mock(Account.class);
            when(accountDao.findAccountIncludingRemoved("deletedAccount", 5L)).thenReturn(fallbackOwner);

            DeleteUserDataCmd cmd = Mockito.mock(DeleteUserDataCmd.class);
            when(cmd.getAccountName()).thenReturn("deletedAccount");
            when(cmd.getDomainId()).thenReturn(5L);
            when(cmd.getProjectId()).thenReturn(null);
            when(cmd.getId()).thenReturn(1L);

            UserDataVO userData = Mockito.mock(UserDataVO.class);
            Mockito.when(userData.getId()).thenReturn(1L);
            when(userDataDao.findById(1L)).thenReturn(userData);
            when(templateDao.findTemplatesLinkedToUserdata(1L)).thenReturn(new ArrayList<VMTemplateVO>());
            when(userVmDao.findByUserDataId(1L)).thenReturn(new ArrayList<UserVmVO>());
            when(userDataDao.remove(1L)).thenReturn(true);

            boolean result = service.deleteUserData(cmd);
            Assert.assertTrue(result);
        }
    }
}
