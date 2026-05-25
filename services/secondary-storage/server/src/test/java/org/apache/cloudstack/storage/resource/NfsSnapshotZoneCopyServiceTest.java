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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class NfsSnapshotZoneCopyServiceTest {

    private final NfsSnapshotZoneCopyService service = new NfsSnapshotZoneCopyService();

    @Test
    public void listSnapshotFilesReturnsExactPathWhenSnapshotFileExists() throws Exception {
        Path root = Files.createTempDirectory("snapshot-zone-copy");
        try {
            Path snapshotDir = Files.createDirectories(root.resolve("snapshots/2/10"));
            Files.createFile(snapshotDir.resolve("snap.vhd"));

            List<String> result = service.listSnapshotFiles(root.toString(), "snapshots/2/10/snap.vhd");

            Assert.assertEquals(List.of("snapshots/2/10/snap.vhd"), result);
        } finally {
            FileUtilsForTest.deleteRecursively(root);
        }
    }

    @Test
    public void listSnapshotFilesReturnsSiblingFilesWhenSnapshotPathIsDirectoryStyle() throws Exception {
        Path root = Files.createTempDirectory("snapshot-zone-copy");
        try {
            Path snapshotDir = Files.createDirectories(root.resolve("snapshots/2/10/abc"));
            Files.createFile(snapshotDir.resolve("abc.vmdk"));
            Files.createFile(snapshotDir.resolve("abc.ovf"));
            Files.createDirectories(snapshotDir.resolve("ignored-dir"));

            List<String> result = service.listSnapshotFiles(root.toString(), "snapshots/2/10/abc/abc");

            Assert.assertEquals(2, result.size());
            Assert.assertTrue(result.contains("snapshots/2/10/abc/abc.vmdk"));
            Assert.assertTrue(result.contains("snapshots/2/10/abc/abc.ovf"));
        } finally {
            FileUtilsForTest.deleteRecursively(root);
        }
    }

    private static class FileUtilsForTest {
        private static void deleteRecursively(Path path) throws Exception {
            if (path == null || !Files.exists(path)) {
                return;
            }
            try (var paths = Files.walk(path)) {
                paths.sorted((left, right) -> right.compareTo(left))
                        .forEach(file -> file.toFile().delete());
            }
        }
    }
}
