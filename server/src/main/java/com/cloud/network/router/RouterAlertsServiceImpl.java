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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.alert.AlertService.AlertType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetRouterAlertsAnswer;
import com.cloud.agent.api.routing.GetRouterAlertsCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.alert.AlertManager;
import com.cloud.network.dao.OpRouterMonitorServiceDao;
import com.cloud.network.dao.OpRouterMonitorServiceVO;
import com.cloud.utils.net.MacAddress;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.DomainRouterDao;

/**
 * Virtual-router monitoring-service alert collector — extracted from
 * {@link VirtualNetworkApplianceManagerImpl}.
 *
 * @see RouterAlertsService
 */
@Component
public class RouterAlertsServiceImpl implements RouterAlertsService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected DomainRouterDao routerDao;
    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected AlertManager alertMgr;
    @Inject
    protected OpRouterMonitorServiceDao opRouterMonitorServiceDao;
    @Inject
    protected RouterControlHelper routerControlHelper;

    protected final long mgmtSrvrId = MacAddress.getMacAddress().toLong();

    @Override
    public void getRouterAlerts() {
        try {
            final List<DomainRouterVO> routers = routerDao.listByStateAndManagementServer(VirtualMachine.State.Running, mgmtSrvrId);

            logger.debug("Found " + routers.size() + " running routers. ");
            for (final DomainRouterVO router : routers) {
                final Boolean serviceMonitoringFlag = VirtualNetworkApplianceManager.SetServiceMonitor.valueIn(router.getDataCenterId());
                // Skip the routers in VPC network or skip the routers where
                // Monitor service is not enabled in the corresponding Zone
                if (serviceMonitoringFlag == null || !serviceMonitoringFlag) {
                    continue;
                }
                String controlIP = routerControlHelper.getRouterControlIp(router.getId());

                if (controlIP != null && !controlIP.equals("0.0.0.0")) {
                    OpRouterMonitorServiceVO opRouterMonitorServiceVO = opRouterMonitorServiceDao.findById(router.getId());

                    GetRouterAlertsCommand command = getGetRouterAlertsCommand(opRouterMonitorServiceVO, controlIP);

                    try {
                        final Answer origAnswer = agentMgr.easySend(router.getHostId(), command);
                        GetRouterAlertsAnswer answer;

                        if (origAnswer == null) {
                            logger.warn("Unable to get alerts from router " + router.getHostName());
                            continue;
                        }
                        if (origAnswer instanceof GetRouterAlertsAnswer) {
                            answer = (GetRouterAlertsAnswer) origAnswer;
                        } else {
                            logger.warn("Unable to get alerts from router " + router.getHostName());
                            continue;
                        }
                        if (!answer.getResult()) {
                            logger.warn("Unable to get alerts from router " + router.getHostName() + " " + answer.getDetails());
                            continue;
                        }

                        final String[] alerts = answer.getAlerts();
                        if (alerts != null) {
                            final String lastAlertTimeStamp = answer.getTimeStamp();
                            final SimpleDateFormat sdfrmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                            sdfrmt.setLenient(false);
                            try {
                                sdfrmt.parse(lastAlertTimeStamp);
                            } catch (final ParseException e) {
                                logger.warn("Invalid last alert timestamp received while collecting alerts from router: " + router.getInstanceName());
                                continue;
                            }
                            for (final String alert : alerts) {
                                alertMgr.sendAlert(AlertType.ALERT_TYPE_DOMAIN_ROUTER, router.getDataCenterId(), router.getPodIdToDeployIn(), "Monitoring Service on VR "
                                        + router.getInstanceName(), alert);
                            }
                            if (opRouterMonitorServiceVO == null) {
                                opRouterMonitorServiceVO = new OpRouterMonitorServiceVO(router.getId(), router.getHostName(), lastAlertTimeStamp);
                                opRouterMonitorServiceDao.persist(opRouterMonitorServiceVO);
                            } else {
                                opRouterMonitorServiceVO.setLastAlertTimestamp(lastAlertTimeStamp);
                                opRouterMonitorServiceDao.update(opRouterMonitorServiceVO.getId(), opRouterMonitorServiceVO);
                            }
                        }
                    } catch (final Exception e) {
                        logger.warn("Error while collecting alerts from router: " + router.getInstanceName(), e);
                    }
                }
            }
        } catch (final Exception e) {
            logger.warn("Error while collecting alerts from router", e);
        }
    }

    protected static GetRouterAlertsCommand getGetRouterAlertsCommand(OpRouterMonitorServiceVO opRouterMonitorServiceVO, String controlIP) {
        GetRouterAlertsCommand command;
        if (opRouterMonitorServiceVO == null) {
            command = new GetRouterAlertsCommand("1970-01-01 00:00:00"); // To avoid sending null value
        } else {
            command = new GetRouterAlertsCommand(opRouterMonitorServiceVO.getLastAlertTimestamp());
        }

        command.setAccessDetail(NetworkElementCommand.ROUTER_IP, controlIP);
        return command;
    }
}
