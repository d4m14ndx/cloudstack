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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.ListTemplateAnswer;
import com.cloud.agent.api.storage.ListTemplateCommand;
import com.cloud.agent.api.storage.ListVolumeAnswer;
import com.cloud.agent.api.storage.ListVolumeCommand;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.S3TO;
import com.cloud.agent.api.to.SwiftTO;
import com.cloud.storage.template.TemplateProp;
import com.cloud.utils.StringUtils;
import com.cloud.utils.SwiftUtil;
import com.cloud.utils.storage.S3.S3Utils;

public class NfsDataStoreListingService {

    private static final String TEMPLATE_ROOT_DIR = "template/tmpl";
    private static final String VOLUME_ROOT_DIR = "volumes";
    private static final String TEMPLATE_PROPERTIES = "template.properties";

    protected Logger logger = LogManager.getLogger(NfsDataStoreListingService.class);

    private final NfsSecondaryStorageResource resource;

    public NfsDataStoreListingService(NfsSecondaryStorageResource resource) {
        this.resource = resource;
    }

    public Answer execute(ListTemplateCommand cmd) {
        if (!resource._inSystemVM) {
            return new ListTemplateAnswer(null, null);
        }

        DataStoreTO store = cmd.getDataStore();
        if (store instanceof NfsTO) {
            NfsTO nfs = (NfsTO)store;
            String secUrl = nfs.getUrl();
            String root = resource.getRootDir(secUrl, cmd.getNfsVersion());
            Map<String, TemplateProp> templateInfos = resource._dlMgr.gatherTemplateInfo(root);
            return new ListTemplateAnswer(secUrl, templateInfos);
        } else if (store instanceof SwiftTO) {
            SwiftTO swift = (SwiftTO)store;
            Map<String, TemplateProp> templateInfos = swiftListTemplate(swift);
            return new ListTemplateAnswer(swift.toString(), templateInfos);
        } else if (store instanceof S3TO) {
            S3TO s3 = (S3TO)store;
            Map<String, TemplateProp> templateInfos = s3ListTemplate(s3);
            return new ListTemplateAnswer(s3.getBucketName(), templateInfos);
        } else {
            return new Answer(cmd, false, "Unsupported image data store: " + store);
        }
    }

    public Answer execute(ListVolumeCommand cmd) {
        if (!resource._inSystemVM) {
            return new ListVolumeAnswer(cmd.getSecUrl(), null);
        }
        DataStoreTO store = cmd.getDataStore();
        if (store instanceof NfsTO) {
            String root = resource.getRootDir(cmd.getSecUrl(), resource.getNfsVersion());
            Map<Long, TemplateProp> templateInfos = resource._dlMgr.gatherVolumeInfo(root);
            return new ListVolumeAnswer(cmd.getSecUrl(), templateInfos);
        } else if (store instanceof S3TO) {
            S3TO s3 = (S3TO)store;
            Map<Long, TemplateProp> templateInfos = s3ListVolume(s3);
            return new ListVolumeAnswer(s3.getBucketName(), templateInfos);
        } else {
            return new Answer(cmd, false, "Unsupported image data store: " + store);
        }

    }

    Map<String, TemplateProp> swiftListTemplate(SwiftTO swift) {
        String[] containers = SwiftUtil.list(swift, "", null);
        if (containers == null) {
            return null;
        }
        Map<String, TemplateProp> tmpltInfos = new HashMap<String, TemplateProp>();
        for (String container : containers) {
            if (container.startsWith("T-")) {
                String[] files = SwiftUtil.list(swift, container, TEMPLATE_PROPERTIES);
                if (files.length != 1) {
                    continue;
                }
                try {
                    File tempFile = File.createTempFile("template", ".tmp");
                    File tmpFile = SwiftUtil.getObject(swift, tempFile, container + File.separator + TEMPLATE_PROPERTIES);
                    if (tmpFile == null) {
                        continue;
                    }
                    try (FileReader fr = new FileReader(tmpFile); BufferedReader brf = new BufferedReader(fr);) {
                        String line = null;
                        String uniqName = null;
                        Long size = null;
                        Long physicalSize = null;
                        String name = null;
                        while ((line = brf.readLine()) != null) {
                            if (line.startsWith("uniquename=")) {
                                uniqName = line.split("=")[1];
                            } else if (line.startsWith("size=")) {
                                physicalSize = Long.parseLong(line.split("=")[1]);
                            } else if (line.startsWith("virtualsize=")) {
                                size = Long.parseLong(line.split("=")[1]);
                            } else if (line.startsWith("filename=")) {
                                name = line.split("=")[1];
                            }
                        }

                        //fallback
                        if (size == null) {
                            size = physicalSize;
                        }

                        tempFile.delete();
                        if (uniqName != null) {
                            TemplateProp prop = new TemplateProp(uniqName, container + File.separator + name, size, physicalSize, true, false);
                            tmpltInfos.put(uniqName, prop);
                        }
                    } catch (IOException ex) {
                        logger.debug("swiftListTemplate:Exception:" + ex.getMessage());
                        continue;
                    }
                } catch (IOException e) {
                    logger.debug("Failed to create templ file:" + e.toString());
                    continue;
                } catch (Exception e) {
                    logger.debug("Failed to get properties: " + e.toString());
                    continue;
                }
            }
        }
        return tmpltInfos;
    }

    Map<String, TemplateProp> s3ListTemplate(S3TO s3) {
        String bucket = s3.getBucketName();
        final List<S3ObjectSummary> objectSummaries = S3Utils.listDirectory(s3, bucket, TEMPLATE_ROOT_DIR);
        if (objectSummaries == null) {
            return null;
        }
        Map<String, TemplateProp> tmpltInfos = new HashMap<String, TemplateProp>();
        for (S3ObjectSummary objectSummary : objectSummaries) {
            String key = objectSummary.getKey();
            String uniqueName = determineS3TemplateNameFromKey(key);
            // TODO: isPublic value, where to get?
            TemplateProp tInfo = new TemplateProp(uniqueName, key, objectSummary.getSize(), objectSummary.getSize(), true, false);
            tmpltInfos.put(uniqueName, tInfo);
        }
        return tmpltInfos;

    }

    Map<Long, TemplateProp> s3ListVolume(S3TO s3) {
        String bucket = s3.getBucketName();
        final List<S3ObjectSummary> objectSummaries = S3Utils.listDirectory(s3, bucket, VOLUME_ROOT_DIR);
        if (objectSummaries == null) {
            return null;
        }
        Map<Long, TemplateProp> tmpltInfos = new HashMap<Long, TemplateProp>();
        for (S3ObjectSummary objectSummary : objectSummaries) {
            String key = objectSummary.getKey();
            Long id = determineS3VolumeIdFromKey(key);
            // TODO: how to get volume template name
            TemplateProp tInfo = new TemplateProp(id.toString(), key, objectSummary.getSize(), objectSummary.getSize(), true, false);
            tmpltInfos.put(id, tInfo);
        }
        return tmpltInfos;

    }

    String determineS3TemplateNameFromKey(String key) {
        return StringUtils.substringAfterLast(StringUtils.substringBeforeLast(key, S3Utils.SEPARATOR), S3Utils.SEPARATOR);
    }

    Long determineS3VolumeIdFromKey(String key) {
        return Long.parseLong(StringUtils.substringAfterLast(StringUtils.substringBeforeLast(key, S3Utils.SEPARATOR), S3Utils.SEPARATOR));
    }
}
