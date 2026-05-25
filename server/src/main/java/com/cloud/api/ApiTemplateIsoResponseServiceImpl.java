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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants.DomainDetails;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.ExtractResponse;
import org.apache.cloudstack.api.response.TemplatePermissionsResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.query.ViewResponseHelper;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.dc.DataCenter;
import com.cloud.domain.Domain;
import com.cloud.host.Host;
import com.cloud.projects.Project;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Upload;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class ApiTemplateIsoResponseServiceImpl implements ApiTemplateIsoResponseService {

    protected Logger logger = LogManager.getLogger(ApiTemplateIsoResponseServiceImpl.class);

    @Inject
    private SnapshotDataStoreDao _snapshotStoreDao;
    @Inject
    private PrimaryDataStoreDao _storagePoolDao;

    @Override
    public TemplateResponse createTemplateUpdateResponse(ResponseView view, VirtualMachineTemplate result) {
        List<TemplateJoinVO> tvo = ApiDBUtils.newTemplateView(result);
        List<TemplateResponse> listVrs = ViewResponseHelper.createTemplateUpdateResponse(view, tvo.toArray(new TemplateJoinVO[tvo.size()]));
        assert listVrs != null && listVrs.size() == 1 : "There should be one template returned";
        return listVrs.get(0);
    }

    @Override
    public List<TemplateResponse> createTemplateResponses(ResponseView view, VirtualMachineTemplate result, Long zoneId, boolean readyOnly) {
        List<TemplateJoinVO> tvo = null;
        if (zoneId == null || zoneId == -1 || result.isCrossZones()) {
            tvo = ApiDBUtils.newTemplateView(result);
        } else {
            tvo = ApiDBUtils.newTemplateView(result, zoneId, readyOnly);

        }
        return ViewResponseHelper.createTemplateResponse(EnumSet.of(DomainDetails.all), view, tvo.toArray(new TemplateJoinVO[tvo.size()]));
    }

    @Override
    public List<TemplateResponse> createTemplateResponses(ResponseView view, VirtualMachineTemplate result, List<Long> zoneIds, boolean readyOnly) {
        List<TemplateJoinVO> tvo = null;
        if (zoneIds == null) {
            return createTemplateResponses(view, result, (Long)null, readyOnly);
        } else {
            for (Long zoneId: zoneIds){
                if (tvo == null)
                    tvo = ApiDBUtils.newTemplateView(result, zoneId, readyOnly);
                else
                    tvo.addAll(ApiDBUtils.newTemplateView(result, zoneId, readyOnly));
            }
        }
        return ViewResponseHelper.createTemplateResponse(EnumSet.of(DomainDetails.all), view, tvo.toArray(new TemplateJoinVO[tvo.size()]));
    }

    @Override
    public List<TemplateResponse> createTemplateResponses(ResponseView view, long templateId, Long zoneId, boolean readyOnly) {
        VirtualMachineTemplate template = findTemplateById(templateId);
        return createTemplateResponses(view, template, zoneId, readyOnly);
    }

    @Override
    public List<TemplateResponse> createIsoResponses(ResponseView view, VirtualMachineTemplate result, Long zoneId, boolean readyOnly) {
        List<TemplateJoinVO> tvo = null;
        if (zoneId == null || zoneId == -1) {
            tvo = ApiDBUtils.newTemplateView(result);
        } else {
            tvo = ApiDBUtils.newTemplateView(result, zoneId, readyOnly);
        }

        return ViewResponseHelper.createIsoResponse(view, tvo.toArray(new TemplateJoinVO[tvo.size()]));
    }

    protected ExtractResponse createExtractResponse(Long zoneId, Long accountId, String url) {
        ExtractResponse response = new ExtractResponse();
        if (zoneId != null) {
            DataCenter zone = ApiDBUtils.findZoneById(zoneId);
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }
        response.setUrl(url);
        response.setState(Upload.Status.DOWNLOAD_URL_CREATED.toString());
        Account account = ApiDBUtils.findAccountById(accountId);
        response.setAccountId(account.getUuid());
        return response;
    }

    @Override
    public ExtractResponse createVolumeExtractResponse(Long id, Long zoneId, Long accountId, String mode, String url) {
        ExtractResponse response = createExtractResponse(zoneId, accountId, url);
        response.setObjectName("volume");
        response.setMode(mode);
        Volume volume = ApiDBUtils.findVolumeById(id);
        response.setId(volume.getUuid());
        response.setName(volume.getName());
        return response;
    }

    @Override
    public ExtractResponse createSnapshotExtractResponse(Long id, Long zoneId, Long accountId, String url) {
        ExtractResponse response = createExtractResponse(zoneId, accountId, url);
        response.setObjectName("snapshot");
        Snapshot snapshot = ApiDBUtils.findSnapshotById(id);
        response.setId(snapshot.getUuid());
        response.setName(snapshot.getName());
        return response;
    }

    @Override
    public ExtractResponse createImageExtractResponse(Long id, Long zoneId, Long accountId, String mode, String url) {
        ExtractResponse response = createExtractResponse(zoneId, accountId, url);
        response.setMode(mode);
        VMTemplateVO template = ApiDBUtils.findTemplateById(id);
        response.setId(template.getUuid());
        response.setName(template.getName());
        return response;
    }

    @Override
    public List<TemplateResponse> createTemplateResponses(ResponseView view, long templateId, Long snapshotId, Long volumeId, boolean readyOnly) {
        Long zoneId = null;

        if (snapshotId != null) {
            Snapshot snapshot = ApiDBUtils.findSnapshotById(snapshotId);
            VolumeVO volume = findVolumeById(snapshot.getVolumeId());

            // it seems that the volume can actually be removed from the DB at some point if it's deleted
            // if volume comes back null, use another technique to try to discover the zone
            if (volume == null) {
                SnapshotDataStoreVO snapshotStore = _snapshotStoreDao.findOneBySnapshotAndDatastoreRole(snapshot.getId(), DataStoreRole.Primary);

                if (snapshotStore != null) {
                    long storagePoolId = snapshotStore.getDataStoreId();

                    StoragePoolVO storagePool = _storagePoolDao.findById(storagePoolId);

                    if (storagePool != null) {
                        zoneId = storagePool.getDataCenterId();
                    }
                }
            }
            else {
                zoneId = volume.getDataCenterId();
            }
        } else {
            VolumeVO volume = findVolumeById(volumeId);

            zoneId = volume.getDataCenterId();
        }

        if (zoneId == null) {
            throw new CloudRuntimeException("Unable to determine the zone ID");
        }

        return createTemplateResponses(view, templateId, zoneId, readyOnly);
    }

    @Override
    public List<TemplateResponse> createTemplateResponses(ResponseView view, long templateId, Long vmId) {
        UserVm vm = findUserVmById(vmId);
        Long hostId = (vm.getHostId() == null ? vm.getLastHostId() : vm.getHostId());
        Host host = findHostById(hostId);
        return createTemplateResponses(view, templateId, host.getDataCenterId(), true);
    }

    @Override
    public TemplatePermissionsResponse createTemplatePermissionsResponse(ResponseView view, List<String> accountNames, Long id) {
        Long templateOwnerDomain = null;
        VirtualMachineTemplate template = ApiDBUtils.findTemplateById(id);
        Account templateOwner = ApiDBUtils.findAccountById(template.getAccountId());
        if (view == ResponseView.Full) {
            // FIXME: we have just template id and need to get template owner
            // from that
            if (templateOwner != null) {
                templateOwnerDomain = templateOwner.getDomainId();
            }
        }

        TemplatePermissionsResponse response = new TemplatePermissionsResponse();
        response.setId(template.getUuid());
        response.setPublicTemplate(template.isPublicTemplate());
        if ((view == ResponseView.Full) && (templateOwnerDomain != null)) {
            Domain domain = ApiDBUtils.findDomainById(templateOwnerDomain);
            if (domain != null) {
                response.setDomainId(domain.getUuid());
            }
        }

        // Set accounts
        List<String> projectIds = new ArrayList<String>();
        List<String> regularAccounts = new ArrayList<String>();
        for (String accountName : accountNames) {
            Account account = ApiDBUtils.findAccountByNameDomain(accountName, templateOwner.getDomainId());
            if (account == null) {
                logger.error("Missing Account " + accountName + " in domain " + templateOwner.getDomainId());
                continue;
            }

            if (account.getType() != Account.Type.PROJECT) {
                regularAccounts.add(accountName);
            } else {
                // convert account to projectIds
                Project project = ApiDBUtils.findProjectByProjectAccountId(account.getId());

                if (project.getUuid() != null && !project.getUuid().isEmpty()) {
                    projectIds.add(project.getUuid());
                } else {
                    projectIds.add(String.valueOf(project.getId()));
                }
            }
        }

        if (!projectIds.isEmpty()) {
            response.setProjectIds(projectIds);
        }

        if (!regularAccounts.isEmpty()) {
            response.setAccountNames(regularAccounts);
        }

        response.setObjectName("templatepermission");
        return response;
    }

    protected Host findHostById(Long hostId) {
        return ApiDBUtils.findHostById(hostId);
    }

    protected UserVm findUserVmById(Long vmId) {
        return ApiDBUtils.findUserVmById(vmId);
    }

    protected VolumeVO findVolumeById(Long volumeId) {
        return ApiDBUtils.findVolumeById(volumeId);
    }

    protected VirtualMachineTemplate findTemplateById(Long templateId) {
        return ApiDBUtils.findTemplateById(templateId);
    }
}
