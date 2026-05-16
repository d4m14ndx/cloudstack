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
package com.cloud.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Storage access group resolution and uniqueness checks —
 * extracted from {@link StorageManagerImpl}.
 *
 * @see StorageAccessGroupService
 */
@Component
public class StorageAccessGroupServiceImpl implements StorageAccessGroupService {

    @Inject
    private DataCenterDao dcDao;
    @Inject
    private HostPodDao podDao;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private HostDao hostDao;

    @Override
    public void checkIfStorageAccessGroupsExistsOnZone(long zoneId, List<String> storageAccessGroups) {
        DataCenterVO zoneVO = dcDao.findById(zoneId);

        String storageAccessGroupsOnZone = zoneVO.getStorageAccessGroups();
        List<String> zoneTagsList = parseTags(storageAccessGroupsOnZone);
        List<String> newTags = storageAccessGroups;

        List<String> existingTagsOnZone = (List<String>) CollectionUtils.intersection(newTags, zoneTagsList);

        if (CollectionUtils.isNotEmpty(existingTagsOnZone)) {
            throw new CloudRuntimeException(String.format("access groups already exist on the zone: %s", existingTagsOnZone));
        }
    }

    @Override
    public void checkIfStorageAccessGroupsExistsOnPod(long podId, List<String> storageAccessGroups) {
        HostPodVO podVO = podDao.findById(podId);
        DataCenterVO zoneVO = dcDao.findById(podVO.getDataCenterId());

        String storageAccessGroupsOnPod = podVO.getStorageAccessGroups();
        String storageAccessGroupsOnZone = zoneVO.getStorageAccessGroups();

        List<String> podTagsList = parseTags(storageAccessGroupsOnPod);
        List<String> zoneTagsList = parseTags(storageAccessGroupsOnZone);
        List<String> newTags = storageAccessGroups;

        List<String> existingTagsOnPod = (List<String>) CollectionUtils.intersection(newTags, podTagsList);
        List<String> existingTagsOnZone = (List<String>) CollectionUtils.intersection(newTags, zoneTagsList);

        if (CollectionUtils.isNotEmpty(existingTagsOnPod) || CollectionUtils.isNotEmpty(existingTagsOnZone)) {
            String message = "access groups already exist ";

            if (CollectionUtils.isNotEmpty(existingTagsOnPod)) {
                message += String.format("on the pod: %s", existingTagsOnPod);
            }
            if (CollectionUtils.isNotEmpty(existingTagsOnZone)) {
                if (CollectionUtils.isNotEmpty(existingTagsOnPod)) {
                    message += ", ";
                }
                message += String.format("on the zone: %s", existingTagsOnZone);
            }

            throw new CloudRuntimeException(message);
        }
    }

    @Override
    public void checkIfStorageAccessGroupsExistsOnCluster(long clusterId, List<String> storageAccessGroups) {
        ClusterVO clusterVO = clusterDao.findById(clusterId);
        HostPodVO podVO = podDao.findById(clusterVO.getPodId());
        DataCenterVO zoneVO = dcDao.findById(podVO.getDataCenterId());

        String storageAccessGroupsOnCluster = clusterVO.getStorageAccessGroups();
        String storageAccessGroupsOnPod = podVO.getStorageAccessGroups();
        String storageAccessGroupsOnZone = zoneVO.getStorageAccessGroups();

        List<String> podTagsList = parseTags(storageAccessGroupsOnPod);
        List<String> zoneTagsList = parseTags(storageAccessGroupsOnZone);
        List<String> clusterTagsList = parseTags(storageAccessGroupsOnCluster);
        List<String> newTags = storageAccessGroups;

        List<String> existingTagsOnCluster = (List<String>) CollectionUtils.intersection(newTags, clusterTagsList);
        List<String> existingTagsOnPod = (List<String>) CollectionUtils.intersection(newTags, podTagsList);
        List<String> existingTagsOnZone = (List<String>) CollectionUtils.intersection(newTags, zoneTagsList);

        if (CollectionUtils.isNotEmpty(existingTagsOnCluster) || CollectionUtils.isNotEmpty(existingTagsOnPod) || CollectionUtils.isNotEmpty(existingTagsOnZone)) {
            String message = "access groups already exist ";

            if (CollectionUtils.isNotEmpty(existingTagsOnCluster)) {
                message += String.format("on the cluster: %s", existingTagsOnCluster);
            }
            if (CollectionUtils.isNotEmpty(existingTagsOnPod)) {
                if (CollectionUtils.isNotEmpty(existingTagsOnCluster)) {
                    message += ", ";
                }
                message += String.format("on the pod: %s", existingTagsOnPod);
            }
            if (CollectionUtils.isNotEmpty(existingTagsOnZone)) {
                if (CollectionUtils.isNotEmpty(existingTagsOnCluster) || CollectionUtils.isNotEmpty(existingTagsOnPod)) {
                    message += ", ";
                }
                message += String.format("on the zone: %s", existingTagsOnZone);
            }

            throw new CloudRuntimeException(message);
        }
    }

    @Override
    public String[] getStorageAccessGroups(Long zoneId, Long podId, Long clusterId, Long hostId) {
        List<String> storageAccessGroups = new ArrayList<>();
        if (hostId != null) {
            HostVO host = hostDao.findById(hostId);
            ClusterVO cluster = clusterDao.findById(host.getClusterId());
            HostPodVO pod = podDao.findById(cluster.getPodId());
            DataCenterVO zone = dcDao.findById(pod.getDataCenterId());
            storageAccessGroups.addAll(List.of(com.cloud.utils.StringUtils.splitCommaSeparatedStrings(host.getStorageAccessGroups(), cluster.getStorageAccessGroups(), pod.getStorageAccessGroups(), zone.getStorageAccessGroups())));
        } else if (clusterId != null) {
            ClusterVO cluster = clusterDao.findById(clusterId);
            HostPodVO pod = podDao.findById(cluster.getPodId());
            DataCenterVO zone = dcDao.findById(pod.getDataCenterId());
            storageAccessGroups.addAll(List.of(com.cloud.utils.StringUtils.splitCommaSeparatedStrings(cluster.getStorageAccessGroups(), pod.getStorageAccessGroups(), zone.getStorageAccessGroups())));
        } else if (podId != null) {
            HostPodVO pod = podDao.findById(podId);
            DataCenterVO zone = dcDao.findById(pod.getDataCenterId());
            storageAccessGroups.addAll(List.of(com.cloud.utils.StringUtils.splitCommaSeparatedStrings(pod.getStorageAccessGroups(), zone.getStorageAccessGroups())));
        } else if (zoneId != null) {
            DataCenterVO zone = dcDao.findById(zoneId);
            storageAccessGroups.addAll(List.of(com.cloud.utils.StringUtils.splitCommaSeparatedStrings(zone.getStorageAccessGroups())));
        }

        storageAccessGroups.removeIf(tag -> tag == null || tag.trim().isEmpty());

        return storageAccessGroups.isEmpty()
                ? new String[0]
                : storageAccessGroups.toArray(org.apache.commons.lang.ArrayUtils.EMPTY_STRING_ARRAY);
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(tags.split(","));
    }
}
