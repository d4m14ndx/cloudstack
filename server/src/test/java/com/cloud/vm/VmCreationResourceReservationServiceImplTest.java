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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.cloud.resourcelimit.CheckedReservation;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.ResourceAllocationException;
import com.cloud.offering.DiskOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.ResourceLimitService;

@RunWith(MockitoJUnitRunner.class)
public class VmCreationResourceReservationServiceImplTest {

    @InjectMocks
    private VmCreationResourceReservationServiceImpl service;

    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private ResourceLimitService resourceLimitService;
    @Mock
    private ReservationDao reservationDao;
    @Mock
    private VmRootDiskValidator vmRootDiskValidator;
    @Mock
    private Account account;

    @Test
    public void reserveStorageResourcesForVmReservesRootAdditionalAndDataDiskResources() {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);

        DiskOfferingVO rootOffering = mockDiskOffering(201L, List.of("root-tag"));
        DiskOfferingVO additionalOffering = mockDiskOffering(202L, List.of("data-tag"));
        DiskOffering dataDiskOffering = mock(DiskOffering.class);
        when(dataDiskOffering.getId()).thenReturn(203L);
        DiskOfferingVO dataOfferingForTags = mockDiskOffering(203L, List.of("extra-tag"));

        VmDiskInfo dataDiskInfo = mock(VmDiskInfo.class);
        when(dataDiskInfo.getDiskOffering()).thenReturn(dataDiskOffering);
        when(dataDiskInfo.getSize()).thenReturn(9L);
        when(vmRootDiskValidator.verifyAndGetDiskSize(additionalOffering, 5L)).thenReturn(5L << 30);
        when(vmRootDiskValidator.verifyAndGetDiskSize(dataDiskOffering, 9L)).thenReturn(9L << 30);

        List<Reserver> reservations = new ArrayList<>();

        try (MockedConstruction<CheckedReservation> ignored = mockConstruction(CheckedReservation.class)) {
            service.reserveStorageResourcesForVm(reservations, account, 202L, 5L, List.of(dataDiskInfo), 201L, offering, 10L);
        } catch (ResourceAllocationException e) {
            fail(e.getMessage());
        }

        assertEquals(6, reservations.size());
        verify(resourceLimitService).getResourceLimitStorageTags(rootOffering);
        verify(resourceLimitService).getResourceLimitStorageTags(additionalOffering);
        verify(resourceLimitService).getResourceLimitStorageTags(dataOfferingForTags);
        verify(vmRootDiskValidator).verifyAndGetDiskSize(additionalOffering, 5L);
        verify(vmRootDiskValidator).verifyAndGetDiskSize(dataDiskOffering, 9L);
    }

    @Test
    public void checkVolumesLimitsIgnoresUndisplayedVolumesAndReusesDiskOfferingTags() {
        DiskOfferingVO firstOffering = mockDiskOffering(301L, List.of("first-tag"));
        DiskOfferingVO secondOffering = mockDiskOffering(302L, List.of("second-tag"));

        VolumeVO firstVolume = mockVolume(301L, 10L, true);
        VolumeVO undisplayedVolume = mockVolume(999L, 20L, false);
        VolumeVO secondVolume = mockVolume(302L, 30L, true);
        VolumeVO repeatedFirstOfferingVolume = mockVolume(301L, 40L, true);
        List<Reserver> reservations = new ArrayList<>();

        try (MockedConstruction<CheckedReservation> ignored = Mockito.mockConstruction(CheckedReservation.class)) {
            service.checkVolumesLimits(account, List.of(firstVolume, undisplayedVolume, secondVolume, repeatedFirstOfferingVolume), reservations);
        } catch (ResourceAllocationException e) {
            fail(e.getMessage());
        }

        assertEquals(6, reservations.size());
        verify(resourceLimitService).getResourceLimitStorageTags(firstOffering);
        verify(resourceLimitService).getResourceLimitStorageTags(secondOffering);
        verify(diskOfferingDao, Mockito.times(1)).findById(301L);
        verify(diskOfferingDao, Mockito.times(1)).findById(302L);
    }

    private DiskOfferingVO mockDiskOffering(long id, List<String> tags) {
        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        when(diskOfferingDao.findById(id)).thenReturn(diskOffering);
        when(resourceLimitService.getResourceLimitStorageTags(diskOffering)).thenReturn(tags);
        return diskOffering;
    }

    private VolumeVO mockVolume(long diskOfferingId, Long size, boolean display) {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.isDisplay()).thenReturn(display);
        if (display) {
            when(volume.getDiskOfferingId()).thenReturn(diskOfferingId);
            when(volume.getSize()).thenReturn(size);
        }
        return volume;
    }
}
