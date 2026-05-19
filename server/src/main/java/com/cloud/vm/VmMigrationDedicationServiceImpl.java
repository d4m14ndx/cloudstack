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
package com.cloud.vm;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.alert.AlertManager;
import com.cloud.configuration.Config;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.deploy.PlannerHostReservationVO;
import com.cloud.deploy.dao.PlannerHostReservationDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.utils.DateUtil;
import com.cloud.utils.NumbersUtil;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmMigrationDedicationServiceImpl implements VmMigrationDedicationService {
    private static final Logger LOG = LogManager.getLogger(VmMigrationDedicationServiceImpl.class);
    private static final String IMPLICIT_DEDICATION_PLANNER = "ImplicitDedicationPlanner";
    private static final String IMPLICIT_DEDICATION_MODE = "ImplicitDedicationMode";
    private static final String PREFERRED = "Preferred";

    @Inject
    private HostDao hostDao;
    @Inject
    private DedicatedResourceDao dedicatedDao;
    @Inject
    private AlertManager alertMgr;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private PlannerHostReservationDao plannerHostReservationDao;
    @Inject
    private ConfigurationDao configDao;
    @Inject
    private VmMigrationValidator vmMigrationValidator;

    private Long accountOfDedicatedHost(HostVO host) {
        long hostId = host.getId();
        DedicatedResourceVO dedicatedHost = dedicatedDao.findByHostId(hostId);
        DedicatedResourceVO dedicatedClusterOfHost = dedicatedDao.findByClusterId(host.getClusterId());
        DedicatedResourceVO dedicatedPodOfHost = dedicatedDao.findByPodId(host.getPodId());
        if (dedicatedHost != null) {
            return dedicatedHost.getAccountId();
        }
        if (dedicatedClusterOfHost != null) {
            return dedicatedClusterOfHost.getAccountId();
        }
        if (dedicatedPodOfHost != null) {
            return dedicatedPodOfHost.getAccountId();
        }
        return null;
    }

    private Long domainOfDedicatedHost(HostVO host) {
        long hostId = host.getId();
        DedicatedResourceVO dedicatedHost = dedicatedDao.findByHostId(hostId);
        DedicatedResourceVO dedicatedClusterOfHost = dedicatedDao.findByClusterId(host.getClusterId());
        DedicatedResourceVO dedicatedPodOfHost = dedicatedDao.findByPodId(host.getPodId());
        if (dedicatedHost != null) {
            return dedicatedHost.getDomainId();
        }
        if (dedicatedClusterOfHost != null) {
            return dedicatedClusterOfHost.getDomainId();
        }
        if (dedicatedPodOfHost != null) {
            return dedicatedPodOfHost.getDomainId();
        }
        return null;
    }

    @Override
    public void checkHostsDedication(VMInstanceVO vm, long srcHostId, long destHostId) {
        HostVO srcHost = hostDao.findById(srcHostId);
        HostVO destHost = hostDao.findById(destHostId);
        boolean srcExplDedicated = vmMigrationValidator.checkIfHostIsDedicated(srcHost);
        boolean destExplDedicated = vmMigrationValidator.checkIfHostIsDedicated(destHost);
        //if srcHost is explicitly dedicated and destination Host is not
        if (srcExplDedicated && !destExplDedicated) {
            //raise an alert
            String msg = String.format("VM is being migrated from a explicitly dedicated host %s to non-dedicated host %s", srcHost, destHost);
            alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
            LOG.warn(msg);
        }
        //if srcHost is non dedicated but destination Host is explicitly dedicated
        if (!srcExplDedicated && destExplDedicated) {
            //raise an alert
            String msg = String.format("VM is being migrated from a non dedicated host %s to a explicitly dedicated host %s", srcHost, destHost);
            alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
            LOG.warn(msg);
        }

        //if hosts are dedicated to different account/domains, raise an alert
        if (srcExplDedicated && destExplDedicated) {
            if (!((accountOfDedicatedHost(srcHost) == null) || (accountOfDedicatedHost(srcHost).equals(accountOfDedicatedHost(destHost))))) {
                String msg = String.format("VM is being migrated from host %s explicitly dedicated to account %d to host %s explicitly dedicated to account %d",
                        srcHost, accountOfDedicatedHost(srcHost), destHost, accountOfDedicatedHost(destHost));
                alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
                LOG.warn(msg);
            }
            if (!((domainOfDedicatedHost(srcHost) == null) || (domainOfDedicatedHost(srcHost).equals(domainOfDedicatedHost(destHost))))) {
                String msg = String.format("VM is being migrated from host %s explicitly dedicated to domain %d to host %s explicitly dedicated to domain %d",
                        srcHost, domainOfDedicatedHost(srcHost), destHost, domainOfDedicatedHost(destHost));
                alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
                LOG.warn(msg);
            }
        }

        // Checks for implicitly dedicated hosts
        ServiceOfferingVO deployPlanner = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
        if (deployPlanner.getDeploymentPlanner() != null && deployPlanner.getDeploymentPlanner().equals(IMPLICIT_DEDICATION_PLANNER)) {
            //VM is deployed using implicit planner
            long accountOfVm = vm.getAccountId();
            String msg = String.format("VM of account %d with implicit deployment planner being migrated to host %s", accountOfVm, destHost);
            //Get all vms on destination host
            boolean emptyDestination = false;
            List<VMInstanceVO> vmsOnDest = getVmsOnHost(destHostId);
            if (vmsOnDest == null || vmsOnDest.isEmpty()) {
                emptyDestination = true;
            }

            if (!emptyDestination) {
                //Check if vm is deployed using strict implicit planner
                if (!isServiceOfferingUsingPlannerInPreferredMode(vm.getServiceOfferingId())) {
                    //Check if all vms on destination host are created using strict implicit mode
                    if (!checkIfAllVmsCreatedInStrictMode(accountOfVm, vmsOnDest)) {
                        msg = String.format("Instance of Account %d with strict implicit deployment planner being migrated to host %s not having all Instances strict implicitly dedicated to Account %d",
                                accountOfVm, destHost, accountOfVm);
                    }
                } else {
                    //If vm is deployed using preferred implicit planner, check if all vms on destination host must be
                    //using implicit planner and must belong to same account
                    for (VMInstanceVO vmsDest : vmsOnDest) {
                        ServiceOfferingVO destPlanner = serviceOfferingDao.findById(vm.getId(), vmsDest.getServiceOfferingId());
                        if (!((destPlanner.getDeploymentPlanner() != null && destPlanner.getDeploymentPlanner().equals(IMPLICIT_DEDICATION_PLANNER)) && vmsDest.getAccountId() == accountOfVm)) {
                            msg = String.format("Instance of Account %d with preferred implicit deployment planner being migrated to host %s not having all Instances implicitly dedicated to Account %d",
                                    accountOfVm, destHost, accountOfVm);
                        }
                    }
                }
            }
            alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
            LOG.warn(msg);

        } else {
            //VM is not deployed using implicit planner, check if it migrated between dedicated hosts
            List<PlannerHostReservationVO> reservedHosts = plannerHostReservationDao.listAllDedicatedHosts();
            boolean srcImplDedicated = false;
            boolean destImplDedicated = false;
            String msg = null;
            for (PlannerHostReservationVO reservedHost : reservedHosts) {
                if (reservedHost.getHostId() == srcHostId) {
                    srcImplDedicated = true;
                }
                if (reservedHost.getHostId() == destHostId) {
                    destImplDedicated = true;
                }
            }
            if (srcImplDedicated) {
                if (destImplDedicated) {
                    msg = String.format("VM is being migrated from implicitly dedicated host %s to another implicitly dedicated host %s", srcHost, destHost);
                } else {
                    msg = String.format("VM is being migrated from implicitly dedicated host %s to shared host %s", srcHost, destHost);
                }
                alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
                LOG.warn(msg);
            } else {
                if (destImplDedicated) {
                    msg = String.format("VM is being migrated from shared host %s to implicitly dedicated host %s", srcHost, destHost);
                    alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
                    LOG.warn(msg);
                }
            }
        }
    }

    private List<VMInstanceVO> getVmsOnHost(long hostId) {
        List<VMInstanceVO> vms =  vmInstanceDao.listUpByHostId(hostId);
        List<VMInstanceVO> vmsByLastHostId = vmInstanceDao.listByLastHostId(hostId);
        if (vmsByLastHostId.size() > 0) {
            // check if any VMs are within skip.counting.hours, if yes we have to consider the host.
            for (VMInstanceVO stoppedVM : vmsByLastHostId) {
                long secondsSinceLastUpdate = (DateUtil.currentGMTTime().getTime() - stoppedVM.getUpdateTime().getTime()) / 1000;
                if (secondsSinceLastUpdate < capacityReleaseInterval()) {
                    vms.add(stoppedVM);
                }
            }
        }

        return vms;
    }

    private boolean isServiceOfferingUsingPlannerInPreferredMode(long serviceOfferingId) {
        boolean preferred = false;
        Map<String, String> details = serviceOfferingDetailsDao.listDetailsKeyPairs(serviceOfferingId);
        if (details != null && !details.isEmpty()) {
            String preferredAttribute = details.get(IMPLICIT_DEDICATION_MODE);
            if (preferredAttribute != null && preferredAttribute.equals(PREFERRED)) {
                preferred = true;
            }
        }
        return preferred;
    }

    private boolean checkIfAllVmsCreatedInStrictMode(Long accountId, List<VMInstanceVO> allVmsOnHost) {
        boolean createdByImplicitStrict = true;
        if (allVmsOnHost.isEmpty()) {
            return false;
        }
        for (VMInstanceVO vm : allVmsOnHost) {
            if (!isImplicitPlannerUsedByOffering(vm.getServiceOfferingId()) || vm.getAccountId() != accountId) {
                LOG.info("Host {} for Instance {} found to be running an Instance created by a planner other than implicit, or running Instances of other Account",
                        hostDao.findById(vm.getHostId()), vm);
                createdByImplicitStrict = false;
                break;
            } else if (isServiceOfferingUsingPlannerInPreferredMode(vm.getServiceOfferingId()) || vm.getAccountId() != accountId) {
                LOG.info("Host {} for Instance {} found to be running an Instance created by an implicit planner in preferred mode, or running Instances of other Account",
                        hostDao.findById(vm.getHostId()), vm);
                createdByImplicitStrict = false;
                break;
            }
        }
        return createdByImplicitStrict;
    }

    private boolean isImplicitPlannerUsedByOffering(long offeringId) {
        boolean implicitPlannerUsed = false;
        ServiceOfferingVO offering = serviceOfferingDao.findByIdIncludingRemoved(offeringId);
        if (offering == null) {
            LOG.error("Couldn't retrieve the offering by the given id : " + offeringId);
        } else {
            String plannerName = offering.getDeploymentPlanner();
            if (plannerName != null) {
                if (plannerName.equals(IMPLICIT_DEDICATION_PLANNER)) {
                    implicitPlannerUsed = true;
                }
            }
        }

        return implicitPlannerUsed;
    }

    private int capacityReleaseInterval() {
        return NumbersUtil.parseInt(configDao.getValue(Config.CapacitySkipcountingHours.key()), 3600);
    }
}
