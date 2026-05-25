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
package com.cloud.configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.offering.CloneDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CloneServiceOfferingCmd;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.vm.VirtualMachine;

@RunWith(MockitoJUnitRunner.Silent.class)
public class OfferingCloneParameterServiceImplTest {

    private OfferingCloneParameterServiceImpl service;

    @Mock
    private ServiceOfferingDao serviceOfferingDao;

    @Mock
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;

    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    private DiskOfferingDetailsDao diskOfferingDetailsDao;

    @Before
    public void setUp() {
        service = new OfferingCloneParameterServiceImpl();
        ReflectionTestUtils.setField(service, "_serviceOfferingDao", serviceOfferingDao);
        ReflectionTestUtils.setField(service, "_serviceOfferingDetailsDao", serviceOfferingDetailsDao);
        ReflectionTestUtils.setField(service, "_diskOfferingDao", diskOfferingDao);
        ReflectionTestUtils.setField(service, "diskOfferingDetailsDao", diskOfferingDetailsDao);
    }

    @Test
    public void getAndValidateSourceOfferingThrowsWhenSourceOfferingMissing() {
        Mockito.when(serviceOfferingDao.findById(101L)).thenReturn(null);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class,
                () -> service.getAndValidateSourceOffering(101L));

        Assert.assertTrue(exception.getMessage().contains("Unable to find service offering with ID: 101"));
    }

    @Test
    public void getAndValidateSourceOfferingReturnsDaoObject() {
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(serviceOfferingDao.findById(101L)).thenReturn(sourceOffering);

        Assert.assertSame(sourceOffering, service.getAndValidateSourceOffering(101L));
    }

    @Test
    public void getSourceDiskOfferingResolvesFromSourceDiskOfferingId() {
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        DiskOfferingVO sourceDiskOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(sourceOffering.getDiskOfferingId()).thenReturn(22L);
        Mockito.when(diskOfferingDao.findById(22L)).thenReturn(sourceDiskOffering);

        Assert.assertSame(sourceDiskOffering, service.getSourceDiskOffering(sourceOffering));
    }

    @Test
    public void getSourceDiskOfferingReturnsNullWhenSourceHasNoDiskOfferingId() {
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(sourceOffering.getDiskOfferingId()).thenReturn(null);

        Assert.assertNull(service.getSourceDiskOffering(sourceOffering));
    }

    @Test
    public void getOrDefaultReturnsCommandValueWhenPresent() {
        Assert.assertEquals("cmd", service.getOrDefault("cmd", "default"));
    }

    @Test
    public void getOrDefaultReturnsDefaultValueWhenCommandValueMissing() {
        Assert.assertEquals("default", service.getOrDefault(null, "default"));
    }

    @Test
    public void resolveBooleanParamUsesCommandSupplierOnlyWhenRequestParamContainsKey() {
        Map<String, String> requestParams = new HashMap<>();
        requestParams.put(ApiConstants.OFFER_HA, "true");

        Assert.assertTrue(service.resolveBooleanParam(requestParams, ApiConstants.OFFER_HA, () -> true, false));
        Assert.assertFalse(service.resolveBooleanParam(requestParams, ApiConstants.LIMIT_CPU_USE, () -> {
            throw new AssertionError("Supplier should not be called");
        }, false));
    }

    @Test
    public void resolveDomainIdsPrefersServiceCommandIds() {
        CloneServiceOfferingCmd cmd = Mockito.mock(CloneServiceOfferingCmd.class);
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        List<Long> commandDomainIds = List.of(1L, 2L);
        Mockito.when(cmd.getDomainIds()).thenReturn(commandDomainIds);

        Assert.assertSame(commandDomainIds, service.resolveDomainIds(cmd, sourceOffering));
    }

    @Test
    public void resolveDomainIdsFallsBackToServiceOfferingDetailsDao() {
        CloneServiceOfferingCmd cmd = Mockito.mock(CloneServiceOfferingCmd.class);
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        List<Long> storedDomainIds = List.of(3L, 4L);
        Mockito.when(cmd.getDomainIds()).thenReturn(null);
        Mockito.when(sourceOffering.getId()).thenReturn(55L);
        Mockito.when(serviceOfferingDetailsDao.findDomainIds(55L)).thenReturn(storedDomainIds);

        Assert.assertSame(storedDomainIds, service.resolveDomainIds(cmd, sourceOffering));
    }

    @Test
    public void resolveZoneIdsPrefersServiceCommandIds() {
        CloneServiceOfferingCmd cmd = Mockito.mock(CloneServiceOfferingCmd.class);
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        List<Long> commandZoneIds = List.of(5L, 6L);
        Mockito.when(cmd.getZoneIds()).thenReturn(commandZoneIds);

        Assert.assertSame(commandZoneIds, service.resolveZoneIds(cmd, sourceOffering));
    }

    @Test
    public void mergeOfferingDetailsCopiesSourceDetailsWhenCommandDetailsEmpty() {
        CloneServiceOfferingCmd cmd = Mockito.mock(CloneServiceOfferingCmd.class);
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        OfferingCloneParameterServiceImpl.CustomOfferingParams customParams = new OfferingCloneParameterServiceImpl.CustomOfferingParams();
        Map<String, String> sourceDetails = Map.of("source-key", "source-value");
        Mockito.when(cmd.getDetails()).thenReturn(null);
        Mockito.when(sourceOffering.getId()).thenReturn(88L);
        Mockito.when(serviceOfferingDetailsDao.listDetailsKeyPairs(88L)).thenReturn(sourceDetails);

        Assert.assertEquals(sourceDetails, service.mergeOfferingDetails(cmd, sourceOffering, customParams));
    }

    @Test
    public void mergeOfferingDetailsPrefersCommandDetailsWhenSupplied() {
        CloneServiceOfferingCmd cmd = Mockito.mock(CloneServiceOfferingCmd.class);
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        OfferingCloneParameterServiceImpl.CustomOfferingParams customParams = new OfferingCloneParameterServiceImpl.CustomOfferingParams();
        Map<String, String> commandDetails = Map.of("cmd-key", "cmd-value");
        Mockito.when(cmd.getDetails()).thenReturn(commandDetails);

        Assert.assertEquals(commandDetails, service.mergeOfferingDetails(cmd, sourceOffering, customParams));
    }

    @Test
    public void mergeOfferingDetailsAddsCustomBoundsOnlyWhenAllBoundsResolve() {
        CloneServiceOfferingCmd cmd = Mockito.mock(CloneServiceOfferingCmd.class);
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(sourceOffering.getId()).thenReturn(99L);
        Mockito.when(cmd.getMinCPUs()).thenReturn(1);
        Mockito.when(cmd.getMaxCPUs()).thenReturn(4);
        Mockito.when(cmd.getMinMemory()).thenReturn(512);
        Mockito.when(cmd.getMaxMemory()).thenReturn(4096);

        OfferingCloneParameterServiceImpl.CustomOfferingParams customParams = service.resolveCustomOfferingParams(cmd, sourceOffering, true);

        Map<String, String> mergedDetails = service.mergeOfferingDetails(cmd, sourceOffering, customParams);
        Assert.assertEquals("1", mergedDetails.get(ApiConstants.MIN_CPU_NUMBER));
        Assert.assertEquals("4", mergedDetails.get(ApiConstants.MAX_CPU_NUMBER));
        Assert.assertEquals("512", mergedDetails.get(ApiConstants.MIN_MEMORY));
        Assert.assertEquals("4096", mergedDetails.get(ApiConstants.MAX_MEMORY));
    }

    @Test
    public void resolvePurgeResourcesUsesCommandValueOnlyWhenPresentInRequestParams() {
        CloneServiceOfferingCmd cmd = Mockito.mock(CloneServiceOfferingCmd.class);
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(sourceOffering.getId()).thenReturn(77L);
        Mockito.when(cmd.isPurgeResources()).thenReturn(true);
        Mockito.when(serviceOfferingDetailsDao.getDetail(77L, ServiceOffering.PURGE_DB_ENTITIES_KEY)).thenReturn("false");

        Assert.assertTrue(service.resolvePurgeResources(cmd, Map.of(ApiConstants.PURGE_RESOURCES, "true"), sourceOffering));
        Assert.assertFalse(service.resolvePurgeResources(cmd, Map.of(), sourceOffering));
    }

    @Test
    public void resolveLeaseParamsReturnsNullsWhenNoDurationOrExpiryActionResolves() {
        CloneServiceOfferingCmd cmd = Mockito.mock(CloneServiceOfferingCmd.class);
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(sourceOffering.getId()).thenReturn(44L);
        Mockito.when(cmd.getLeaseDuration()).thenReturn(null);

        OfferingCloneParameterServiceImpl.LeaseParams leaseParams = service.resolveLeaseParams(cmd, sourceOffering);

        Assert.assertNull(leaseParams.leaseDuration);
        Assert.assertNull(leaseParams.leaseExpiryAction);
    }

    @Test
    public void resolveVmTypeReturnsMatchingTypeAndNullForInvalidType() {
        ServiceOfferingVO sourceOffering = Mockito.mock(ServiceOfferingVO.class);
        ServiceOfferingVO invalidSourceOffering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(sourceOffering.getVmType()).thenReturn(VirtualMachine.Type.DomainRouter.name());
        Mockito.when(invalidSourceOffering.getVmType()).thenReturn("not-a-type");

        Assert.assertEquals(VirtualMachine.Type.DomainRouter, service.resolveVmType(sourceOffering));
        Assert.assertNull(service.resolveVmType(invalidSourceOffering));
    }

    @Test
    public void getAndValidateSourceDiskOfferingThrowsWhenSourceDiskOfferingMissing() {
        Mockito.when(diskOfferingDao.findById(202L)).thenReturn(null);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class,
                () -> service.getAndValidateSourceDiskOffering(202L));

        Assert.assertTrue(exception.getMessage().contains("Unable to find disk offering with ID: 202"));
    }

    @Test
    public void resolveDiskOfferingDomainAndZoneIdsFallBackToDiskOfferingDetailsDao() {
        CloneDiskOfferingCmd cmd = Mockito.mock(CloneDiskOfferingCmd.class);
        DiskOfferingVO sourceOffering = Mockito.mock(DiskOfferingVO.class);
        List<Long> domainIds = List.of(11L, 12L);
        List<Long> zoneIds = List.of(13L, 14L);
        Mockito.when(sourceOffering.getId()).thenReturn(66L);
        Mockito.when(diskOfferingDetailsDao.findDomainIds(66L)).thenReturn(domainIds);
        Mockito.when(diskOfferingDetailsDao.findZoneIds(66L)).thenReturn(zoneIds);

        Assert.assertSame(domainIds, service.resolveDomainIdsForDiskOffering(cmd, sourceOffering));
        Assert.assertSame(zoneIds, service.resolveZoneIdsForDiskOffering(cmd, sourceOffering));
    }

    @Test
    public void resolveCacheModePrefersCommandThenSourceThenNull() {
        CloneDiskOfferingCmd commandCacheModeCmd = Mockito.mock(CloneDiskOfferingCmd.class);
        CloneDiskOfferingCmd sourceCacheModeCmd = Mockito.mock(CloneDiskOfferingCmd.class);
        CloneDiskOfferingCmd noCacheModeCmd = Mockito.mock(CloneDiskOfferingCmd.class);
        DiskOfferingVO sourceOffering = Mockito.mock(DiskOfferingVO.class);
        DiskOfferingVO noCacheModeSourceOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(commandCacheModeCmd.getCacheMode()).thenReturn("none");
        Mockito.doReturn(DiskOffering.DiskCacheMode.WRITEBACK).when(sourceOffering).getCacheMode();

        Assert.assertEquals("none", service.resolveCacheMode(commandCacheModeCmd, sourceOffering));
        Assert.assertEquals(DiskOffering.DiskCacheMode.WRITEBACK.toString(), service.resolveCacheMode(sourceCacheModeCmd, sourceOffering));
        Assert.assertNull(service.resolveCacheMode(noCacheModeCmd, noCacheModeSourceOffering));
    }

    @Test
    public void resolveStoragePolicyForDiskOfferingFallsBackFromDetailsDao() {
        CloneDiskOfferingCmd cmd = Mockito.mock(CloneDiskOfferingCmd.class);
        DiskOfferingVO sourceOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(cmd.getStoragePolicy()).thenReturn(null);
        Mockito.when(sourceOffering.getId()).thenReturn(77L);
        Mockito.when(diskOfferingDetailsDao.getDetail(77L, ApiConstants.STORAGE_POLICY)).thenReturn("1234");

        Assert.assertEquals(Long.valueOf(1234L), service.resolveStoragePolicyForDiskOffering(cmd, sourceOffering));
    }

    @Test
    public void mergeDiskOfferingDetailsCopiesSourceOrCommandDetails() {
        CloneDiskOfferingCmd cmd = Mockito.mock(CloneDiskOfferingCmd.class);
        DiskOfferingVO sourceOffering = Mockito.mock(DiskOfferingVO.class);
        Map<String, String> sourceDetails = Map.of("source", "detail");
        Map<String, String> commandDetails = Map.of("command", "detail");
        Mockito.when(sourceOffering.getId()).thenReturn(88L);
        Mockito.when(cmd.getDetails()).thenReturn(null, commandDetails);
        Mockito.when(diskOfferingDetailsDao.listDetailsKeyPairs(88L)).thenReturn(sourceDetails);

        Assert.assertEquals(sourceDetails, service.mergeDiskOfferingDetails(cmd, sourceOffering));
        Assert.assertEquals(commandDetails, service.mergeDiskOfferingDetails(cmd, sourceOffering));
    }
}
