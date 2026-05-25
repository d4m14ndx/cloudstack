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

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import com.cloud.network.RouterHealthCheckResult;
import com.cloud.network.VirtualNetworkApplianceService.RouterHealthStatus;
import com.cloud.network.dao.RouterHealthCheckResultDao;
import com.cloud.network.dao.RouterHealthCheckResultVO;
import com.cloud.serializer.GsonHelper;
import com.cloud.vm.DomainRouterVO;

/**
 * Router health-check result parser and persister — extracted from
 * {@link VirtualNetworkApplianceManagerImpl}.
 *
 * @see RouterHealthCheckResultsService
 */
@Component
public class RouterHealthCheckResultsServiceImpl implements RouterHealthCheckResultsService {

    protected static final String CONNECTIVITY_TEST = "connectivity.test";
    protected static final String FILESYSTEM_WRITABLE_TEST = "filesystem.writable.test";
    protected static final String BASIC_CHECK_TYPE = "basic";

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected RouterHealthCheckResultDao routerHealthCheckResultDao;

    @Override
    public void resetRouterHealthChecksAndConnectivity(final long routerId, RouterHealthStatus connected, RouterHealthStatus writable, String message) {
        routerHealthCheckResultDao.expungeHealthChecks(routerId);
        updateRouterHealthCheckResult(routerId, CONNECTIVITY_TEST, BASIC_CHECK_TYPE, connected,
                connected.equals(RouterHealthStatus.SUCCESS) ? "Successfully connected to router" : message);
        updateRouterHealthCheckResult(routerId, FILESYSTEM_WRITABLE_TEST, BASIC_CHECK_TYPE, writable,
                writable.equals(RouterHealthStatus.SUCCESS) ? "Successfully written to file system" : message);
    }

    @Override
    public void updateDbHealthChecksFromRouterResponse(final DomainRouterVO router, final String monitoringResult) {
        if (StringUtils.isBlank(monitoringResult)) {
            logger.warn("Attempted parsing empty monitoring results string for router {}", router);
            return;
        }

        try {
            logger.debug("Parsing and updating DB health check data for router: {} with data: {}", router, monitoringResult);
            final Type t = new TypeToken<Map<String, Map<String, Map<String, String>>>>() {}.getType();
            final Map<String, Map<String, Map<String, String>>> checks = GsonHelper.getGson().fromJson(monitoringResult, t);
            parseHealthCheckResults(checks, router);
        } catch (JsonSyntaxException ex) {
            logger.error("Unable to parse the result of health checks due to " + ex.getLocalizedMessage(), ex);
        }
    }

    protected Map<String, Map<String, RouterHealthCheckResultVO>> getHealthChecksFromDb(long routerId) {
        List<RouterHealthCheckResultVO> healthChecksList = routerHealthCheckResultDao.getHealthCheckResults(routerId);
        Map<String, Map<String, RouterHealthCheckResultVO>> healthCheckResults = new HashMap<>();
        if (healthChecksList.isEmpty()) {
            return healthCheckResults;
        }

        for (RouterHealthCheckResultVO healthCheck : healthChecksList) {
            if (!healthCheckResults.containsKey(healthCheck.getCheckType())) {
                healthCheckResults.put(healthCheck.getCheckType(), new HashMap<>());
            }
            healthCheckResults.get(healthCheck.getCheckType()).put(healthCheck.getCheckName(), healthCheck);
        }

        return healthCheckResults;
    }

    protected void updateRouterHealthCheckResult(final long routerId, String checkName, String checkType, RouterHealthStatus checkResult, String checkMessage) {
        boolean newHealthCheckEntry = false;
        RouterHealthCheckResultVO connectivityVO = routerHealthCheckResultDao.getRouterHealthCheckResult(routerId, checkName, checkType);
        if (connectivityVO == null) {
            connectivityVO = new RouterHealthCheckResultVO(routerId, checkName, checkType);
            newHealthCheckEntry = true;
        }

        connectivityVO.setCheckResult(checkResult);
        connectivityVO.setLastUpdateTime(new Date());
        if (StringUtils.isNotEmpty(checkMessage)) {
            connectivityVO.setCheckDetails(checkMessage.getBytes(com.cloud.utils.StringUtils.getPreferredCharset()));
        }

        if (newHealthCheckEntry) {
            routerHealthCheckResultDao.persist(connectivityVO);
        } else {
            routerHealthCheckResultDao.update(connectivityVO.getId(), connectivityVO);
        }
    }

    protected RouterHealthCheckResultVO parseHealthCheckVOFromJson(final long routerId,
                                                                   final String checkName, final String checkType, final Map<String, String> checkData,
                                                                   final Map<String, Map<String, RouterHealthCheckResultVO>> checksInDb) {
        RouterHealthStatus success = getRouterHealthStatus(checkData.get("success"));
        Date lastUpdate = new Date(Long.parseLong(checkData.get("lastUpdate")));
        double lastRunDuration = Double.parseDouble(checkData.get("lastRunDuration"));
        String message = checkData.get("message");
        final RouterHealthCheckResultVO hcVo;
        boolean newEntry = false;
        if (checksInDb.containsKey(checkType) && checksInDb.get(checkType).containsKey(checkName)) {
            hcVo = checksInDb.get(checkType).get(checkName);
        } else {
            hcVo = new RouterHealthCheckResultVO(routerId, checkName, checkType);
            newEntry = true;
        }

        hcVo.setCheckResult(success);
        hcVo.setLastUpdateTime(lastUpdate);
        if (StringUtils.isNotEmpty(message)) {
            hcVo.setCheckDetails(message.getBytes(com.cloud.utils.StringUtils.getPreferredCharset()));
        }

        if (newEntry) {
            routerHealthCheckResultDao.persist(hcVo);
        } else {
            routerHealthCheckResultDao.update(hcVo.getId(), hcVo);
        }
        logger.info("Found health check " + hcVo + " which took running duration (ms) " + lastRunDuration);
        return hcVo;
    }

    protected static RouterHealthStatus getRouterHealthStatus(String status) {
        RouterHealthStatus success;
        try {
            success = RouterHealthStatus.valueOf(status.trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            success = RouterHealthStatus.UNKNOWN;
        }
        return success;
    }

    /**
     * Walk the parsed JSON tree and upsert one row per check.
     *
     * @param checksJson JSON expected is
     *                   {
     *                      checkType1: {
     *                          checkName1: {
     *                              success: true/false,
     *                              lastUpdate: date string,
     *                              lastRunDuration: ms spent on test,
     *                              message: detailed message from check execution
     *                          },
     *                          checkType2: .....
     *                      },
     *                      checkType2: ......
     *                   }
     * @return the parsed RouterHealthCheckResult rows.
     */
    protected List<RouterHealthCheckResult> parseHealthCheckResults(
            final Map<String, Map<String, Map<String, String>>> checksJson, final DomainRouterVO router) {
        final Map<String, Map<String, RouterHealthCheckResultVO>> checksInDb = getHealthChecksFromDb(router.getId());
        List<RouterHealthCheckResult> healthChecks = new ArrayList<>();
        final String lastRunKey = "lastRun";
        for (String checkType : checksJson.keySet()) {
            if (checksJson.get(checkType).containsKey(lastRunKey)) { // Log last run of this check type run info
                Map<String, String> lastRun = checksJson.get(checkType).get(lastRunKey);
                logger.info("Found check types executed on VR " + checkType + ", start: " + lastRun.get("start") +
                        ", end: " + lastRun.get("end") + ", duration: " + lastRun.get("duration"));
            }

            for (String checkName : checksJson.get(checkType).keySet()) {
                if (lastRunKey.equals(checkName)) {
                    continue;
                }

                try {
                    final RouterHealthCheckResultVO hcVo = parseHealthCheckVOFromJson(
                            router.getId(), checkName, checkType, checksJson.get(checkType).get(checkName), checksInDb);
                    healthChecks.add(hcVo);
                } catch (Exception ex) {
                    logger.error("Skipping health check: Exception while parsing check result data for router {}, check type: {}, check name: {}:{}", router, checkType, checkName, ex.getLocalizedMessage(), ex);
                }
            }
        }
        return healthChecks;
    }
}
