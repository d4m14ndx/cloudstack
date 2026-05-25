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

package org.apache.cloudstack.backup;

import java.util.List;

import org.apache.cloudstack.api.command.admin.backup.CreateImageTransferCmd;
import org.apache.cloudstack.api.command.admin.backup.DeleteVmCheckpointCmd;
import org.apache.cloudstack.api.command.admin.backup.FinalizeBackupCmd;
import org.apache.cloudstack.api.command.admin.backup.FinalizeImageTransferCmd;
import org.apache.cloudstack.api.command.admin.backup.ListImageTransfersCmd;
import org.apache.cloudstack.api.command.admin.backup.ListVmCheckpointsCmd;
import org.apache.cloudstack.api.command.admin.backup.StartBackupCmd;
import org.apache.cloudstack.api.response.CheckpointResponse;
import org.apache.cloudstack.api.response.ImageTransferResponse;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

import com.cloud.utils.component.PluggableService;

public interface KVMBackupExportService extends Configurable, PluggableService {

    ConfigKey<Integer> ImageTransferIdleTimeoutSeconds = new ConfigKey<>("Advanced", Integer.class,
            "image.transfer.idle.timeout.seconds",
            "600",
            "Seconds since last completed HTTP request to an image transfer before the image server unregisters it (idle timeout).",
            true, ConfigKey.Scope.Zone);

    ConfigKey<Boolean> ExposeKVMBackupExportServiceApis = new ConfigKey<>("Advanced", Boolean.class,
            "expose.kvm.backup.export.service.apis",
            "false",
            "Enable to expose APIs for testing the KVM Backup Export Service.",
            false, ConfigKey.Scope.Global);

    Backup createBackup(StartBackupCmd cmd);

    Backup startBackup(StartBackupCmd cmd);

    Backup finalizeBackup(FinalizeBackupCmd cmd);

    ImageTransferResponse createImageTransfer(CreateImageTransferCmd cmd);

    ImageTransfer createImageTransfer(long volumeId, Long backupId, ImageTransfer.Direction direction, ImageTransfer.Format format);

    boolean cancelImageTransfer(long imageTransferId);

    boolean finalizeImageTransfer(FinalizeImageTransferCmd cmd);

    boolean finalizeImageTransfer(long imageTransferId);

    List<ImageTransferResponse> listImageTransfers(ListImageTransfersCmd cmd);

    List<CheckpointResponse> listVmCheckpoints(ListVmCheckpointsCmd cmd);

    boolean deleteVmCheckpoint(DeleteVmCheckpointCmd cmd);

    List<Long> listCompatibleDataCenterIds();
}
