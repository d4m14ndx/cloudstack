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
            long virtualSize = getVirtualSize(srcPath);
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
            return createTemplateOnSecondary(convertSourceArgsFor(srcVolume.getPath(), srcPath), destTemplate, (NfsTO) imageStore, "volume.name");
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
                convertSourceArgs = snapshotConvertSourceArgs(node, volidAndSnap.first(), volidAndSnap.second());
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
            String convertSourceArgs = snapshotConvertSourceArgs(node, volid, snapshotName);

            String mountPoint = mountSecondaryStorage((NfsTO) imageStore);
            String destRelPath = trimSlashes(destSnapshot.getPath());
            String destDir = mountPoint + "/" + destRelPath;
            String fileName = snapshotName + ".qcow2";
            String destPath = destDir + "/" + fileName;

            executeOrFail("mkdir -p " + quoted(destDir), DEFAULT_SSH_TIMEOUT_SEC);
            executeOrFail(String.format("qemu-img convert -O qcow2 %s %s", convertSourceArgs, quoted(destPath)),
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

    /**
     * Makes an ISO from NFS secondary storage available on a PVE iso-content storage
     * (copied once, cached by file name) and returns its PVE volid. Shared iso storages
     * are preferred so the staged file is visible cluster-wide. Used by both the live
     * attach path and StartCommand, which must re-insert an ISO attached while the VM
     * was stopped.
     */
    public String stageIso(TemplateObjectTO iso, String node) {
        DataStoreTO store = iso.getDataStore();
        if (!(store instanceof NfsTO)) {
            throw new CloudRuntimeException("Attaching ISOs is only supported from NFS secondary storage by the Proxmox plugin (v1)");
        }
        String isoInstallPath = trimSlashes(iso.getPath());
        String fileName = isoInstallPath.substring(isoInstallPath.lastIndexOf('/') + 1);
        if (!fileName.toLowerCase().endsWith(".iso")) {
            throw new CloudRuntimeException("ISO file name '" + fileName + "' does not end with .iso; Proxmox VE requires the .iso extension");
        }

        String isoStorage = findIsoCapableStorage(node);
        if (isoStorage == null) {
            throw new CloudRuntimeException(String.format("No PVE storage on node '%s' supports the 'iso' content type; add 'ISO image' content to a storage in the PVE datacenter storage configuration", node));
        }

        String isoVolid = isoStorage + ":iso/" + fileName;
        String localIsoPath = executeOrFail("pvesm path " + quoted(isoVolid), DEFAULT_SSH_TIMEOUT_SEC).trim();
        validateRemotePath(localIsoPath);
        if (!localIsoPath.startsWith("/") || localIsoPath.lastIndexOf('/') == 0) {
            throw new CloudRuntimeException("Unexpected local path for ISO volume " + isoVolid + ": " + localIsoPath);
        }
        String localIsoDir = localIsoPath.substring(0, localIsoPath.lastIndexOf('/'));

        // Copy the ISO from secondary storage to the PVE iso storage once; cache by file name.
        // This works for any file-backed iso-capable storage (dir/NFS/CephFS): `pvesm path`
        // returns the node-local mounted path (e.g. /mnt/pve/<storage>/template/iso/... for
        // NFS and CephFS storages), which cp/mv operate on directly.
        String mountPoint = mountSecondaryStorage((NfsTO) store);
        String srcIsoPath = mountPoint + "/" + isoInstallPath;
        validateRemotePath(srcIsoPath);
        String copyCommand = String.format("if [ ! -f %s ]; then mkdir -p %s && cp %s %s && mv %s %s; fi",
                quoted(localIsoPath), quoted(localIsoDir), quoted(srcIsoPath), quoted(localIsoPath + ".part"),
                quoted(localIsoPath + ".part"), quoted(localIsoPath));
        executeOrFail(copyCommand, CONVERT_TIMEOUT_SEC);
        return isoVolid;
    }

    @Override
    public Answer attachIso(AttachCommand cmd) {
        DiskTO disk = cmd.getDisk();
        TemplateObjectTO iso = (TemplateObjectTO) disk.getData();
        try {
            int vmid = requireVmid(cmd.getVmName());
            String node = nodeOfVm(vmid);

            String isoVolid = stageIso(iso, node);

            Map<String, Object> params = new HashMap<>();
            params.put("ide2", isoVolid + ",media=cdrom");
            resource.getApiClient().setVmConfig(node, vmid, params);
            logger.debug("Attached ISO {} to VM {} (vmid {}) as {}", iso.getPath(), cmd.getVmName(), vmid, isoVolid);
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
            int vmid = requireVmid(cmd.getVmName());
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
            int vmid = requireVmid(cmd.getVmName());
            ProxmoxApiClient api = resource.getApiClient();

            String volid = volume.getPath();
            if (volid == null || volid.isEmpty()) {
                return new AttachAnswer("Volume " + volume.getUuid() + " has no PVE volid path");
            }

            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                // The VM has not been created in PVE yet (stopped instance that never started):
                // the disk list of the next StartCommand wires the volume into the config.
                logger.info("PVE VM {} does not exist yet; volume {} will be attached by the next start of {}", vmid, volid, cmd.getVmName());
                return new AttachAnswer(disk);
            }

            // Volumes are attached under their existing volid, never renamed to the target vmid:
            // CloudStack does not persist path changes from attach answers, so a rename here would
            // silently diverge the DB from PVE and break every later detach/delete of the volume.
            // PVE only derives ownership from the name; referencing a foreign-owned volid is fine.
            int owner = ownerOfVolid(volid);
            if (owner != vmid && owner != templateHolderVmid()) {
                ensureNotHeldByOwnerVm(volid, owner);
            }

            JsonObject config = api.getVmConfig(node, vmid);
            String attachedKey = findDiskKey(config, volid, ACTIVE_DISK_KEY);
            if (attachedKey != null) {
                // already attached (e.g. a retried command): report the existing slot
                long existingSlot = Long.parseLong(attachedKey.replaceAll("\\D", ""));
                volume.setDeviceId(existingSlot);
                disk.setDiskSeq(existingSlot);
                logger.info("Volume {} is already attached to PVE VM {} as {}", volid, vmid, attachedKey);
                return new AttachAnswer(disk);
            }

            int slot = findFreeScsiSlot(config, disk.getDiskSeq());
            if (slot < 0) {
                return new AttachAnswer(String.format("No free scsi slot (scsi%d..scsi%d) on PVE VM %d to attach volume %s",
                        MIN_DATA_SCSI_SLOT, MAX_DATA_SCSI_SLOT, vmid, volid));
            }

            Map<String, Object> params = new HashMap<>();
            params.put("scsi" + slot, volid);
            api.setVmConfig(node, vmid, params);

            volume.setDeviceId((long) slot);
            disk.setDiskSeq((long) slot);
            logger.debug("Attached volume {} to VM {} (vmid {}) as scsi{}", volid, cmd.getVmName(), vmid, slot);
            return new AttachAnswer(disk);
        } catch (Exception e) {
            logger.error("Failed to attach volume {} to VM {}: {}", volume.getPath(), cmd.getVmName(), e.getMessage(), e);
            return new AttachAnswer(errorToString(e));
        }
    }

    /**
     * Guards attaching a volume whose volid still names another VM as its PVE owner: while that
     * VM exists and references the volume (attached, or parked as an unusedN entry), attaching it
     * elsewhere risks double use now and data loss later, because PVE frees owned volumes that
     * are still referenced when the owning VM (or its unusedN entry) is deleted. Detach renames
     * volumes to the template holder; hitting this guard means that hand-off never happened.
     */
    private void ensureNotHeldByOwnerVm(String volid, int owner) {
        ProxmoxApiClient api = resource.getApiClient();
        JsonObject ownerConfig;
        try {
            String ownerNode = api.findNodeOfVm(owner);
            if (ownerNode == null) {
                // owner VM is gone; CloudStack never reuses vmids, so the name is only historical
                return;
            }
            ownerConfig = api.getVmConfig(ownerNode, owner);
        } catch (Exception e) {
            logger.warn("Could not inspect PVE VM {} (the owner named by volid {}); assuming the volume is free: {}", owner, volid, e.getMessage());
            return;
        }
        String activeKey = findDiskKey(ownerConfig, volid, ACTIVE_DISK_KEY);
        if (activeKey != null) {
            throw new CloudRuntimeException(String.format("Volume %s is still attached as %s of PVE VM %d; detach it there before attaching it elsewhere", volid, activeKey, owner));
        }
        String unusedKey = findDiskKey(ownerConfig, volid, UNUSED_DISK_KEY);
        if (unusedKey != null) {
            throw new CloudRuntimeException(String.format(
                    "Volume %s is still parked as %s in the config of PVE VM %d and would be destroyed together with that VM; move it out of that config (e.g. qm disk move --target-vmid) before attaching it to another instance",
                    volid, unusedKey, owner));
        }
    }

    @Override
    public Answer dettachVolume(DettachCommand cmd) {
        DiskTO disk = cmd.getDisk();
        VolumeObjectTO volume = (VolumeObjectTO) disk.getData();
        try {
            int vmid = requireVmid(cmd.getVmName());
            String volid = volume.getPath();
            if (volid == null || volid.isEmpty()) {
                return new DettachAnswer("Volume " + volume.getUuid() + " has no PVE volid path");
            }
            ProxmoxApiClient api = resource.getApiClient();
            String node = api.findNodeOfVm(vmid);
            if (node == null) {
                logger.info("PVE VM {} no longer exists; treating detach of volume {} as already done", vmid, volid);
                return new DettachAnswer(disk);
            }

            JsonObject config = api.getVmConfig(node, vmid);
            String diskKey = findDiskKey(config, volid, ACTIVE_DISK_KEY);
            if (diskKey != null) {
                api.unlinkDisk(node, vmid, diskKey, false);
                config = api.getVmConfig(node, vmid);
                if (findDiskKey(config, volid, ACTIVE_DISK_KEY) != null) {
                    // e.g. hotplug disabled on a running VM: the unlink is queued as a pending
                    // change and the guest still uses the disk; do not report a successful detach.
                    return new DettachAnswer(String.format("PVE VM %d did not release %s (%s); the change may be pending until the instance is stopped",
                            vmid, diskKey, volid));
                }
            }

            // De-referencing a volume the VM owns parks it as an unusedN entry. That entry must
            // never be deleted through the PVE API while the volume exists ("unlink of unused[n]
            // always cause physical removal"), and a volume left parked is destroyed together
            // with its VM. Instead, hand the volume back to the reserved template-holder vmid by
            // renaming it, and report the new volid for CloudStack to persist ("volumePath").
            DettachAnswer answer = new DettachAnswer(disk);
            String unusedKey = findDiskKey(config, volid, UNUSED_DISK_KEY);
            if (unusedKey != null) {
                String storageType = storageTypeOfVolid(volid);
                if (isRawBlockStorageType(storageType) && !TYPE_RBD.equals(storageType)) {
                    logger.warn("Volume {} stays parked as {} of PVE VM {}: the Proxmox plugin cannot reassign ownership on storage type '{}' yet;"
                            + " the volume can only be re-attached to the same instance and is destroyed if that PVE VM is deleted",
                            volid, unusedKey, vmid, storageType);
                } else {
                    String newVolid = renameVolumeOwner(node, volid, templateHolderVmid(), volume.getUuid());
                    dropDanglingUnusedEntry(node, vmid, unusedKey, volid);
                    volume.setPath(newVolid);
                    disk.setPath(newVolid);
                    answer.setContextParam("volumePath", newVolid);
                }
            } else if (diskKey == null) {
                logger.info("Volume {} is not referenced by PVE VM {}; treating detach as already done", volid, vmid);
            }
            logger.debug("Detached volume {} from VM {} (vmid {})", volid, cmd.getVmName(), vmid);
            return answer;
        } catch (Exception e) {
            logger.error("Failed to detach volume {} from VM {}: {}", volume.getPath(), cmd.getVmName(), e.getMessage(), e);
            return new DettachAnswer(errorToString(e));
        }
    }

    /**
     * Best-effort removal of an unusedN entry whose volume has already been renamed away. PVE
     * couples deleting an unused entry with freeing the volume it points to; since the recorded
     * volid no longer resolves, this either just drops the entry or fails without touching any
     * data, in which case the dangling entry stays behind (harmless, but logged).
     */
    private void dropDanglingUnusedEntry(String node, int vmid, String unusedKey, String oldVolid) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("delete", unusedKey);
            resource.getApiClient().setVmConfig(node, vmid, params);
        } catch (Exception e) {
            logger.info("Could not remove the dangling config entry {} ({}) of PVE VM {}: {}", unusedKey, oldVolid, vmid, e.getMessage());
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
            String fileName = pveVolumeNameFor("disk", holderVmid, uuid, storage);

            String volid = resource.getApiClient().allocDiskImage(node, storage, holderVmid, fileName, size, allocFormatFor(storage));

            VolumeObjectTO newVolume = new VolumeObjectTO();
            newVolume.setPath(volid);
            newVolume.setSize(size);
            newVolume.setFormat(isRawBlockStorage(storage) ? ImageFormat.RAW : ImageFormat.QCOW2);
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
                    int vmid = requireVmid(vmName);
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

            String storageType = storageTypeOfVolid(volid);
            String snapshotName = UUID.randomUUID().toString();
            String volPath = resource.getApiClient().getVolumePath(node, volid);
            if (TYPE_RBD.equals(storageType)) {
                RbdPathInfo rbd = parseRbdPath(volPath);
                executeOrFail(buildRbdCliCommand(rbd, String.format("snap create '%s/%s@%s'", rbd.pool, rbd.image, validShellToken(snapshotName, "snapshot name"))),
                        DEFAULT_SSH_TIMEOUT_SEC);
            } else if (isRawBlockStorageType(storageType)) {
                return new CreateObjectAnswer(String.format("Volume snapshots on PVE storage type '%s' are not supported by the Proxmox plugin yet", storageType));
            } else {
                executeOrFail(String.format("qemu-img snapshot -c %s %s", quoted(snapshotName), quoted(volPath)), DEFAULT_SSH_TIMEOUT_SEC);
            }

            SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
            newSnapshot.setPath(volid + "@" + snapshotName);
            logger.debug("Created {} snapshot {} on volume {}", TYPE_RBD.equals(storageType) ? "rbd" : "qcow2 internal", snapshotName, volid);
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
                        dropVolumeReferencesForDelete(ownerNode, owner, volid);
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
                srcVirtualSize = getVirtualSize(srcPath);
                convertSourceArgs = quoted(srcPath);
            } else if (srcSnapshot.getPath() != null && srcSnapshot.getPath().contains("@")) {
                // snapshot still on primary storage: <volid>@<snapshotName>
                Pair<String, String> volidAndSnap = parseSnapshotPath(srcSnapshot.getPath());
                String volid = volidAndSnap.first();
                String snapshotName = volidAndSnap.second();
                String volPath = resource.getApiClient().getVolumePath(node, volid);
                String storageType = storageTypeOfVolid(volid);
                if (TYPE_RBD.equals(storageType)) {
                    String snapPath = rbdPathWithSnapshot(volPath, snapshotName);
                    srcVirtualSize = getVirtualSize(snapPath);
                    convertSourceArgs = "-f raw " + quoted(snapPath);
                } else if (isRawBlockStorageType(storageType)) {
                    return new CopyCmdAnswer(String.format("Volume snapshots on PVE storage type '%s' are not supported by the Proxmox plugin yet", storageType));
                } else {
                    srcVirtualSize = getVirtualSize(volPath);
                    convertSourceArgs = String.format("-f qcow2 -l %s %s", quoted("snapshot.name=" + snapshotName), quoted(volPath));
                }
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
            String storageType = storageTypeOfVolid(volid);
            String deleteCommand;
            if (TYPE_RBD.equals(storageType)) {
                RbdPathInfo rbd = parseRbdPath(volPath);
                deleteCommand = buildRbdCliCommand(rbd, String.format("snap rm '%s/%s@%s'", rbd.pool, rbd.image, validShellToken(snapshotName, "snapshot name")));
            } else if (isRawBlockStorageType(storageType)) {
                return new Answer(cmd, false, String.format("Volume snapshots on PVE storage type '%s' are not supported by the Proxmox plugin yet; cannot delete snapshot %s", storageType, path));
            } else {
                deleteCommand = String.format("qemu-img snapshot -d %s %s", quoted(snapshotName), quoted(volPath));
            }
            Pair<Boolean, String> result = resource.executeOnNode(deleteCommand, DEFAULT_SSH_TIMEOUT_SEC);
            if (result != null && Boolean.TRUE.equals(result.first())) {
                logger.debug("Deleted snapshot {} on volume {}", snapshotName, volid);
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
     * Allocates a new disk image on the destination primary storage (qcow2 on file storages, RAW
     * on raw block storages) and fills it with {@code qemu-img convert} from the given (already
     * quoted/validated) source arguments. Raw block volumes are allocated at their final size and
     * converted in place ({@code -n}); qcow2 files are grown afterwards when the requested volume
     * size exceeds the source size.
     */
    private VolumeObjectTO convertToNewPrimaryDisk(String node, String convertSourceArgs, long srcVirtualSize,
            VolumeObjectTO destVolume, PrimaryDataStoreTO destStore) {
        String storage = resource.getPveStorageId(destStore);
        verifyStorageSupportsImages(node, storage);
        boolean rawDest = isRawBlockStorage(storage);

        long requestedSize = destVolume.getSize() != null ? destVolume.getSize() : 0L;
        long size = Math.max(srcVirtualSize, requestedSize);
        if (size <= 0) {
            throw new CloudRuntimeException("Cannot determine a positive size for new volume " + destVolume.getUuid());
        }

        int vmid = vmidForInstance(destVolume.getVmName(), templateHolderVmid());
        String uuid = destVolume.getUuid() != null ? destVolume.getUuid() : UUID.randomUUID().toString();
        String fileName = pveVolumeNameFor("disk", vmid, uuid, storage);

        ProxmoxApiClient api = resource.getApiClient();
        String volid = api.allocDiskImage(node, storage, vmid, fileName, size, allocFormatFor(storage));
        try {
            String destPath = api.getVolumePath(node, volid);
            convertIntoPrimaryVolume(convertSourceArgs, destPath, rawDest);
            if (!rawDest && requestedSize > srcVirtualSize) {
                executeOrFail(String.format("qemu-img resize %s %d", quoted(destPath), requestedSize), DEFAULT_SSH_TIMEOUT_SEC);
            }
        } catch (RuntimeException e) {
            tryFreeVolume(node, volid);
            throw e;
        }

        VolumeObjectTO newVolume = new VolumeObjectTO();
        newVolume.setPath(volid);
        newVolume.setSize(size);
        newVolume.setFormat(rawDest ? ImageFormat.RAW : ImageFormat.QCOW2);
        return newVolume;
    }

    /**
     * Runs the {@code qemu-img convert} that fills a freshly allocated primary-storage volume.
     * Raw block destinations (rbd/lvmthin/zfspool) are written in place with {@code -n} (no
     * create): the destination was pre-allocated at its final size and, for RBD, the destination
     * path is a {@code rbd:<pool>/<image>:...} URI that qemu-img writes to directly. File
     * destinations keep the qcow2 behavior (convert recreates the file).
     */
    private void convertIntoPrimaryVolume(String convertSourceArgs, String destPath, boolean rawDest) {
        if (rawDest) {
            executeOrFail(String.format("qemu-img convert -n -O raw %s %s", convertSourceArgs, quoted(destPath)), CONVERT_TIMEOUT_SEC);
        } else {
            executeOrFail(String.format("qemu-img convert -O qcow2 %s %s", convertSourceArgs, quoted(destPath)), CONVERT_TIMEOUT_SEC);
        }
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

        long virtualSize = getVirtualSize(destPath);
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
     * Renames a volume to a new owner vmid (PVE derives volume ownership from the
     * {@code vm-<vmid>-} name prefix). Used on detach to hand volumes back to the reserved
     * template-holder vmid so they no longer share the lifecycle of the VM they were created
     * for. On file-based (dir/NFS/CephFS) storages the backing file is moved; on RBD the image
     * is renamed with the rbd CLI. Other raw block storages (lvmthin/zfspool) are not supported.
     *
     * @return the new volid
     */
    private String renameVolumeOwner(String node, String volid, int newOwnerVmid, String volumeUuid) {
        ProxmoxApiClient api = resource.getApiClient();
        String storageId = storageOfVolid(volid);
        String storageType = resource.getStorageType(storageId);
        String uuid = volumeUuid != null ? volumeUuid : UUID.randomUUID().toString();
        String srcPath = api.getVolumePath(node, volid);
        validateRemotePath(srcPath);

        String newVolid;
        if (TYPE_RBD.equals(storageType)) {
            RbdPathInfo rbd = parseRbdPath(srcPath);
            String newImageName = String.format("vm-%d-disk-%s", newOwnerVmid, shortId(uuid));
            executeOrFail(buildRbdCliCommand(rbd, String.format("rename '%s/%s' '%s/%s'", rbd.pool, rbd.image, rbd.pool, newImageName)),
                    DEFAULT_SSH_TIMEOUT_SEC);
            newVolid = storageId + ":" + newImageName;
        } else if (isRawBlockStorageType(storageType)) {
            throw new CloudRuntimeException(String.format("Cannot reassign owner of volume %s: ownership reassignment on PVE storage type '%s' is not supported by the Proxmox plugin yet (supported: dir/NFS/CephFS and RBD)", volid, storageType));
        } else {
            int owner = ownerOfVolid(volid);
            String ownerMarker = "/images/" + owner + "/";
            int markerIndex = srcPath.indexOf(ownerMarker);
            if (markerIndex < 0) {
                throw new CloudRuntimeException(String.format("Cannot reassign owner of volume %s (path %s): unexpected layout for a file-based PVE storage", volid, srcPath));
            }
            String storageMount = srcPath.substring(0, markerIndex);
            String newFileName = String.format("vm-%d-disk-%s.qcow2", newOwnerVmid, uuid);
            String destDir = storageMount + "/images/" + newOwnerVmid;
            String destPath = destDir + "/" + newFileName;

            executeOrFail(String.format("mkdir -p %s && mv %s %s", quoted(destDir), quoted(srcPath), quoted(destPath)), DEFAULT_SSH_TIMEOUT_SEC);
            newVolid = storageId + ":" + newOwnerVmid + "/" + newFileName;
        }
        logger.debug("Renamed volume {} to owner vmid {}; new volid {}", volid, newOwnerVmid, newVolid);
        return newVolid;
    }

    /**
     * Drops the config references of a volume that is about to be deleted. Unlinking the active
     * entry parks an owned volume as unusedN, and deleting that unusedN entry makes PVE free the
     * volume right there ("unlink of unused[n] always cause physical removal") — which is exactly
     * what the caller wants here, so the following storage-level free may 404. Must ONLY be used
     * on the volume-deletion path; detach uses {@link #dettachVolume} semantics that preserve the
     * volume.
     *
     * @return true if any reference was found and removed
     */
    private boolean dropVolumeReferencesForDelete(String node, int vmid, String volid) {
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
        // Prefer shared storages (CephFS/NFS): an ISO on a node-local storage pins the VM to
        // that node — PVE refuses to live-migrate a VM whose cdrom volume the target can't see.
        String localFallback = null;
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
            if (storage.has("shared") && storage.get("shared").getAsInt() == 1) {
                return storage.get("storage").getAsString();
            }
            if (localFallback == null) {
                localFallback = storage.get("storage").getAsString();
            }
        }
        return localFallback;
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

    private long getVirtualSize(String filePath) {
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

    /**
     * Resolves the PVE vmid of an instance by cluster inventory lookup (which also covers
     * imported VMs that keep their original vmid), failing when the VM cannot be found.
     */
    private int requireVmid(String vmName) {
        Integer vmid = resource.findVmid(vmName);
        if (vmid == null) {
            throw new CloudRuntimeException("VM " + vmName + " not found in the Proxmox cluster");
        }
        return vmid;
    }

    private int vmidForInstance(String vmName, int fallback) {
        if (vmName == null || vmName.isEmpty()) {
            return fallback;
        }
        try {
            Integer vmid = resource.findVmid(vmName);
            if (vmid != null && vmid > 0) {
                return vmid;
            }
        } catch (Exception e) {
            logger.debug("Could not resolve the PVE vmid of instance {}: {}", vmName, e.getMessage());
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

    // ---------------------------------------------------------------------
    // Storage-type helpers: file-based storages (dir/nfs/cephfs) hold qcow2
    // files, raw block storages (rbd/lvmthin/zfspool) hold RAW images.
    // ---------------------------------------------------------------------

    /** True for PVE storage types whose images are RAW block devices/objects rather than qcow2 files. */
    public static boolean isRawBlockStorageType(String type) {
        return TYPE_RBD.equals(type) || "lvmthin".equals(type) || "zfspool".equals(type);
    }

    private boolean isRawBlockStorage(String storageId) {
        return isRawBlockStorageType(resource.getStorageType(storageId));
    }

    private String storageTypeOfVolid(String volid) {
        return resource.getStorageType(storageOfVolid(volid));
    }

    /** PVE allocation format for new images on the given storage: raw on raw block storages, qcow2 on file storages. */
    private String allocFormatFor(String storageId) {
        return isRawBlockStorage(storageId) ? FORMAT_RAW : FORMAT_QCOW2;
    }

    /**
     * PVE volume name for a new image owned by the given vmid: raw block storages take
     * extension-less names with a short unique suffix ({@code vm-<vmid>-<kind>-<8charuuid>}),
     * file storages take full-uuid qcow2 file names.
     */
    private String pveVolumeNameFor(String kind, int vmid, String uuid, String storageId) {
        if (isRawBlockStorage(storageId)) {
            return String.format("vm-%d-%s-%s", vmid, kind, shortId(uuid));
        }
        return String.format("vm-%d-%s-%s.qcow2", vmid, kind, uuid);
    }

    private String shortId(String uuid) {
        String cleaned = uuid != null ? uuid.replaceAll("[^a-zA-Z0-9]", "") : "";
        if (cleaned.isEmpty()) {
            cleaned = Long.toHexString(System.nanoTime());
        }
        return cleaned.length() > 8 ? cleaned.substring(0, 8) : cleaned;
    }

    /**
     * qemu-img source arguments for reading a primary-storage volume: raw block volumes get an
     * explicit {@code -f raw}, file-based volumes let qemu-img probe the (qcow2) format.
     */
    private String convertSourceArgsFor(String volid, String volPath) {
        if (isRawBlockStorage(storageOfVolid(volid))) {
            return "-f raw " + quoted(volPath);
        }
        return quoted(volPath);
    }

    /**
     * qemu-img source arguments for reading a snapshot of a primary-storage volume: qcow2
     * internal snapshots via {@code -l snapshot.name=} (the {@code -s} spelling was removed from
     * qemu-img convert), rbd snapshots via the {@code @snap} path suffix.
     * Snapshots on other raw block storages are not supported.
     */
    private String snapshotConvertSourceArgs(String node, String volid, String snapshotName) {
        String volPath = resource.getApiClient().getVolumePath(node, volid);
        String storageType = storageTypeOfVolid(volid);
        if (TYPE_RBD.equals(storageType)) {
            return "-f raw " + quoted(rbdPathWithSnapshot(volPath, snapshotName));
        }
        if (isRawBlockStorageType(storageType)) {
            throw new CloudRuntimeException(String.format("Volume snapshots on PVE storage type '%s' are not supported by the Proxmox plugin yet", storageType));
        }
        return String.format("-f qcow2 -l %s %s", quoted("snapshot.name=" + snapshotName), quoted(volPath));
    }

    // ---------------------------------------------------------------------
    // Ceph RBD helpers. `pvesm path`/getVolumePath on rbd storages returns a
    // qemu-usable URI: rbd:<pool>/<image>[:key=value[:key=value...]] whose
    // option segments embed the cluster auth (conf=, id=, keyring=); ':' in
    // option values is escaped as '\:'. qemu-img consumes the URI directly;
    // the rbd CLI needs pool/image and auth flags parsed back out of it.
    // ---------------------------------------------------------------------

    /** Parsed form of a PVE rbd path URI. */
    static final class RbdPathInfo {
        final String pool;
        final String image;
        final String conf;     // ceph.conf path, nullable
        final String id;       // cephx user (without the "client." prefix), nullable
        final String keyring;  // keyring path, nullable

        RbdPathInfo(String pool, String image, String conf, String id, String keyring) {
            this.pool = pool;
            this.image = image;
            this.conf = conf;
            this.id = id;
            this.keyring = keyring;
        }
    }

    static RbdPathInfo parseRbdPath(String rbdPath) {
        if (rbdPath == null || !rbdPath.startsWith("rbd:")) {
            throw new CloudRuntimeException("Not a PVE rbd path URI: " + rbdPath);
        }
        String[] segments = rbdPath.split("(?<!\\\\):");
        if (segments.length < 2 || segments[1].indexOf('/') <= 0) {
            throw new CloudRuntimeException("Malformed PVE rbd path URI (expected rbd:<pool>/<image>[:options]): " + rbdPath);
        }
        String poolAndImage = segments[1];
        int slash = poolAndImage.indexOf('/');
        String pool = validShellToken(poolAndImage.substring(0, slash), "rbd pool name");
        String image = validShellToken(poolAndImage.substring(slash + 1), "rbd image name");
        String conf = null;
        String id = null;
        String keyring = null;
        for (int i = 2; i < segments.length; i++) {
            int eq = segments[i].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = segments[i].substring(0, eq);
            String value = segments[i].substring(eq + 1).replace("\\:", ":");
            if ("conf".equals(key)) {
                conf = validShellToken(value, "ceph conf path");
            } else if ("id".equals(key)) {
                id = validShellToken(value, "cephx id");
            } else if ("keyring".equals(key)) {
                keyring = validShellToken(value, "ceph keyring path");
            }
        }
        return new RbdPathInfo(pool, image, conf, id, keyring);
    }

    /**
     * Inserts the {@code @<snapshot>} suffix into an rbd path URI right after the
     * {@code <pool>/<image>} segment (before the first unescaped ':' option separator) so that
     * qemu-img reads the snapshot instead of the live image.
     */
    static String rbdPathWithSnapshot(String rbdPath, String snapshotName) {
        if (rbdPath == null || !rbdPath.startsWith("rbd:")) {
            throw new CloudRuntimeException("Not a PVE rbd path URI: " + rbdPath);
        }
        validShellToken(snapshotName, "snapshot name");
        for (int i = 4; i < rbdPath.length(); i++) {
            if (rbdPath.charAt(i) == ':' && rbdPath.charAt(i - 1) != '\\') {
                return rbdPath.substring(0, i) + "@" + snapshotName + rbdPath.substring(i);
            }
        }
        return rbdPath + "@" + snapshotName;
    }

    /**
     * Builds an SSH command that runs the rbd CLI with the cluster auth carried by the parsed
     * {@code pvesm path} URI: {@code -c <conf>} / {@code --keyring <keyring>} are added only when
     * those files exist on the node (hyper-converged and external Ceph keep them in different
     * places), falling back to the node defaults (/etc/ceph/ceph.conf) otherwise. All tokens
     * expanded into the command are validated against {@link #SAFE_SHELL_TOKEN}.
     */
    static String buildRbdCliCommand(RbdPathInfo rbd, String args) {
        StringBuilder cmd = new StringBuilder("RBDAUTH=; ");
        if (rbd.conf != null) {
            cmd.append(String.format("[ -f '%s' ] && RBDAUTH=\"$RBDAUTH -c %s\"; ", rbd.conf, rbd.conf));
        }
        if (rbd.keyring != null) {
            cmd.append(String.format("[ -f '%s' ] && RBDAUTH=\"$RBDAUTH --keyring %s\"; ", rbd.keyring, rbd.keyring));
        }
        if (rbd.id != null) {
            cmd.append(String.format("RBDAUTH=\"$RBDAUTH -n client.%s\"; ", rbd.id));
        }
        cmd.append("rbd $RBDAUTH ").append(args);
        return cmd.toString();
    }

    /**
     * Builds the SSH command that resizes an RBD image, given the volume's {@code pvesm path}
     * rbd URI, to the requested size (rounded up to whole MiB, the rbd CLI unit). Used by
     * {@link ProxmoxResource} for resizing detached rbd volumes.
     */
    public static String buildRbdResizeCommand(String rbdPath, long newSizeBytes) {
        RbdPathInfo rbd = parseRbdPath(rbdPath);
        long sizeMib = (newSizeBytes + (1L << 20) - 1) >> 20;
        return buildRbdCliCommand(rbd, String.format("resize --size %dM '%s/%s'", sizeMib, rbd.pool, rbd.image));
    }

    /** Rejects values that could break out of the remote shell commands they are embedded in. */
    private static String validShellToken(String value, String what) {
        if (value == null || !SAFE_SHELL_TOKEN.matcher(value).matches()) {
            throw new CloudRuntimeException("Refusing to use " + what + " containing unsafe characters in a remote command: " + value);
        }
        return value;
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
