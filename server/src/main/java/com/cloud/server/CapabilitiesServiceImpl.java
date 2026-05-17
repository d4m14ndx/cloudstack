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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.config.ListCapabilitiesCmd;
import org.apache.cloudstack.config.ApiServiceConfiguration;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.query.QueryService;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Config;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpn.Site2SiteVpnManagerImpl;
import com.cloud.projects.ProjectManager;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.snapshot.SnapshotManager;
import com.cloud.template.TemplateManager;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.vm.UserVmManager;

/**
 * @see CapabilitiesService
 */
@Component
public class CapabilitiesServiceImpl implements CapabilitiesService {

    @Inject
    protected ConfigurationDao configDao;
    @Inject
    protected DomainDao domainDao;
    @Inject
    protected AccountService accountService;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected ProjectManager projectManager;
    @Inject
    protected ImageStoreDao imgStoreDao;
    @Inject
    protected ExtensionsManager extensionsManager;

    @Override
    public Map<String, Object> listCapabilities(final ListCapabilitiesCmd cmd) {
        final Map<String, Object> capabilities = new HashMap<>();

        final Account caller = getCaller();
        Long domainId = cmd.getDomainId();
        if (domainId == null) {
            domainId = caller.getDomainId();
        } else {
            Domain domain = domainDao.findById(domainId);
            accountService.checkAccess(caller, domain);
        }

        final boolean isCallerRootAdmin = accountService.isRootAdmin(caller.getId());
        final boolean isCallerAdmin = isCallerRootAdmin || accountService.isAdmin(caller.getId());
        boolean securityGroupsEnabled = false;
        boolean elasticLoadBalancerEnabled;
        String supportELB = "false";
        final List<NetworkVO> networks = networkDao.listSecurityGroupEnabledNetworks();
        if (networks != null && !networks.isEmpty()) {
            securityGroupsEnabled = true;
            final String elbEnabled = configDao.getValue(Config.ElasticLoadBalancerEnabled.key());
            elasticLoadBalancerEnabled = elbEnabled == null ? false : Boolean.parseBoolean(elbEnabled);
            if (elasticLoadBalancerEnabled) {
                final String networkType = configDao.getValue(Config.ElasticLoadBalancerNetwork.key());
                if (networkType != null) {
                    supportELB = networkType;
                }
            }
        }

        final long diskOffMinSize = VolumeOrchestrationService.CustomDiskOfferingMinSize.value();
        final long diskOffMaxSize = VolumeOrchestrationService.CustomDiskOfferingMaxSize.value();
        final boolean KVMSnapshotEnabled = SnapshotManager.KVMSnapshotEnabled.value();
        final boolean SnapshotShowChainSize = SnapshotManager.snapshotShowChainSize.value();

        final boolean userPublicTemplateEnabled = TemplateManager.AllowPublicUserTemplates.valueIn(caller.getId());

        // add some parameters UI needs to handle API throttling
        final boolean apiLimitEnabled = Boolean.parseBoolean(configDao.getValue(Config.ApiLimitEnabled.key()));
        final Integer apiLimitInterval = Integer.valueOf(configDao.getValue(Config.ApiLimitInterval.key()));
        final Integer apiLimitMax = Integer.valueOf(configDao.getValue(Config.ApiLimitMax.key()));

        final boolean allowUserViewDestroyedVM = (QueryService.AllowUserViewDestroyedVM.valueIn(caller.getId()) | isCallerAdmin);
        final boolean allowUserExpungeRecoverVM = (UserVmManager.AllowUserExpungeRecoverVm.valueIn(caller.getId()) | isCallerAdmin);
        final boolean allowUserExpungeRecoverVolume = (VolumeApiServiceImpl.AllowUserExpungeRecoverVolume.valueIn(caller.getId()) | isCallerAdmin);
        final boolean allowUserForceStopVM = (UserVmManager.AllowUserForceStopVm.valueIn(caller.getId()) | isCallerAdmin);

        final boolean allowUserViewAllDomainAccounts = (QueryService.AllowUserViewAllDomainAccounts.valueIn(domainId));

        final boolean kubernetesServiceEnabled = Boolean.parseBoolean(configDao.getValue("cloud.kubernetes.service.enabled"));
        final boolean kubernetesClusterExperimentalFeaturesEnabled = Boolean.parseBoolean(configDao.getValue("cloud.kubernetes.cluster.experimental.features.enabled"));

        // check if region-wide secondary storage is used
        boolean regionSecondaryEnabled = false;
        final List<ImageStoreVO> imgStores = imgStoreDao.findRegionImageStores();
        if (imgStores != null && !imgStores.isEmpty()) {
            regionSecondaryEnabled = true;
        }

        final Integer fsVmMinCpu = Integer.parseInt(configDao.getValue("sharedfsvm.min.cpu.count"));
        final Integer fsVmMinRam = Integer.parseInt(configDao.getValue("sharedfsvm.min.ram.size"));
        if (ManagementServerImpl.exposeCloudStackVersionInApiListCapabilities.value()) {
            capabilities.put("cloudStackVersion", getVersion());
        }

        capabilities.put("securityGroupsEnabled", securityGroupsEnabled);
        capabilities.put("userPublicTemplateEnabled", userPublicTemplateEnabled);
        capabilities.put("supportELB", supportELB);
        capabilities.put("projectInviteRequired", projectManager.projectInviteRequired());
        capabilities.put("allowusercreateprojects", projectManager.allowUserToCreateProject());
        capabilities.put("customDiskOffMinSize", diskOffMinSize);
        capabilities.put("customDiskOffMaxSize", diskOffMaxSize);
        capabilities.put("regionSecondaryEnabled", regionSecondaryEnabled);
        capabilities.put("KVMSnapshotEnabled", KVMSnapshotEnabled);
        capabilities.put("SnapshotShowChainSize", SnapshotShowChainSize);
        capabilities.put("allowUserViewDestroyedVM", allowUserViewDestroyedVM);
        capabilities.put("allowUserExpungeRecoverVM", allowUserExpungeRecoverVM);
        capabilities.put("allowUserExpungeRecoverVolume", allowUserExpungeRecoverVolume);
        capabilities.put("allowUserViewAllDomainAccounts", allowUserViewAllDomainAccounts);
        capabilities.put(ApiConstants.ALLOW_USER_FORCE_STOP_VM, allowUserForceStopVM);
        capabilities.put("kubernetesServiceEnabled", kubernetesServiceEnabled);
        capabilities.put("kubernetesClusterExperimentalFeaturesEnabled", kubernetesClusterExperimentalFeaturesEnabled);
        capabilities.put("customHypervisorDisplayName", HypervisorGuru.HypervisorCustomDisplayName.value());
        capabilities.put(ApiServiceConfiguration.DefaultUIPageSize.key(), ApiServiceConfiguration.DefaultUIPageSize.value());
        capabilities.put(ApiConstants.INSTANCES_STATS_RETENTION_TIME, StatsCollector.vmStatsMaxRetentionTime.value());
        capabilities.put(ApiConstants.INSTANCES_STATS_USER_ONLY, StatsCollector.vmStatsCollectUserVMOnly.value());
        capabilities.put(ApiConstants.INSTANCES_DISKS_STATS_RETENTION_ENABLED, StatsCollector.vmDiskStatsRetentionEnabled.value());
        capabilities.put(ApiConstants.INSTANCES_DISKS_STATS_RETENTION_TIME, StatsCollector.vmDiskStatsMaxRetentionTime.value());
        capabilities.put(ApiConstants.INSTANCE_LEASE_ENABLED, VMLeaseManager.InstanceLeaseEnabled.value());
        capabilities.put(ApiConstants.DYNAMIC_SCALING_ENABLED, UserVmManager.EnableDynamicallyScaleVm.value());
        if (apiLimitEnabled) {
            capabilities.put("apiLimitInterval", apiLimitInterval);
            capabilities.put("apiLimitMax", apiLimitMax);
        }
        capabilities.put(ApiConstants.SHAREDFSVM_MIN_CPU_COUNT, fsVmMinCpu);
        capabilities.put(ApiConstants.SHAREDFSVM_MIN_RAM_SIZE, fsVmMinRam);
        if (isCallerRootAdmin) {
            capabilities.put(ApiConstants.EXTENSIONS_PATH, extensionsManager.getExtensionsPath());
        }
        capabilities.put(ApiConstants.ADDITONAL_CONFIG_ENABLED, UserVmManager.EnableAdditionalVmConfig.valueIn(caller.getId()));

        Map<String, Object> vpnParams = getVpnCustomerGatewayParameters(domainId);
        if (!vpnParams.isEmpty()) {
            capabilities.put(ApiConstants.VPN_CUSTOMER_GATEWAY_PARAMETERS, vpnParams);
        }

        return capabilities;
    }

    protected Map<String, Object> getVpnCustomerGatewayParameters(Long domainId) {
        Map<String, Object> vpnParams = new HashMap<>();

        String excludedEncryption = Site2SiteVpnManagerImpl.VpnCustomerGatewayExcludedEncryptionAlgorithms.valueIn(domainId);
        String excludedHashing = Site2SiteVpnManagerImpl.VpnCustomerGatewayExcludedHashingAlgorithms.valueIn(domainId);
        String excludedIkeVersions = Site2SiteVpnManagerImpl.VpnCustomerGatewayExcludedIkeVersions.valueIn(domainId);
        String excludedDhGroup = Site2SiteVpnManagerImpl.VpnCustomerGatewayExcludedDhGroup.valueIn(domainId);
        String obsoleteEncryption = Site2SiteVpnManagerImpl.VpnCustomerGatewayObsoleteEncryptionAlgorithms.valueIn(domainId);
        String obsoleteHashing = Site2SiteVpnManagerImpl.VpnCustomerGatewayObsoleteHashingAlgorithms.valueIn(domainId);
        String obsoleteIkeVersions = Site2SiteVpnManagerImpl.VpnCustomerGatewayObsoleteIkeVersions.valueIn(domainId);
        String obsoleteDhGroup = Site2SiteVpnManagerImpl.VpnCustomerGatewayObsoleteDhGroup.valueIn(domainId);

        if (!excludedEncryption.isEmpty()) {
            vpnParams.put("excludedencryptionalgorithms", excludedEncryption);
        }
        if (!obsoleteEncryption.isEmpty()) {
            vpnParams.put("obsoleteencryptionalgorithms", obsoleteEncryption);
        }
        if (!excludedHashing.isEmpty()) {
            vpnParams.put("excludedhashingalgorithms", excludedHashing);
        }
        if (!obsoleteHashing.isEmpty()) {
            vpnParams.put("obsoletehashingalgorithms", obsoleteHashing);
        }
        if (!excludedIkeVersions.isEmpty()) {
            vpnParams.put("excludedikeversions", excludedIkeVersions);
        }
        if (!obsoleteIkeVersions.isEmpty()) {
            vpnParams.put("obsoleteikeversions", obsoleteIkeVersions);
        }
        if (!excludedDhGroup.isEmpty()) {
            vpnParams.put("excludeddhgroups", excludedDhGroup);
        }
        if (!obsoleteDhGroup.isEmpty()) {
            vpnParams.put("obsoletedhgroups", obsoleteDhGroup);
        }
        return vpnParams;
    }

    @Override
    public String getVersion() {
        final Class<?> c = ManagementServer.class;
        final String fullVersion = c.getPackage().getImplementationVersion();
        if (fullVersion != null && !fullVersion.isEmpty()) {
            return fullVersion;
        }

        return "unknown";
    }

    protected Account getCaller() {
        return CallContext.current().getCallingAccount();
    }
}
