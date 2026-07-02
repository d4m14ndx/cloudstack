//
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
//
package com.cloud.hypervisor.proxmox.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.cloudstack.agent.directdownload.DirectDownloadAnswer;
import org.apache.cloudstack.agent.directdownload.DirectDownloadCommand;
import org.apache.cloudstack.storage.command.AttachAnswer;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.CheckDataStoreStoragePolicyComplianceCommand;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.CreateObjectAnswer;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.DettachAnswer;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.command.ForgetObjectCmd;
import org.apache.cloudstack.storage.command.IntroduceObjectCmd;
import org.apache.cloudstack.storage.command.ResignatureAnswer;
import org.apache.cloudstack.storage.command.ResignatureCommand;
import org.apache.cloudstack.storage.command.SnapshotAndCopyAnswer;
import org.apache.cloudstack.storage.command.SnapshotAndCopyCommand;
import org.apache.cloudstack.storage.command.SyncVolumePathCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.hypervisor.proxmox.api.ProxmoxApiClient;
import com.cloud.hypervisor.proxmox.api.ProxmoxApiException;
import com.cloud.hypervisor.proxmox.resource.ProxmoxResource;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.resource.StorageProcessor;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Storage processor for the native Proxmox VE hypervisor plugin.
 *
 * All operations execute on the management server: PVE-node-local work runs over SSH via
 * {@link ProxmoxResource#executeOnNode(String)}, everything else goes through the PVE REST API
 * ({@link ProxmoxResource#getApiClient()}).
 *
 * Conventions (see plugins/hypervisors/proxmox/CONTRACT.md):
 * <ul>
 *   <li>CloudStack volume/template {@code path} stores the PVE volid
 *       ({@code <storage>:<ownerVmid>/vm-<ownerVmid>-disk-<n>.qcow2} for file-based storages,
 *       {@code <storage>:vm-<ownerVmid>-disk-<n>} for raw block storages such as RBD).</li>
 *   <li>Templates on primary storage are owned by the reserved "template holder" vmid
 *       ({@code vmidBase - 1}); no actual PVE VM exists with that vmid.</li>
 *   <li>Secondary storage is NFS, mounted on the PVE node under
 *       {@code /mnt/cloudstack/sec/<md5-8 of url>}; mounts are cached (never unmounted eagerly).</li>
 * </ul>
 *
 * Storage-type handling: file-based storages (dir/NFS/CephFS) hold qcow2 images; raw block
 * storages (rbd/lvmthin/zfspool) hold RAW images with extension-less PVE volume names. Ceph RBD
 * is fully supported (snapshots via the rbd CLI, ownership reassignment via {@code rbd rename},
 * qemu-img reads/writes the {@code rbd:<pool>/<image>:...} URIs that {@code pvesm path} reports).
 * lvmthin/zfspool get raw allocation/copy support only; their snapshots and disk reassignment
 * return clear not-supported errors.
 */
public class ProxmoxStorageProcessor implements StorageProcessor {

    protected Logger logger = LogManager.getLogger(getClass());

    private static final String FORMAT_QCOW2 = "qcow2";
    private static final String FORMAT_RAW = "raw";
    private static final String TYPE_RBD = "rbd";
    private static final String SECONDARY_MOUNT_BASE = "/mnt/cloudstack/sec/";
    private static final String TEMPLATE_PROPERTIES = "template.properties";
    private static final int DEFAULT_SSH_TIMEOUT_SEC = 600;
    private static final int CONVERT_TIMEOUT_SEC = 7200;
    private static final int MOUNT_TIMEOUT_SEC = 300;
    private static final int MIN_DATA_SCSI_SLOT = 1;
    private static final int MAX_DATA_SCSI_SLOT = 13;

    private static final Pattern ACTIVE_DISK_KEY = Pattern.compile("^(scsi|virtio|sata|ide)\\d+$");
    private static final Pattern UNUSED_DISK_KEY = Pattern.compile("^unused\\d+$");
    private static final Pattern VOLID_VMID = Pattern.compile("(?:vm|base)-(\\d+)-");
    /** Characters allowed in tokens (pool/image names, ceph conf/keyring paths, cephx ids, snapshot names) that are expanded unquoted inside remote shell commands. */
    private static final Pattern SAFE_SHELL_TOKEN = Pattern.compile("[A-Za-z0-9._/+-]+");

    private final ProxmoxResource resource;

    public ProxmoxStorageProcessor(ProxmoxResource resource) {
        this.resource = resource;
    }

    @Override
    public Answer copyTemplateToPrimaryStorage(CopyCommand cmd) {
        TemplateObjectTO srcTemplate = (TemplateObjectTO) cmd.getSrcTO();
        TemplateObjectTO destTemplate = (TemplateObjectTO) cmd.getDestTO();
        DataStoreTO srcStore = srcTemplate.getDataStore();
        if (!(srcStore instanceof NfsTO)) {
            return new CopyCmdAnswer(String.format("Copying templates from %s image stores is not supported by the Proxmox plugin (v1); only NFS secondary storage is supported",
                    srcStore == null ? "unknown" : srcStore.getClass().getSimpleName()));
        }
        if (!(destTemplate.getDataStore() instanceof PrimaryDataStoreTO)) {
            return new CopyCmdAnswer("Destination data store is not a primary storage pool");
        }
        PrimaryDataStoreTO destStore = (PrimaryDataStoreTO) destTemplate.getDataStore();
        try {
            String node = resource.getNodeName();
            String storage = resource.getPveStorageId(destStore);
            verifyStorageSupportsImages(node, storage);

            String mountPoint = mountSecondaryStorage((NfsTO) srcStore);
            String srcPath = resolveSecondaryQcow2(mountPoint, srcTemplate.getPath());
            long virtualSize = getQcow2VirtualSize(srcPath);
            if (virtualSize <= 0) {
                return new CopyCmdAnswer("Could not determine virtual size of template file " + srcPath);
            }

            String templateUuid = destTemplate.getUuid() != null ? destTemplate.getUuid() : srcTemplate.getUuid();
            if (templateUuid == null) {
                templateUuid = UUID.randomUUID().toString();
            }
            int holderVmid = templateHolderVmid();
            boolean rawDest = isRawBlockStorage(storage);
            String fileName = pveVolumeNameFor("cstmpl", holderVmid, templateUuid, storage);

            ProxmoxApiClient api = resource.getApiClient();
            String volid = api.allocDiskImage(node, storage, holderVmid, fileName, virtualSize, allocFormatFor(storage));
            try {
                String destPath = api.getVolumePath(node, volid);
                convertIntoPrimaryVolume(quoted(srcPath), destPath, rawDest);
            } catch (RuntimeException e) {
                tryFreeVolume(node, volid);
                throw e;
            }

            TemplateObjectTO newTemplate = new TemplateObjectTO();
            newTemplate.setPath(volid);
            newTemplate.setSize(virtualSize);
            newTemplate.setFormat(rawDest ? ImageFormat.RAW : ImageFormat.QCOW2);
            logger.debug("Copied template {} from secondary storage to primary storage as {}", srcTemplate.getPath(), volid);
            return new CopyCmdAnswer(newTemplate);
        } catch (Exception e) {
            logger.error("Failed to copy template {} to primary storage: {}", srcTemplate.getPath(), e.getMessage(), e);
            return new CopyCmdAnswer(errorToString(e));
        }
    }

    @Override
    public Answer cloneVolumeFromBaseTemplate(CopyCommand cmd) {
        TemplateObjectTO template = (TemplateObjectTO) cmd.getSrcTO();
        VolumeObjectTO destVolume = (VolumeObjectTO) cmd.getDestTO();
        try {
            PrimaryDataStoreTO destStore = (PrimaryDataStoreTO) destVolume.getDataStore();
            String node = resource.getNodeName();
            String templateVolid = template.getPath();
            String srcPath = resource.getApiClient().getVolumePath(node, templateVolid);
            long templateSize = getVirtualSize(srcPath);

            VolumeObjectTO newVolume = convertToNewPrimaryDisk(node, convertSourceArgsFor(templateVolid, srcPath), templateSize, destVolume, destStore);
            logger.debug("Cloned volume {} from base template {}", newVolume.getPath(), templateVolid);
            return new CopyCmdAnswer(newVolume);
        } catch (Exception e) {
            logger.error("Failed to clone volume from base template {}: {}", template.getPath(), e.getMessage(), e);
            return new CopyCmdAnswer(errorToString(e));
        }
    }

    @Override
    public Answer copyVolumeFromImageCacheToPrimary(CopyCommand cmd) {
        VolumeObjectTO srcVolume = (VolumeObjectTO) cmd.getSrcTO();
        VolumeObjectTO destVolume = (VolumeObjectTO) cmd.getDestTO();
        DataStoreTO srcStore = srcVolume.getDataStore();
        if (!(srcStore instanceof NfsTO)) {
            return new CopyCmdAnswer("Copying volumes is only supported from NFS image/cache stores by the Proxmox plugin (v1)");
        }
        try {
            PrimaryDataStoreTO destStore = (PrimaryDataStoreTO) destVolume.getDataStore();
            String node = resource.getNodeName();
            String mountPoint = mountSecondaryStorage((NfsTO) srcStore);
            String srcPath = resolveSecondaryQcow2(mountPoint, srcVolume.getPath());
            long srcSize = getVirtualSize(srcPath);

            VolumeObjectTO newVolume = convertToNewPrimaryDisk(node, quoted(srcPath), srcSize, destVolume, destStore);
            logger.debug("Copied volume {} from image cache to primary storage as {}", srcVolume.getPath(), newVolume.getPath());
            return new CopyCmdAnswer(newVolume);
        } catch (Exception e) {
            logger.error("Failed to copy volume {} from image cache to primary storage: {}", srcVolume.getPath(), e.getMessage(), e);
            return new CopyCmdAnswer(errorToString(e));
        }
    }

    @Override
    public Answer copyVolumeFromPrimaryToSecondary(CopyCommand cmd) {
        VolumeObjectTO srcVolume = (VolumeObjectTO) cmd.getSrcTO();
        VolumeObjectTO destVolume = (VolumeObjectTO) cmd.getDestTO();
        DataStoreTO destStore = destVolume.getDataStore();
        if (!(destStore instanceof NfsTO)) {
            return new CopyCmdAnswer("Copying volumes is only supported to NFS secondary storage by the Proxmox plugin (v1)");
        }
        try {
            String node = resource.getNodeName();
            String srcPath = resource.getApiClient().getVolumePath(node, srcVolume.getPath());
            long virtualSize = getVirtualSize(srcPath);

            String mountPoint = mountSecondaryStorage((NfsTO) destStore);
            String destRelPath = trimSlashes(destVolume.getPath());
            String destDir = mountPoint + "/" + destRelPath;
            String fileName = (destVolume.getUuid() != null ? destVolume.getUuid() : UUID.randomUUID().toString()) + ".qcow2";
            String destPath = destDir + "/" + fileName;

            executeOrFail("mkdir -p " + quoted(destDir), DEFAULT_SSH_TIMEOUT_SEC);
            executeOrFail(String.format("qemu-img convert -O qcow2 %s %s", convertSourceArgsFor(srcVolume.getPath(), srcPath), quoted(destPath)), CONVERT_TIMEOUT_SEC);

            VolumeObjectTO newVolume = new VolumeObjectTO();
            newVolume.setPath(destRelPath + "/" + fileName);
            newVolume.setSize(virtualSize);
            newVolume.setFormat(ImageFormat.QCOW2);
            logger.debug("Copied volume {} from primary storage to secondary storage path {}", srcVolume.getPath(), newVolume.getPath());
            return new CopyCmdAnswer(newVolume);
        } catch (Exception e) {
            logger.error("Failed to copy volume {} from primary to secondary storage: {}", srcVolume.getPath(), e.getMessage(), e);
            return new CopyCmdAnswer(errorToString(e));
        }
    }

    @Override
    public Answer createTemplateFromVolume(CopyCommand cmd) {
        VolumeObjectTO srcVolume = (VolumeObjectTO) cmd.getSrcTO();
        TemplateObjectTO destTemplate = (TemplateObjectTO) cmd.getDestTO();
        DataStoreTO imageStore = destTemplate.getDataStore();
        if (!(imageStore instanceof NfsTO)) {
            return new CopyCmdAnswer("Creating templates is only supported on NFS secondary storage by the Proxmox plugin (v1)");
        }
        try {
            String node = resource.getNodeName();
            String srcPath = resource.getApiClient().getVolumePath(node, srcVolume.getPath());
            return createTemplateOnSecondary(quoted(srcPath), destTemplate, (NfsTO) imageStore, "volume.name");
        } catch (Exception e) {
            logger.error("Failed to create template from volume {}: {}", srcVolume.getPath(), e.getMessage(), e);
            return new CopyCmdAnswer(errorToString(e));
        }
    }

    @Override
    public Answer createTemplateFromSnapshot(CopyCommand cmd) {
        SnapshotObjectTO srcSnapshot = (SnapshotObjectTO) cmd.getSrcTO();
        TemplateObjectTO destTemplate = (TemplateObjectTO) cmd.getDestTO();
        DataStoreTO imageStore = destTemplate.getDataStore();
        if (!(imageStore instanceof NfsTO)) {
            return new CopyCmdAnswer("Creating templates is only supported on NFS secondary storage by the Proxmox plugin (v1)");
        }
        try {
            DataStoreTO srcStore = srcSnapshot.getDataStore();
            String convertSourceArgs;
            if (srcStore instanceof NfsTO) {
                String mountPoint = mountSecondaryStorage((NfsTO) srcStore);
                String srcPath = resolveSecondaryQcow2(mountPoint, srcSnapshot.getPath());
                convertSourceArgs = quoted(srcPath);
            } else {
                // snapshot still on primary storage: path is <volid>@<snapshotName>
                Pair<String, String> volidAndSnap = parseSnapshotPath(srcSnapshot.getPath());
                String node = resource.getNodeName();
                String volPath = resource.getApiClient().getVolumePath(node, volidAndSnap.first());
                convertSourceArgs = String.format("-f qcow2 -s %s %s", quoted(volidAndSnap.second()), quoted(volPath));
            }
            return createTemplateOnSecondary(convertSourceArgs, destTemplate, (NfsTO) imageStore, "snapshot.name");
        } catch (Exception e) {
            logger.error("Failed to create template from snapshot {}: {}", srcSnapshot.getPath(), e.getMessage(), e);
            return new CopyCmdAnswer(errorToString(e));
        }
    }

    @Override
    public Answer backupSnapshot(CopyCommand cmd) {
        SnapshotObjectTO srcSnapshot = (SnapshotObjectTO) cmd.getSrcTO();
        SnapshotObjectTO destSnapshot = (SnapshotObjectTO) cmd.getDestTO();
        DataStoreTO imageStore = destSnapshot.getDataStore();
        if (!(imageStore instanceof NfsTO)) {
            return new CopyCmdAnswer("Backing up snapshots is only supported to NFS secondary storage by the Proxmox plugin (v1)");
        }
        try {
            Pair<String, String> volidAndSnap = parseSnapshotPath(srcSnapshot.getPath());
            String volid = volidAndSnap.first();
            String snapshotName = volidAndSnap.second();

            String node = resource.getNodeName();
            String volPath = resource.getApiClient().getVolumePath(node, volid);

            String mountPoint = mountSecondaryStorage((NfsTO) imageStore);
            String destRelPath = trimSlashes(destSnapshot.getPath());
            String destDir = mountPoint + "/" + destRelPath;
            String fileName = snapshotName + ".qcow2";
            String destPath = destDir + "/" + fileName;

            executeOrFail("mkdir -p " + quoted(destDir), DEFAULT_SSH_TIMEOUT_SEC);
            executeOrFail(String.format("qemu-img convert -f qcow2 -O qcow2 -s %s %s %s", quoted(snapshotName), quoted(volPath), quoted(destPath)),
                    CONVERT_TIMEOUT_SEC);

            long physicalSize = getFileSize(destPath);
            SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
            newSnapshot.setPath(destRelPath + "/" + fileName);
            newSnapshot.setPhysicalSize(physicalSize);
            logger.debug("Backed up snapshot {} to secondary storage path {}", srcSnapshot.getPath(), newSnapshot.getPath());
            return new CopyCmdAnswer(newSnapshot);
        } catch (Exception e) {
            logger.error("Failed to backup snapshot {}: {}", srcSnapshot.getPath(), e.getMessage(), e);
            return new CopyCmdAnswer(errorToString(e));
        }
    }

    @Override
    public Answer attachIso(AttachCommand cmd) {
        DiskTO disk = cmd.getDisk();
        TemplateObjectTO iso = (TemplateObjectTO) disk.getData();
        DataStoreTO store = iso.getDataStore();
        if (!(store instanceof NfsTO)) {
            return new AttachAnswer("Attaching ISOs is only supported from NFS secondary storage by the Proxmox plugin (v1)");
        }
        try {
            int vmid = resource.vmidOfInstanceName(cmd.getVmName());
            String node = nodeOfVm(vmid);

            String isoInstallPath = trimSlashes(iso.getPath());
            String fileName = isoInstallPath.substring(isoInstallPath.lastIndexOf('/') + 1);
            if (!fileName.toLowerCase().endsWith(".iso")) {
                return new AttachAnswer("ISO file name '" + fileName + "' does not end with .iso; Proxmox VE requires the .iso extension");
            }

            String isoStorage = findIsoCapableStorage(node);
            if (isoStorage == null) {
                return new AttachAnswer(String.format("No PVE storage on node '%s' supports the 'iso' content type; add 'ISO image' content to a storage in the PVE datacenter storage configuration", node));
            }

            String isoVolid = isoStorage + ":iso/" + fileName;
            String localIsoPath = executeOrFail("pvesm path " + quoted(isoVolid), DEFAULT_SSH_TIMEOUT_SEC).trim();
            validateRemotePath(localIsoPath);
            if (!localIsoPath.startsWith("/") || localIsoPath.lastIndexOf('/') == 0) {
                return new AttachAnswer("Unexpected local path for ISO volume " + isoVolid + ": " + localIsoPath);
            }
            String localIsoDir = localIsoPath.substring(0, localIsoPath.lastIndexOf('/'));

            // Copy the ISO from secondary storage to the PVE iso storage once; cache by file name.
            String mountPoint = mountSecondaryStorage((NfsTO) store);
            String srcIsoPath = mountPoint + "/" + isoInstallPath;
            validateRemotePath(srcIsoPath);
            String copyCommand = String.format("if [ ! -f %s ]; then mkdir -p %s && cp %s %s && mv %s %s; fi",
                    quoted(localIsoPath), quoted(localIsoDir), quoted(srcIsoPath), quoted(localIsoPath + ".part"),
                    quoted(localIsoPath + ".part"), quoted(localIsoPath));
            executeOrFail(copyCommand, CONVERT_TIMEOUT_SEC);

            Map<String, Object> params = new HashMap<>();
            params.put("ide2", isoVolid + ",media=cdrom");
            resource.getApiClient().setVmConfig(node, vmid, params);
            logger.debug("Attached ISO {} to VM {} (vmid {}) as {}", fileName, cmd.getVmName(), vmid, isoVolid);
            return new AttachAnswer(disk);
        } catch (Exception e) {
            logger.error("Failed to attach ISO {} to VM {}: {}", iso.getPath(), cmd.getVmName(), e.getMessage(), e);
            return new AttachAnswer(errorToString(e));
        }
    }

    @Override
    public Answer dettachIso(DettachCommand cmd) {
        DiskTO disk = cmd.getDisk();
        try {
            int vmid = resource.vmidOfInstanceName(cmd.getVmName());
            String node = nodeOfVm(vmid);
            Map<String, Object> params = new HashMap<>();
            params.put("ide2", "none,media=cdrom");
            resource.getApiClient().setVmConfig(node, vmid, params);
            logger.debug("Detached ISO from VM {} (vmid {})", cmd.getVmName(), vmid);
            return new DettachAnswer(disk);
        } catch (Exception e) {
            logger.error("Failed to detach ISO from VM {}: {}", cmd.getVmName(), e.getMessage(), e);
            return new DettachAnswer(errorToString(e));
        }
    }

    @Override
    public Answer attachVolume(AttachCommand cmd) {
        DiskTO disk = cmd.getDisk();
        VolumeObjectTO volume = (VolumeObjectTO) disk.getData();
        try {
            int vmid = resource.vmidOfInstanceName(cmd.getVmName());
            String node = nodeOfVm(vmid);
            ProxmoxApiClient api = resource.getApiClient();

            String volid = volume.getPath();
            if (volid == null || volid.isEmpty()) {
                return new AttachAnswer("Volume " + volume.getUuid() + " has no PVE volid path");
            }
            int owner = ownerOfVolid(volid);
            if (owner != vmid) {
                volid = reassignVolumeOwner(node, volid, owner, vmid, volume.getUuid());
            }

            JsonObject config = api.getVmConfig(node, vmid);
            int slot = findFreeScsiSlot(config, disk.getDiskSeq());
            if (slot < 0) {
                return new AttachAnswer(String.format("No free scsi slot (scsi%d..scsi%d) on PVE VM %d to attach volume %s",
                        MIN_DATA_SCSI_SLOT, MAX_DATA_SCSI_SLOT, vmid, volid));
            }

            Map<String, Object> params = new HashMap<>();
            params.put("scsi" + slot, volid);
            api.setVmConfig(node, vmid, params);

            volume.setPath(volid);
            volume.setDeviceId((long) slot);
            disk.setPath(volid);
            disk.setDiskSeq((long) slot);
            logger.debug("Attached volume {} to VM {} (vmid {}) as scsi{}", volid, cmd.getVmName(), vmid, slot);
            return new AttachAnswer(disk);
        } catch (Exception e) {
            logger.error("Failed to attach volume {} to VM {}: {}", volume.getPath(), cmd.getVmName(), e.getMessage(), e);
            return new AttachAnswer(errorToString(e));
        }
    }

    @Override
    public Answer dettachVolume(DettachCommand cmd) {
        DiskTO disk = cmd.getDisk();
        VolumeObjectTO volume = (VolumeObjectTO) disk.getData();
        try {
            int vmid = resource.vmidOfInstanceName(cmd.getVmName());
            String node = nodeOfVm(vmid);
            String volid = volume.getPath();
            if (volid == null || volid.isEmpty()) {
                return new DettachAnswer("Volume " + volume.getUuid() + " has no PVE volid path");
            }
            boolean removed = removeVolumeReference(node, vmid, volid);
            if (!removed) {
                logger.info("Volume {} is not referenced by PVE VM {}; treating detach as already done", volid, vmid);
            } else {
                logger.debug("Detached volume {} from VM {} (vmid {})", volid, cmd.getVmName(), vmid);
            }
            return new DettachAnswer(disk);
        } catch (Exception e) {
            logger.error("Failed to detach volume {} from VM {}: {}", volume.getPath(), cmd.getVmName(), e.getMessage(), e);
            return new DettachAnswer(errorToString(e));
        }
    }

    @Override
    public Answer createVolume(CreateObjectCommand cmd) {
        VolumeObjectTO volume = (VolumeObjectTO) cmd.getData();
        try {
            PrimaryDataStoreTO store = (PrimaryDataStoreTO) volume.getDataStore();
            String node = resource.getNodeName();
            String storage = resource.getPveStorageId(store);
            verifyStorageSupportsImages(node, storage);

            Long size = volume.getSize();
            if (size == null || size <= 0) {
                return new CreateObjectAnswer("Cannot create volume " + volume.getUuid() + ": no size specified");
            }
            int holderVmid = templateHolderVmid();
            String uuid = volume.getUuid() != null ? volume.getUuid() : UUID.randomUUID().toString();
            String fileName = String.format("vm-%d-disk-%s.qcow2", holderVmid, uuid);

            String volid = resource.getApiClient().allocDiskImage(node, storage, holderVmid, fileName, size, FORMAT_QCOW2);

            VolumeObjectTO newVolume = new VolumeObjectTO();
            newVolume.setPath(volid);
            newVolume.setSize(size);
            newVolume.setFormat(ImageFormat.QCOW2);
            logger.debug("Created volume {} of size {} on storage {}", volid, size, storage);
            return new CreateObjectAnswer(newVolume);
        } catch (Exception e) {
            logger.error("Failed to create volume {}: {}", volume.getUuid(), e.getMessage(), e);
            return new CreateObjectAnswer(errorToString(e));
        }
    }

    @Override
    public Answer createSnapshot(CreateObjectCommand cmd) {
        SnapshotObjectTO snapshot = (SnapshotObjectTO) cmd.getData();
        VolumeObjectTO volume = snapshot.getVolume();
        if (volume == null || volume.getPath() == null) {
            return new CreateObjectAnswer("Snapshot request does not carry the source volume path");
        }
        try {
            String volid = volume.getPath();
            String node = resource.getNodeName();
            String vmName = snapshot.getVmName() != null ? snapshot.getVmName() : volume.getVmName();

            if (vmName != null && !vmName.isEmpty()) {
                try {
                    int vmid = resource.vmidOfInstanceName(vmName);
                    String vmNode = resource.getApiClient().findNodeOfVm(vmid);
                    if (vmNode != null) {
                        node = vmNode;
                        JsonObject status = resource.getApiClient().getVmStatus(vmNode, vmid);
                        if (status.has("status") && "running".equals(status.get("status").getAsString())) {
                            return new CreateObjectAnswer("Online volume snapshots are not supported by the Proxmox plugin (v1); stop the instance or use VM snapshots instead");
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not determine run state of VM {} while snapshotting volume {}; assuming it is not running: {}",
                            vmName, volid, e.getMessage());
                }
            }

            String snapshotName = UUID.randomUUID().toString();
            String volPath = resource.getApiClient().getVolumePath(node, volid);
            executeOrFail(String.format("qemu-img snapshot -c %s %s", quoted(snapshotName), quoted(volPath)), DEFAULT_SSH_TIMEOUT_SEC);

            SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
            newSnapshot.setPath(volid + "@" + snapshotName);
            logger.debug("Created qcow2 internal snapshot {} on volume {}", snapshotName, volid);
            return new CreateObjectAnswer(newSnapshot);
        } catch (Exception e) {
            logger.error("Failed to create snapshot of volume {}: {}", volume.getPath(), e.getMessage(), e);
            return new CreateObjectAnswer(errorToString(e));
        }
    }

    @Override
    public Answer deleteVolume(DeleteCommand cmd) {
        DataTO data = cmd.getData();
        String volid = data.getPath();
        if (volid == null || volid.isEmpty()) {
            return new Answer(cmd);
        }
        try {
            String node = resource.getNodeName();
            String storage = storageOfVolid(volid);

            // Graceful cleanup: if the volume is still referenced by its owner VM's config, drop the reference first.
            try {
                int owner = ownerOfVolid(volid);
                if (owner != templateHolderVmid()) {
                    String ownerNode = resource.getApiClient().findNodeOfVm(owner);
                    if (ownerNode != null) {
                        removeVolumeReference(ownerNode, owner, volid);
                        node = ownerNode;
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not check/remove VM config references of volume {} before deletion: {}", volid, e.getMessage());
            }

            resource.getApiClient().freeVolume(node, storage, volid, resource.getTaskTimeoutMs());
            logger.debug("Deleted volume {}", volid);
            return new Answer(cmd);
        } catch (Exception e) {
            if (isNotFound(e)) {
                logger.info("Volume {} no longer exists; treating delete as successful", volid);
                return new Answer(cmd);
            }
            logger.error("Failed to delete volume {}: {}", volid, e.getMessage(), e);
            return new Answer(cmd, false, errorToString(e));
        }
    }

    @Override
    public Answer createVolumeFromSnapshot(CopyCommand cmd) {
        SnapshotObjectTO srcSnapshot = (SnapshotObjectTO) cmd.getSrcTO();
        VolumeObjectTO destVolume = (VolumeObjectTO) cmd.getDestTO();
        try {
            PrimaryDataStoreTO destStore = (PrimaryDataStoreTO) destVolume.getDataStore();
            String node = resource.getNodeName();
            DataStoreTO srcStore = srcSnapshot.getDataStore();

            String convertSourceArgs;
            long srcVirtualSize;
            if (srcStore instanceof NfsTO) {
                String mountPoint = mountSecondaryStorage((NfsTO) srcStore);
                String srcPath = resolveSecondaryQcow2(mountPoint, srcSnapshot.getPath());
                srcVirtualSize = getQcow2VirtualSize(srcPath);
                convertSourceArgs = quoted(srcPath);
            } else if (srcSnapshot.getPath() != null && srcSnapshot.getPath().contains("@")) {
                // snapshot still on primary storage: <volid>@<snapshotName>
                Pair<String, String> volidAndSnap = parseSnapshotPath(srcSnapshot.getPath());
                String volPath = resource.getApiClient().getVolumePath(node, volidAndSnap.first());
                srcVirtualSize = getQcow2VirtualSize(volPath);
                convertSourceArgs = String.format("-f qcow2 -s %s %s", quoted(volidAndSnap.second()), quoted(volPath));
            } else {
                return new CopyCmdAnswer("Unsupported snapshot source for createVolumeFromSnapshot: " + srcSnapshot.getPath());
            }

            VolumeObjectTO newVolume = convertToNewPrimaryDisk(node, convertSourceArgs, srcVirtualSize, destVolume, destStore);
            logger.debug("Created volume {} from snapshot {}", newVolume.getPath(), srcSnapshot.getPath());
            return new CopyCmdAnswer(newVolume);
        } catch (Exception e) {
            logger.error("Failed to create volume from snapshot {}: {}", srcSnapshot.getPath(), e.getMessage(), e);
            return new CopyCmdAnswer(errorToString(e));
        }
    }

    @Override
    public Answer deleteSnapshot(DeleteCommand cmd) {
        SnapshotObjectTO snapshot = (SnapshotObjectTO) cmd.getData();
        String path = snapshot.getPath();
        if (path == null || path.isEmpty()) {
            return new Answer(cmd);
        }
        int at = path.lastIndexOf('@');
        if (at < 0) {
            logger.info("Snapshot path {} does not name a qcow2 internal snapshot; nothing to delete on primary storage", path);
            return new Answer(cmd);
        }
        String volid = path.substring(0, at);
        String snapshotName = path.substring(at + 1);
        try {
            String node = resource.getNodeName();
            String volPath;
            try {
                volPath = resource.getApiClient().getVolumePath(node, volid);
            } catch (Exception e) {
                if (isNotFound(e)) {
                    logger.info("Volume {} of snapshot {} no longer exists; treating snapshot delete as successful", volid, snapshotName);
                    return new Answer(cmd);
                }
                throw e;
            }
            validateRemotePath(volPath);
            validateRemotePath(snapshotName);
            Pair<Boolean, String> result = resource.executeOnNode(
                    String.format("qemu-img snapshot -d %s %s", quoted(snapshotName), quoted(volPath)), DEFAULT_SSH_TIMEOUT_SEC);
            if (result != null && Boolean.TRUE.equals(result.first())) {
                logger.debug("Deleted qcow2 internal snapshot {} on volume {}", snapshotName, volid);
                return new Answer(cmd);
            }
            String output = result != null && result.second() != null ? result.second() : "";
            String lower = output.toLowerCase();
            if (lower.contains("not found") || lower.contains("does not exist") || lower.contains("no such") || lower.contains("can't find")) {
                logger.info("Snapshot {} not present on volume {}; treating delete as successful", snapshotName, volid);
                return new Answer(cmd);
            }
            return new Answer(cmd, false, "Failed to delete snapshot " + snapshotName + " on volume " + volid + ": " + output);
        } catch (Exception e) {
            logger.error("Failed to delete snapshot {}: {}", path, e.getMessage(), e);
            return new Answer(cmd, false, errorToString(e));
        }
    }

    @Override
    public Answer introduceObject(IntroduceObjectCmd cmd) {
        return new Answer(cmd, false, "Introducing objects is not supported by the Proxmox plugin (v1)");
    }

    @Override
    public Answer forgetObject(ForgetObjectCmd cmd) {
        return new Answer(cmd, false, "Forgetting objects is not supported by the Proxmox plugin (v1)");
    }

    @Override
    public Answer snapshotAndCopy(SnapshotAndCopyCommand cmd) {
        return new SnapshotAndCopyAnswer("Snapshot-and-copy (managed storage) is not supported by the Proxmox plugin (v1)");
    }

    @Override
    public Answer resignature(ResignatureCommand cmd) {
        return new ResignatureAnswer("Resignature (managed storage) is not supported by the Proxmox plugin (v1)");
    }

    @Override
    public Answer handleDownloadTemplateToPrimaryStorage(DirectDownloadCommand cmd) {
        return new DirectDownloadAnswer(false, "Direct download of templates to primary storage is not supported by the Proxmox plugin (v1)", false);
    }

    @Override
    public Answer copyVolumeFromPrimaryToPrimary(CopyCommand cmd) {
        return new CopyCmdAnswer("Copying volumes between primary storage pools is not supported by the Proxmox plugin (v1)");
    }

    @Override
    public Answer checkDataStoreStoragePolicyCompliance(CheckDataStoreStoragePolicyComplianceCommand cmd) {
        return new Answer(cmd, false, "Storage policy compliance checks are not applicable to the Proxmox plugin");
    }

    @Override
    public Answer syncVolumePath(SyncVolumePathCommand cmd) {
        return new Answer(cmd, false, "Syncing volume paths is not supported by the Proxmox plugin (v1)");
    }

    /**
     * Allocates a new qcow2 disk image on the destination primary storage and fills it with
     * {@code qemu-img convert} from the given (already quoted/validated) source arguments,
     * growing the image afterwards when the requested volume size exceeds the source size.
     */
    private VolumeObjectTO convertToNewPrimaryDisk(String node, String convertSourceArgs, long srcVirtualSize,
            VolumeObjectTO destVolume, PrimaryDataStoreTO destStore) {
        String storage = resource.getPveStorageId(destStore);
        verifyStorageSupportsImages(node, storage);

        long requestedSize = destVolume.getSize() != null ? destVolume.getSize() : 0L;
        long size = Math.max(srcVirtualSize, requestedSize);
        if (size <= 0) {
            throw new CloudRuntimeException("Cannot determine a positive size for new volume " + destVolume.getUuid());
        }

        int vmid = vmidForInstance(destVolume.getVmName(), templateHolderVmid());
        String uuid = destVolume.getUuid() != null ? destVolume.getUuid() : UUID.randomUUID().toString();
        String fileName = String.format("vm-%d-disk-%s.qcow2", vmid, uuid);

        ProxmoxApiClient api = resource.getApiClient();
        String volid = api.allocDiskImage(node, storage, vmid, fileName, size, FORMAT_QCOW2);
        try {
            String destPath = api.getVolumePath(node, volid);
            executeOrFail(String.format("qemu-img convert -O qcow2 %s %s", convertSourceArgs, quoted(destPath)), CONVERT_TIMEOUT_SEC);
            if (requestedSize > srcVirtualSize) {
                executeOrFail(String.format("qemu-img resize %s %d", quoted(destPath), requestedSize), DEFAULT_SSH_TIMEOUT_SEC);
            }
        } catch (RuntimeException e) {
            tryFreeVolume(node, volid);
            throw e;
        }

        VolumeObjectTO newVolume = new VolumeObjectTO();
        newVolume.setPath(volid);
        newVolume.setSize(size);
        newVolume.setFormat(ImageFormat.QCOW2);
        return newVolume;
    }

    /**
     * Converts the given (already quoted/validated) qemu-img source arguments into a new template
     * qcow2 on NFS secondary storage and writes the template.properties descriptor next to it,
     * mirroring what the KVM agent's TemplateLocation produces.
     */
    private Answer createTemplateOnSecondary(String convertSourceArgs, TemplateObjectTO destTemplate, NfsTO imageStore, String sourceNameProperty) {
        String mountPoint = mountSecondaryStorage(imageStore);
        String templateRelPath = trimSlashes(destTemplate.getPath());
        String templateDir = mountPoint + "/" + templateRelPath;
        String templateName = destTemplate.getUuid() != null ? destTemplate.getUuid() : UUID.randomUUID().toString();
        String fileName = templateName + ".qcow2";
        String destPath = templateDir + "/" + fileName;

        executeOrFail("mkdir -p " + quoted(templateDir), DEFAULT_SSH_TIMEOUT_SEC);
        executeOrFail(String.format("qemu-img convert -O qcow2 %s %s", convertSourceArgs, quoted(destPath)), CONVERT_TIMEOUT_SEC);

        long virtualSize = getQcow2VirtualSize(destPath);
        long physicalSize = getFileSize(destPath);
        writeTemplateProperties(templateDir, fileName, templateName, destTemplate, virtualSize, physicalSize, sourceNameProperty);

        TemplateObjectTO newTemplate = new TemplateObjectTO();
        newTemplate.setPath(templateRelPath + "/" + fileName);
        newTemplate.setName(templateName);
        newTemplate.setSize(virtualSize);
        newTemplate.setPhysicalSize(physicalSize);
        newTemplate.setFormat(ImageFormat.QCOW2);
        logger.debug("Created template {} on secondary storage (virtual size {}, physical size {})", newTemplate.getPath(), virtualSize, physicalSize);
        return new CopyCmdAnswer(newTemplate);
    }

    private void writeTemplateProperties(String templateDir, String fileName, String templateName, TemplateObjectTO template,
            long virtualSize, long physicalSize, String sourceNameProperty) {
        long id = template.getId() > 0 ? template.getId() : 1;
        String date = new SimpleDateFormat("MM_dd_yyyy").format(new Date());
        StringBuilder props = new StringBuilder();
        props.append("filename=").append(fileName).append('\n');
        props.append(sourceNameProperty).append('=').append(date).append('\n');
        props.append("id=").append(id).append('\n');
        props.append("public=true").append('\n');
        props.append("uniquename=").append(templateName).append('\n');
        props.append("qcow2=true").append('\n');
        props.append("qcow2.filename=").append(fileName).append('\n');
        props.append("qcow2.size=").append(physicalSize).append('\n');
        props.append("qcow2.virtualsize=").append(virtualSize).append('\n');
        props.append("virtualsize=").append(virtualSize).append('\n');
        props.append("size=").append(physicalSize).append('\n');
        String propsFile = templateDir + "/" + TEMPLATE_PROPERTIES;
        executeOrFail("cat > " + quoted(propsFile) + " <<'CLOUDSTACKEOF'\n" + props + "CLOUDSTACKEOF", DEFAULT_SSH_TIMEOUT_SEC);
    }

    /**
     * Reassigns ownership of an unreferenced volume to another vmid by moving the backing file on
     * a file-based (dir/NFS) PVE storage, since {@code move_disk} with target-vmid requires the
     * disk to be referenced by a VM config and the template holder vmid has no VM.
     *
     * @return the new volid
     */
    private String reassignVolumeOwner(String node, String volid, int owner, int vmid, String volumeUuid) {
        ProxmoxApiClient api = resource.getApiClient();

        if (owner != templateHolderVmid()) {
            // Safety: never move a file out from under a VM config that still references it.
            try {
                String ownerNode = api.findNodeOfVm(owner);
                if (ownerNode != null) {
                    JsonObject ownerConfig = api.getVmConfig(ownerNode, owner);
                    String activeKey = findDiskKey(ownerConfig, volid, ACTIVE_DISK_KEY);
                    if (activeKey != null) {
                        throw new CloudRuntimeException(String.format("Volume %s is still attached as %s of PVE VM %d; detach it before attaching elsewhere", volid, activeKey, owner));
                    }
                    String unusedKey = findDiskKey(ownerConfig, volid, UNUSED_DISK_KEY);
                    if (unusedKey != null) {
                        Map<String, Object> params = new HashMap<>();
                        params.put("delete", unusedKey);
                        api.setVmConfig(ownerNode, owner, params);
                    }
                }
            } catch (CloudRuntimeException e) {
                throw e;
            } catch (Exception e) {
                logger.debug("Could not inspect previous owner VM {} of volume {}: {}", owner, volid, e.getMessage());
            }
        }

        String srcPath = api.getVolumePath(node, volid);
        validateRemotePath(srcPath);
        String ownerMarker = "/images/" + owner + "/";
        int markerIndex = srcPath.indexOf(ownerMarker);
        if (markerIndex < 0) {
            throw new CloudRuntimeException(String.format("Cannot reassign owner of volume %s (path %s): ownership reassignment is only supported on file-based (dir/NFS) PVE storages", volid, srcPath));
        }
        String storageMount = srcPath.substring(0, markerIndex);
        String uuid = volumeUuid != null ? volumeUuid : UUID.randomUUID().toString();
        String newFileName = String.format("vm-%d-disk-%s.qcow2", vmid, uuid);
        String destDir = storageMount + "/images/" + vmid;
        String destPath = destDir + "/" + newFileName;

        executeOrFail(String.format("mkdir -p %s && mv %s %s", quoted(destDir), quoted(srcPath), quoted(destPath)), DEFAULT_SSH_TIMEOUT_SEC);

        String newVolid = storageOfVolid(volid) + ":" + vmid + "/" + newFileName;
        logger.debug("Reassigned volume {} from vmid {} to vmid {}; new volid {}", volid, owner, vmid, newVolid);
        return newVolid;
    }

    /**
     * Unlinks the config entry referencing the given volid (attached disk becomes unusedN) and then
     * removes the resulting unused entry while keeping the volume on storage.
     *
     * @return true if any reference was found and removed
     */
    private boolean removeVolumeReference(String node, int vmid, String volid) {
        ProxmoxApiClient api = resource.getApiClient();
        JsonObject config = api.getVmConfig(node, vmid);
        String diskKey = findDiskKey(config, volid, ACTIVE_DISK_KEY);
        if (diskKey != null) {
            api.unlinkDisk(node, vmid, diskKey, false);
        }
        JsonObject refreshed = api.getVmConfig(node, vmid);
        String unusedKey = findDiskKey(refreshed, volid, UNUSED_DISK_KEY);
        if (unusedKey != null) {
            Map<String, Object> params = new HashMap<>();
            params.put("delete", unusedKey);
            api.setVmConfig(node, vmid, params);
        }
        return diskKey != null || unusedKey != null;
    }

    private String findDiskKey(JsonObject config, String volid, Pattern keyPattern) {
        for (Map.Entry<String, JsonElement> entry : config.entrySet()) {
            if (!keyPattern.matcher(entry.getKey()).matches()) {
                continue;
            }
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive()) {
                continue;
            }
            String reference = value.getAsString().split(",")[0].trim();
            if (volid.equals(reference)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private int findFreeScsiSlot(JsonObject config, Long requestedSeq) {
        if (requestedSeq != null && requestedSeq >= MIN_DATA_SCSI_SLOT && requestedSeq <= MAX_DATA_SCSI_SLOT
                && !config.has("scsi" + requestedSeq)) {
            return requestedSeq.intValue();
        }
        for (int i = MIN_DATA_SCSI_SLOT; i <= MAX_DATA_SCSI_SLOT; i++) {
            if (!config.has("scsi" + i)) {
                return i;
            }
        }
        return -1;
    }

    private String findIsoCapableStorage(String node) {
        JsonArray storages = resource.getApiClient().listStorage(node);
        for (JsonElement element : storages) {
            JsonObject storage = element.getAsJsonObject();
            String content = storage.has("content") ? storage.get("content").getAsString() : "";
            boolean isoCapable = Arrays.stream(content.split(",")).map(String::trim).anyMatch("iso"::equals);
            if (!isoCapable) {
                continue;
            }
            if (storage.has("enabled") && storage.get("enabled").getAsInt() == 0) {
                continue;
            }
            if (storage.has("active") && storage.get("active").getAsInt() == 0) {
                continue;
            }
            return storage.get("storage").getAsString();
        }
        return null;
    }

    private void verifyStorageSupportsImages(String node, String storage) {
        JsonObject status = resource.getApiClient().getStorageStatus(node, storage);
        String content = status.has("content") ? status.get("content").getAsString() : "";
        boolean supportsImages = Arrays.stream(content.split(",")).map(String::trim).anyMatch("images"::equals);
        if (!supportsImages) {
            throw new CloudRuntimeException(String.format("PVE storage '%s' on node '%s' does not support the 'images' content type (content: '%s'); cannot place disk images on it",
                    storage, node, content));
        }
    }

    /**
     * Idempotently mounts the given NFS store on the PVE node under
     * {@code /mnt/cloudstack/sec/<md5-8 of url>} and returns the mount point. Mounts are left in
     * place (cached) and never unmounted eagerly.
     */
    private String mountSecondaryStorage(NfsTO nfs) {
        String url = nfs.getUrl();
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw new CloudRuntimeException("Malformed secondary storage url: " + url, e);
        }
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || path == null || path.isEmpty()) {
            throw new CloudRuntimeException("Secondary storage url is missing host or path: " + url);
        }
        String mountPoint = SECONDARY_MOUNT_BASE + md5First8(url);
        String command = String.format("mkdir -p %s && (mountpoint -q %s || mount -t nfs %s %s)",
                quoted(mountPoint), quoted(mountPoint), quoted(host + ":" + path), quoted(mountPoint));
        executeOrFail(command, MOUNT_TIMEOUT_SEC);
        return mountPoint;
    }

    /**
     * Resolves a path on mounted secondary storage to an actual image file: the path may point
     * directly at a file, or at a directory containing a single qcow2 image.
     */
    private String resolveSecondaryQcow2(String mountPoint, String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            throw new CloudRuntimeException("No install path given for object on secondary storage");
        }
        String fullPath = mountPoint + "/" + trimSlashes(relativePath);
        validateRemotePath(fullPath);
        Pair<Boolean, String> isFile = resource.executeOnNode("test -f " + quoted(fullPath), DEFAULT_SSH_TIMEOUT_SEC);
        if (isFile != null && Boolean.TRUE.equals(isFile.first())) {
            return fullPath;
        }
        String output = executeOrFail("find " + quoted(fullPath) + " -maxdepth 1 -type f -name '*.qcow2' 2>/dev/null | head -n 1", DEFAULT_SSH_TIMEOUT_SEC);
        String found = output.trim();
        if (found.isEmpty()) {
            throw new CloudRuntimeException("Could not locate a qcow2 image under secondary storage path " + fullPath);
        }
        int newline = found.indexOf('\n');
        if (newline > 0) {
            found = found.substring(0, newline).trim();
        }
        validateRemotePath(found);
        return found;
    }

    private long getQcow2VirtualSize(String filePath) {
        String output = executeOrFail("qemu-img info -U --output=json " + quoted(filePath), DEFAULT_SSH_TIMEOUT_SEC);
        try {
            JsonObject info = JsonParser.parseString(output.trim()).getAsJsonObject();
            return info.get("virtual-size").getAsLong();
        } catch (Exception e) {
            throw new CloudRuntimeException("Could not parse qemu-img info output for " + filePath + ": " + output, e);
        }
    }

    private long getFileSize(String filePath) {
        String output = executeOrFail("stat -c %s " + quoted(filePath), DEFAULT_SSH_TIMEOUT_SEC);
        try {
            return Long.parseLong(output.trim());
        } catch (NumberFormatException e) {
            throw new CloudRuntimeException("Could not parse file size of " + filePath + ": " + output, e);
        }
    }

    private String executeOrFail(String command, int timeoutSec) {
        Pair<Boolean, String> result = resource.executeOnNode(command, timeoutSec);
        if (result == null || !Boolean.TRUE.equals(result.first())) {
            String output = result != null && result.second() != null ? result.second() : "no output";
            throw new CloudRuntimeException(String.format("Command failed on node %s: [%s]: %s", resource.getNodeName(), command, output));
        }
        return result.second() != null ? result.second() : "";
    }

    private void tryFreeVolume(String node, String volid) {
        try {
            resource.getApiClient().freeVolume(node, storageOfVolid(volid), volid, resource.getTaskTimeoutMs());
        } catch (Exception e) {
            logger.warn("Failed to clean up volume {} after error: {}", volid, e.getMessage());
        }
    }

    private int templateHolderVmid() {
        return resource.getVmidBase() - 1;
    }

    private int vmidForInstance(String vmName, int fallback) {
        if (vmName == null || vmName.isEmpty()) {
            return fallback;
        }
        try {
            int vmid = resource.vmidOfInstanceName(vmName);
            if (vmid > 0) {
                return vmid;
            }
        } catch (Exception e) {
            logger.debug("Could not derive PVE vmid from instance name {}: {}", vmName, e.getMessage());
        }
        return fallback;
    }

    private String nodeOfVm(int vmid) {
        try {
            String node = resource.getApiClient().findNodeOfVm(vmid);
            if (node != null) {
                return node;
            }
        } catch (Exception e) {
            logger.debug("Could not locate node of PVE VM {}: {}", vmid, e.getMessage());
        }
        return resource.getNodeName();
    }

    private String storageOfVolid(String volid) {
        int colon = volid.indexOf(':');
        if (colon <= 0) {
            throw new CloudRuntimeException("Not a PVE volid: " + volid);
        }
        return volid.substring(0, colon);
    }

    private int ownerOfVolid(String volid) {
        int colon = volid.indexOf(':');
        String rest = colon >= 0 ? volid.substring(colon + 1) : volid;
        int slash = rest.indexOf('/');
        if (slash > 0) {
            try {
                return Integer.parseInt(rest.substring(0, slash));
            } catch (NumberFormatException ignored) {
                // fall through to file-name based parsing
            }
        }
        Matcher matcher = VOLID_VMID.matcher(rest);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        throw new CloudRuntimeException("Cannot determine the owner vmid of PVE volid: " + volid);
    }

    /**
     * Splits a primary-storage snapshot path of the form {@code <volid>@<snapshotName>}.
     */
    private Pair<String, String> parseSnapshotPath(String path) {
        int at = path != null ? path.lastIndexOf('@') : -1;
        if (at <= 0 || at == path.length() - 1) {
            throw new CloudRuntimeException("Unexpected snapshot path '" + path + "'; expected <volid>@<snapshotName>");
        }
        return new Pair<>(path.substring(0, at), path.substring(at + 1));
    }

    private String trimSlashes(String path) {
        String trimmed = path;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void validateRemotePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new CloudRuntimeException("Empty path in remote command");
        }
        if (path.contains("'")) {
            throw new CloudRuntimeException("Refusing to use a path containing a single quote in a remote command: " + path);
        }
    }

    private String quoted(String path) {
        validateRemotePath(path);
        return "'" + path + "'";
    }

    private String md5First8(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 8);
        } catch (Exception e) {
            throw new CloudRuntimeException("Could not compute md5 of " + input, e);
        }
    }

    private boolean isNotFound(Exception e) {
        if (e instanceof ProxmoxApiException && ((ProxmoxApiException) e).getStatusCode() == 404) {
            return true;
        }
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return message.contains("does not exist") || message.contains("no such") || message.contains("not found");
    }

    private String errorToString(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
