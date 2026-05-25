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
package com.cloud.server;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.command.admin.systemvm.DestroySystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.RebootSystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.StopSystemVmCmd;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.storage.secondary.SecondaryStorageVmManager;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.ConsoleProxyVO;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.ConsoleProxyDao;
import com.cloud.vm.dao.SecondaryStorageVmDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * @see SystemVmLifecycleService
 */
@Component
public class SystemVmLifecycleServiceImpl implements SystemVmLifecycleService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private ConsoleProxyDao consoleProxyDao;
    @Inject
    private SecondaryStorageVmDao secStorageVmDao;
    @Inject
    private ConsoleProxyManager consoleProxyManager;
    @Inject
    private SecondaryStorageVmManager secStorageVmManager;
    @Inject
    private VirtualMachineManager itManager;
    @Inject
    private VolumeDataStoreDao volumeStoreDao;
    @Inject
    private ImageStoreDao imgStoreDao;
    @Inject
    private TemplateDataStoreDao vmTemplateStoreDao;

    @Override
    public VirtualMachine.Type findSystemVMTypeById(final long instanceId) {
        final VMInstanceVO systemVm = vmInstanceDao.findByIdTypes(instanceId,
                VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);
        if (systemVm == null) {
            final InvalidParameterValueException ex =
                    new InvalidParameterValueException("Unable to find a system vm of specified instanceId");
            ex.addProxyObject(String.valueOf(instanceId), "instanceId");
            throw ex;
        }
        return systemVm.getType();
    }

    @Override
    public VirtualMachine startSystemVM(final long vmId) {
        final VMInstanceVO systemVm = vmInstanceDao.findByIdTypes(vmId,
                VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);
        if (systemVm == null) {
            final InvalidParameterValueException ex =
                    new InvalidParameterValueException("unable to find a system vm with specified vmId");
            ex.addProxyObject(String.valueOf(vmId), "vmId");
            throw ex;
        }

        if (systemVm.getType() == VirtualMachine.Type.ConsoleProxy) {
            ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_PROXY_START,
                    "starting console proxy Vm", vmId,
                    ApiCommandResourceType.ConsoleProxy.toString());
            return startConsoleProxy(vmId);
        } else if (systemVm.getType() == VirtualMachine.Type.SecondaryStorageVm) {
            ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_SSVM_START,
                    "starting secondary storage Vm", vmId,
                    ApiCommandResourceType.SystemVm.toString());
            return startSecondaryStorageVm(vmId);
        } else {
            final InvalidParameterValueException ex =
                    new InvalidParameterValueException("Unable to find a system vm with specified vmId");
            ex.addProxyObject(systemVm.getUuid(), "vmId");
            throw ex;
        }
    }

    @Override
    public VMInstanceVO stopSystemVM(final StopSystemVmCmd cmd)
            throws ResourceUnavailableException, ConcurrentOperationException {
        final Long id = cmd.getId();

        // verify parameters
        final VMInstanceVO systemVm = vmInstanceDao.findByIdTypes(id,
                VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);
        if (systemVm == null) {
            final InvalidParameterValueException ex =
                    new InvalidParameterValueException("unable to find a system vm with specified vmId");
            ex.addProxyObject(id.toString(), "vmId");
            throw ex;
        }

        try {
            if (systemVm.getType() == VirtualMachine.Type.ConsoleProxy) {
                ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_PROXY_STOP,
                        "stopping console proxy VM", systemVm.getId(),
                        ApiCommandResourceType.ConsoleProxy.toString());
                return stopConsoleProxy(systemVm, cmd.isForced());
            } else if (systemVm.getType() == VirtualMachine.Type.SecondaryStorageVm) {
                ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_SSVM_STOP,
                        "stopping secondary storage VM", systemVm.getId(),
                        ApiCommandResourceType.SystemVm.toString());
                return stopSecondaryStorageVm(systemVm, cmd.isForced());
            }
            return null;
        } catch (final OperationTimedoutException e) {
            throw new CloudRuntimeException("Unable to stop " + systemVm, e);
        }
    }

    @Override
    public VMInstanceVO rebootSystemVM(final RebootSystemVmCmd cmd) {
        final VMInstanceVO systemVm = vmInstanceDao.findByIdTypes(cmd.getId(),
                VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);

        if (systemVm == null) {
            final InvalidParameterValueException ex =
                    new InvalidParameterValueException("unable to find a system VM with specified vmId");
            ex.addProxyObject(cmd.getId().toString(), "vmId");
            throw ex;
        }

        try {
            if (systemVm.getType().equals(VirtualMachine.Type.ConsoleProxy)) {
                ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_PROXY_REBOOT,
                        "rebooting console proxy VM", systemVm.getId(),
                        ApiCommandResourceType.ConsoleProxy.toString());
                if (cmd.isForced()) {
                    return forceRebootConsoleProxy(systemVm);
                }
                return rebootConsoleProxy(cmd.getId());
            } else {
                ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_SSVM_REBOOT,
                        "rebooting secondary storage VM", systemVm.getId(),
                        ApiCommandResourceType.SystemVm.toString());
                if (cmd.isForced()) {
                    return forceRebootSecondaryStorageVm(systemVm);
                }
                return rebootSecondaryStorageVm(cmd.getId());
            }
        } catch (final ResourceUnavailableException e) {
            throw new CloudRuntimeException("Unable to reboot " + systemVm, e);
        } catch (final OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation timed out - Unable to reboot " + systemVm, e);
        }
    }

    @Override
    public VMInstanceVO destroySystemVM(final DestroySystemVmCmd cmd) {
        final VMInstanceVO systemVm = vmInstanceDao.findByIdTypes(cmd.getId(),
                VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);

        if (systemVm == null) {
            final InvalidParameterValueException ex =
                    new InvalidParameterValueException("unable to find a system VM with specified vmId");
            ex.addProxyObject(cmd.getId().toString(), "vmId");
            throw ex;
        }

        if (systemVm.getType().equals(VirtualMachine.Type.ConsoleProxy)) {
            ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_PROXY_DESTROY,
                    "destroying console proxy VM", systemVm.getId(),
                    ApiCommandResourceType.ConsoleProxy.toString());
            return destroyConsoleProxy(cmd.getId());
        } else {
            ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_SSVM_DESTROY,
                    "destroying secondary storage VM", systemVm.getId(),
                    ApiCommandResourceType.SystemVm.toString());
            return destroySecondaryStorageVm(cmd.getId());
        }
    }

    // --- console-proxy helpers ------------------------------------------------

    /**
     * Defer to {@link ConsoleProxyManager#startProxy} for the CPVM identified by
     * {@code instanceId}, asking it to bring the proxy up unconditionally. Kept
     * package-private so the focused unit tests can stub the manager response.
     */
    ConsoleProxyVO startConsoleProxy(final long instanceId) {
        return consoleProxyManager.startProxy(instanceId, true);
    }

    /**
     * Cleanly stop the CPVM via the virtual-machine manager and return the
     * persisted {@link ConsoleProxyVO} record. {@code isForced} is propagated
     * verbatim so {@link VirtualMachineManager#advanceStop} can power-off an
     * unresponsive proxy without a clean shutdown when needed.
     */
    ConsoleProxyVO stopConsoleProxy(final VMInstanceVO systemVm, final boolean isForced)
            throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        itManager.advanceStop(systemVm.getUuid(), isForced);
        return consoleProxyDao.findById(systemVm.getId());
    }

    /**
     * Trigger the warm reboot path on the {@link ConsoleProxyManager} and
     * return the (potentially refreshed) {@link ConsoleProxyVO} record so
     * callers can observe state transitions.
     */
    ConsoleProxyVO rebootConsoleProxy(final long instanceId) {
        consoleProxyManager.rebootProxy(instanceId);
        return consoleProxyDao.findById(instanceId);
    }

    /**
     * Forced CPVM reboot — stop the proxy via the virtual-machine manager
     * without waiting for a clean shutdown, then start it again via the
     * {@link ConsoleProxyManager}. Returns the freshly-started
     * {@link ConsoleProxyVO}.
     */
    ConsoleProxyVO forceRebootConsoleProxy(final VMInstanceVO systemVm)
            throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        itManager.advanceStop(systemVm.getUuid(), false);
        return consoleProxyManager.startProxy(systemVm.getId(), true);
    }

    /**
     * Destroy the CPVM via the {@link ConsoleProxyManager}. Returns the
     * pre-destroy {@link ConsoleProxyVO} on success, or {@code null} when the
     * manager refuses (e.g. proxy is no longer present or already cleaned up).
     */
    ConsoleProxyVO destroyConsoleProxy(final long instanceId) {
        final ConsoleProxyVO proxy = consoleProxyDao.findById(instanceId);

        if (consoleProxyManager.destroyProxy(instanceId)) {
            return proxy;
        }
        return null;
    }

    // --- secondary-storage-VM helpers ----------------------------------------

    /**
     * Defer to {@link SecondaryStorageVmManager#startSecStorageVm} for the
     * SSVM identified by {@code instanceId}.
     */
    SecondaryStorageVmVO startSecondaryStorageVm(final long instanceId) {
        return secStorageVmManager.startSecStorageVm(instanceId);
    }

    /**
     * Cleanly stop the SSVM via the virtual-machine manager and return the
     * persisted {@link SecondaryStorageVmVO} record. {@code isForced} is
     * propagated to {@link VirtualMachineManager#advanceStop}.
     */
    SecondaryStorageVmVO stopSecondaryStorageVm(final VMInstanceVO systemVm, final boolean isForced)
            throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        itManager.advanceStop(systemVm.getUuid(), isForced);
        return secStorageVmDao.findById(systemVm.getId());
    }

    /**
     * Trigger the warm reboot path on the {@link SecondaryStorageVmManager}
     * and return the (potentially refreshed) {@link SecondaryStorageVmVO}
     * record so callers can observe state transitions.
     */
    public SecondaryStorageVmVO rebootSecondaryStorageVm(final long instanceId) {
        secStorageVmManager.rebootSecStorageVm(instanceId);
        return secStorageVmDao.findById(instanceId);
    }

    /**
     * Forced SSVM reboot — stop the SSVM via the virtual-machine manager
     * without waiting for a clean shutdown, then start it again via the
     * {@link SecondaryStorageVmManager}. Returns the freshly-started
     * {@link SecondaryStorageVmVO}.
     */
    SecondaryStorageVmVO forceRebootSecondaryStorageVm(final VMInstanceVO systemVm)
            throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        itManager.advanceStop(systemVm.getUuid(), false);
        return secStorageVmManager.startSecStorageVm(systemVm.getId());
    }

    /**
     * Destroy the SSVM. Before delegating to
     * {@link SecondaryStorageVmManager#destroySecStorageVm}, clear any volume
     * or template extract URLs recorded in the SSVM's zone so the next SSVM
     * does not serve stale download links. Returns the pre-destroy
     * {@link SecondaryStorageVmVO} on success, or {@code null} when the
     * manager refuses.
     */
    SecondaryStorageVmVO destroySecondaryStorageVm(final long instanceId) {
        final SecondaryStorageVmVO secStorageVm = secStorageVmDao.findById(instanceId);
        cleanupDownloadUrlsInZone(secStorageVm.getDataCenterId());
        if (secStorageVmManager.destroySecStorageVm(instanceId)) {
            return secStorageVm;
        }
        return null;
    }

    /**
     * Clear any volume-extract URLs and template-extract URLs recorded for
     * the supplied {@code zoneId}. Called from
     * {@link #destroySecondaryStorageVm} so the freshly-spawned SSVM
     * regenerates those download links rather than serving the ones the
     * previous SSVM published. Kept package-private so the focused unit
     * tests can verify the cleanup without driving a full SSVM destroy.
     */
    void cleanupDownloadUrlsInZone(final long zoneId) {
        // clean download URLs when destroying ssvm
        // clean only the volumes and templates of the zone to which ssvm belongs to
        for (VolumeDataStoreVO volume : volumeStoreDao.listVolumeDownloadUrlsByZoneId(zoneId)) {
            volume.setExtractUrl(null);
            volumeStoreDao.update(volume.getId(), volume);
        }
        for (ImageStoreVO imageStore : imgStoreDao.listStoresByZoneId(zoneId)) {
            for (TemplateDataStoreVO template : vmTemplateStoreDao.listTemplateDownloadUrlsByStoreId(imageStore.getId())) {
                template.setExtractUrl(null);
                template.setExtractUrlCreated(null);
                vmTemplateStoreDao.update(template.getId(), template);
            }
        }
    }
}
