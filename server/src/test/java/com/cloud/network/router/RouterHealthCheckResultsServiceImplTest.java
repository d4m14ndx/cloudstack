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
package com.cloud.network.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.network.RouterHealthCheckResult;
import com.cloud.network.VirtualNetworkApplianceService.RouterHealthStatus;
import com.cloud.network.dao.RouterHealthCheckResultDao;
import com.cloud.network.dao.RouterHealthCheckResultVO;
import com.cloud.vm.DomainRouterVO;

@RunWith(MockitoJUnitRunner.class)
public class RouterHealthCheckResultsServiceImplTest {

    private static final long ROUTER_ID = 42L;
    private static final String CHECK_NAME = "cpu.usage";
    private static final String CHECK_TYPE = "advanced";

    @Mock
    private RouterHealthCheckResultDao routerHealthCheckResultDao;

    private RouterHealthCheckResultsServiceImpl service;
    private AutoCloseable closeable;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        service = new RouterHealthCheckResultsServiceImpl();
        ReflectionTestUtils.setField(service, "routerHealthCheckResultDao", routerHealthCheckResultDao);
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    // ----------------------- resetRouterHealthChecksAndConnectivity -----------------------

    @Test
    public void resetExpungesAndWritesBasicRows() {
        service.resetRouterHealthChecksAndConnectivity(ROUTER_ID, RouterHealthStatus.SUCCESS, RouterHealthStatus.SUCCESS, "msg");

        verify(routerHealthCheckResultDao).expungeHealthChecks(ROUTER_ID);
        // Two basic rows should be upserted, one per synthetic check
        verify(routerHealthCheckResultDao).getRouterHealthCheckResult(eq(ROUTER_ID), eq("connectivity.test"), eq("basic"));
        verify(routerHealthCheckResultDao).getRouterHealthCheckResult(eq(ROUTER_ID), eq("filesystem.writable.test"), eq("basic"));
    }

    @Test
    public void resetUsesSuccessMessageWhenStatusSuccess() {
        when(routerHealthCheckResultDao.getRouterHealthCheckResult(anyLong(), anyString(), anyString())).thenReturn(null);

        service.resetRouterHealthChecksAndConnectivity(ROUTER_ID, RouterHealthStatus.SUCCESS, RouterHealthStatus.SUCCESS, "fallback");

        ArgumentCaptor<RouterHealthCheckResultVO> captor = ArgumentCaptor.forClass(RouterHealthCheckResultVO.class);
        verify(routerHealthCheckResultDao, times(2)).persist(captor.capture());
        List<RouterHealthCheckResultVO> persisted = captor.getAllValues();

        // Both rows should have the success canned messages (not the fallback)
        for (RouterHealthCheckResultVO vo : persisted) {
            String details = new String(vo.getCheckDetails(), com.cloud.utils.StringUtils.getPreferredCharset());
            assertTrue("expected canned success message, got: " + details,
                    details.startsWith("Successfully"));
        }
    }

    @Test
    public void resetUsesFailureMessageWhenStatusNotSuccess() {
        when(routerHealthCheckResultDao.getRouterHealthCheckResult(anyLong(), anyString(), anyString())).thenReturn(null);

        service.resetRouterHealthChecksAndConnectivity(ROUTER_ID, RouterHealthStatus.FAILED, RouterHealthStatus.UNKNOWN, "comm-broke");

        ArgumentCaptor<RouterHealthCheckResultVO> captor = ArgumentCaptor.forClass(RouterHealthCheckResultVO.class);
        verify(routerHealthCheckResultDao, times(2)).persist(captor.capture());
        for (RouterHealthCheckResultVO vo : captor.getAllValues()) {
            String details = new String(vo.getCheckDetails(), com.cloud.utils.StringUtils.getPreferredCharset());
            assertEquals("comm-broke", details);
        }
    }

    @Test
    public void resetUpdatesExistingRowsRatherThanCreatingNew() {
        RouterHealthCheckResultVO existingConn = new RouterHealthCheckResultVO(ROUTER_ID, "connectivity.test", "basic");
        RouterHealthCheckResultVO existingFs = new RouterHealthCheckResultVO(ROUTER_ID, "filesystem.writable.test", "basic");
        when(routerHealthCheckResultDao.getRouterHealthCheckResult(ROUTER_ID, "connectivity.test", "basic")).thenReturn(existingConn);
        when(routerHealthCheckResultDao.getRouterHealthCheckResult(ROUTER_ID, "filesystem.writable.test", "basic")).thenReturn(existingFs);

        service.resetRouterHealthChecksAndConnectivity(ROUTER_ID, RouterHealthStatus.SUCCESS, RouterHealthStatus.SUCCESS, "n/a");

        verify(routerHealthCheckResultDao, never()).persist(any());
        verify(routerHealthCheckResultDao).update(eq(existingConn.getId()), eq(existingConn));
        verify(routerHealthCheckResultDao).update(eq(existingFs.getId()), eq(existingFs));
        assertEquals(RouterHealthStatus.SUCCESS, existingConn.getCheckResult());
        assertEquals(RouterHealthStatus.SUCCESS, existingFs.getCheckResult());
    }

    // ----------------------- updateDbHealthChecksFromRouterResponse -----------------------

    @Test
    public void updateDbHealthChecksBailsOnBlankInput() {
        DomainRouterVO router = mockRouter();
        service.updateDbHealthChecksFromRouterResponse(router, "");
        service.updateDbHealthChecksFromRouterResponse(router, null);
        service.updateDbHealthChecksFromRouterResponse(router, "    ");

        verify(routerHealthCheckResultDao, never()).getHealthCheckResults(anyLong());
        verify(routerHealthCheckResultDao, never()).persist(any());
    }

    @Test
    public void updateDbHealthChecksSwallowsInvalidJsonSyntax() {
        DomainRouterVO router = mockRouter();
        service.updateDbHealthChecksFromRouterResponse(router, "{not-valid-json");

        // should log error and not blow up; nothing should be persisted
        verify(routerHealthCheckResultDao, never()).persist(any());
        verify(routerHealthCheckResultDao, never()).update(anyLong(), any());
    }

    @Test
    public void updateDbHealthChecksParsesSingleCheckAndPersists() {
        DomainRouterVO router = mockRouter();
        when(routerHealthCheckResultDao.getHealthCheckResults(ROUTER_ID)).thenReturn(Collections.emptyList());

        String json = "{\"advanced\":{\"cpu.usage\":{\"success\":\"SUCCESS\",\"lastUpdate\":\"1717000000000\",\"lastRunDuration\":\"12.5\",\"message\":\"all good\"}}}";

        service.updateDbHealthChecksFromRouterResponse(router, json);

        ArgumentCaptor<RouterHealthCheckResultVO> captor = ArgumentCaptor.forClass(RouterHealthCheckResultVO.class);
        verify(routerHealthCheckResultDao, times(1)).persist(captor.capture());
        RouterHealthCheckResultVO persisted = captor.getValue();
        assertEquals(ROUTER_ID, persisted.getRouterId());
        assertEquals("cpu.usage", persisted.getCheckName());
        assertEquals("advanced", persisted.getCheckType());
        assertEquals(RouterHealthStatus.SUCCESS, persisted.getCheckResult());
        assertEquals("all good", new String(persisted.getCheckDetails(), com.cloud.utils.StringUtils.getPreferredCharset()));
    }

    @Test
    public void updateDbHealthChecksSkipsLastRunPseudoCheck() {
        DomainRouterVO router = mockRouter();
        when(routerHealthCheckResultDao.getHealthCheckResults(ROUTER_ID)).thenReturn(Collections.emptyList());

        // 'lastRun' under a check type is the start/end/duration block, not a check itself
        String json = "{\"advanced\":{\"lastRun\":{\"start\":\"s\",\"end\":\"e\",\"duration\":\"d\"}}}";

        service.updateDbHealthChecksFromRouterResponse(router, json);

        verify(routerHealthCheckResultDao, never()).persist(any());
        verify(routerHealthCheckResultDao, never()).update(anyLong(), any());
    }

    @Test
    public void updateDbHealthChecksSkipsCheckWhenInnerDataMalformed() {
        DomainRouterVO router = mockRouter();
        when(routerHealthCheckResultDao.getHealthCheckResults(ROUTER_ID)).thenReturn(Collections.emptyList());

        // lastUpdate cannot be parsed → NumberFormatException → check is skipped but loop continues
        String json = "{\"advanced\":{"
                + "\"bad.check\":{\"success\":\"SUCCESS\",\"lastUpdate\":\"NOT-A-LONG\",\"lastRunDuration\":\"1.0\",\"message\":\"m\"},"
                + "\"good.check\":{\"success\":\"FAILED\",\"lastUpdate\":\"1717000000000\",\"lastRunDuration\":\"2.0\",\"message\":\"oops\"}}}";

        service.updateDbHealthChecksFromRouterResponse(router, json);

        // Only the well-formed entry should be persisted
        ArgumentCaptor<RouterHealthCheckResultVO> captor = ArgumentCaptor.forClass(RouterHealthCheckResultVO.class);
        verify(routerHealthCheckResultDao, times(1)).persist(captor.capture());
        assertEquals("good.check", captor.getValue().getCheckName());
        assertEquals(RouterHealthStatus.FAILED, captor.getValue().getCheckResult());
    }

    @Test
    public void updateDbHealthChecksUpdatesExistingRowsRatherThanInserting() {
        DomainRouterVO router = mockRouter();
        RouterHealthCheckResultVO existing = new RouterHealthCheckResultVO(ROUTER_ID, "cpu.usage", "advanced");
        when(routerHealthCheckResultDao.getHealthCheckResults(ROUTER_ID)).thenReturn(List.of(existing));

        String json = "{\"advanced\":{\"cpu.usage\":{\"success\":\"FAILED\",\"lastUpdate\":\"1717000000000\",\"lastRunDuration\":\"1.0\",\"message\":\"high\"}}}";

        service.updateDbHealthChecksFromRouterResponse(router, json);

        verify(routerHealthCheckResultDao, never()).persist(any());
        verify(routerHealthCheckResultDao).update(eq(existing.getId()), eq(existing));
        assertEquals(RouterHealthStatus.FAILED, existing.getCheckResult());
        assertEquals("high", new String(existing.getCheckDetails(), com.cloud.utils.StringUtils.getPreferredCharset()));
    }

    @Test
    public void updateDbHealthChecksParsesMultipleCheckTypesAndChecks() {
        DomainRouterVO router = mockRouter();
        when(routerHealthCheckResultDao.getHealthCheckResults(ROUTER_ID)).thenReturn(Collections.emptyList());

        String json = "{"
                + "\"basic\":{\"connectivity.test\":{\"success\":\"SUCCESS\",\"lastUpdate\":\"1717000000000\",\"lastRunDuration\":\"1.0\",\"message\":\"ok\"}},"
                + "\"advanced\":{\"cpu.usage\":{\"success\":\"FAILED\",\"lastUpdate\":\"1717000000001\",\"lastRunDuration\":\"2.0\",\"message\":\"bad\"},"
                +              "\"mem.usage\":{\"success\":\"UNKNOWN\",\"lastUpdate\":\"1717000000002\",\"lastRunDuration\":\"3.0\",\"message\":\"\"}}"
                + "}";

        service.updateDbHealthChecksFromRouterResponse(router, json);

        verify(routerHealthCheckResultDao, times(3)).persist(any());
    }

    // ----------------------- getRouterHealthStatus -----------------------

    @Test
    public void getRouterHealthStatusReturnsParsedEnum() {
        assertEquals(RouterHealthStatus.SUCCESS, RouterHealthCheckResultsServiceImpl.getRouterHealthStatus("SUCCESS"));
        assertEquals(RouterHealthStatus.FAILED, RouterHealthCheckResultsServiceImpl.getRouterHealthStatus("FAILED"));
        assertEquals(RouterHealthStatus.UNKNOWN, RouterHealthCheckResultsServiceImpl.getRouterHealthStatus("UNKNOWN"));
    }

    @Test
    public void getRouterHealthStatusTrimsWhitespace() {
        assertEquals(RouterHealthStatus.SUCCESS, RouterHealthCheckResultsServiceImpl.getRouterHealthStatus("  SUCCESS  "));
    }

    @Test
    public void getRouterHealthStatusReturnsUnknownForBadInput() {
        assertEquals(RouterHealthStatus.UNKNOWN, RouterHealthCheckResultsServiceImpl.getRouterHealthStatus("nonsense"));
        assertEquals(RouterHealthStatus.UNKNOWN, RouterHealthCheckResultsServiceImpl.getRouterHealthStatus(null));
        assertEquals(RouterHealthStatus.UNKNOWN, RouterHealthCheckResultsServiceImpl.getRouterHealthStatus(""));
    }

    // ----------------------- getHealthChecksFromDb -----------------------

    @Test
    public void getHealthChecksFromDbReturnsEmptyMapWhenNoRows() {
        when(routerHealthCheckResultDao.getHealthCheckResults(ROUTER_ID)).thenReturn(Collections.emptyList());

        Map<String, Map<String, RouterHealthCheckResultVO>> result = service.getHealthChecksFromDb(ROUTER_ID);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void getHealthChecksFromDbBucketsByTypeThenName() {
        RouterHealthCheckResultVO a = new RouterHealthCheckResultVO(ROUTER_ID, "a", "basic");
        RouterHealthCheckResultVO b = new RouterHealthCheckResultVO(ROUTER_ID, "b", "basic");
        RouterHealthCheckResultVO c = new RouterHealthCheckResultVO(ROUTER_ID, "c", "advanced");
        when(routerHealthCheckResultDao.getHealthCheckResults(ROUTER_ID)).thenReturn(List.of(a, b, c));

        Map<String, Map<String, RouterHealthCheckResultVO>> result = service.getHealthChecksFromDb(ROUTER_ID);

        assertEquals(2, result.size());
        assertEquals(2, result.get("basic").size());
        assertEquals(a, result.get("basic").get("a"));
        assertEquals(b, result.get("basic").get("b"));
        assertEquals(1, result.get("advanced").size());
        assertEquals(c, result.get("advanced").get("c"));
    }

    // ----------------------- updateRouterHealthCheckResult -----------------------

    @Test
    public void updateRouterHealthCheckResultInsertsWhenNotFound() {
        when(routerHealthCheckResultDao.getRouterHealthCheckResult(ROUTER_ID, CHECK_NAME, CHECK_TYPE)).thenReturn(null);

        service.updateRouterHealthCheckResult(ROUTER_ID, CHECK_NAME, CHECK_TYPE, RouterHealthStatus.SUCCESS, "ok");

        ArgumentCaptor<RouterHealthCheckResultVO> captor = ArgumentCaptor.forClass(RouterHealthCheckResultVO.class);
        verify(routerHealthCheckResultDao).persist(captor.capture());
        RouterHealthCheckResultVO v = captor.getValue();
        assertEquals(RouterHealthStatus.SUCCESS, v.getCheckResult());
        assertNotNull(v.getLastUpdateTime());
        assertEquals("ok", new String(v.getCheckDetails(), com.cloud.utils.StringUtils.getPreferredCharset()));
        verify(routerHealthCheckResultDao, never()).update(anyLong(), any());
    }

    @Test
    public void updateRouterHealthCheckResultUpdatesWhenFound() {
        RouterHealthCheckResultVO existing = new RouterHealthCheckResultVO(ROUTER_ID, CHECK_NAME, CHECK_TYPE);
        when(routerHealthCheckResultDao.getRouterHealthCheckResult(ROUTER_ID, CHECK_NAME, CHECK_TYPE)).thenReturn(existing);

        service.updateRouterHealthCheckResult(ROUTER_ID, CHECK_NAME, CHECK_TYPE, RouterHealthStatus.FAILED, "broken");

        verify(routerHealthCheckResultDao, never()).persist(any());
        verify(routerHealthCheckResultDao).update(eq(existing.getId()), eq(existing));
        assertEquals(RouterHealthStatus.FAILED, existing.getCheckResult());
        assertEquals("broken", new String(existing.getCheckDetails(), com.cloud.utils.StringUtils.getPreferredCharset()));
    }

    @Test
    public void updateRouterHealthCheckResultSkipsDetailsWhenMessageEmpty() {
        when(routerHealthCheckResultDao.getRouterHealthCheckResult(ROUTER_ID, CHECK_NAME, CHECK_TYPE)).thenReturn(null);

        service.updateRouterHealthCheckResult(ROUTER_ID, CHECK_NAME, CHECK_TYPE, RouterHealthStatus.SUCCESS, "");

        ArgumentCaptor<RouterHealthCheckResultVO> captor = ArgumentCaptor.forClass(RouterHealthCheckResultVO.class);
        verify(routerHealthCheckResultDao).persist(captor.capture());
        // empty message → check_details left null
        assertNull(captor.getValue().getCheckDetails());
    }

    // ----------------------- parseHealthCheckVOFromJson -----------------------

    @Test
    public void parseHealthCheckVOInsertsNewRowWhenNotInDb() {
        Map<String, Map<String, RouterHealthCheckResultVO>> checksInDb = new HashMap<>();
        Map<String, String> checkData = new HashMap<>();
        checkData.put("success", "SUCCESS");
        checkData.put("lastUpdate", "1717000000000");
        checkData.put("lastRunDuration", "1.5");
        checkData.put("message", "hello");

        RouterHealthCheckResultVO result = service.parseHealthCheckVOFromJson(
                ROUTER_ID, CHECK_NAME, CHECK_TYPE, checkData, checksInDb);

        assertNotNull(result);
        assertEquals(CHECK_NAME, result.getCheckName());
        assertEquals(CHECK_TYPE, result.getCheckType());
        assertEquals(RouterHealthStatus.SUCCESS, result.getCheckResult());
        verify(routerHealthCheckResultDao).persist(result);
        verify(routerHealthCheckResultDao, never()).update(anyLong(), any());
    }

    @Test
    public void parseHealthCheckVOReusesAndUpdatesWhenAlreadyInDb() {
        RouterHealthCheckResultVO existing = new RouterHealthCheckResultVO(ROUTER_ID, CHECK_NAME, CHECK_TYPE);
        Map<String, Map<String, RouterHealthCheckResultVO>> checksInDb = new HashMap<>();
        Map<String, RouterHealthCheckResultVO> inner = new HashMap<>();
        inner.put(CHECK_NAME, existing);
        checksInDb.put(CHECK_TYPE, inner);

        Map<String, String> checkData = new HashMap<>();
        checkData.put("success", "FAILED");
        checkData.put("lastUpdate", "1717000000000");
        checkData.put("lastRunDuration", "2.0");
        checkData.put("message", "boom");

        RouterHealthCheckResultVO result = service.parseHealthCheckVOFromJson(
                ROUTER_ID, CHECK_NAME, CHECK_TYPE, checkData, checksInDb);

        assertEquals(existing, result);
        verify(routerHealthCheckResultDao, never()).persist(any());
        verify(routerHealthCheckResultDao).update(eq(existing.getId()), eq(existing));
        assertEquals(RouterHealthStatus.FAILED, existing.getCheckResult());
    }

    // ----------------------- parseHealthCheckResults -----------------------

    @Test
    public void parseHealthCheckResultsReturnsListOfPersistedRows() {
        DomainRouterVO router = mockRouter();
        when(routerHealthCheckResultDao.getHealthCheckResults(ROUTER_ID)).thenReturn(Collections.emptyList());

        Map<String, Map<String, Map<String, String>>> input = new HashMap<>();
        Map<String, Map<String, String>> basic = new HashMap<>();
        Map<String, String> check = new HashMap<>();
        check.put("success", "SUCCESS");
        check.put("lastUpdate", "1717000000000");
        check.put("lastRunDuration", "1.0");
        check.put("message", "ok");
        basic.put("only.check", check);
        input.put("basic", basic);

        List<RouterHealthCheckResult> result = service.parseHealthCheckResults(input, router);

        assertEquals(1, result.size());
        verify(routerHealthCheckResultDao).persist(any());
    }

    @Test
    public void parseHealthCheckResultsIgnoresLastRunSubKey() {
        DomainRouterVO router = mockRouter();
        when(routerHealthCheckResultDao.getHealthCheckResults(ROUTER_ID)).thenReturn(Collections.emptyList());

        Map<String, Map<String, Map<String, String>>> input = new HashMap<>();
        Map<String, Map<String, String>> basic = new HashMap<>();
        Map<String, String> lastRun = new HashMap<>();
        lastRun.put("start", "s");
        lastRun.put("end", "e");
        lastRun.put("duration", "1");
        basic.put("lastRun", lastRun);
        input.put("basic", basic);

        List<RouterHealthCheckResult> result = service.parseHealthCheckResults(input, router);

        assertTrue(result.isEmpty());
        verify(routerHealthCheckResultDao, never()).persist(any());
    }

    // ----------------------- helpers -----------------------

    private DomainRouterVO mockRouter() {
        DomainRouterVO router = org.mockito.Mockito.mock(DomainRouterVO.class);
        lenient().when(router.getId()).thenReturn(ROUTER_ID);
        return router;
    }
}
