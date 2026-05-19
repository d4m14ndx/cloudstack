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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

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
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmMigrationDedicationServiceImplTest {
    private static final long VM_ID = 42L;
    private static final long VM_ACCOUNT_ID = 7L;
    private static final long SRC_HOST_ID = 100L;
    private static final long DEST_HOST_ID = 200L;
    private static final long SERVICE_OFFERING_ID = 300L;
    private static final long DATA_CENTER_ID = 10L;
    private static final long POD_ID = 20L;

    @Mock
    private HostDao hostDao;
    @Mock
    private DedicatedResourceDao dedicatedDao;
    @Mock
    private AlertManager alertMgr;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private PlannerHostReservationDao plannerHostReservationDao;
    @Mock
    private ConfigurationDao configDao;
    @Mock
    private VmMigrationValidator vmMigrationValidator;

    private VmMigrationDedicationServiceImpl service;
    private VMInstanceVO vm;
    private HostVO srcHost;
    private HostVO destHost;

    @Before
    public void setUp() {
        service = new VmMigrationDedicationServiceImpl();
        ReflectionTestUtils.setField(service, "hostDao", hostDao);
        ReflectionTestUtils.setField(service, "dedicatedDao", dedicatedDao);
        ReflectionTestUtils.setField(service, "alertMgr", alertMgr);
        ReflectionTestUtils.setField(service, "serviceOfferingDao", serviceOfferingDao);
        ReflectionTestUtils.setField(service, "serviceOfferingDetailsDao", serviceOfferingDetailsDao);
        ReflectionTestUtils.setField(service, "vmInstanceDao", vmInstanceDao);
        ReflectionTestUtils.setField(service, "plannerHostReservationDao", plannerHostReservationDao);
        ReflectionTestUtils.setField(service, "configDao", configDao);
        ReflectionTestUtils.setField(service, "vmMigrationValidator", vmMigrationValidator);

        vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getAccountId()).thenReturn(VM_ACCOUNT_ID);
        when(vm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        when(vm.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        when(vm.getPodIdToDeployIn()).thenReturn(POD_ID);

        srcHost = host(SRC_HOST_ID, 11L, 21L);
        destHost = host(DEST_HOST_ID, 12L, 22L);
        when(hostDao.findById(SRC_HOST_ID)).thenReturn(srcHost);
        when(hostDao.findById(DEST_HOST_ID)).thenReturn(destHost);
        lenient().when(configDao.getValue(Config.CapacitySkipcountingHours.key())).thenReturn("3600");
        lenient().when(vmInstanceDao.listUpByHostId(DEST_HOST_ID)).thenReturn(new ArrayList<>());
        lenient().when(vmInstanceDao.listByLastHostId(DEST_HOST_ID)).thenReturn(Collections.emptyList());
        when(plannerHostReservationDao.listAllDedicatedHosts()).thenReturn(Collections.emptyList());
        ServiceOfferingVO defaultOffering = offering(null);
        when(serviceOfferingDao.findById(VM_ID, SERVICE_OFFERING_ID)).thenReturn(defaultOffering);
    }

    @Test
    public void explicitDedicatedSourceToNonDedicatedDestinationSendsAlert() {
        when(vmMigrationValidator.checkIfHostIsDedicated(srcHost)).thenReturn(true);
        when(vmMigrationValidator.checkIfHostIsDedicated(destHost)).thenReturn(false);

        service.checkHostsDedication(vm, SRC_HOST_ID, DEST_HOST_ID);

        verifyAlertContains(String.format("VM is being migrated from a explicitly dedicated host %s to non-dedicated host %s", srcHost, destHost));
    }

    @Test
    public void nonDedicatedSourceToExplicitDedicatedDestinationSendsAlert() {
        when(vmMigrationValidator.checkIfHostIsDedicated(srcHost)).thenReturn(false);
        when(vmMigrationValidator.checkIfHostIsDedicated(destHost)).thenReturn(true);

        service.checkHostsDedication(vm, SRC_HOST_ID, DEST_HOST_ID);

        verifyAlertContains(String.format("VM is being migrated from a non dedicated host %s to a explicitly dedicated host %s", srcHost, destHost));
    }

    @Test
    public void explicitDedicatedHostsWithDifferentAccountsSendAccountMismatchAlert() {
        when(vmMigrationValidator.checkIfHostIsDedicated(srcHost)).thenReturn(true);
        when(vmMigrationValidator.checkIfHostIsDedicated(destHost)).thenReturn(true);
        DedicatedResourceVO srcDedication = new DedicatedResourceVO(null, null, null, SRC_HOST_ID, 501L, 101L, 1L);
        DedicatedResourceVO destDedication = new DedicatedResourceVO(null, null, null, DEST_HOST_ID, 501L, 202L, 1L);
        when(dedicatedDao.findByHostId(SRC_HOST_ID)).thenReturn(srcDedication);
        when(dedicatedDao.findByHostId(DEST_HOST_ID)).thenReturn(destDedication);

        service.checkHostsDedication(vm, SRC_HOST_ID, DEST_HOST_ID);

        verifyAlertContains(String.format("VM is being migrated from host %s explicitly dedicated to account %d to host %s explicitly dedicated to account %d", srcHost, 101L,
                destHost, 202L));
    }

    @Test
    public void explicitDedicatedHostsWithDifferentDomainsSendDomainMismatchAlert() {
        when(vmMigrationValidator.checkIfHostIsDedicated(srcHost)).thenReturn(true);
        when(vmMigrationValidator.checkIfHostIsDedicated(destHost)).thenReturn(true);
        DedicatedResourceVO srcDedication = new DedicatedResourceVO(null, null, null, SRC_HOST_ID, 501L, null, 1L);
        DedicatedResourceVO destDedication = new DedicatedResourceVO(null, null, null, DEST_HOST_ID, 502L, null, 1L);
        when(dedicatedDao.findByHostId(SRC_HOST_ID)).thenReturn(srcDedication);
        when(dedicatedDao.findByHostId(DEST_HOST_ID)).thenReturn(destDedication);

        service.checkHostsDedication(vm, SRC_HOST_ID, DEST_HOST_ID);

        verifyAlertContains(String.format("VM is being migrated from host %s explicitly dedicated to domain %d to host %s explicitly dedicated to domain %d", srcHost, 501L,
                destHost, 502L));
    }

    @Test
    public void strictImplicitVmWarnsWhenDestinationHasVmFromAnotherAccount() {
        ServiceOfferingVO implicitOffering = offering("ImplicitDedicationPlanner");
        ServiceOfferingVO existingVmOffering = offering("ImplicitDedicationPlanner");
        when(serviceOfferingDao.findById(VM_ID, SERVICE_OFFERING_ID)).thenReturn(implicitOffering);
        when(serviceOfferingDetailsDao.listDetailsKeyPairs(SERVICE_OFFERING_ID)).thenReturn(Collections.emptyMap());
        VMInstanceVO existingVm = destinationVm(77L, 999L, 301L, DateUtil.currentGMTTime());
        when(vmInstanceDao.listUpByHostId(DEST_HOST_ID)).thenReturn(new ArrayList<>(Collections.singletonList(existingVm)));
        when(serviceOfferingDao.findByIdIncludingRemoved(301L)).thenReturn(existingVmOffering);

        service.checkHostsDedication(vm, SRC_HOST_ID, DEST_HOST_ID);

        verifyAlertContains(String.format("Instance of Account %d with strict implicit deployment planner being migrated to host %s not having all Instances strict implicitly dedicated to Account %d",
                VM_ACCOUNT_ID, destHost, VM_ACCOUNT_ID));
    }

    @Test
    public void preferredImplicitVmWarnsWhenDestinationHasNonImplicitVm() {
        ServiceOfferingVO implicitOffering = offering("ImplicitDedicationPlanner");
        ServiceOfferingVO nonImplicitOffering = offering(null);
        when(serviceOfferingDao.findById(VM_ID, SERVICE_OFFERING_ID)).thenReturn(implicitOffering);
        Map<String, String> details = new HashMap<>();
        details.put("ImplicitDedicationMode", "Preferred");
        when(serviceOfferingDetailsDao.listDetailsKeyPairs(SERVICE_OFFERING_ID)).thenReturn(details);
        VMInstanceVO existingVm = destinationVm(77L, VM_ACCOUNT_ID, 301L, DateUtil.currentGMTTime());
        when(vmInstanceDao.listUpByHostId(DEST_HOST_ID)).thenReturn(new ArrayList<>(Collections.singletonList(existingVm)));
        when(serviceOfferingDao.findById(VM_ID, 301L)).thenReturn(nonImplicitOffering);

        service.checkHostsDedication(vm, SRC_HOST_ID, DEST_HOST_ID);

        verifyAlertContains(String.format("Instance of Account %d with preferred implicit deployment planner being migrated to host %s not having all Instances implicitly dedicated to Account %d",
                VM_ACCOUNT_ID, destHost, VM_ACCOUNT_ID));
    }

    @Test
    public void implicitVmToEmptyDestinationSendsDefaultImplicitAlert() {
        ServiceOfferingVO implicitOffering = offering("ImplicitDedicationPlanner");
        when(serviceOfferingDao.findById(VM_ID, SERVICE_OFFERING_ID)).thenReturn(implicitOffering);

        service.checkHostsDedication(vm, SRC_HOST_ID, DEST_HOST_ID);

        verifyAlertContains(String.format("VM of account %d with implicit deployment planner being migrated to host %s", VM_ACCOUNT_ID, destHost));
    }

    @Test
    public void nonImplicitVmFromImplicitReservedHostToSharedHostSendsAlert() {
        PlannerHostReservationVO reservation = new PlannerHostReservationVO(SRC_HOST_ID, DATA_CENTER_ID, POD_ID, 1L);
        when(plannerHostReservationDao.listAllDedicatedHosts()).thenReturn(Collections.singletonList(reservation));

        service.checkHostsDedication(vm, SRC_HOST_ID, DEST_HOST_ID);

        verifyAlertContains(String.format("VM is being migrated from implicitly dedicated host %s to shared host %s", srcHost, destHost));
    }

    @Test
    public void nonImplicitVmFromSharedHostToImplicitReservedHostSendsAlert() {
        PlannerHostReservationVO reservation = new PlannerHostReservationVO(DEST_HOST_ID, DATA_CENTER_ID, POD_ID, 1L);
        when(plannerHostReservationDao.listAllDedicatedHosts()).thenReturn(Collections.singletonList(reservation));

        service.checkHostsDedication(vm, SRC_HOST_ID, DEST_HOST_ID);

        verifyAlertContains(String.format("VM is being migrated from shared host %s to implicitly dedicated host %s", srcHost, destHost));
    }

    @Test
    public void nonImplicitVmSharedToSharedSendsNoAlert() {
        service.checkHostsDedication(vm, SRC_HOST_ID, DEST_HOST_ID);

        verify(alertMgr, never()).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_USERVM), eq(DATA_CENTER_ID), eq(POD_ID), anyString(), anyString());
    }

    @Test
    public void recentStoppedVmFromLastHostIsIncludedInDestinationOccupancy() {
        ServiceOfferingVO implicitOffering = offering("ImplicitDedicationPlanner");
        ServiceOfferingVO stoppedVmOffering = offering("ImplicitDedicationPlanner");
        when(serviceOfferingDao.findById(VM_ID, SERVICE_OFFERING_ID)).thenReturn(implicitOffering);
        when(serviceOfferingDetailsDao.listDetailsKeyPairs(SERVICE_OFFERING_ID)).thenReturn(Collections.emptyMap());
        when(configDao.getValue(Config.CapacitySkipcountingHours.key())).thenReturn("3600");
        VMInstanceVO recentStoppedVm = destinationVm(77L, 999L, 301L, new Date(DateUtil.currentGMTTime().getTime() - 1000L));
        when(vmInstanceDao.listByLastHostId(DEST_HOST_ID)).thenReturn(Collections.singletonList(recentStoppedVm));
        when(serviceOfferingDao.findByIdIncludingRemoved(301L)).thenReturn(stoppedVmOffering);

        service.checkHostsDedication(vm, SRC_HOST_ID, DEST_HOST_ID);

        verifyAlertContains(String.format("Instance of Account %d with strict implicit deployment planner being migrated to host %s not having all Instances strict implicitly dedicated to Account %d",
                VM_ACCOUNT_ID, destHost, VM_ACCOUNT_ID));
    }

    private HostVO host(long hostId, Long clusterId, Long podId) {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(hostId);
        when(host.getClusterId()).thenReturn(clusterId);
        when(host.getPodId()).thenReturn(podId);
        return host;
    }

    private ServiceOfferingVO offering(String planner) {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.getDeploymentPlanner()).thenReturn(planner);
        return offering;
    }

    private VMInstanceVO destinationVm(long id, long accountId, long offeringId, Date updateTime) {
        VMInstanceVO destinationVm = mock(VMInstanceVO.class);
        when(destinationVm.getAccountId()).thenReturn(accountId);
        when(destinationVm.getServiceOfferingId()).thenReturn(offeringId);
        when(destinationVm.getHostId()).thenReturn(DEST_HOST_ID);
        when(destinationVm.getUpdateTime()).thenReturn(updateTime);
        return destinationVm;
    }

    private void verifyAlertContains(String message) {
        verify(alertMgr).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_USERVM), eq(DATA_CENTER_ID), eq(POD_ID), eq(message), eq(message));
    }
}
