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

import static java.util.Arrays.asList;

import java.io.File;

import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DataObjectType;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.S3TO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.utils.storage.S3.S3Utils;

public class NfsS3TransferService {

    protected Logger logger = LogManager.getLogger(NfsS3TransferService.class);

    private final NfsSecondaryStorageResource resource;

    public NfsS3TransferService(NfsSecondaryStorageResource resource) {
        this.resource = resource;
    }

    public Answer copyFromS3ToNfs(CopyCommand cmd, DataTO srcData, S3TO s3, DataTO destData, NfsTO destImageStore) {
        final String storagePath = destImageStore.getUrl();
        final String destPath = destData.getPath();

        try {

            String downloadPath = determineStorageTemplatePath(storagePath, destPath, resource.getNfsVersion());
            final File downloadDirectory = resource._storage.getFile(downloadPath);

            if (downloadDirectory.exists()) {
                logger.debug("Directory " + downloadPath + " already exists");
            } else {
                if (!downloadDirectory.mkdirs()) {
                    final String errMsg = "Unable to create directory " + downloadPath + " to copy from S3 to cache.";
                    logger.error(errMsg);
                    return new CopyCmdAnswer(errMsg);
                }
            }
            File destFile = new File(downloadDirectory, StringUtils.substringAfterLast(srcData.getPath(), S3Utils.SEPARATOR));
            S3Utils.getFile(s3, s3.getBucketName(), srcData.getPath(), destFile).waitForCompletion();

            return resource.postProcessing(destFile, downloadPath, destPath, srcData, destData);
        } catch (Exception e) {

            final String errMsg = String.format("Failed to download" + "due to $1%s", e.getMessage());
            logger.error(errMsg, e);
            return new CopyCmdAnswer(errMsg);
        }
    }

    public Answer copyFromNfsToS3(CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        DataStoreTO srcDataStore = srcData.getDataStore();
        NfsTO srcStore = (NfsTO)srcDataStore;
        DataStoreTO destDataStore = destData.getDataStore();

        final S3TO s3 = (S3TO)destDataStore;

        try {
            final String templatePath = determineStorageTemplatePath(srcStore.getUrl(), srcData.getPath(), resource.getNfsVersion());

            if (logger.isDebugEnabled()) {
                logger.debug("Found " + srcData.getObjectType() + " from directory " + templatePath + " to upload to S3.");
            }

            final String bucket = s3.getBucketName();
            File srcFile = resource.findFile(templatePath);
            if (srcFile == null) {
                return new CopyCmdAnswer("Can't find src file:" + templatePath);
            }

            ImageFormat format = resource.getTemplateFormat(srcFile.getName());
            String key = destData.getPath() + S3Utils.SEPARATOR + srcFile.getName();

            S3Utils.putFile(s3, srcFile, bucket, key).waitForCompletion();

            DataTO retObj = null;
            if (destData.getObjectType() == DataObjectType.TEMPLATE) {
                TemplateObjectTO newTemplate = new TemplateObjectTO();
                newTemplate.setPath(key);
                newTemplate.setSize(resource.getVirtualSize(srcFile, format));
                newTemplate.setPhysicalSize(srcFile.length());
                newTemplate.setFormat(format);
                retObj = newTemplate;
            } else if (destData.getObjectType() == DataObjectType.VOLUME) {
                VolumeObjectTO newVol = new VolumeObjectTO();
                newVol.setPath(key);
                newVol.setSize(srcFile.length());
                retObj = newVol;
            } else if (destData.getObjectType() == DataObjectType.SNAPSHOT) {
                SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
                newSnapshot.setPath(key);
                retObj = newSnapshot;
            }

            return new CopyCmdAnswer(retObj);
        } catch (Exception e) {
            logger.error("failed to upload" + srcData.getPath(), e);
            return new CopyCmdAnswer("failed to upload" + srcData.getPath() + e.toString());
        }
    }

    private String determineStorageTemplatePath(final String storagePath, String dataPath, String nfsVersion) {
        return StringUtils.join(asList(resource.getRootDir(storagePath, nfsVersion), dataPath), File.separator);
    }
}
