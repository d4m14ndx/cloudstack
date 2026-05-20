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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DataObjectType;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.SwiftTO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.utils.SwiftUtil;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;

public class NfsSwiftTransferService {

    private static final String TEMPLATE_PROPERTIES = "template.properties";
    private static final long SWIFT_MAX_SIZE = 5L * 1024L * 1024L * 1024L;
    private static final String SWIFT_SCRIPT = "/usr/bin/python /usr/local/cloud/systemvm/scripts/storage/secondary/swift";

    protected Logger logger = LogManager.getLogger(NfsSwiftTransferService.class);

    private final NfsSecondaryStorageResource resource;

    public NfsSwiftTransferService(NfsSecondaryStorageResource resource) {
        this.resource = resource;
    }

    public File swiftWriteMetadataFile(String metaFileName, String uniqueName, String filename, long size, long virtualSize) throws IOException {
        File metaFile = new File(metaFileName);
        FileWriter writer = new FileWriter(metaFile);
        BufferedWriter bufferWriter = new BufferedWriter(writer);
        bufferWriter.write("uniquename=" + uniqueName);
        bufferWriter.write("\n");
        bufferWriter.write("filename=" + filename);
        bufferWriter.write("\n");
        bufferWriter.write("size=" + size);
        bufferWriter.write("\n");
        bufferWriter.write("virtualsize=" + virtualSize);
        bufferWriter.close();
        writer.close();
        return metaFile;
    }

    public boolean swiftUploadMetadataFile(SwiftTO swift, File srcFile, String containerName, String uniqueName) throws IOException {
        File uniqDir = resource._storage.createUniqDir();
        String metaFileName = uniqDir.getAbsolutePath() + File.separator + TEMPLATE_PROPERTIES;
        resource._storage.create(uniqDir.getAbsolutePath(), TEMPLATE_PROPERTIES);

        long virtualSize = resource.getVirtualSize(srcFile, resource.getTemplateFormat(srcFile.getName()));

        File metaFile = swiftWriteMetadataFile(metaFileName, uniqueName, srcFile.getName(), srcFile.length(), virtualSize);

        SwiftUtil.putObject(swift, metaFile, containerName, TEMPLATE_PROPERTIES);
        metaFile.delete();
        uniqDir.delete();

        return true;
    }

    public Answer copyFromNfsToSwift(CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();

        DataStoreTO srcDataStore = srcData.getDataStore();
        NfsTO srcStore = (NfsTO)srcDataStore;
        DataStoreTO destDataStore = destData.getDataStore();
        File srcFile = resource.getFile(srcData.getPath(), srcStore.getUrl(), resource.getNfsVersion());

        SwiftTO swift = (SwiftTO)destDataStore;
        long pathId = destData.getId();

        try {
            if (destData instanceof SnapshotObjectTO) {
                pathId = ((SnapshotObjectTO)destData).getVolume().getId();
            }

            String containerName = SwiftUtil.getContainerName(destData.getObjectType().toString(), pathId);
            String swiftPath = SwiftUtil.putObject(swift, srcFile, containerName, srcFile.getName());

            DataTO retObj = null;
            if (destData.getObjectType() == DataObjectType.TEMPLATE) {
                TemplateObjectTO destTemplateData = (TemplateObjectTO)destData;
                String uniqueName = destTemplateData.getName();
                swiftUploadMetadataFile(swift, srcFile, containerName, uniqueName);
                TemplateObjectTO newTemplate = new TemplateObjectTO();
                ImageFormat format = resource.getTemplateFormat(srcFile.getName());
                newTemplate.setPath(swiftPath);
                newTemplate.setSize(resource.getVirtualSize(srcFile, format));
                newTemplate.setPhysicalSize(srcFile.length());
                newTemplate.setFormat(format);
                retObj = newTemplate;
            } else if (destData.getObjectType() == DataObjectType.VOLUME) {
                VolumeObjectTO newVol = new VolumeObjectTO();
                newVol.setPath(containerName);
                newVol.setSize(resource.getVirtualSize(srcFile, resource.getTemplateFormat(srcFile.getName())));
                retObj = newVol;
            } else if (destData.getObjectType() == DataObjectType.SNAPSHOT) {
                SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
                newSnapshot.setPath(containerName + File.separator + srcFile.getName());
                retObj = newSnapshot;
            }

            return new CopyCmdAnswer(retObj);

        } catch (Exception e) {
            logger.error("failed to upload " + srcData.getPath(), e);
            return new CopyCmdAnswer("failed to upload " + srcData.getPath() + e.toString());
        }
    }

    String buildSwiftDownloadCommand(SwiftTO swift, String container, String rfilename, String lFullPath) {
        return swiftCommand(swift) + " download " + container + " " + rfilename + " -o " + lFullPath;
    }

    String buildSwiftDownloadContainerCommand(SwiftTO swift, String container, String ldir) {
        return "cd " + ldir + ";" + swiftCommand(swift) + " download " + container;
    }

    String buildSwiftUploadCommand(SwiftTO swift, String container, String lDir, String file, long size) {
        if (size <= SWIFT_MAX_SIZE) {
            return "cd " + lDir + ";" + swiftCommand(swift) + " upload " + container + " " + file;
        }
        return "cd " + lDir + ";" + swiftCommand(swift) + " upload -S " + SWIFT_MAX_SIZE + " " + container + " " + file;
    }

    String buildSwiftListCommand(SwiftTO swift, String container, String rFilename) {
        return swiftCommand(swift) + " list " + container + " " + rFilename;
    }

    String buildSwiftDeleteCommand(SwiftTO swift, String container, String object) {
        return swiftCommand(swift) + " delete " + container + " " + object;
    }

    private String swiftCommand(SwiftTO swift) {
        return SWIFT_SCRIPT + " -A " + swift.getUrl() + " -U " + swift.getAccount() + ":" + swift.getUserName()
                + " -K " + swift.getKey();
    }

    String swiftDownload(SwiftTO swift, String container, String rfilename, String lFullPath) {
        Script command = new Script("/bin/bash", logger);
        command.add("-c");
        command.add(buildSwiftDownloadCommand(swift, container, rfilename, lFullPath));
        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = command.execute(parser);
        if (result != null) {
            String errMsg = "swiftDownload failed  err=" + result;
            logger.warn(errMsg);
            return errMsg;
        }
        if (parser.getLines() != null) {
            String[] lines = parser.getLines().split("\\n");
            for (String line : lines) {
                if (line.contains("Errno") || line.contains("failed")) {
                    String errMsg = "swiftDownload failed , err=" + parser.getLines();
                    logger.warn(errMsg);
                    return errMsg;
                }
            }
        }
        return null;

    }

    String swiftDownloadContainer(SwiftTO swift, String container, String ldir) {
        Script command = new Script("/bin/bash", logger);
        command.add("-c");
        command.add(buildSwiftDownloadContainerCommand(swift, container, ldir));
        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = command.execute(parser);
        if (result != null) {
            String errMsg = "swiftDownloadContainer failed  err=" + result;
            logger.warn(errMsg);
            return errMsg;
        }
        if (parser.getLines() != null) {
            String[] lines = parser.getLines().split("\\n");
            for (String line : lines) {
                if (line.contains("Errno") || line.contains("failed")) {
                    String errMsg = "swiftDownloadContainer failed , err=" + parser.getLines();
                    logger.warn(errMsg);
                    return errMsg;
                }
            }
        }
        return null;

    }

    String swiftUpload(SwiftTO swift, String container, String lDir, String lFilename) {
        List<String> files = listUploadFiles(lDir, lFilename);
        for (String file : files) {
            File f = new File(lDir + "/" + file);
            Script command = new Script("/bin/bash", logger);
            command.add("-c");
            command.add(buildSwiftUploadCommand(swift, container, lDir, file, f.length()));
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = command.execute(parser);
            if (result != null) {
                String errMsg = "swiftUpload failed , err=" + result;
                logger.warn(errMsg);
                return errMsg;
            }
            if (parser.getLines() != null) {
                String[] lines = parser.getLines().split("\\n");
                for (String line : lines) {
                    if (line.contains("Errno") || line.contains("failed")) {
                        String errMsg = "swiftUpload failed , err=" + parser.getLines();
                        logger.warn(errMsg);
                        return errMsg;
                    }
                }
            }
        }

        return null;
    }

    List<String> listUploadFiles(String lDir, String lFilename) {
        List<String> files = new ArrayList<String>();
        if (lFilename.equals("*")) {
            File dir = new File(lDir);
            String[] dir_lst = dir.list();
            if (dir_lst != null) {
                for (String file : dir_lst) {
                    if (file.startsWith(".")) {
                        continue;
                    }
                    files.add(file);
                }
            }
        } else {
            files.add(lFilename);
        }
        return files;
    }

    String[] swiftList(SwiftTO swift, String container, String rFilename) {
        Script command = new Script("/bin/bash", logger);
        command.add("-c");
        command.add(buildSwiftListCommand(swift, container, rFilename));
        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = command.execute(parser);
        if (result == null && parser.getLines() != null) {
            String[] lines = parser.getLines().split("\\n");
            return lines;
        } else {
            if (result != null) {
                String errMsg = "swiftList failed , err=" + result;
                logger.warn(errMsg);
            } else {
                String errMsg = "swiftList failed, no lines returns";
                logger.warn(errMsg);
            }
        }
        return null;
    }

    String swiftDelete(SwiftTO swift, String container, String object) {
        Script command = new Script("/bin/bash", logger);
        command.add("-c");
        command.add(buildSwiftDeleteCommand(swift, container, object));
        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = command.execute(parser);
        if (result != null) {
            String errMsg = "swiftDelete failed , err=" + result;
            logger.warn(errMsg);
            return errMsg;
        }
        if (parser.getLines() != null) {
            String[] lines = parser.getLines().split("\\n");
            for (String line : lines) {
                if (line.contains("Errno") || line.contains("failed")) {
                    String errMsg = "swiftDelete failed , err=" + parser.getLines();
                    logger.warn(errMsg);
                    return errMsg;
                }
            }
        }
        return null;
    }
}
