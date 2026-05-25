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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class NfsSnapshotZoneCopyService {

    protected Logger logger = LogManager.getLogger(NfsSnapshotZoneCopyService.class);

    public List<String> listSnapshotFiles(String parentPath, String path) {
        File snapFile = new File(parentPath + File.separator + path);
        if (snapFile.exists() && !snapFile.isDirectory()) {
            return List.of(path);
        }

        int index = path.lastIndexOf(File.separator);
        String snapDir = path.substring(0, index);
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(Paths.get(parentPath + File.separator + snapDir))) {
            List<String> fileNames = stream
                    .filter(file -> !Files.isDirectory(file))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .collect(Collectors.toList());
            for (String file : fileNames) {
                file = snapDir + "/" + file;
                logger.debug(String.format("Found snapshot file %s", file));
                files.add(file);
            }
        } catch (IOException ioe) {
            logger.error("Error preparing file list for snapshot copy", ioe);
        }
        return files;
    }
}
