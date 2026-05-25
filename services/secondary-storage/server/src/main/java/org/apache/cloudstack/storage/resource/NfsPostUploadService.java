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
package org.apache.cloudstack.storage.resource;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cloudstack.storage.command.UploadStatusAnswer;
import org.apache.cloudstack.storage.command.UploadStatusAnswer.UploadStatus;
import org.apache.cloudstack.storage.command.UploadStatusCommand;
import org.apache.cloudstack.storage.template.UploadEntity;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.channel.Channel;

public class NfsPostUploadService {

    protected Logger logger = LogManager.getLogger(NfsPostUploadService.class);

    private final Map<String, UploadEntity> uploadEntityStateMap = new ConcurrentHashMap<>();
    private final Map<String, Channel> uploadChannelMap = new ConcurrentHashMap<>();

    public UploadStatusAnswer execute(UploadStatusCommand cmd) {
        String entityUuid = cmd.getEntityUuid();
        if (uploadEntityStateMap.containsKey(entityUuid)) {
            UploadEntity uploadEntity = uploadEntityStateMap.get(entityUuid);
            if (Boolean.TRUE.equals(cmd.getAbort())) {
                updateStateMapWithError(entityUuid, "Upload Entity aborted");
                String errorMsg = uploadEntity.getErrorMessage();
                if (errorMsg == null) {
                    errorMsg = "Upload aborted by management server";
                }
                Channel channel = uploadChannelMap.remove(entityUuid);
                if (channel != null && channel.isActive()) {
                    logger.info("Closing upload channel for entity {}", entityUuid);
                    channel.close();
                }
                uploadEntityStateMap.remove(entityUuid);
                return new UploadStatusAnswer(cmd, UploadStatus.ERROR, errorMsg);
            }
            if (uploadEntity.getUploadState() == UploadEntity.Status.ERROR) {
                uploadEntityStateMap.remove(entityUuid);
                return new UploadStatusAnswer(cmd, UploadStatus.ERROR, uploadEntity.getErrorMessage());
            } else if (uploadEntity.getUploadState() == UploadEntity.Status.COMPLETED) {
                UploadStatusAnswer answer = new UploadStatusAnswer(cmd, UploadStatus.COMPLETED);
                answer.setVirtualSize(uploadEntity.getVirtualSize());
                answer.setInstallPath(uploadEntity.getTmpltPath());
                answer.setPhysicalSize(uploadEntity.getPhysicalSize());
                answer.setDownloadPercent(100);
                if (uploadEntity.getOvfInformationTO() != null) {
                    answer.setOvfInformationTO(uploadEntity.getOvfInformationTO());
                }
                uploadEntityStateMap.remove(entityUuid);
                return answer;
            } else if (uploadEntity.getUploadState() == UploadEntity.Status.IN_PROGRESS) {
                UploadStatusAnswer answer = new UploadStatusAnswer(cmd, UploadStatus.IN_PROGRESS);
                long downloadedSize = FileUtils.sizeOfDirectory(new File(uploadEntity.getInstallPathPrefix()));
                int downloadPercent = (int)(100 * downloadedSize / uploadEntity.getContentLength());
                answer.setPhysicalSize(downloadedSize);
                answer.setDownloadPercent(Math.min(downloadPercent, 100));
                return answer;
            }
        }
        return new UploadStatusAnswer(cmd, UploadStatus.UNKNOWN);
    }

    public boolean hasUploadEntity(String uuid) {
        return uploadEntityStateMap.containsKey(uuid);
    }

    public UploadEntity getUploadEntity(String uuid) {
        return uploadEntityStateMap.get(uuid);
    }

    public void putUploadEntity(String uuid, UploadEntity uploadEntity) {
        uploadEntityStateMap.put(uuid, uploadEntity);
    }

    public void registerUploadChannel(String uuid, Channel channel) {
        uploadChannelMap.put(uuid, channel);
    }

    public void deregisterUploadChannel(String uuid) {
        if (uuid != null) {
            uploadChannelMap.remove(uuid);
        }
    }

    public void updateStateMapWithError(String uuid, String errorMessage) {
        UploadEntity uploadEntity = null;
        if (uploadEntityStateMap.get(uuid) != null) {
            uploadEntity = uploadEntityStateMap.get(uuid);
        } else {
            uploadEntity = new UploadEntity();
        }
        uploadEntity.setStatus(UploadEntity.Status.ERROR);
        uploadEntity.setErrorMessage(errorMessage);
        uploadEntityStateMap.put(uuid, uploadEntity);
    }
}
