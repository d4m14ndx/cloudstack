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
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StorageLayer;
import com.cloud.storage.template.OVAProcessor;
import com.cloud.storage.template.Processor;
import com.cloud.storage.template.QCOW2Processor;
import com.cloud.storage.template.RawImageProcessor;
import com.cloud.storage.template.TARProcessor;
import com.cloud.storage.template.VhdProcessor;
import com.cloud.storage.template.VmdkProcessor;

public class NfsImageMetadataService {

    protected Logger logger = LogManager.getLogger(NfsImageMetadataService.class);

    public ImageFormat getTemplateFormat(String filePath) {
        String ext = null;
        int extensionPos = filePath.lastIndexOf('.');
        int lastSeparator = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        int i = lastSeparator > extensionPos ? -1 : extensionPos;
        if (i > 0) {
            ext = filePath.substring(i + 1);
        }
        if (ext != null) {
            if (ext.equalsIgnoreCase("vhd")) {
                return ImageFormat.VHD;
            } else if (ext.equalsIgnoreCase("vhdx")) {
                return ImageFormat.VHDX;
            } else if (ext.equalsIgnoreCase("qcow2")) {
                return ImageFormat.QCOW2;
            } else if (ext.equalsIgnoreCase("ova")) {
                return ImageFormat.OVA;
            } else if (ext.equalsIgnoreCase("tar")) {
                return ImageFormat.TAR;
            } else if (ext.equalsIgnoreCase("img") || ext.equalsIgnoreCase("raw")) {
                return ImageFormat.RAW;
            } else if (ext.equalsIgnoreCase("vmdk")) {
                return ImageFormat.VMDK;
            } else if (ext.equalsIgnoreCase("vdi")) {
                return ImageFormat.VDI;
            }
        }

        return null;
    }

    public long getVirtualSize(File file, ImageFormat format, StorageLayer storage) {
        Processor processor = null;
        try {
            if (format == null) {
                return file.length();
            } else if (format == ImageFormat.QCOW2) {
                processor = new QCOW2Processor();
            } else if (format == ImageFormat.OVA) {
                processor = new OVAProcessor();
            } else if (format == ImageFormat.VHD) {
                processor = new VhdProcessor();
            } else if (format == ImageFormat.RAW) {
                processor = new RawImageProcessor();
            } else if (format == ImageFormat.VMDK) {
                processor = new VmdkProcessor();
            }
            if (format == ImageFormat.TAR) {
                processor = new TARProcessor();
            }

            if (processor == null) {
                return file.length();
            }

            Map<String, Object> params = new HashMap<String, Object>();
            params.put(StorageLayer.InstanceConfigKey, storage);
            processor.configure("template processor", params);
            return processor.getVirtualSize(file);
        } catch (Exception e) {
            logger.warn("Failed to get virtual size of file " + file.getPath() + ", returning file size instead: ", e);
            return file.length();
        }
    }

    public File findFile(StorageLayer storage, String path) {
        File srcFile = storage.getFile(path);
        if (!srcFile.exists()) {
            srcFile = storage.getFile(path + ".qcow2");
            if (!srcFile.exists()) {
                srcFile = storage.getFile(path + ".vhd");
                if (!srcFile.exists()) {
                    srcFile = storage.getFile(path + ".ova");
                    if (!srcFile.exists()) {
                        srcFile = storage.getFile(path + ".vmdk");
                        if (!srcFile.exists()) {
                            return null;
                        }
                    }
                }
            }
        }

        return srcFile;
    }
}
