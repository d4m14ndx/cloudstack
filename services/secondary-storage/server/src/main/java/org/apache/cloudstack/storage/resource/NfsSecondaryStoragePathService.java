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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.Pair;
import com.cloud.utils.UuidUtils;

public class NfsSecondaryStoragePathService {

    protected Logger logger = LogManager.getLogger(NfsSecondaryStoragePathService.class);

    /*
     *  return Pair of <Template relative path, Template name>
     *  Template url may or may not end with .ova extension
     */
    public Pair<String, String> decodeTemplateRelativePathAndNameFromUrl(String storeUrl, String templateUrl, String defaultName) {
        logger.debug(String.format("Trying to get template relative path and name from URL [%s].", templateUrl));
        String templateName = null;
        String mountPoint = null;
        if (templateUrl.endsWith(".ova")) {
            int index = templateUrl.lastIndexOf("/");
            mountPoint = templateUrl.substring(0, index);
            mountPoint = mountPoint.substring(storeUrl.length() + 1);
            if (!mountPoint.endsWith("/")) {
                mountPoint = mountPoint + "/";
            }

            templateName = templateUrl.substring(index + 1).replace(".ova", "");

            if (templateName == null || templateName.isEmpty()) {
                logger.debug(String.format("Cannot find template name from URL [%s]. Using default name [%s].", templateUrl, defaultName));
                templateName = defaultName;
            }
        } else {
            mountPoint = templateUrl.substring(storeUrl.length() + 1);
            if (!mountPoint.endsWith("/")) {
                mountPoint = mountPoint + "/";
            }
            templateName = defaultName;
        }

        logger.debug(String.format("Template relative path [%s] and name [%s] found from URL [%s].", mountPoint, templateName, templateUrl));
        return new Pair<String, String>(mountPoint, templateName);
    }

    public String getTemplateOnSecStorageFilePath(String secStorageMountPoint, String templateRelativeFolderPath, String templateName, String fileExtension) {
        logger.debug(String.format("Trying to find template [%s] with file extension [%s] in secondary storage mount point [%s] using relative folder path [%s].",
                templateName, fileExtension, secStorageMountPoint, templateRelativeFolderPath));
        StringBuffer sb = new StringBuffer();
        sb.append(secStorageMountPoint);
        if (!secStorageMountPoint.endsWith("/")) {
            sb.append("/");
        }

        sb.append(templateRelativeFolderPath);
        if (!secStorageMountPoint.endsWith("/")) {
            sb.append("/");
        }

        sb.append(templateName);
        if (!fileExtension.startsWith(".")) {
            sb.append(".");
        }
        sb.append(fileExtension);

        return sb.toString();
    }

    public String getSecondaryDatastoreUUID(String storeUrl) {
        return UuidUtils.nameUUIDFromBytes(storeUrl.getBytes()).toString();
    }

    public String getTemplateRelativeDirInSecStorage(long accountId, long templateId) {
        return "template/tmpl/" + accountId + "/" + templateId;
    }
}
