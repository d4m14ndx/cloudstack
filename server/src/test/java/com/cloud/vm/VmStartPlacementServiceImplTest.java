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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.resource.ResourceState;

@RunWith(MockitoJUnitRunner.class)
public class VmStartPlacementServiceImplTest {

    private static final long POD_ID = 11L;
    private static final long CLUSTER_ID = 22L;
    private static final long HOST_ID = 33L;

    @Mock
    private HostPodDao hostPodDao;
    @Mock
    private ClusterDao clusterDao;
    @Mock
    private HostDao hostDao;

    private VmStartPlacementServiceImpl service;

    @Before
    public void setUp() {
        service = new VmStartPlacementServiceImpl();
        ReflectionTestUtils.setField(service, "hostPodDao", hostPodDao);
        ReflectionTestUtils.setField(service, "clusterDao", clusterDao);
        ReflectionTestUtils.setField(service, "hostDao", hostDao);
    }

    @Test
    public void getDestinationPodReturnsNullWhenPodIdIsNull() {
        assertNull(service.getDestinationPod(null, true));
        verifyNoInteractions(hostPodDao);
    }

    @Test
    public void getDestinationPodRejectsNonRootCaller() {
        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.getDestinationPod(POD_ID, false));

        assertTrue(exception.getMessage().contains("Parameter podid can only be specified by a Root Admin"));
        verifyNoInteractions(hostPodDao);
    }

    @Test
    public void getDestinationPodReturnsExistingPod() {
        HostPodVO pod = mock(HostPodVO.class);
        when(hostPodDao.findById(POD_ID)).thenReturn(pod);

        assertSame(pod, service.getDestinationPod(POD_ID, true));
    }

    @Test
    public void getDestinationPodRejectsMissingPod() {
        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.getDestinationPod(POD_ID, true));

        assertTrue(exception.getMessage().contains("Unable to find the pod to deploy the VM, pod id=" + POD_ID));
    }

    @Test
    public void getDestinationClusterReturnsNullWhenClusterIdIsNull() {
        assertNull(service.getDestinationCluster(null, true));
        verifyNoInteractions(clusterDao);
    }

    @Test
    public void getDestinationClusterRejectsNonRootCaller() {
        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.getDestinationCluster(CLUSTER_ID, false));

        assertTrue(exception.getMessage().contains("Parameter clusterid can only be specified by a Root Admin"));
        verifyNoInteractions(clusterDao);
    }

    @Test
    public void getDestinationClusterReturnsExistingCluster() {
        ClusterVO cluster = mock(ClusterVO.class);
        when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);

        assertSame(cluster, service.getDestinationCluster(CLUSTER_ID, true));
    }

    @Test
    public void getDestinationClusterRejectsMissingCluster() {
        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.getDestinationCluster(CLUSTER_ID, true));

        assertTrue(exception.getMessage().contains("Unable to find the cluster to deploy the VM, cluster id=" + CLUSTER_ID));
    }

    @Test
    public void getDestinationHostReturnsNullWhenHostIdIsNull() {
        assertNull(service.getDestinationHost(null, true, true));
        verifyNoInteractions(hostDao);
    }

    @Test
    public void getDestinationHostRejectsExplicitHostForNonRootCaller() {
        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.getDestinationHost(HOST_ID, false, true));

        assertTrue(exception.getMessage().contains("Parameter hostid can only be specified by a Root Admin"));
        verifyNoInteractions(hostDao);
    }

    @Test
    public void getDestinationHostReturnsExistingUpEnabledHost() {
        HostVO host = host("host-a", ResourceState.Enabled, Status.Up);
        when(hostDao.findById(HOST_ID)).thenReturn(host);

        assertSame(host, service.getDestinationHost(HOST_ID, true, true));
    }

    @Test
    public void getDestinationHostAllowsImplicitHostForNonRootCaller() {
        HostVO host = host("host-a", ResourceState.Enabled, Status.Up);
        when(hostDao.findById(HOST_ID)).thenReturn(host);

        assertSame(host, service.getDestinationHost(HOST_ID, false, false));
    }

    @Test
    public void getDestinationHostRejectsMissingHost() {
        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.getDestinationHost(HOST_ID, true, true));

        assertTrue(exception.getMessage().contains("Unable to find the host to deploy the VM, host id=" + HOST_ID));
    }

    @Test
    public void getDestinationHostRejectsHostThatIsNotUpAndEnabled() {
        HostVO host = host("host-a", ResourceState.Disabled, Status.Up);
        when(hostDao.findById(HOST_ID)).thenReturn(host);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.getDestinationHost(HOST_ID, true, true));

        assertTrue(exception.getMessage().contains("Unable to deploy the VM as the host: host-a is not in the right state"));
    }

    private HostVO host(String name, ResourceState resourceState, Status status) {
        HostVO host = mock(HostVO.class);
        when(host.getName()).thenReturn(name);
        when(host.getResourceState()).thenReturn(resourceState);
        when(host.getStatus()).thenReturn(status);
        return host;
    }
}
