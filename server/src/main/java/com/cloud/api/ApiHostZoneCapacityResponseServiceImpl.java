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
package com.cloud.api;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiConstants.HostDetails;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.CapacityResponse;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.HostForMigrationResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.IpRangeResponse;
import org.apache.cloudstack.api.response.PodResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;
import org.springframework.stereotype.Component;

import com.cloud.api.query.ViewResponseHelper;
import com.cloud.api.query.vo.DataCenterJoinVO;
import com.cloud.api.query.vo.HostJoinVO;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityVO;
import com.cloud.capacity.dao.CapacityDaoImpl.SummedCapacity;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Pod;
import com.cloud.dc.Vlan;
import com.cloud.host.Host;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.org.Cluster;
import com.cloud.user.AccountManager;
import com.cloud.utils.net.NetUtils;

@Component
public class ApiHostZoneCapacityResponseServiceImpl implements ApiHostZoneCapacityResponseService {

    private static final DecimalFormat s_percentFormat = new DecimalFormat("##.##");

    @Inject
    protected AccountManager _accountMgr;
    @Inject
    private ClusterDetailsDao _clusterDetailsDao;
    @Inject
    private AnnotationDao annotationDao;

    @Override
    public HostResponse createHostResponse(Host host) {
        return createHostResponse(host, EnumSet.of(HostDetails.all));
    }

    @Override
    public HostResponse createHostResponse(Host host, EnumSet<HostDetails> details) {
        List<HostJoinVO> viewHosts = ApiDBUtils.newHostView(host);
        List<HostResponse> listHosts = ViewResponseHelper.createHostResponse(details, viewHosts.toArray(new HostJoinVO[viewHosts.size()]));
        assert listHosts != null && listHosts.size() == 1 : "There should be one host returned";
        return listHosts.get(0);
    }

    @Override
    public HostForMigrationResponse createHostForMigrationResponse(Host host) {
        return createHostForMigrationResponse(host, EnumSet.of(HostDetails.all));
    }

    @Override
    public HostForMigrationResponse createHostForMigrationResponse(Host host, EnumSet<HostDetails> details) {
        List<HostJoinVO> viewHosts = ApiDBUtils.newHostView(host);
        List<HostForMigrationResponse> listHosts = ViewResponseHelper.createHostForMigrationResponse(details, viewHosts.toArray(new HostJoinVO[viewHosts.size()]));
        assert listHosts != null && listHosts.size() == 1 : "There should be one host returned";
        return listHosts.get(0);
    }

    @Override
    public PodResponse createMinimalPodResponse(Pod pod) {
        PodResponse podResponse = new PodResponse();
        podResponse.setId(pod.getUuid());
        podResponse.setName(pod.getName());
        podResponse.setObjectName("pod");
        return podResponse;
    }

    @Override
    public PodResponse createPodResponse(Pod pod, Boolean showCapacities) {
        List<String> startIps = new ArrayList<String>();
        List<String> endIps = new ArrayList<String>();
        List<String> forSystemVms = new ArrayList<String>();
        List<String> vlanIds = new ArrayList<String>();

        List<IpRangeResponse> ipRanges = new ArrayList<>();

        if (pod.getDescription() != null && pod.getDescription().length() > 0) {
            final String[] existingPodIpRanges = pod.getDescription().split(",");

            for (String podIpRange: existingPodIpRanges) {
                IpRangeResponse ipRangeResponse = new IpRangeResponse();
                final String[] existingPodIpRange = podIpRange.split("-");

                String startIp = ((existingPodIpRange.length > 0) && (existingPodIpRange[0] != null)) ? existingPodIpRange[0] : "";
                ipRangeResponse.setStartIp(startIp);
                startIps.add(startIp);

                String endIp = ((existingPodIpRange.length > 1) && (existingPodIpRange[1] != null)) ? existingPodIpRange[1] : "";
                ipRangeResponse.setEndIp(endIp);
                endIps.add(endIp);

                String forSystemVm = (existingPodIpRange.length > 2) && (existingPodIpRange[2] != null) ? existingPodIpRange[2] : "0";
                ipRangeResponse.setForSystemVms(forSystemVm);
                forSystemVms.add(forSystemVm);

                String vlanId = (existingPodIpRange.length > 3) &&
                        (existingPodIpRange[3] != null && !existingPodIpRange[3].equals("untagged")) ?
                        BroadcastDomainType.Vlan.toUri(existingPodIpRange[3]).toString() :
                        BroadcastDomainType.Vlan.toUri(Vlan.UNTAGGED).toString();
                ipRangeResponse.setVlanId(vlanId);
                vlanIds.add(vlanId);

                ipRanges.add(ipRangeResponse);
            }
        }

        PodResponse podResponse = new PodResponse();
        podResponse.setId(pod.getUuid());
        podResponse.setName(pod.getName());
        DataCenterVO zone = ApiDBUtils.findZoneById(pod.getDataCenterId());
        if (zone != null) {
            podResponse.setZoneId(zone.getUuid());
            podResponse.setZoneName(zone.getName());
        }
        podResponse.setNetmask(NetUtils.getCidrNetmask(pod.getCidrSize()));
        podResponse.setIpRanges(ipRanges);
        podResponse.setStartIp(startIps);
        podResponse.setEndIp(endIps);
        podResponse.setForSystemVms(forSystemVms);
        podResponse.setVlanId(vlanIds);
        podResponse.setGateway(pod.getGateway());
        podResponse.setAllocationState(pod.getAllocationState().toString());
        podResponse.setStorageAccessGroups(pod.getStorageAccessGroups());
        podResponse.setZoneStorageAccessGroups(zone.getStorageAccessGroups());
        if (showCapacities != null && showCapacities) {
            Set<CapacityResponse> capacityResponses = new HashSet<CapacityResponse>(getCapacityResponses(null, pod.getId(), null));
            capacityResponses.addAll(getStatsCapacityresponse(null, null, pod.getId(), pod.getDataCenterId()));
            podResponse.setCapacities(new ArrayList<CapacityResponse>(capacityResponses));
        }

        podResponse.setHasAnnotation(annotationDao.hasAnnotations(pod.getUuid(), AnnotationService.EntityType.POD.name(),
                _accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())));
        podResponse.setObjectName("pod");
        return podResponse;
    }

    @Override
    public ZoneResponse createZoneResponse(ResponseView view, DataCenter dataCenter, Boolean showCapacities, Boolean showResourceIcon) {
        DataCenterJoinVO vOffering = ApiDBUtils.newDataCenterView(dataCenter);
        return ApiDBUtils.newDataCenterResponse(view, vOffering, showCapacities, showResourceIcon);
    }

    @Override
    public List<CapacityResponse> getDataCenterCapacityResponse(Long zoneId) {
        Set<CapacityResponse> capacityResponses = new HashSet<CapacityResponse>(getCapacityResponses(zoneId, null, null));
        capacityResponses.addAll(getStatsCapacityresponse(null, null, null, zoneId));
        return new ArrayList<CapacityResponse>(capacityResponses);
    }

    private static List<CapacityResponse> getCapacityResponses(Long zoneId, Long podId, Long clusterId) {
        List<SummedCapacity> capacities = ApiDBUtils.getCapacityByClusterPodZone(zoneId, podId, clusterId);
        Set<CapacityResponse> capacityResponses = new HashSet<CapacityResponse>();

        for (SummedCapacity capacity : capacities) {
            CapacityResponse capacityResponse = new CapacityResponse();
            capacityResponse.setCapacityType(capacity.getCapacityType());
            capacityResponse.setCapacityName(CapacityVO.getCapacityName(capacity.getCapacityType()));
            capacityResponse.setCapacityUsed(capacity.getUsedCapacity() + capacity.getReservedCapacity());
            if (capacity.getCapacityType() == Capacity.CAPACITY_TYPE_STORAGE_ALLOCATED) {
                List<SummedCapacity> c = ApiDBUtils.findNonSharedStorageForClusterPodZone(zoneId, podId, clusterId);
                capacityResponse.setCapacityTotal(capacity.getTotalCapacity() - c.get(0).getTotalCapacity());
                capacityResponse.setCapacityUsed(capacity.getUsedCapacity() - c.get(0).getUsedCapacity());
            } else {
                capacityResponse.setCapacityTotal(capacity.getTotalCapacity());
            }
            if (capacityResponse.getCapacityTotal() != 0) {
                capacityResponse.setPercentUsed(s_percentFormat.format((float)capacityResponse.getCapacityUsed() / (float)capacityResponse.getCapacityTotal() * 100f));
            } else {
                capacityResponse.setPercentUsed(s_percentFormat.format(0L));
            }
            capacityResponses.add(capacityResponse);
        }

        return new ArrayList<CapacityResponse>(capacityResponses);
    }

    private static List<CapacityResponse> getStatsCapacityresponse(Long poolId, Long clusterId, Long podId, Long zoneId) {
        List<CapacityVO> capacities = new ArrayList<CapacityVO>();
        capacities.add(ApiDBUtils.getStoragePoolUsedStats(poolId, clusterId, podId, zoneId));
        if (clusterId == null && podId == null) {
            capacities.add(ApiDBUtils.getSecondaryStorageUsedStats(poolId, zoneId));
            capacities.add(ApiDBUtils.getObjectStorageUsedStats(zoneId));
        }

        List<CapacityResponse> capacityResponses = new ArrayList<CapacityResponse>();
        for (CapacityVO capacity : capacities) {
            CapacityResponse capacityResponse = new CapacityResponse();
            capacityResponse.setCapacityType(capacity.getCapacityType());
            capacityResponse.setCapacityName(CapacityVO.getCapacityName(capacity.getCapacityType()));
            capacityResponse.setCapacityUsed(capacity.getUsedCapacity());
            capacityResponse.setCapacityTotal(capacity.getTotalCapacity());
            if (capacityResponse.getCapacityTotal() != 0) {
                capacityResponse.setPercentUsed(s_percentFormat.format((float)capacityResponse.getCapacityUsed() / (float)capacityResponse.getCapacityTotal() * 100f));
            } else {
                capacityResponse.setPercentUsed(s_percentFormat.format(0L));
            }
            capacityResponses.add(capacityResponse);
        }

        return capacityResponses;
    }

    @Override
    public ClusterResponse createMinimalClusterResponse(Cluster cluster) {
        ClusterResponse clusterResponse = new ClusterResponse();
        clusterResponse.setId(cluster.getUuid());
        clusterResponse.setName(cluster.getName());
        clusterResponse.setObjectName("cluster");
        return clusterResponse;
    }

    @Override
    public ClusterResponse createClusterResponse(Cluster cluster, Boolean showCapacities) {
        ClusterResponse clusterResponse = new ClusterResponse();
        clusterResponse.setInternalId(cluster.getId());
        clusterResponse.setId(cluster.getUuid());
        clusterResponse.setName(cluster.getName());
        HostPodVO pod = ApiDBUtils.findPodById(cluster.getPodId());
        if (pod != null) {
            clusterResponse.setPodId(pod.getUuid());
            clusterResponse.setPodName(pod.getName());
        }
        DataCenterVO dc = ApiDBUtils.findZoneById(cluster.getDataCenterId());
        if (dc != null) {
            clusterResponse.setZoneId(dc.getUuid());
            clusterResponse.setZoneName(dc.getName());
        }
        clusterResponse.setHypervisorType(cluster.getHypervisorType().getHypervisorDisplayName());
        clusterResponse.setClusterType(cluster.getClusterType().toString());
        clusterResponse.setAllocationState(cluster.getAllocationState().toString());
        clusterResponse.setManagedState(cluster.getManagedState().toString());
        String cpuOvercommitRatio = ApiDBUtils.findClusterDetails(cluster.getId(), "cpuOvercommitRatio");
        String memoryOvercommitRatio = ApiDBUtils.findClusterDetails(cluster.getId(), "memoryOvercommitRatio");
        clusterResponse.setCpuOvercommitRatio(cpuOvercommitRatio);
        clusterResponse.setMemoryOvercommitRatio(memoryOvercommitRatio);
        clusterResponse.setResourceDetails(_clusterDetailsDao.findDetails(cluster.getId()));
        if (cluster.getArch() != null) {
            clusterResponse.setArch(cluster.getArch().getType());
        }

        clusterResponse.setStorageAccessGroups(cluster.getStorageAccessGroups());
        clusterResponse.setPodStorageAccessGroups(pod.getStorageAccessGroups());
        clusterResponse.setZoneStorageAccessGroups(dc.getStorageAccessGroups());

        if (showCapacities != null && showCapacities) {
            Set<CapacityResponse> capacityResponses = new HashSet<CapacityResponse>(getCapacityResponses(null, null, cluster.getId()));
            capacityResponses.addAll(getStatsCapacityresponse(null, cluster.getId(), pod.getId(), pod.getDataCenterId()));
            clusterResponse.setCapacities(new ArrayList<CapacityResponse>(capacityResponses));
        }
        clusterResponse.setHasAnnotation(annotationDao.hasAnnotations(cluster.getUuid(), AnnotationService.EntityType.CLUSTER.name(),
                _accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())));
        clusterResponse.setObjectName("cluster");
        return clusterResponse;
    }
}
