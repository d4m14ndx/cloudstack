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

import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.ApiKeyPairVO;
import com.cloud.api.query.MutualExclusiveIdsManagerBase;
import org.apache.cloudstack.affinity.AffinityGroupProcessor;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.account.CreateAccountCmd;
import org.apache.cloudstack.api.command.admin.account.DeleteAccountCmd;
import org.apache.cloudstack.api.command.admin.account.DisableAccountCmd;
import org.apache.cloudstack.api.command.admin.account.EnableAccountCmd;
import org.apache.cloudstack.api.command.admin.account.ListAccountsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.account.LockAccountCmd;
import org.apache.cloudstack.api.command.admin.account.UpdateAccountCmd;
import org.apache.cloudstack.api.command.admin.address.AcquirePodIpCmdByAdmin;
import org.apache.cloudstack.api.command.admin.address.AssociateIPAddrCmdByAdmin;
import org.apache.cloudstack.api.command.admin.address.ListPublicIpAddressesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.address.ReleasePodIpCmdByAdmin;
import org.apache.cloudstack.api.command.admin.affinitygroup.UpdateVMAffinityGroupCmdByAdmin;
import org.apache.cloudstack.api.command.admin.alert.GenerateAlertCmd;
import org.apache.cloudstack.api.command.admin.autoscale.CreateCounterCmd;
import org.apache.cloudstack.api.command.admin.autoscale.DeleteCounterCmd;
import org.apache.cloudstack.api.command.admin.cluster.AddClusterCmd;
import org.apache.cloudstack.api.command.admin.cluster.DeleteClusterCmd;
import org.apache.cloudstack.api.command.admin.cluster.ListClustersCmd;
import org.apache.cloudstack.api.command.admin.cluster.UpdateClusterCmd;
import org.apache.cloudstack.api.command.admin.config.ListCfgGroupsByCmd;
import org.apache.cloudstack.api.command.admin.config.ListCfgsByCmd;
import org.apache.cloudstack.api.command.admin.config.ListDeploymentPlannersCmd;
import org.apache.cloudstack.api.command.admin.config.ListHypervisorCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.config.ResetCfgCmd;
import org.apache.cloudstack.api.command.admin.config.UpdateCfgCmd;
import org.apache.cloudstack.api.command.admin.config.UpdateHypervisorCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.direct.download.ListTemplateDirectDownloadCertificatesCmd;
import org.apache.cloudstack.api.command.admin.direct.download.ProvisionTemplateDirectDownloadCertificateCmd;
import org.apache.cloudstack.api.command.admin.direct.download.RevokeTemplateDirectDownloadCertificateCmd;
import org.apache.cloudstack.api.command.admin.direct.download.UploadTemplateDirectDownloadCertificateCmd;
import org.apache.cloudstack.api.command.admin.domain.CreateDomainCmd;
import org.apache.cloudstack.api.command.admin.domain.DeleteDomainCmd;
import org.apache.cloudstack.api.command.admin.domain.ListDomainChildrenCmd;
import org.apache.cloudstack.api.command.admin.domain.ListDomainsCmd;
import org.apache.cloudstack.api.command.admin.domain.ListDomainsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.domain.MoveDomainCmd;
import org.apache.cloudstack.api.command.admin.domain.UpdateDomainCmd;
import org.apache.cloudstack.api.command.admin.guest.AddGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.admin.guest.AddGuestOsCmd;
import org.apache.cloudstack.api.command.admin.guest.AddGuestOsMappingCmd;
import org.apache.cloudstack.api.command.admin.guest.DeleteGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.admin.guest.GetHypervisorGuestOsNamesCmd;
import org.apache.cloudstack.api.command.admin.guest.ListGuestOsMappingCmd;
import org.apache.cloudstack.api.command.admin.guest.RemoveGuestOsCmd;
import org.apache.cloudstack.api.command.admin.guest.RemoveGuestOsMappingCmd;
import org.apache.cloudstack.api.command.admin.guest.UpdateGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.admin.guest.UpdateGuestOsCmd;
import org.apache.cloudstack.api.command.admin.guest.UpdateGuestOsMappingCmd;
import org.apache.cloudstack.api.command.admin.host.AddHostCmd;
import org.apache.cloudstack.api.command.admin.host.AddSecondaryStorageCmd;
import org.apache.cloudstack.api.command.admin.host.CancelHostAsDegradedCmd;
import org.apache.cloudstack.api.command.admin.host.CancelHostMaintenanceCmd;
import org.apache.cloudstack.api.command.admin.host.DeclareHostAsDegradedCmd;
import org.apache.cloudstack.api.command.admin.host.DeleteHostCmd;
import org.apache.cloudstack.api.command.admin.host.FindHostsForMigrationCmd;
import org.apache.cloudstack.api.command.admin.host.ListHostTagsCmd;
import org.apache.cloudstack.api.command.admin.host.ListHostsCmd;
import org.apache.cloudstack.api.command.admin.host.PrepareForHostMaintenanceCmd;
import org.apache.cloudstack.api.command.admin.host.ReconnectHostCmd;
import org.apache.cloudstack.api.command.admin.host.ReleaseHostReservationCmd;
import org.apache.cloudstack.api.command.admin.host.UpdateHostCmd;
import org.apache.cloudstack.api.command.admin.host.UpdateHostPasswordCmd;
import org.apache.cloudstack.api.command.admin.internallb.ConfigureInternalLoadBalancerElementCmd;
import org.apache.cloudstack.api.command.admin.internallb.CreateInternalLoadBalancerElementCmd;
import org.apache.cloudstack.api.command.admin.internallb.ListInternalLBVMsCmd;
import org.apache.cloudstack.api.command.admin.internallb.ListInternalLoadBalancerElementsCmd;
import org.apache.cloudstack.api.command.admin.internallb.StartInternalLBVMCmd;
import org.apache.cloudstack.api.command.admin.internallb.StopInternalLBVMCmd;
import org.apache.cloudstack.api.command.admin.iso.AttachIsoCmdByAdmin;
import org.apache.cloudstack.api.command.admin.iso.CopyIsoCmdByAdmin;
import org.apache.cloudstack.api.command.admin.iso.DetachIsoCmdByAdmin;
import org.apache.cloudstack.api.command.admin.iso.ListIsoPermissionsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.iso.ListIsosCmdByAdmin;
import org.apache.cloudstack.api.command.admin.iso.RegisterIsoCmdByAdmin;
import org.apache.cloudstack.api.command.admin.loadbalancer.ListLoadBalancerRuleInstancesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.management.ListMgmtsCmd;
import org.apache.cloudstack.api.command.admin.management.RemoveManagementServerCmd;
import org.apache.cloudstack.api.command.admin.network.AddNetworkDeviceCmd;
import org.apache.cloudstack.api.command.admin.network.AddNetworkServiceProviderCmd;
import org.apache.cloudstack.api.command.admin.network.CloneNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.CreateManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.CreateNetworkCmdByAdmin;
import org.apache.cloudstack.api.command.admin.network.CreateNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.CreatePhysicalNetworkCmd;
import org.apache.cloudstack.api.command.admin.network.CreateStorageNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.DedicateGuestVlanRangeCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteNetworkDeviceCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteNetworkServiceProviderCmd;
import org.apache.cloudstack.api.command.admin.network.DeletePhysicalNetworkCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteStorageNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.ListDedicatedGuestVlanRangesCmd;
import org.apache.cloudstack.api.command.admin.network.ListGuestVlansCmd;
import org.apache.cloudstack.api.command.admin.network.ListNetworkDeviceCmd;
import org.apache.cloudstack.api.command.admin.network.ListNetworkIsolationMethodsCmd;
import org.apache.cloudstack.api.command.admin.network.ListNetworkServiceProvidersCmd;
import org.apache.cloudstack.api.command.admin.network.ListNetworksCmdByAdmin;
import org.apache.cloudstack.api.command.admin.network.ListPhysicalNetworksCmd;
import org.apache.cloudstack.api.command.admin.network.ListStorageNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.ListSupportedNetworkServicesCmd;
import org.apache.cloudstack.api.command.admin.network.MigrateNetworkCmd;
import org.apache.cloudstack.api.command.admin.network.MigrateVPCCmd;
import org.apache.cloudstack.api.command.admin.network.ReleaseDedicatedGuestVlanRangeCmd;
import org.apache.cloudstack.api.command.admin.network.UpdateNetworkCmdByAdmin;
import org.apache.cloudstack.api.command.admin.network.UpdateNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.UpdateNetworkServiceProviderCmd;
import org.apache.cloudstack.api.command.admin.network.UpdatePhysicalNetworkCmd;
import org.apache.cloudstack.api.command.admin.network.UpdatePodManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.UpdateStorageNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.offering.CloneDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CloneServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CreateDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CreateServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.IsAccountAllowedToCreateOfferingsWithTagsCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.ChangeOutOfBandManagementPasswordCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.ConfigureOutOfBandManagementCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.DisableOutOfBandManagementForClusterCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.DisableOutOfBandManagementForHostCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.DisableOutOfBandManagementForZoneCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.EnableOutOfBandManagementForClusterCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.EnableOutOfBandManagementForHostCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.EnableOutOfBandManagementForZoneCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.IssueOutOfBandManagementPowerActionCmd;
import org.apache.cloudstack.api.command.admin.pod.CreatePodCmd;
import org.apache.cloudstack.api.command.admin.pod.DeletePodCmd;
import org.apache.cloudstack.api.command.admin.pod.ListPodsByCmd;
import org.apache.cloudstack.api.command.admin.pod.UpdatePodCmd;
import org.apache.cloudstack.api.command.admin.region.AddRegionCmd;
import org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd;
import org.apache.cloudstack.api.command.admin.region.DeletePortableIpRangeCmd;
import org.apache.cloudstack.api.command.admin.region.ListPortableIpRangesCmd;
import org.apache.cloudstack.api.command.admin.region.RemoveRegionCmd;
import org.apache.cloudstack.api.command.admin.region.UpdateRegionCmd;
import org.apache.cloudstack.api.command.admin.resource.ArchiveAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.CleanVMReservationsCmd;
import org.apache.cloudstack.api.command.admin.resource.DeleteAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.ListAlertTypesCmd;
import org.apache.cloudstack.api.command.admin.resource.ListAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.ListCapacityCmd;
import org.apache.cloudstack.api.command.admin.resource.StartRollingMaintenanceCmd;
import org.apache.cloudstack.api.command.admin.resource.UploadCustomCertificateCmd;
import org.apache.cloudstack.api.command.admin.resource.icon.DeleteResourceIconCmd;
import org.apache.cloudstack.api.command.admin.resource.icon.ListResourceIconCmd;
import org.apache.cloudstack.api.command.admin.resource.icon.UploadResourceIconCmd;
import org.apache.cloudstack.api.command.admin.router.ConfigureOvsElementCmd;
import org.apache.cloudstack.api.command.admin.router.ConfigureVirtualRouterElementCmd;
import org.apache.cloudstack.api.command.admin.router.CreateVirtualRouterElementCmd;
import org.apache.cloudstack.api.command.admin.router.DestroyRouterCmd;
import org.apache.cloudstack.api.command.admin.router.GetRouterHealthCheckResultsCmd;
import org.apache.cloudstack.api.command.admin.router.ListOvsElementsCmd;
import org.apache.cloudstack.api.command.admin.router.ListRoutersCmd;
import org.apache.cloudstack.api.command.admin.router.ListVirtualRouterElementsCmd;
import org.apache.cloudstack.api.command.admin.router.RebootRouterCmd;
import org.apache.cloudstack.api.command.admin.router.StartRouterCmd;
import org.apache.cloudstack.api.command.admin.router.StopRouterCmd;
import org.apache.cloudstack.api.command.admin.router.UpgradeRouterCmd;
import org.apache.cloudstack.api.command.admin.router.UpgradeRouterTemplateCmd;
import org.apache.cloudstack.api.command.admin.snapshot.ListSnapshotsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.storage.AddImageStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.AddImageStoreS3CMD;
import org.apache.cloudstack.api.command.admin.storage.AddObjectStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.CancelPrimaryStorageMaintenanceCmd;
import org.apache.cloudstack.api.command.admin.storage.ChangeStoragePoolScopeCmd;
import org.apache.cloudstack.api.command.admin.storage.ConfigureStorageAccessCmd;
import org.apache.cloudstack.api.command.admin.storage.CreateSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.CreateStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteImageStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteObjectStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.DeletePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.FindStoragePoolsForMigrationCmd;
import org.apache.cloudstack.api.command.admin.storage.ListImageStoresCmd;
import org.apache.cloudstack.api.command.admin.storage.ListObjectStoragePoolsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListSecondaryStagingStoresCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageAccessGroupsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStoragePoolsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageProvidersCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageTagsCmd;
import org.apache.cloudstack.api.command.admin.storage.MigrateResourcesToAnotherSecondaryStorageCmd;
import org.apache.cloudstack.api.command.admin.storage.MigrateSecondaryStorageDataCmd;
import org.apache.cloudstack.api.command.admin.storage.PreparePrimaryStorageForMaintenanceCmd;
import org.apache.cloudstack.api.command.admin.storage.SyncStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateCloudToUseObjectStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateImageStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateObjectStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateStorageCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.CreateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.ListSecondaryStorageSelectorsCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.RemoveSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.UpdateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.swift.AddSwiftCmd;
import org.apache.cloudstack.api.command.admin.swift.ListSwiftsCmd;
import org.apache.cloudstack.api.command.admin.systemvm.DestroySystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.ListSystemVMsCmd;
import org.apache.cloudstack.api.command.admin.systemvm.MigrateSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.PatchSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.RebootSystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.ScaleSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.StartSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.StopSystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.UpgradeSystemVMCmd;
import org.apache.cloudstack.api.command.admin.template.CopyTemplateCmdByAdmin;
import org.apache.cloudstack.api.command.admin.template.CreateTemplateCmdByAdmin;
import org.apache.cloudstack.api.command.admin.template.ListTemplatePermissionsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.template.ListTemplatesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.template.PrepareTemplateCmd;
import org.apache.cloudstack.api.command.admin.template.RegisterTemplateCmdByAdmin;
import org.apache.cloudstack.api.command.admin.usage.AddTrafficMonitorCmd;
import org.apache.cloudstack.api.command.admin.usage.AddTrafficTypeCmd;
import org.apache.cloudstack.api.command.admin.usage.DeleteTrafficMonitorCmd;
import org.apache.cloudstack.api.command.admin.usage.DeleteTrafficTypeCmd;
import org.apache.cloudstack.api.command.admin.usage.GenerateUsageRecordsCmd;
import org.apache.cloudstack.api.command.admin.usage.ListTrafficMonitorsCmd;
import org.apache.cloudstack.api.command.admin.usage.ListTrafficTypeImplementorsCmd;
import org.apache.cloudstack.api.command.admin.usage.ListTrafficTypesCmd;
import org.apache.cloudstack.api.command.admin.usage.ListUsageRecordsCmd;
import org.apache.cloudstack.api.command.admin.usage.ListUsageTypesCmd;
import org.apache.cloudstack.api.command.admin.usage.RemoveRawUsageRecordsCmd;
import org.apache.cloudstack.api.command.admin.usage.UpdateTrafficTypeCmd;
import org.apache.cloudstack.api.command.admin.user.CreateUserCmd;
import org.apache.cloudstack.api.command.admin.user.DeleteUserKeysCmd;
import org.apache.cloudstack.api.command.admin.user.DeleteUserCmd;
import org.apache.cloudstack.api.command.admin.user.DisableUserCmd;
import org.apache.cloudstack.api.command.admin.user.EnableUserCmd;
import org.apache.cloudstack.api.command.admin.user.GetUserCmd;
import org.apache.cloudstack.api.command.admin.user.GetUserKeysCmd;
import org.apache.cloudstack.api.command.admin.user.ListUserKeyRulesCmd;
import org.apache.cloudstack.api.command.admin.user.ListUserKeysCmd;
import org.apache.cloudstack.api.command.admin.user.ListUsersCmd;
import org.apache.cloudstack.api.command.admin.user.LockUserCmd;
import org.apache.cloudstack.api.command.admin.user.MoveUserCmd;
import org.apache.cloudstack.api.command.admin.user.RegisterUserKeysCmd;
import org.apache.cloudstack.api.command.admin.user.UpdateUserCmd;
import org.apache.cloudstack.api.command.admin.vlan.CreateVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.DedicatePublicIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.DeleteVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.ListVlanIpRangesCmd;
import org.apache.cloudstack.api.command.admin.vlan.ReleasePublicIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.UpdateVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vm.AddNicToVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.AssignVMCmd;
import org.apache.cloudstack.api.command.admin.vm.DeployVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.DestroyVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.ExpungeVMCmd;
import org.apache.cloudstack.api.command.admin.vm.GetVMUserDataCmd;
import org.apache.cloudstack.api.command.admin.vm.ListAffectedVmsForStorageScopeChangeCmd;
import org.apache.cloudstack.api.command.admin.vm.ListVMsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.MigrateVMCmd;
import org.apache.cloudstack.api.command.admin.vm.MigrateVirtualMachineWithVolumeCmd;
import org.apache.cloudstack.api.command.admin.vm.RebootVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.RecoverVMCmd;
import org.apache.cloudstack.api.command.admin.vm.RemoveNicFromVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.ResetVMPasswordCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.ResetVMSSHKeyCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.RestoreVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.ScaleVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.StartVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.StopVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.UpdateDefaultNicForVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.UpdateVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.UpgradeVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vmsnapshot.RevertToVMSnapshotCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.AttachVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.CreateVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.DestroyVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.DetachVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.ListVolumesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.MigrateVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.RecoverVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.ResizeVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.UpdateVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.UploadVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vpc.CloneVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.CreatePrivateGatewayByAdminCmd;
import org.apache.cloudstack.api.command.admin.vpc.CreateVPCCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vpc.CreateVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.DeletePrivateGatewayCmd;
import org.apache.cloudstack.api.command.admin.vpc.DeleteVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.ListPrivateGatewaysCmdByAdminCmd;
import org.apache.cloudstack.api.command.admin.vpc.ListVPCsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vpc.UpdateVPCCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vpc.UpdateVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.zone.CreateZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.DeleteZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.ListZonesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.zone.MarkDefaultZoneForAccountCmd;
import org.apache.cloudstack.api.command.admin.zone.UpdateZoneCmd;
import org.apache.cloudstack.api.command.user.account.AddAccountToProjectCmd;
import org.apache.cloudstack.api.command.user.account.AddUserToProjectCmd;
import org.apache.cloudstack.api.command.user.account.DeleteAccountFromProjectCmd;
import org.apache.cloudstack.api.command.user.account.DeleteUserFromProjectCmd;
import org.apache.cloudstack.api.command.user.account.ListAccountsCmd;
import org.apache.cloudstack.api.command.user.account.ListProjectAccountsCmd;
import org.apache.cloudstack.api.command.user.address.AssociateIPAddrCmd;
import org.apache.cloudstack.api.command.user.address.DisassociateIPAddrCmd;
import org.apache.cloudstack.api.command.user.address.ListPublicIpAddressesCmd;
import org.apache.cloudstack.api.command.user.address.ListQuarantinedIpsCmd;
import org.apache.cloudstack.api.command.user.address.ReleaseIPAddrCmd;
import org.apache.cloudstack.api.command.user.address.RemoveQuarantinedIpCmd;
import org.apache.cloudstack.api.command.user.address.ReserveIPAddrCmd;
import org.apache.cloudstack.api.command.user.address.UpdateIPAddrCmd;
import org.apache.cloudstack.api.command.user.address.UpdateQuarantinedIpCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.CreateAffinityGroupCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.DeleteAffinityGroupCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.ListAffinityGroupTypesCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.ListAffinityGroupsCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.UpdateVMAffinityGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateAutoScalePolicyCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateAutoScaleVmProfileCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateConditionCmd;
import org.apache.cloudstack.api.command.user.autoscale.DeleteAutoScalePolicyCmd;
import org.apache.cloudstack.api.command.user.autoscale.DeleteAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.DeleteAutoScaleVmProfileCmd;
import org.apache.cloudstack.api.command.user.autoscale.DeleteConditionCmd;
import org.apache.cloudstack.api.command.user.autoscale.DisableAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.EnableAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListAutoScalePoliciesCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListAutoScaleVmGroupsCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListAutoScaleVmProfilesCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListConditionsCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListCountersCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateAutoScalePolicyCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateAutoScaleVmProfileCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateConditionCmd;
import org.apache.cloudstack.api.command.user.bucket.CreateBucketCmd;
import org.apache.cloudstack.api.command.user.bucket.DeleteBucketCmd;
import org.apache.cloudstack.api.command.user.bucket.ListBucketsCmd;
import org.apache.cloudstack.api.command.user.bucket.UpdateBucketCmd;
import org.apache.cloudstack.api.command.user.config.ListCapabilitiesCmd;
import org.apache.cloudstack.api.command.user.consoleproxy.CreateConsoleEndpointCmd;
import org.apache.cloudstack.api.command.user.consoleproxy.ListConsoleSessionsCmd;
import org.apache.cloudstack.api.command.user.event.ArchiveEventsCmd;
import org.apache.cloudstack.api.command.user.event.DeleteEventsCmd;
import org.apache.cloudstack.api.command.user.event.ListEventTypesCmd;
import org.apache.cloudstack.api.command.user.event.ListEventsCmd;
import org.apache.cloudstack.api.command.user.firewall.CreateEgressFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.CreateFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.CreatePortForwardingRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.DeleteEgressFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.DeleteFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.DeletePortForwardingRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.ListEgressFirewallRulesCmd;
import org.apache.cloudstack.api.command.user.firewall.ListFirewallRulesCmd;
import org.apache.cloudstack.api.command.user.firewall.ListPortForwardingRulesCmd;
import org.apache.cloudstack.api.command.user.firewall.UpdateEgressFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.UpdateFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.UpdatePortForwardingRuleCmd;
import org.apache.cloudstack.api.command.user.guest.ListGuestOsCategoriesCmd;
import org.apache.cloudstack.api.command.user.guest.ListGuestOsCmd;
import org.apache.cloudstack.api.command.user.gui.theme.CreateGuiThemeCmd;
import org.apache.cloudstack.api.command.user.gui.theme.ListGuiThemesCmd;
import org.apache.cloudstack.api.command.user.gui.theme.RemoveGuiThemeCmd;
import org.apache.cloudstack.api.command.user.gui.theme.UpdateGuiThemeCmd;
import org.apache.cloudstack.api.command.user.iso.AttachIsoCmd;
import org.apache.cloudstack.api.command.user.iso.CopyIsoCmd;
import org.apache.cloudstack.api.command.user.iso.DeleteIsoCmd;
import org.apache.cloudstack.api.command.user.iso.DetachIsoCmd;
import org.apache.cloudstack.api.command.user.iso.ExtractIsoCmd;
import org.apache.cloudstack.api.command.user.iso.GetUploadParamsForIsoCmd;
import org.apache.cloudstack.api.command.user.iso.ListIsoPermissionsCmd;
import org.apache.cloudstack.api.command.user.iso.ListIsosCmd;
import org.apache.cloudstack.api.command.user.iso.RegisterIsoCmd;
import org.apache.cloudstack.api.command.user.iso.UpdateIsoCmd;
import org.apache.cloudstack.api.command.user.iso.UpdateIsoPermissionsCmd;
import org.apache.cloudstack.api.command.user.job.ListAsyncJobsCmd;
import org.apache.cloudstack.api.command.user.job.QueryAsyncJobResultCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.AssignCertToLoadBalancerCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.AssignToLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.CreateApplicationLoadBalancerCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.CreateLBHealthCheckPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.CreateLBStickinessPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.CreateLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.DeleteApplicationLoadBalancerCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.DeleteLBHealthCheckPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.DeleteLBStickinessPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.DeleteLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.DeleteSslCertCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListApplicationLoadBalancersCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListLBHealthCheckPoliciesCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListLBStickinessPoliciesCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListLoadBalancerRuleInstancesCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListLoadBalancerRulesCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListSslCertsCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.RemoveCertFromLoadBalancerCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.RemoveFromLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.UpdateApplicationLoadBalancerCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.UpdateLBHealthCheckPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.UpdateLBStickinessPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.UpdateLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.UploadSslCertCmd;
import org.apache.cloudstack.api.command.user.nat.CreateIpForwardingRuleCmd;
import org.apache.cloudstack.api.command.user.nat.DeleteIpForwardingRuleCmd;
import org.apache.cloudstack.api.command.user.nat.DisableStaticNatCmd;
import org.apache.cloudstack.api.command.user.nat.EnableStaticNatCmd;
import org.apache.cloudstack.api.command.user.nat.ListIpForwardingRulesCmd;
import org.apache.cloudstack.api.command.user.network.CreateNetworkACLCmd;
import org.apache.cloudstack.api.command.user.network.CreateNetworkACLListCmd;
import org.apache.cloudstack.api.command.user.network.CreateNetworkCmd;
import org.apache.cloudstack.api.command.user.network.CreateNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.DeleteNetworkACLCmd;
import org.apache.cloudstack.api.command.user.network.DeleteNetworkACLListCmd;
import org.apache.cloudstack.api.command.user.network.DeleteNetworkCmd;
import org.apache.cloudstack.api.command.user.network.ImportNetworkACLCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkACLListsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkACLsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkOfferingsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkProtocolsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworksCmd;
import org.apache.cloudstack.api.command.user.network.MoveNetworkAclItemCmd;
import org.apache.cloudstack.api.command.user.network.RemoveNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ReplaceNetworkACLListCmd;
import org.apache.cloudstack.api.command.user.network.ResetNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.RestartNetworkCmd;
import org.apache.cloudstack.api.command.user.network.UpdateNetworkACLItemCmd;
import org.apache.cloudstack.api.command.user.network.UpdateNetworkACLListCmd;
import org.apache.cloudstack.api.command.user.network.UpdateNetworkCmd;
import org.apache.cloudstack.api.command.user.offering.ListDiskOfferingsCmd;
import org.apache.cloudstack.api.command.user.offering.ListServiceOfferingsCmd;
import org.apache.cloudstack.api.command.user.project.ActivateProjectCmd;
import org.apache.cloudstack.api.command.user.project.CreateProjectCmd;
import org.apache.cloudstack.api.command.user.project.DeleteProjectCmd;
import org.apache.cloudstack.api.command.user.project.DeleteProjectInvitationCmd;
import org.apache.cloudstack.api.command.user.project.ListProjectInvitationsCmd;
import org.apache.cloudstack.api.command.user.project.ListProjectsCmd;
import org.apache.cloudstack.api.command.user.project.SuspendProjectCmd;
import org.apache.cloudstack.api.command.user.project.UpdateProjectCmd;
import org.apache.cloudstack.api.command.user.project.UpdateProjectInvitationCmd;
import org.apache.cloudstack.api.command.user.region.ListRegionsCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.AssignToGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.CreateGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.DeleteGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.ListGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.RemoveFromGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.UpdateGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.resource.GetCloudIdentifierCmd;
import org.apache.cloudstack.api.command.user.resource.ListDetailOptionsCmd;
import org.apache.cloudstack.api.command.user.resource.ListHypervisorsCmd;
import org.apache.cloudstack.api.command.user.resource.ListResourceLimitsCmd;
import org.apache.cloudstack.api.command.user.resource.UpdateResourceCountCmd;
import org.apache.cloudstack.api.command.user.resource.UpdateResourceLimitCmd;
import org.apache.cloudstack.api.command.user.securitygroup.AuthorizeSecurityGroupEgressCmd;
import org.apache.cloudstack.api.command.user.securitygroup.AuthorizeSecurityGroupIngressCmd;
import org.apache.cloudstack.api.command.user.securitygroup.CreateSecurityGroupCmd;
import org.apache.cloudstack.api.command.user.securitygroup.DeleteSecurityGroupCmd;
import org.apache.cloudstack.api.command.user.securitygroup.ListSecurityGroupsCmd;
import org.apache.cloudstack.api.command.user.securitygroup.RevokeSecurityGroupEgressCmd;
import org.apache.cloudstack.api.command.user.securitygroup.RevokeSecurityGroupIngressCmd;
import org.apache.cloudstack.api.command.user.securitygroup.UpdateSecurityGroupCmd;
import org.apache.cloudstack.api.command.user.snapshot.ArchiveSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.CopySnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotFromVMSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotPolicyCmd;
import org.apache.cloudstack.api.command.user.snapshot.DeleteSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.DeleteSnapshotPoliciesCmd;
import org.apache.cloudstack.api.command.user.snapshot.ExtractSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.ListSnapshotPoliciesCmd;
import org.apache.cloudstack.api.command.user.snapshot.ListSnapshotsCmd;
import org.apache.cloudstack.api.command.user.snapshot.RevertSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.UpdateSnapshotPolicyCmd;
import org.apache.cloudstack.api.command.user.ssh.CreateSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.ssh.DeleteSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.ssh.ListSSHKeyPairsCmd;
import org.apache.cloudstack.api.command.user.ssh.RegisterSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.tag.CreateTagsCmd;
import org.apache.cloudstack.api.command.user.tag.DeleteTagsCmd;
import org.apache.cloudstack.api.command.user.tag.ListTagsCmd;
import org.apache.cloudstack.api.command.user.template.CopyTemplateCmd;
import org.apache.cloudstack.api.command.user.template.CreateTemplateCmd;
import org.apache.cloudstack.api.command.user.template.DeleteTemplateCmd;
import org.apache.cloudstack.api.command.user.template.ExtractTemplateCmd;
import org.apache.cloudstack.api.command.user.template.GetUploadParamsForTemplateCmd;
import org.apache.cloudstack.api.command.user.template.ListTemplatePermissionsCmd;
import org.apache.cloudstack.api.command.user.template.ListTemplatesCmd;
import org.apache.cloudstack.api.command.user.template.RegisterTemplateCmd;
import org.apache.cloudstack.api.command.user.template.UpdateTemplateCmd;
import org.apache.cloudstack.api.command.user.template.UpdateTemplatePermissionsCmd;
import org.apache.cloudstack.api.command.user.userdata.DeleteCniConfigurationCmd;
import org.apache.cloudstack.api.command.user.userdata.DeleteUserDataCmd;
import org.apache.cloudstack.api.command.user.userdata.LinkUserDataToTemplateCmd;
import org.apache.cloudstack.api.command.user.userdata.ListCniConfigurationCmd;
import org.apache.cloudstack.api.command.user.userdata.ListUserDataCmd;
import org.apache.cloudstack.api.command.user.userdata.RegisterCniConfigurationCmd;
import org.apache.cloudstack.api.command.user.userdata.RegisterUserDataCmd;
import org.apache.cloudstack.api.command.user.vm.AddIpToVmNicCmd;
import org.apache.cloudstack.api.command.user.vm.AddNicToVMCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.DestroyVMCmd;
import org.apache.cloudstack.api.command.user.vm.GetVMPasswordCmd;
import org.apache.cloudstack.api.command.user.vm.ListNicsCmd;
import org.apache.cloudstack.api.command.user.vm.ListVMsCmd;
import org.apache.cloudstack.api.command.user.vm.RebootVMCmd;
import org.apache.cloudstack.api.command.user.vm.RemoveIpFromVmNicCmd;
import org.apache.cloudstack.api.command.user.vm.RemoveNicFromVMCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMPasswordCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMSSHKeyCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMUserDataCmd;
import org.apache.cloudstack.api.command.user.vm.RestoreVMCmd;
import org.apache.cloudstack.api.command.user.vm.ScaleVMCmd;
import org.apache.cloudstack.api.command.user.vm.StartVMCmd;
import org.apache.cloudstack.api.command.user.vm.StopVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateDefaultNicForVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVmNicCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVmNicIpCmd;
import org.apache.cloudstack.api.command.user.vm.UpgradeVMCmd;
import org.apache.cloudstack.api.command.user.vmgroup.CreateVMGroupCmd;
import org.apache.cloudstack.api.command.user.vmgroup.DeleteVMGroupCmd;
import org.apache.cloudstack.api.command.user.vmgroup.ListVMGroupsCmd;
import org.apache.cloudstack.api.command.user.vmgroup.UpdateVMGroupCmd;
import org.apache.cloudstack.api.command.user.vmsnapshot.CreateVMSnapshotCmd;
import org.apache.cloudstack.api.command.user.vmsnapshot.DeleteVMSnapshotCmd;
import org.apache.cloudstack.api.command.user.vmsnapshot.ListVMSnapshotCmd;
import org.apache.cloudstack.api.command.user.vmsnapshot.RevertToVMSnapshotCmd;
import org.apache.cloudstack.api.command.user.volume.AddResourceDetailCmd;
import org.apache.cloudstack.api.command.user.volume.AssignVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.AttachVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.ChangeOfferingForVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.CheckAndRepairVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.CreateVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.DeleteVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.DestroyVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.DetachVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.ExtractVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.GetUploadParamsForVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.ListResourceDetailsCmd;
import org.apache.cloudstack.api.command.user.volume.ListVolumesCmd;
import org.apache.cloudstack.api.command.user.volume.MigrateVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.RecoverVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.RemoveResourceDetailCmd;
import org.apache.cloudstack.api.command.user.volume.ResizeVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.UpdateVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.UploadVolumeCmd;
import org.apache.cloudstack.api.command.user.vpc.CreatePrivateGatewayCmd;
import org.apache.cloudstack.api.command.user.vpc.CreateStaticRouteCmd;
import org.apache.cloudstack.api.command.user.vpc.CreateVPCCmd;
import org.apache.cloudstack.api.command.user.vpc.DeleteStaticRouteCmd;
import org.apache.cloudstack.api.command.user.vpc.DeleteVPCCmd;
import org.apache.cloudstack.api.command.user.vpc.ListPrivateGatewaysCmd;
import org.apache.cloudstack.api.command.user.vpc.ListStaticRoutesCmd;
import org.apache.cloudstack.api.command.user.vpc.ListVPCOfferingsCmd;
import org.apache.cloudstack.api.command.user.vpc.ListVPCsCmd;
import org.apache.cloudstack.api.command.user.vpc.RestartVPCCmd;
import org.apache.cloudstack.api.command.user.vpc.UpdateVPCCmd;
import org.apache.cloudstack.api.command.user.vpn.AddVpnUserCmd;
import org.apache.cloudstack.api.command.user.vpn.CreateRemoteAccessVpnCmd;
import org.apache.cloudstack.api.command.user.vpn.CreateVpnConnectionCmd;
import org.apache.cloudstack.api.command.user.vpn.CreateVpnCustomerGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.CreateVpnGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.DeleteRemoteAccessVpnCmd;
import org.apache.cloudstack.api.command.user.vpn.DeleteVpnConnectionCmd;
import org.apache.cloudstack.api.command.user.vpn.DeleteVpnCustomerGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.DeleteVpnGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.ListRemoteAccessVpnsCmd;
import org.apache.cloudstack.api.command.user.vpn.ListVpnConnectionsCmd;
import org.apache.cloudstack.api.command.user.vpn.ListVpnCustomerGatewaysCmd;
import org.apache.cloudstack.api.command.user.vpn.ListVpnGatewaysCmd;
import org.apache.cloudstack.api.command.user.vpn.ListVpnUsersCmd;
import org.apache.cloudstack.api.command.user.vpn.RemoveVpnUserCmd;
import org.apache.cloudstack.api.command.user.vpn.ResetVpnConnectionCmd;
import org.apache.cloudstack.api.command.user.vpn.UpdateRemoteAccessVpnCmd;
import org.apache.cloudstack.api.command.user.vpn.UpdateVpnConnectionCmd;
import org.apache.cloudstack.api.command.user.vpn.UpdateVpnCustomerGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.UpdateVpnGatewayCmd;
import org.apache.cloudstack.api.command.user.zone.ListZonesCmd;
import org.apache.cloudstack.auth.UserAuthenticator;
import org.apache.cloudstack.auth.UserTwoFactorAuthenticator;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.config.ConfigurationGroup;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.framework.security.keystore.KeystoreManager;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.resourcedetail.dao.GuestOsDetailsDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.manager.allocator.HostAllocator;
import com.cloud.alert.Alert;
import com.cloud.alert.AlertVO;
import com.cloud.alert.dao.AlertDao;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.StoragePoolJoinDao;
import com.cloud.api.query.vo.StoragePoolJoinVO;
import com.cloud.capacity.CapacityVO;
import com.cloud.cluster.ClusterManager;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.configuration.Config;
import com.cloud.consoleproxy.ConsoleProxyManagementState;
import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.cpu.CPU;
import com.cloud.dc.AccountVlanMapVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DomainVlanMapVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Pod;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.AccountVlanMapDao;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DomainVlanMapDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.dc.dao.VlanDetailsDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.event.EventVO;
import com.cloud.event.dao.EventDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.gpu.GPU;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.Host.Type;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorCapabilities;
import com.cloud.hypervisor.HypervisorCapabilitiesVO;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.hypervisor.kvm.dpdk.DpdkHelper;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkAccountDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Cluster;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.GuestOS;
import com.cloud.storage.GuestOSHypervisor;
import com.cloud.storage.GuestOSHypervisorVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.GuestOsCategory;
import com.cloud.storage.ScopeType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.GuestOSHypervisorDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.secondary.SecondaryStorageVmManager;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.SSHKeyPair;
import com.cloud.user.User;
import com.cloud.user.UserData;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.PasswordGenerator;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ComponentLifecycle;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.net.MacAddress;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.security.CertificateHelper;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.InstanceGroupVO;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.InstanceGroupDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.SecondaryStorageVmDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

public class ManagementServerImpl extends MutualExclusiveIdsManagerBase implements ManagementServer, Configurable {
    protected StateMachine2<State, VirtualMachine.Event, VirtualMachine> _stateMachine;

    static final String FOR_SYSTEMVMS = "forsystemvms";
    static final ConfigKey<Integer> vmPasswordLength = new ConfigKey<>("Advanced", Integer.class, "vm.password.length", "6", "Specifies the length of a randomly generated password", false);
    static final ConfigKey<Integer> sshKeyLength = new ConfigKey<>("Advanced", Integer.class, "ssh.key.length", "2048", "Specifies custom SSH key length (bit)", true, ConfigKey.Scope.Global);
    static final ConfigKey<Boolean> humanReadableSizes = new ConfigKey<>("Advanced", Boolean.class, "display.human.readable.sizes", "true", "Enables outputting human readable byte sizes to logs and usage records.", false, ConfigKey.Scope.Global);
    public static final ConfigKey<String> customCsIdentifier = new ConfigKey<>("Advanced", String.class, "custom.cs.identifier", UUID.randomUUID().toString().split("-")[0].substring(4), "Custom identifier for the cloudstack installation", true, ConfigKey.Scope.Global);
    public static final ConfigKey<Boolean> exposeCloudStackVersionInApiXmlResponse = new ConfigKey<>("Advanced", Boolean.class, "expose.cloudstack.version.api.xml.response", "true", "Indicates whether ACS version should appear in the root element of an API XML response.", true, ConfigKey.Scope.Global);
    public static final ConfigKey<Boolean> exposeCloudStackVersionInApiListCapabilities = new ConfigKey<>("Advanced", Boolean.class, "expose.cloudstack.version.api.list.capabilities", "true", "Indicates whether ACS version should show in the listCapabilities API.", true, ConfigKey.Scope.Global);

    private static final List<HypervisorType> LIVE_MIGRATION_SUPPORTING_HYPERVISORS = List.of(HypervisorType.Hyperv, HypervisorType.KVM,
            HypervisorType.LXC, HypervisorType.Simulator, HypervisorType.VMware, HypervisorType.XenServer);

    @Inject
    public AccountManager _accountMgr;
    @Inject
    private AgentManager _agentMgr;
    @Inject
    private IPAddressDao _publicIpAddressDao;
    @Inject
    private ClusterDao _clusterDao;
    @Inject
    protected VMInstanceDetailsDao _vmInstanceDetailsDao;
    @Inject
    private SecondaryStorageVmDao _secStorageVmDao;
    @Inject
    public EventDao _eventDao;
    @Inject
    private DataCenterDao _dcDao;
    @Inject
    private VlanDao _vlanDao;
    @Inject
    private VlanDetailsDao vlanDetailsDao;
    @Inject
    private AccountVlanMapDao _accountVlanMapDao;
    @Inject
    private PodVlanMapDao _podVlanMapDao;
    @Inject
    private HostDao _hostDao;
    @Inject
    protected HostDetailsDao _detailsDao;
    @Inject
    private UserDao _userDao;
    @Inject
    protected UserVmDao _userVmDao;
    @Inject
    protected ConfigurationDao _configDao;
    @Inject
    private ConsoleProxyManager _consoleProxyMgr;
    @Inject
    private SecondaryStorageVmManager _secStorageVmMgr;
    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    protected DomainDao _domainDao;
    @Inject
    private AccountDao _accountDao;
    @Inject
    public AlertDao _alertDao;
    @Inject
    private GuestOSDao _guestOSDao;
    @Inject
    private GuestOSCategoryDao _guestOSCategoryDao;
    @Inject
    private GuestOSHypervisorDao _guestOSHypervisorDao;
    @Inject
    private PrimaryDataStoreDao _poolDao;
    @Inject
    private StoragePoolJoinDao _poolJoinDao;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    private VirtualMachineManager _itMgr;
    @Inject
    private HostPodDao _hostPodDao;
    @Inject
    VgpuProfileDao vgpuProfileDao;
    @Inject
    private VMInstanceDao _vmInstanceDao;
    @Inject
    private VolumeDao _volumeDao;
    private int _purgeDelay;
    private int _alertPurgeDelay;
    @Inject
    private InstanceGroupDao _vmGroupDao;
    @Inject
    protected SshKeyPairService sshKeyPairService;
    @Inject
    protected AuditTrailService auditTrailService;
    @Inject
    protected HypervisorCapabilitiesService hypervisorCapabilitiesService;
    @Inject
    protected HostCredentialsService hostCredentialsService;
    @Inject
    protected ConsoleAccessService consoleAccessService;
    @Inject
    protected SystemVmLifecycleService systemVmLifecycleService;
    @Inject
    protected CapabilitiesService capabilitiesService;
    @Inject
    protected ConfigurationListingService configurationListingService;
    @Inject
    protected InfrastructureUsageService infrastructureUsageService;
    @Inject
    protected GuestOsManagementService guestOsManagementService;
    @Inject
    protected UserDataRegistryService userDataRegistryService;
    @Inject
    protected SystemVmOperationsService systemVmOperationsService;
    @Inject
    protected ClusterHostQueryService clusterHostQueryService;
    @Inject
    protected PublicIpAddressSearchService publicIpAddressSearchService;
    @Inject
    private LoadBalancerDao _loadbalancerDao;
    @Inject
    private HypervisorCapabilitiesDao _hypervisorCapabilitiesDao;
    private List<HostAllocator> hostAllocators;
    private List<StoragePoolAllocator> _storagePoolAllocators;
    @Inject
    private ResourceTagDao _resourceTagDao;
    @Inject
    private ServiceOfferingDetailsDao _serviceOfferingDetailsDao;
    @Inject
    private ProjectManager _projectMgr;
    @Inject
    private HighAvailabilityManager _haMgr;
    @Inject
    private HostTagsDao _hostTagsDao;
    @Inject
    private UserVmManager _userVmMgr;
    @Inject
    private AccountService _accountService;
    @Inject
    private ServiceOfferingDao _offeringDao;
    @Inject
    private DeploymentPlanningManager _dpMgr;
    @Inject
    private GuestOsDetailsDao _guestOsDetailsDao;
    @Inject
    private KeystoreManager _ksMgr;
    @Inject
    private DpdkHelper dpdkHelper;
    @Inject
    private PrimaryDataStoreDao _primaryDataStoreDao;
    @Inject
    private DataStoreManager dataStoreManager;
    @Inject
    private IpAddressManager _ipAddressMgr;
    @Inject
    private NetworkAccountDao _networkAccountDao;
    @Inject
    private NetworkDomainDao _networkDomainDao;
    @Inject
    private NetworkModel _networkMgr;
    @Inject
    protected VpcDao _vpcDao;
    @Inject
    private DomainVlanMapDao _domainVlanMapDao;
    @Inject
    private NicDao nicDao;
    @Inject
    DomainRouterDao routerDao;
    @Inject
    protected VMTemplateDao templateDao;
    @Inject
    protected ManagementServerHostDao managementServerHostDao;
    @Inject
    ClusterManager _clusterMgr;

    @Inject
    protected AffinityGroupVMMapDao _affinityGroupVMMapDao;
    @Inject
    ExtensionsManager extensionsManager;

    private LockControllerListener _lockControllerListener;
    private final ScheduledExecutorService _eventExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("EventChecker"));
    private final ScheduledExecutorService _alertExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("AlertChecker"));


    private Map<String, String> _configs;

    private List<UserAuthenticator> _userAuthenticators;
    private List<UserTwoFactorAuthenticator> _userTwoFactorAuthenticators;
    private List<UserAuthenticator> _userPasswordEncoders;

    protected List<DeploymentPlanner> _planners;

    public List<DeploymentPlanner> getPlanners() {
        return _planners;
    }

    public void setPlanners(final List<DeploymentPlanner> planners) {
        _planners = planners;
    }

    protected List<AffinityGroupProcessor> _affinityProcessors;

    public List<AffinityGroupProcessor> getAffinityGroupProcessors() {
        return _affinityProcessors;
    }

    public void setAffinityGroupProcessors(final List<AffinityGroupProcessor> affinityProcessors) {
        _affinityProcessors = affinityProcessors;
    }

    public ManagementServerImpl() {
        setRunLevel(ComponentLifecycle.RUN_LEVEL_APPLICATION_MAINLOOP);
        setStateMachine();
    }

    public List<UserAuthenticator> getUserAuthenticators() {
        return _userAuthenticators;
    }

    public void setUserAuthenticators(final List<UserAuthenticator> authenticators) {
        _userAuthenticators = authenticators;
    }

    public List<UserTwoFactorAuthenticator> getUserTwoFactorAuthenticators() {
        return _userTwoFactorAuthenticators;
    }

    public void setUserTwoFactorAuthenticators(final List<UserTwoFactorAuthenticator> userTwoFactorAuthenticators) {
        _userTwoFactorAuthenticators = userTwoFactorAuthenticators;
    }

    public List<UserAuthenticator> getUserPasswordEncoders() {
        return _userPasswordEncoders;
    }

    public void setUserPasswordEncoders(final List<UserAuthenticator> encoders) {
        _userPasswordEncoders = encoders;
    }

    public List<HostAllocator> getHostAllocators() {
        return hostAllocators;
    }

    public void setHostAllocators(final List<HostAllocator> hostAllocators) {
        this.hostAllocators = hostAllocators;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {

        _configs = _configDao.getConfiguration();

        final String value = _configs.get("event.purge.interval");
        final int cleanup = NumbersUtil.parseInt(value, 60 * 60 * 24); // 1 day.

        _purgeDelay = NumbersUtil.parseInt(_configs.get("event.purge.delay"), 0);
        if (_purgeDelay != 0) {
            _eventExecutor.scheduleAtFixedRate(new EventPurgeTask(), cleanup, cleanup, TimeUnit.SECONDS);
        }

        //Alerts purge configurations
        final int alertPurgeInterval = NumbersUtil.parseInt(_configDao.getValue(Config.AlertPurgeInterval.key()), 60 * 60 * 24); // 1 day.
        _alertPurgeDelay = NumbersUtil.parseInt(_configDao.getValue(Config.AlertPurgeDelay.key()), 0);
        if (_alertPurgeDelay != 0) {
            _alertExecutor.scheduleAtFixedRate(new AlertPurgeTask(), alertPurgeInterval, alertPurgeInterval, TimeUnit.SECONDS);
        }

        return true;
    }

    private void setStateMachine() {
        _stateMachine = VirtualMachine.State.getStateMachine();
    }

    @Override
    public boolean start() {
        logger.info("Startup CloudStack management server...");
        // Set human readable sizes
        NumbersUtil.enableHumanReadableSizes = _configDao.findByName("display.human.readable.sizes").getValue().equals("true");

        if (_lockControllerListener == null) {
            _lockControllerListener = new LockControllerListener(ManagementServerNode.getManagementServerId());
        }

        _clusterMgr.registerListener(_lockControllerListener);

        enableAdminUser("password");
        return true;
    }

    protected Map<String, String> getConfigs() {
        return _configs;
    }

    @Override
    public String generateRandomPassword() {
        final Integer passwordLength = vmPasswordLength.value();
        return PasswordGenerator.generateRandomPassword(passwordLength);
    }

    @Override
    public HostVO getHostBy(final long hostId) {
        return _hostDao.findById(hostId);
    }

    @Override
    public DetailVO findDetail(final long hostId, final String name) {
        return _detailsDao.findDetail(hostId, name);
    }

    @Override
    public long getId() {
        return MacAddress.getMacAddress().toLong();
    }

    protected void checkPortParameters(final String publicPort, final String privatePort, final String privateIp, final String proto) {

        if (!NetUtils.isValidPort(publicPort)) {
            throw new InvalidParameterValueException("publicPort is an invalid value");
        }
        if (!NetUtils.isValidPort(privatePort)) {
            throw new InvalidParameterValueException("privatePort is an invalid value");
        }

        if (!NetUtils.isValidProto(proto)) {
            throw new InvalidParameterValueException("Invalid protocol");
        }
    }

    @Override
    public boolean archiveEvents(final ArchiveEventsCmd cmd) {
        return auditTrailService.archiveEvents(cmd);
    }

    @Override
    public boolean deleteEvents(final DeleteEventsCmd cmd) {
        return auditTrailService.deleteEvents(cmd);
    }

    @Override
    public List<? extends Cluster> searchForClusters(long zoneId, final Long startIndex, final Long pageSizeVal, final String hypervisorType) {
        return clusterHostQueryService.searchForClusters(zoneId, startIndex, pageSizeVal, hypervisorType);
    }

    @Override
    public Pair<List<? extends Cluster>, Integer> searchForClusters(final ListClustersCmd cmd) {
        return clusterHostQueryService.searchForClusters(cmd);
    }

    private HypervisorType getHypervisorType(VMInstanceVO vm, StoragePool srcVolumePool) {
        HypervisorType type = null;
        if (vm == null) {
            StoragePoolVO poolVo = _poolDao.findById(srcVolumePool.getId());
            if (ScopeType.CLUSTER.equals(poolVo.getScope())) {
                Long clusterId = poolVo.getClusterId();
                if (clusterId != null) {
                    ClusterVO cluster = _clusterDao.findById(clusterId);
                    type = cluster.getHypervisorType();
                }
            }

            if (null == type) {
                type = srcVolumePool.getHypervisor();
            }
        } else {
            type = vm.getHypervisorType();
        }
        return type;
    }

    @Override
    public Pair<List<? extends Host>, Integer> searchForServers(final ListHostsCmd cmd) {
        return clusterHostQueryService.searchForServers(cmd);
    }

    protected Pair<Boolean, List<HostVO>> filterUefiHostsForMigration(List<HostVO> allHosts, List<HostVO> filteredHosts, VirtualMachine vm) {
        VMInstanceDetailVO vmInstanceDetailVO = _vmInstanceDetailsDao.findDetail(vm.getId(), ApiConstants.BootType.UEFI.toString());
        if (vmInstanceDetailVO != null &&
                (ApiConstants.BootMode.LEGACY.toString().equalsIgnoreCase(vmInstanceDetailVO.getValue()) ||
                        ApiConstants.BootMode.SECURE.toString().equalsIgnoreCase(vmInstanceDetailVO.getValue()))) {
            logger.debug("{} VM is UEFI enabled, Checking for other UEFI enabled hosts as it can be live migrated to UEFI enabled host only.", vm.getInstanceName());
            if (CollectionUtils.isEmpty(filteredHosts)) {
                filteredHosts = new ArrayList<>(allHosts);
            }
            List<DetailVO> details = _detailsDao.findByName(Host.HOST_UEFI_ENABLE);
            List<Long> uefiEnabledHosts = details.stream().filter(x -> Boolean.TRUE.toString().equalsIgnoreCase(x.getValue())).map(DetailVO::getHostId).collect(Collectors.toList());
            if (CollectionUtils.isEmpty(uefiEnabledHosts)) {
                return new Pair<>(false, null);
            }
            filteredHosts.removeIf(host -> !uefiEnabledHosts.contains(host.getId()));
            if (filteredHosts.isEmpty()) {
                logger.warn("No UEFI enabled hosts are available for the live migration of VM {}", vm.getInstanceName());
            }
            return new Pair<>(!filteredHosts.isEmpty(), filteredHosts);
        }
        return new Pair<>(true, filteredHosts);
    }

    private void validateVmForHostMigration(VirtualMachine vm) {
        final Account caller = getCaller();
        if (!_accountMgr.isRootAdmin(caller.getId())) {
            if (logger.isDebugEnabled()) {
                logger.debug("Caller is not a root admin, permission denied to migrate the Instance");
            }
            throw new PermissionDeniedException("No permission to migrate instance, Only Root Admin can migrate an instance!");
        }

        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find the Instance with given ID");
        }

        if (vm.getState() != State.Running) {
            if (logger.isDebugEnabled()) {
                logger.debug("Instance is not running, cannot migrate the Instance" + vm);
            }
            final InvalidParameterValueException ex = new InvalidParameterValueException("Instance is not Running, cannot " + "migrate the instance with specified id");
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        if (!LIVE_MIGRATION_SUPPORTING_HYPERVISORS.contains(vm.getHypervisorType())) {
            if (logger.isDebugEnabled()) {
                logger.debug(vm + " is not XenServer/VMware/KVM/Hyperv, cannot migrate this Instance.");
            }
            throw new InvalidParameterValueException("Unsupported Hypervisor Type for Instance migration, we support XenServer/VMware/KVM/Hyperv only");
        }

        if (VirtualMachine.Type.User.equals(vm.getType()) && HypervisorType.LXC.equals(vm.getHypervisorType())) {
            throw new InvalidParameterValueException("Unsupported Hypervisor Type for User instance migration, we support XenServer/VMware/KVM/Hyperv only");
        }
    }

    @Override
    public Ternary<Pair<List<? extends Host>, Integer>, List<? extends Host>, Map<Host, Boolean>> listHostsForMigrationOfVM(final Long vmId, final Long startIndex, final Long pageSize,
                                                                                                                            final String keyword) {
        final VMInstanceVO vm = _vmInstanceDao.findById(vmId);
        return listHostsForMigrationOfVM(vm, startIndex, pageSize, keyword, Collections.emptyList());
    }

    protected boolean zoneWideVolumeRequiresStorageMotion(PrimaryDataStore volumeDataStore,
               final Host sourceHost, final Host destinationHost) {
        if (volumeDataStore.isManaged() && !Objects.equals(sourceHost.getClusterId(), destinationHost.getClusterId())) {
            PrimaryDataStoreDriver driver = (PrimaryDataStoreDriver)volumeDataStore.getDriver();
            // Depends on the storage driver. For some storages simply
            // changing volume access to host should work: grant access on destination
            // host and revoke access on source host. For others, we still have to perform a storage migration
            // because we need to create a new target volume and copy the contents of the
            // source volume into it before deleting the source volume.
            return !driver.zoneWideVolumesAvailableWithoutClusterMotion();
        }
        return false;
    }

    /**
     * Get technically compatible hosts for VM migration (storage, hypervisor, UEFI filtering).
     * This determines which hosts are technically capable of hosting the VM based on
     * storage requirements, hypervisor capabilities, and UEFI requirements.
     *
     * @param vm The virtual machine to migrate
     * @param startIndex Starting index for pagination
     * @param pageSize Page size for pagination
     * @param keyword Keyword filter for host search
     * @return Ternary containing: (all hosts with count, filtered compatible hosts, storage motion requirements map)
     */
    Ternary<Pair<List<? extends Host>, Integer>, List<? extends Host>, Map<Host, Boolean>> getTechnicallyCompatibleHosts(
            final VirtualMachine vm,
            final Long startIndex,
            final Long pageSize,
            final String keyword) {

        // GPU check
        if (_serviceOfferingDetailsDao.findDetail(vm.getServiceOfferingId(), GPU.Keys.pciDevice.toString()) != null) {
            logger.info("Live Migration of GPU enabled VM : {} is not supported", vm);
            return new Ternary<>(new Pair<>(new ArrayList<>(), 0), new ArrayList<>(), new HashMap<>());
        }

        final long srcHostId = vm.getHostId();
        final Host srcHost = _hostDao.findById(srcHostId);
        if (srcHost == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to find the host with ID: " + srcHostId + " of this Instance: " + vm);
            }
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find the host (with specified ID) of instance with specified ID");
            ex.addProxyObject(String.valueOf(srcHostId), "hostId");
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        String srcHostVersion = srcHost.getHypervisorVersion();
        if (HypervisorType.KVM.equals(srcHost.getHypervisorType()) && srcHostVersion == null) {
            srcHostVersion = "";
        }

        // Check if the vm can be migrated with storage.
        boolean canMigrateWithStorage = false;

        List<HypervisorType> hypervisorTypes = Arrays.asList(new HypervisorType[]{HypervisorType.VMware, HypervisorType.KVM});
        if (VirtualMachine.Type.User.equals(vm.getType()) || hypervisorTypes.contains(vm.getHypervisorType())) {
            canMigrateWithStorage = _hypervisorCapabilitiesDao.isStorageMotionSupported(srcHost.getHypervisorType(), srcHostVersion);
        }

        // Check if the vm is using any disks on local storage.
        final VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(vm, null, _offeringDao.findById(vm.getId(), vm.getServiceOfferingId()), null, null);
        final List<VolumeVO> volumes = _volumeDao.findCreatedByInstance(vmProfile.getId());
        boolean usesLocal = false;
        for (final VolumeVO volume : volumes) {
            final DiskOfferingVO diskOffering = _diskOfferingDao.findById(volume.getDiskOfferingId());
            final DiskProfile diskProfile = new DiskProfile(volume, diskOffering, vmProfile.getHypervisorType());
            if (diskProfile.useLocalStorage()) {
                usesLocal = true;
                break;
            }
        }

        if (!canMigrateWithStorage && usesLocal) {
            throw new InvalidParameterValueException("Unsupported operation, instance uses Local storage, cannot migrate");
        }

        validateVgpuProfileForVmMigration(vmProfile);

        final Type hostType = srcHost.getType();
        Pair<List<HostVO>, Integer> allHostsPair;
        List<HostVO> allHosts;
        List<HostVO> filteredHosts = null;
        final Map<Host, Boolean> requiresStorageMotion = new HashMap<>();

        if (canMigrateWithStorage) {
            Long podId = !VirtualMachine.Type.User.equals(vm.getType()) ? srcHost.getPodId() : null;
            allHostsPair = searchForServers(startIndex, pageSize, null, hostType, null, srcHost.getDataCenterId(), podId, null, null, keyword,
                null, null, srcHost.getHypervisorType(), null, srcHost.getId());
            allHosts = allHostsPair.first();
            filteredHosts = new ArrayList<>(allHosts);

            for (final VolumeVO volume : volumes) {
                PrimaryDataStore primaryDataStore = (PrimaryDataStore)dataStoreManager.getPrimaryDataStore(volume.getPoolId());
                Long volClusterId = primaryDataStore.getClusterId();

                for (Iterator<HostVO> iterator = filteredHosts.iterator(); iterator.hasNext();) {
                    final Host host = iterator.next();
                    String hostVersion = host.getHypervisorVersion();
                    if (HypervisorType.KVM.equals(host.getHypervisorType()) && hostVersion == null) {
                        hostVersion = "";
                    }

                    if (volClusterId != null) {
                        if (primaryDataStore.isLocal() || !host.getClusterId().equals(volClusterId) || usesLocal) {
                            if (primaryDataStore.isManaged()) {
                                // At the time being, we do not support storage migration of a volume from managed storage unless the managed storage
                                // is at the zone level and the source and target storage pool is the same.
                                // If the source and target storage pool is the same and it is managed, then we still have to perform a storage migration
                                // because we need to create a new target volume and copy the contents of the source volume into it before deleting the
                                // source volume.
                                iterator.remove();
                            } else {
                                boolean hostSupportsStorageMigration = (srcHostVersion != null && srcHostVersion.equals(hostVersion)) ||
                                        _hypervisorCapabilitiesDao.isStorageMotionSupported(host.getHypervisorType(), hostVersion);
                                if (hostSupportsStorageMigration && hasSuitablePoolsForVolume(volume, host, vmProfile)) {
                                    requiresStorageMotion.put(host, true);
                                } else {
                                    iterator.remove();
                                }
                            }
                        }
                    } else {
                        if (zoneWideVolumeRequiresStorageMotion(primaryDataStore, srcHost, host)) {
                            requiresStorageMotion.put(host, true);
                        }
                    }
                }
            }

            if (CollectionUtils.isEmpty(filteredHosts)) {
                return new Ternary<>(new Pair<>(allHosts, allHostsPair.second()), new ArrayList<>(), new HashMap<>());
            }
        } else {
            final Long cluster = srcHost.getClusterId();
            if (logger.isDebugEnabled()) {
                logger.debug("Searching for all hosts in cluster " + cluster + " for migrating Instance " + vm);
            }
            allHostsPair = searchForServers(startIndex, pageSize, null, hostType, null, null, null, cluster, null, keyword, null, null, null,
                null, srcHost.getId());
            allHosts = allHostsPair.first();
            filteredHosts = allHosts;
        }

        final Pair<List<? extends Host>, Integer> allHostsPairResult = new Pair<>(allHosts, allHostsPair.second());
        Pair<Boolean, List<HostVO>> uefiFilteredResult = filterUefiHostsForMigration(allHosts, filteredHosts, vm);
        if (!uefiFilteredResult.first()) {
            return new Ternary<>(allHostsPairResult, new ArrayList<>(), new HashMap<>());
        }
        filteredHosts = uefiFilteredResult.second();

        return new Ternary<>(allHostsPairResult, filteredHosts, requiresStorageMotion);
    }

    /**
     * Apply affinity group constraints and other exclusion rules for VM migration.
     * This builds an ExcludeList based on affinity groups, DPDK requirements, and dedicated resources.
     *
     * @param vm The virtual machine to migrate
     * @param vmProfile The VM profile
     * @param plan The deployment plan
     * @param vmList List of VMs with current/simulated placements for affinity processing
     * @return ExcludeList containing hosts to avoid
     */
    @Override
    public ExcludeList applyAffinityConstraints(VirtualMachine vm, VirtualMachineProfile vmProfile, DeploymentPlan plan, List<VirtualMachine> vmList) {
        final ExcludeList excludes = new ExcludeList();
        excludes.addHost(vm.getHostId());

        if (dpdkHelper.isVMDpdkEnabled(vm.getId())) {
            if (plan instanceof DataCenterDeployment) {
                excludeNonDPDKEnabledHosts((DataCenterDeployment) plan, excludes);
            }
        }

        // call affinitygroup chain
        final long vmGroupCount = _affinityGroupVMMapDao.countAffinityGroupsForVm(vm.getId());

        if (vmGroupCount > 0) {
            for (final AffinityGroupProcessor processor : _affinityProcessors) {
                processor.process(vmProfile, plan, excludes, vmList);
            }
        }

        if (vm.getType() == VirtualMachine.Type.User || vm.getType() == VirtualMachine.Type.DomainRouter) {
            final DataCenterVO dc = _dcDao.findById(plan.getDataCenterId());
            _dpMgr.checkForNonDedicatedResources(vmProfile, dc, excludes);
        }

        return excludes;
    }

    /**
     * Get hosts with available capacity using host allocators, and apply architecture filtering.
     *
     * @param vm The virtual machine (for architecture filtering)
     * @param vmProfile The VM profile
     * @param plan The deployment plan
     * @param compatibleHosts List of technically compatible hosts
     * @param excludes ExcludeList with hosts to avoid
     * @param srcHost Source host (for architecture filtering)
     * @return List of suitable hosts with capacity
     */
    protected List<Host> getCapableSuitableHosts(
            final VirtualMachine vm,
            final VirtualMachineProfile vmProfile,
            final DataCenterDeployment plan,
            final List<? extends Host> compatibleHosts,
            final ExcludeList excludes,
            final Host srcHost) {

        List<Host> suitableHosts = new ArrayList<>();

        for (final HostAllocator allocator : hostAllocators) {
            suitableHosts = allocator.allocateTo(vmProfile, plan, Host.Type.Routing, excludes, compatibleHosts, HostAllocator.RETURN_UPTO_ALL, false);

            if (CollectionUtils.isNotEmpty(suitableHosts)) {
                break;
            }
        }

        _dpMgr.reorderHostsByPriority(plan.getHostPriorities(), suitableHosts);

        if (CollectionUtils.isEmpty(suitableHosts)) {
            logger.warn("No suitable hosts found.");
            return suitableHosts;
        }

        logger.debug("Hosts having capacity and are suitable for migration: {}", suitableHosts);

        // Only list hosts of the same architecture as the source Host in a multi-arch zone
        List<CPU.CPUArch> clusterArchs = ApiDBUtils.listZoneClustersArchs(vm.getDataCenterId());
        if (CollectionUtils.isNotEmpty(clusterArchs) && clusterArchs.size() > 1) {
            suitableHosts = suitableHosts.stream().filter(h -> h.getArch() == srcHost.getArch()).collect(Collectors.toList());
        }

        return suitableHosts;
    }

    @Override
    public Ternary<Pair<List<? extends Host>, Integer>, List<? extends Host>, Map<Host, Boolean>> listHostsForMigrationOfVM(final VirtualMachine vm, final Long startIndex, final Long pageSize,
            final String keyword, List<VirtualMachine> vmList) {

        validateVmForHostMigration(vm);

        // Get technically compatible hosts (storage, hypervisor, UEFI)
        Ternary<Pair<List<? extends Host>, Integer>, List<? extends Host>, Map<Host, Boolean>> compatibilityResult =
            getTechnicallyCompatibleHosts(vm, startIndex, pageSize, keyword);

        Pair<List<? extends Host>, Integer> allHostsPair = compatibilityResult.first();
        List<? extends Host> filteredHosts = compatibilityResult.second();
        Map<Host, Boolean> requiresStorageMotion = compatibilityResult.third();

        // If no compatible hosts, return early
        if (CollectionUtils.isEmpty(filteredHosts)) {
            final Pair<List<? extends Host>, Integer> otherHosts = new Pair<>(allHostsPair.first(), allHostsPair.second());
            return new Ternary<>(otherHosts, new ArrayList<>(), requiresStorageMotion);
        }

        // Create deployment plan and VM profile
        final Host srcHost = _hostDao.findById(vm.getHostId());
        final DataCenterDeployment plan = new DataCenterDeployment(
            srcHost.getDataCenterId(), srcHost.getPodId(), srcHost.getClusterId(), null, null, null);
        final VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(
            vm, null, _offeringDao.findById(vm.getId(), vm.getServiceOfferingId()), null, null);

        // Apply affinity constraints
        final ExcludeList excludes = applyAffinityConstraints(vm, vmProfile, plan, vmList);

        // Get hosts with capacity
        List<Host> suitableHosts = getCapableSuitableHosts(vm, vmProfile, plan, filteredHosts, excludes, srcHost);

        final Pair<List<? extends Host>, Integer> otherHosts = new Pair<>(allHostsPair.first(), allHostsPair.second());
        return new Ternary<>(otherHosts, suitableHosts, requiresStorageMotion);
    }

    private void validateVgpuProfileForVmMigration(final VirtualMachineProfile vmProfile) {
        // Validate if the VM is using a vGPU profile that supports migration.
        ServiceOffering serviceOffering = vmProfile.getServiceOffering();
        if (serviceOffering.getVgpuProfileId() != null) {
            VgpuProfileVO vgpuProfile = vgpuProfileDao.findById(serviceOffering.getVgpuProfileId());
            if (vgpuProfile == null || "passthrough".equals(vgpuProfile.getName())) {
                throw new InvalidParameterValueException("Unsupported operation, VM uses host passthrough, cannot migrate");
            }
        }
    }

    /**
     * Add non DPDK enabled hosts to the avoid list
     */
    private void excludeNonDPDKEnabledHosts(DataCenterDeployment plan, ExcludeList excludes) {
        long dataCenterId = plan.getDataCenterId();
        Long clusterId = plan.getClusterId();
        Long podId = plan.getPodId();
        List<HostVO> hosts = _hostDao.listAllUpAndEnabledNonHAHosts(Type.Routing, clusterId, podId, dataCenterId, null);
        for (HostVO host : hosts) {
            if (!dpdkHelper.isHostDpdkEnabled(host.getId())) {
                excludes.addHost(host.getId());
            }
        }
    }

    private boolean hasSuitablePoolsForVolume(final VolumeVO volume, final Host host, final VirtualMachineProfile vmProfile) {
        final DiskOfferingVO diskOffering = _diskOfferingDao.findById(volume.getDiskOfferingId());
        final DiskProfile diskProfile = new DiskProfile(volume, diskOffering, vmProfile.getHypervisorType());
        final DataCenterDeployment plan = new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), host.getId(), null, null);
        final ExcludeList avoid = new ExcludeList();

        for (final StoragePoolAllocator allocator : _storagePoolAllocators) {
            final List<StoragePool> poolList = allocator.allocateToPool(diskProfile, vmProfile, plan, avoid, 1);
            if (poolList != null && !poolList.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    @Override
    public Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForMigrationOfVolume(final Long volumeId, String keyword) {

        Pair<List<? extends StoragePool>, List<? extends StoragePool>> allPoolsAndSuitablePoolsPair = listStoragePoolsForMigrationOfVolumeInternal(volumeId, null, null, null, null, false, true, false, keyword);
        List<? extends StoragePool> allPools = allPoolsAndSuitablePoolsPair.first();
        List<? extends StoragePool> suitablePools = allPoolsAndSuitablePoolsPair.second();
        List<StoragePool> avoidPools = new ArrayList<>();

        final VolumeVO volume = _volumeDao.findById(volumeId);
        StoragePool srcVolumePool = _poolDao.findById(volume.getPoolId());
        if (srcVolumePool.getParent() != 0L) {
            StoragePool datastoreCluster = _poolDao.findById(srcVolumePool.getParent());
            avoidPools.add(datastoreCluster);
        }
        abstractDataStoreClustersList((List<StoragePool>) allPools, new ArrayList<>());
        abstractDataStoreClustersList((List<StoragePool>) suitablePools, avoidPools);
        return new Pair<>(allPools, suitablePools);
    }

    @Override
    public Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForSystemMigrationOfVolume(final Long volumeId, Long newDiskOfferingId, Long newSize, Long newMinIops, Long newMaxIops, boolean keepSourceStoragePool, boolean bypassStorageTypeCheck) {
        return listStoragePoolsForMigrationOfVolumeInternal(volumeId, newDiskOfferingId, newSize, newMinIops, newMaxIops, keepSourceStoragePool, bypassStorageTypeCheck, true, null);
    }

    public Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForMigrationOfVolumeInternal(final Long volumeId, Long newDiskOfferingId, Long newSize, Long newMinIops, Long newMaxIops, boolean keepSourceStoragePool, boolean bypassStorageTypeCheck, boolean bypassAccountCheck, String keyword) {
        if (!bypassAccountCheck) {
            final Account caller = getCaller();
            if (!_accountMgr.isRootAdmin(caller.getId())) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Caller is not a root admin, permission denied to migrate the volume");
                }
                throw new PermissionDeniedException("No permission to migrate volume, only root admin can migrate a volume");
            }
        }

        final VolumeVO volume = _volumeDao.findById(volumeId);
        if (volume == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find volume with" + " specified id.");
            ex.addProxyObject(volumeId.toString(), "volumeId");
            throw ex;
        }

        Long diskOfferingId = volume.getDiskOfferingId();
        if (newDiskOfferingId != null) {
            diskOfferingId = newDiskOfferingId;
        }

        // Volume must be attached to an instance for live migration.
        List<? extends StoragePool> allPools = new ArrayList<>();
        List<StoragePool> suitablePools = new ArrayList<>();

        // Volume must be in Ready state to be migrated.
        if (!Volume.State.Ready.equals(volume.getState())) {
            logger.info("Volume " + volume + " must be in ready state for migration.");
            return new Pair<>(allPools, suitablePools);
        }

        final Long instanceId = volume.getInstanceId();
        VMInstanceVO vm = null;
        if (instanceId != null) {
            vm = _vmInstanceDao.findById(instanceId);
        }

        if (vm == null) {
            logger.info("Volume " + volume + " isn't attached to any Instance. Looking for storage pools in the " + "zone to which this volumes can be migrated.");
        } else if (vm.getState() != State.Running) {
            logger.info("Volume " + volume + " isn't attached to any running Instance. Looking for storage pools in the " + "cluster to which this volumes can be migrated.");
        } else {
            logger.info("Volume " + volume + " is attached to any running Instance. Looking for storage pools in the " + "cluster to which this volumes can be migrated.");
            boolean storageMotionSupported = false;
            // Check if the underlying hypervisor supports storage motion.
            final Long hostId = vm.getHostId();
            if (hostId != null) {
                final HostVO host = _hostDao.findById(hostId);
                HypervisorCapabilitiesVO capabilities = null;
                if (host != null) {
                    capabilities = _hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(host.getHypervisorType(), host.getHypervisorVersion());
                } else {
                    logger.error("Details of the host on which the Instance " + vm + ", to which volume " + volume + " is " + "attached, couldn't be retrieved.");
                }

                if (capabilities != null) {
                    storageMotionSupported = capabilities.isStorageMotionSupported();
                } else {
                    logger.error("Capabilities for host " + host + " couldn't be retrieved.");
                }
            }

            if (!storageMotionSupported) {
                logger.info("Volume " + volume + " is attached to a running Instance and the hypervisor doesn't support" + " storage motion.");
                return new Pair<>(allPools, suitablePools);
            }
        }

        StoragePool srcVolumePool = _poolDao.findById(volume.getPoolId());
        HypervisorType hypervisorType = getHypervisorType(vm, srcVolumePool);
        Pair<Host, List<Cluster>> hostClusterPair = getVolumeVmHostClusters(srcVolumePool, vm, hypervisorType);
        Host vmHost = hostClusterPair.first();
        List<Cluster> clusters = hostClusterPair.second();
        allPools = getAllStoragePoolCompatibleWithVolumeSourceStoragePool(srcVolumePool, hypervisorType, clusters, keyword);
        ExcludeList avoid = new ExcludeList();
        if (!keepSourceStoragePool) {
            allPools.remove(srcVolumePool);
            avoid.addPool(srcVolumePool.getId());
        }
        if (vm != null) {
            suitablePools = findAllSuitableStoragePoolsForVm(volume, diskOfferingId, newSize, newMinIops, newMaxIops, vm, vmHost, avoid,
                    CollectionUtils.isNotEmpty(clusters) ? clusters.get(0) : null, hypervisorType, bypassStorageTypeCheck, keyword);
        } else {
            suitablePools = findAllSuitableStoragePoolsForDetachedVolume(volume, diskOfferingId, allPools);
        }
        removeDataStoreClusterParents((List<StoragePool>) allPools);
        removeDataStoreClusterParents(suitablePools);
        return new Pair<>(allPools, suitablePools);
    }

    private void removeDataStoreClusterParents(List<StoragePool> storagePools) {
        Predicate<StoragePool> childDatastorePredicate = pool -> (pool.getParent() != 0);
        List<StoragePool> childDatastores = storagePools.stream().filter(childDatastorePredicate).collect(Collectors.toList());
        if (!childDatastores.isEmpty()) {
            Set<Long> parentStoragePoolIds = childDatastores.stream().map(mo -> mo.getParent()).collect(Collectors.toSet());
            for (Long parentStoragePoolId : parentStoragePoolIds) {
                StoragePool parentPool = _poolDao.findById(parentStoragePoolId);
                storagePools.remove(parentPool);
            }
        }
    }

        private void abstractDataStoreClustersList(List<StoragePool> storagePools, List<StoragePool> avoidPools) {
        Predicate<StoragePool> childDatastorePredicate = pool -> (pool.getParent() != 0);
        List<StoragePool> childDatastores = storagePools.stream().filter(childDatastorePredicate).collect(Collectors.toList());
        storagePools.removeAll(avoidPools);
        if (!childDatastores.isEmpty()) {
            storagePools.removeAll(childDatastores);
            Set<Long> parentStoragePoolIds = childDatastores.stream().map(mo -> mo.getParent()).collect(Collectors.toSet());
            for (Long parentStoragePoolId : parentStoragePoolIds) {
                StoragePool parentPool = _poolDao.findById(parentStoragePoolId);
                if (!storagePools.contains(parentPool) && !avoidPools.contains(parentPool))
                    storagePools.add(parentPool);
            }
        }
    }

    private Pair<Host, List<Cluster>> getVolumeVmHostClusters(StoragePool srcVolumePool, VirtualMachine vm, HypervisorType hypervisorType) {
        Host host = null;
        List<Cluster> clusters = new ArrayList<>();
        Long clusterId = srcVolumePool.getClusterId();
        if (vm != null) {
            Long hostId = vm.getHostId();
            if (hostId == null) {
                hostId = vm.getLastHostId();
            }
            if (hostId != null) {
                host = _hostDao.findById(hostId);
            }
        }
        if (clusterId == null && host != null) {
            clusterId = host.getClusterId();
        }
        if (clusterId != null && vm != null) {
            clusters.add(_clusterDao.findById(clusterId));
        } else {
            clusters.addAll(_clusterDao.listByDcHyType(srcVolumePool.getDataCenterId(), hypervisorType.toString()));
        }
        return new Pair<>(host, clusters);
    }

    /**
     * This method looks for all storage pools that are compatible with the given volume.
     * <ul>
     *  <li>We will look for storage systems that are zone wide.</li>
     *  <li>We also all storage available filtering by data center, pod and cluster as the current storage pool used by the given volume.</li>
     * </ul>
     */
    private List<? extends StoragePool> getAllStoragePoolCompatibleWithVolumeSourceStoragePool(StoragePool srcVolumePool, HypervisorType hypervisorType, List<Cluster> clusters, String keyword) {
        List<StoragePoolVO> storagePools = new ArrayList<>();
        List<StoragePoolVO> zoneWideStoragePools = _poolDao.findZoneWideStoragePoolsByHypervisor(srcVolumePool.getDataCenterId(), hypervisorType, keyword);
        if (CollectionUtils.isNotEmpty(zoneWideStoragePools)) {
            storagePools.addAll(zoneWideStoragePools);
        }
        if (CollectionUtils.isNotEmpty(clusters)) {
            List<Long> clusterIds = clusters.stream().map(Cluster::getId).collect(Collectors.toList());
            List<StoragePoolVO> clusterAndLocalStoragePools = _poolDao.findPoolsInClusters(clusterIds, keyword);
            if (CollectionUtils.isNotEmpty(clusterAndLocalStoragePools)) {
                storagePools.addAll(clusterAndLocalStoragePools);
            }
        }

        return storagePools;
    }

    /**
     *  Looks for all suitable storage pools to allocate the given volume.
     *  We take into account the service offering of the VM and volume to find suitable storage pools. It is also excluded from the search the current storage pool used by the volume.
     *  We use {@link StoragePoolAllocator} to look for possible storage pools to allocate the given volume. We will look for possible local storage poosl even if the volume is using a shared storage disk offering.
     * <p>
     *  Side note: the idea behind this method is to provide power for administrators of manually overriding deployments defined by CloudStack.
     */
    private List<StoragePool> findAllSuitableStoragePoolsForVm(final VolumeVO volume, Long diskOfferingId, Long newSize, Long newMinIops, Long newMaxIops, VMInstanceVO vm, Host vmHost, ExcludeList avoid, Cluster srcCluster, HypervisorType hypervisorType, boolean bypassStorageTypeCheck, String keyword) {
        List<StoragePool> suitablePools = new ArrayList<>();
        Long clusterId = null;
        Long podId = null;
        if (srcCluster != null) {
            clusterId = srcCluster.getId();
            podId = srcCluster.getPodId();
        }
        DataCenterDeployment plan = new DataCenterDeployment(volume.getDataCenterId(), podId, clusterId,
                null, null, null, null);
        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        // OfflineVmwareMigration: vm might be null here; deal!

        DiskOfferingVO diskOffering = _diskOfferingDao.findById(diskOfferingId);
        DiskProfile diskProfile = new DiskProfile(volume, diskOffering, hypervisorType);
        if (!Objects.equals(volume.getDiskOfferingId(), diskOfferingId)) {
            diskProfile.setSize(newSize);
            diskProfile.setMinIops(newMinIops);
            diskProfile.setMaxIops(newMaxIops);
        }

        for (StoragePoolAllocator allocator : _storagePoolAllocators) {
            List<StoragePool> pools = allocator.allocateToPool(diskProfile, profile, plan, avoid, StoragePoolAllocator.RETURN_UPTO_ALL, bypassStorageTypeCheck, keyword);
            if (CollectionUtils.isEmpty(pools)) {
                continue;
            }
            for (StoragePool pool : pools) {
                boolean isLocalPoolSameHostAsVmHost = pool.isLocal() &&
                        (vmHost == null || StringUtils.equals(vmHost.getPrivateIpAddress(), pool.getHostAddress()));
                if (isLocalPoolSameHostAsVmHost || pool.isShared()) {
                    suitablePools.add(pool);
                }
            }
        }
        return suitablePools;
    }

    private List<StoragePool> findAllSuitableStoragePoolsForDetachedVolume(Volume volume, Long diskOfferingId, List<? extends StoragePool> allPools) {
        List<StoragePool> suitablePools = new ArrayList<>();
        if (CollectionUtils.isEmpty(allPools)) {
            return  suitablePools;
        }
        DiskOfferingVO diskOffering = _diskOfferingDao.findById(diskOfferingId);
        List<String> tags = new ArrayList<>();
        String[] tagsArray = diskOffering.getTagsArray();
        if (tagsArray != null && tagsArray.length > 0) {
            tags = Arrays.asList(tagsArray);
        }
        Long[] poolIds = allPools.stream().map(StoragePool::getId).toArray(Long[]::new);
        List<StoragePoolJoinVO> pools = _poolJoinDao.searchByIds(poolIds);
        for (StoragePoolJoinVO storagePool : pools) {
            if (StoragePoolStatus.Up.equals(storagePool.getStatus()) &&
                    (CollectionUtils.isEmpty(tags) || tags.contains(storagePool.getTag()))) {
                Optional<? extends StoragePool> match = allPools.stream().filter(x -> x.getId() == storagePool.getId()).findFirst();
                match.ifPresent(suitablePools::add);
            }
        }
        return suitablePools;
    }

    Pair<List<HostVO>, Integer> searchForServers(final Long startIndex, final Long pageSize, final Object name, final Object type,
        final Object state, final Object zone, final Object pod, final Object cluster, final Object id, final Object keyword,
        final Object resourceState, final Object haHosts, final Object hypervisorType, final Object hypervisorVersion, final Object... excludes) {
        return clusterHostQueryService.searchForServers(startIndex, pageSize, name, type, state, zone, pod, cluster, id, keyword, resourceState,
                haHosts, hypervisorType, hypervisorVersion, excludes);
    }

    @Override
    public Pair<List<? extends Pod>, Integer> searchForPods(final ListPodsByCmd cmd) {
        final String podName = cmd.getPodName();
        final Long id = cmd.getId();
        Long zoneId = cmd.getZoneId();
        final Object keyword = cmd.getKeyword();
        final Object allocationState = cmd.getAllocationState();
        final String storageAccessGroup = cmd.getStorageAccessGroup();

        zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), zoneId);

        final Filter searchFilter = new Filter(HostPodVO.class, "dataCenterId", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchBuilder<HostPodVO> sb = _hostPodDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("allocationState", sb.entity().getAllocationState(), SearchCriteria.Op.EQ);
        if (storageAccessGroup != null) {
            sb.and().op("storageAccessGroupExact", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.EQ);
            sb.or("storageAccessGroupPrefix", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.or("storageAccessGroupSuffix", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.or("storageAccessGroupMiddle", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.cp();
        }

        final SearchCriteria<HostPodVO> sc = sb.create();
        if (keyword != null) {
            final SearchCriteria<HostPodVO> ssc = _hostPodDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("description", SearchCriteria.Op.LIKE, "%" + keyword + "%");

            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (podName != null) {
            sc.setParameters("name", podName);
        }

        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }

        if (allocationState != null) {
            sc.setParameters("allocationState", allocationState);
        }

        if (storageAccessGroup != null) {
            sc.setParameters("storageAccessGroupExact", storageAccessGroup);
            sc.setParameters("storageAccessGroupPrefix", storageAccessGroup + ",%");
            sc.setParameters("storageAccessGroupSuffix", "%," + storageAccessGroup);
            sc.setParameters("storageAccessGroupMiddle", "%," + storageAccessGroup + ",%");
        }

        final Pair<List<HostPodVO>, Integer> result = _hostPodDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public Pair<List<? extends Vlan>, Integer> searchForVlans(final ListVlanIpRangesCmd cmd) {
        // If an account name and domain ID are specified, look up the account
        final String accountName = cmd.getAccountName();
        final Long domainId = cmd.getDomainId();
        Long accountId = null;
        final Long networkId = cmd.getNetworkId();
        final Boolean forVirtual = cmd.isForVirtualNetwork();
        String vlanType = null;
        final Long projectId = cmd.getProjectId();
        final Long physicalNetworkId = cmd.getPhysicalNetworkId();

        if (accountName != null && domainId != null) {
            if (projectId != null) {
                throw new InvalidParameterValueException("Account and projectId can't be specified together");
            }
            final Account account = _accountDao.findActiveAccount(accountName, domainId);
            if (account == null) {
                final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find account " + accountName + " in specified domain");
                // Since we don't have a DomainVO object here, we directly set
                // tablename to "domain".
                final DomainVO domain = ApiDBUtils.findDomainById(domainId);
                String domainUuid = domainId.toString();
                if (domain != null) {
                    domainUuid = domain.getUuid();
                }
                ex.addProxyObject(domainUuid, "domainId");
                throw ex;
            } else {
                accountId = account.getId();
            }
        }

        if (forVirtual != null) {
            if (forVirtual) {
                vlanType = VlanType.VirtualNetwork.toString();
            } else {
                vlanType = VlanType.DirectAttached.toString();
            }
        }

        // set project information
        if (projectId != null) {
            final Project project = _projectMgr.getProject(projectId);
            if (project == null) {
                final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find project by id " + projectId);
                ex.addProxyObject(projectId.toString(), "projectId");
                throw ex;
            }
            accountId = project.getProjectAccountId();
        }

        final Filter searchFilter = new Filter(VlanVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());

        final Object id = cmd.getId();
        final Object vlan = cmd.getVlan();
        final Object dataCenterId = cmd.getZoneId();
        final Object podId = cmd.getPodId();
        final Object keyword = cmd.getKeyword();

        final SearchBuilder<VlanVO> sb = _vlanDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("vlan", sb.entity().getVlanTag(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("vlan", sb.entity().getVlanTag(), SearchCriteria.Op.EQ);
        sb.and("networkId", sb.entity().getNetworkId(), SearchCriteria.Op.EQ);
        sb.and("vlanType", sb.entity().getVlanType(), SearchCriteria.Op.EQ);
        sb.and("physicalNetworkId", sb.entity().getPhysicalNetworkId(), SearchCriteria.Op.EQ);

        if (accountId != null) {
            final SearchBuilder<AccountVlanMapVO> accountVlanMapSearch = _accountVlanMapDao.createSearchBuilder();
            accountVlanMapSearch.and("accountId", accountVlanMapSearch.entity().getAccountId(), SearchCriteria.Op.EQ);
            sb.join("accountVlanMapSearch", accountVlanMapSearch, sb.entity().getId(), accountVlanMapSearch.entity().getVlanDbId(), JoinBuilder.JoinType.INNER);
        }

        if (domainId != null) {
            DomainVO domain = ApiDBUtils.findDomainById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Unable to find domain with id " + domainId);
            }
            final SearchBuilder<DomainVlanMapVO> domainVlanMapSearch = _domainVlanMapDao.createSearchBuilder();
            domainVlanMapSearch.and("domainId", domainVlanMapSearch.entity().getDomainId(), SearchCriteria.Op.EQ);
            sb.join("domainVlanMapSearch", domainVlanMapSearch, sb.entity().getId(), domainVlanMapSearch.entity().getVlanDbId(), JoinType.INNER);
        }

        if (podId != null) {
            final SearchBuilder<PodVlanMapVO> podVlanMapSearch = _podVlanMapDao.createSearchBuilder();
            podVlanMapSearch.and("podId", podVlanMapSearch.entity().getPodId(), SearchCriteria.Op.EQ);
            sb.join("podVlanMapSearch", podVlanMapSearch, sb.entity().getId(), podVlanMapSearch.entity().getVlanDbId(), JoinBuilder.JoinType.INNER);
        }

        final SearchCriteria<VlanVO> sc = sb.create();
        if (keyword != null) {
            final SearchCriteria<VlanVO> ssc = _vlanDao.createSearchCriteria();
            ssc.addOr("vlanTag", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("ipRange", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("vlanTag", SearchCriteria.Op.SC, ssc);
        } else {
            if (id != null) {
                sc.setParameters("id", id);
            }

            if (vlan != null) {
                sc.setParameters("vlan", vlan);
            }

            if (dataCenterId != null) {
                sc.setParameters("dataCenterId", dataCenterId);
            }

            if (networkId != null) {
                sc.setParameters("networkId", networkId);
            }

            if (accountId != null) {
                sc.setJoinParameters("accountVlanMapSearch", "accountId", accountId);
            }

            if (podId != null) {
                sc.setJoinParameters("podVlanMapSearch", "podId", podId);
            }
            if (vlanType != null) {
                sc.setParameters("vlanType", vlanType);
            }

            if (physicalNetworkId != null) {
                sc.setParameters("physicalNetworkId", physicalNetworkId);
            }

            if (domainId != null) {
                sc.setJoinParameters("domainVlanMapSearch", "domainId", domainId);
            }
        }

        final Pair<List<VlanVO>, Integer> result = _vlanDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public Pair<List<? extends Configuration>, Integer> searchForConfigurations(final ListCfgsByCmd cmd) {
        return configurationListingService.searchForConfigurations(cmd);
    }

    @Override
    public Pair<List<? extends ConfigurationGroup>, Integer> listConfigurationGroups(ListCfgGroupsByCmd cmd) {
        return configurationListingService.listConfigurationGroups(cmd);
    }

    protected List<IpAddress.State> getStatesForIpAddressSearch(final ListPublicIpAddressesCmd cmd) {
        return publicIpAddressSearchService.getStatesForIpAddressSearch(cmd);
    }

    @Override
    public Pair<List<? extends IpAddress>, Integer> searchForIPAddresses(final ListPublicIpAddressesCmd cmd) {
        return publicIpAddressSearchService.searchForIPAddresses(cmd);
    }

    protected void setParameters(SearchCriteria<IPAddressVO> sc, final ListPublicIpAddressesCmd cmd, VlanType vlanType,
                 Boolean isAllocated, List<IpAddress.State> states) {
        publicIpAddressSearchService.setParameters(sc, cmd, vlanType, isAllocated, states);
    }

    @Override
    public Pair<List<? extends GuestOS>, Integer> listGuestOSByCriteria(final ListGuestOsCmd cmd) {
        return guestOsManagementService.listGuestOSByCriteria(cmd);
    }

    @Override
    public Pair<List<? extends GuestOsCategory>, Integer> listGuestOSCategoriesByCriteria(final ListGuestOsCategoriesCmd cmd) {
        return guestOsManagementService.listGuestOSCategoriesByCriteria(cmd);
    }

    @Override
    public GuestOsCategory addGuestOsCategory(AddGuestOsCategoryCmd cmd) {
        return guestOsManagementService.addGuestOsCategory(cmd);
    }

    @Override
    public GuestOsCategory updateGuestOsCategory(UpdateGuestOsCategoryCmd cmd) {
        return guestOsManagementService.updateGuestOsCategory(cmd);
    }

    @Override
    public boolean deleteGuestOsCategory(DeleteGuestOsCategoryCmd cmd) {
        return guestOsManagementService.deleteGuestOsCategory(cmd);
    }

    @Override
    public Pair<List<? extends GuestOSHypervisor>, Integer> listGuestOSMappingByCriteria(final ListGuestOsMappingCmd cmd) {
        return guestOsManagementService.listGuestOSMappingByCriteria(cmd);
    }

    @Override
    public GuestOSHypervisor addGuestOsMapping(final AddGuestOsMappingCmd cmd) {
        return guestOsManagementService.addGuestOsMapping(cmd);
    }

    @Override
    public GuestOSHypervisor getAddedGuestOsMapping(final Long guestOsMappingId) {
        return guestOsManagementService.getAddedGuestOsMapping(guestOsMappingId);
    }

    @Override
    public List<Pair<String, String>> getHypervisorGuestOsNames(GetHypervisorGuestOsNamesCmd getHypervisorGuestOsNamesCmd) {
        return guestOsManagementService.getHypervisorGuestOsNames(getHypervisorGuestOsNamesCmd);
    }

    @Override
    public GuestOS addGuestOs(final AddGuestOsCmd cmd) {
        return guestOsManagementService.addGuestOs(cmd);
    }

    @Override
    public GuestOS getAddedGuestOs(final Long guestOsId) {
        return guestOsManagementService.getAddedGuestOs(guestOsId);
    }

    @Override
    public GuestOS updateGuestOs(final UpdateGuestOsCmd cmd) {
        return guestOsManagementService.updateGuestOs(cmd);
    }

    @Override
    public boolean removeGuestOs(final RemoveGuestOsCmd cmd) {
        return guestOsManagementService.removeGuestOs(cmd);
    }

    @Override
    public GuestOSHypervisor updateGuestOsMapping(final UpdateGuestOsMappingCmd cmd) {
        return guestOsManagementService.updateGuestOsMapping(cmd);
    }

    @Override
    public boolean removeGuestOsMapping(final RemoveGuestOsMappingCmd cmd) {
        return guestOsManagementService.removeGuestOsMapping(cmd);
    }

    @Override
    public String getConsoleAccessUrlRoot(final long vmId) {
        return consoleAccessService.getConsoleAccessUrlRoot(vmId);
    }

    @Override
    public Pair<Boolean, String> setConsoleAccessForVm(long vmId, String sessionUuid) {
        return consoleAccessService.setConsoleAccessForVm(vmId, sessionUuid);
    }

    @Override
    public String getConsoleAccessAddress(long vmId) {
        return consoleAccessService.getConsoleAccessAddress(vmId);
    }

    @Override
    public Pair<String, Integer> getVncPort(final VirtualMachine vm) {
        return consoleAccessService.getVncPort(vm);
    }

    @Override
    public Pair<List<? extends Alert>, Integer> searchForAlerts(final ListAlertsCmd cmd) {
        return auditTrailService.searchForAlerts(cmd);
    }

    @Override
    public boolean archiveAlerts(final ArchiveAlertsCmd cmd) {
        return auditTrailService.archiveAlerts(cmd);
    }

    @Override
    public boolean deleteAlerts(final DeleteAlertsCmd cmd) {
        return auditTrailService.deleteAlerts(cmd);
    }

    @Override
    public List<CapacityVO> listTopConsumedResources(final ListCapacityCmd cmd) {
        return infrastructureUsageService.listTopConsumedResources(cmd);
    }

    @Override
    public List<CapacityVO> listCapacities(final ListCapacityCmd cmd) {
        return infrastructureUsageService.listCapacities(cmd);
    }

    @Override
    public long getMemoryOrCpuCapacityByHost(final Long hostId, final short capacityType) {
        return infrastructureUsageService.getMemoryOrCpuCapacityByHost(hostId, capacityType);
    }

    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(CreateAccountCmd.class);
        cmdList.add(DeleteAccountCmd.class);
        cmdList.add(DisableAccountCmd.class);
        cmdList.add(EnableAccountCmd.class);
        cmdList.add(LockAccountCmd.class);
        cmdList.add(UpdateAccountCmd.class);
        cmdList.add(CreateCounterCmd.class);
        cmdList.add(DeleteCounterCmd.class);
        cmdList.add(AddClusterCmd.class);
        cmdList.add(DeleteClusterCmd.class);
        cmdList.add(ListClustersCmd.class);
        cmdList.add(UpdateClusterCmd.class);
        cmdList.add(ListCfgsByCmd.class);
        cmdList.add(ListCfgGroupsByCmd.class);
        cmdList.add(ListHypervisorCapabilitiesCmd.class);
        cmdList.add(UpdateCfgCmd.class);
        cmdList.add(UpdateHypervisorCapabilitiesCmd.class);
        cmdList.add(ResetCfgCmd.class);
        cmdList.add(CreateDomainCmd.class);
        cmdList.add(DeleteDomainCmd.class);
        cmdList.add(ListDomainChildrenCmd.class);
        cmdList.add(ListDomainsCmd.class);
        cmdList.add(ListDomainsCmdByAdmin.class);
        cmdList.add(UpdateDomainCmd.class);
        cmdList.add(MoveDomainCmd.class);
        cmdList.add(AddHostCmd.class);
        cmdList.add(AddSecondaryStorageCmd.class);
        cmdList.add(CancelHostMaintenanceCmd.class);
        cmdList.add(CancelHostAsDegradedCmd.class);
        cmdList.add(DeclareHostAsDegradedCmd.class);
        cmdList.add(DeleteHostCmd.class);
        cmdList.add(ListHostsCmd.class);
        cmdList.add(ListHostTagsCmd.class);
        cmdList.add(FindHostsForMigrationCmd.class);
        cmdList.add(PrepareForHostMaintenanceCmd.class);
        cmdList.add(ReconnectHostCmd.class);
        cmdList.add(UpdateHostCmd.class);
        cmdList.add(UpdateHostPasswordCmd.class);
        cmdList.add(AddNetworkDeviceCmd.class);
        cmdList.add(AddNetworkServiceProviderCmd.class);
        cmdList.add(CreateNetworkOfferingCmd.class);
        cmdList.add(CloneNetworkOfferingCmd.class);
        cmdList.add(CreatePhysicalNetworkCmd.class);
        cmdList.add(CreateStorageNetworkIpRangeCmd.class);
        cmdList.add(DeleteNetworkDeviceCmd.class);
        cmdList.add(DeleteNetworkOfferingCmd.class);
        cmdList.add(DeleteNetworkServiceProviderCmd.class);
        cmdList.add(DeletePhysicalNetworkCmd.class);
        cmdList.add(DeleteStorageNetworkIpRangeCmd.class);
        cmdList.add(ListNetworkDeviceCmd.class);
        cmdList.add(ListNetworkServiceProvidersCmd.class);
        cmdList.add(ListPhysicalNetworksCmd.class);
        cmdList.add(ListStorageNetworkIpRangeCmd.class);
        cmdList.add(ListSupportedNetworkServicesCmd.class);
        cmdList.add(UpdateNetworkOfferingCmd.class);
        cmdList.add(UpdateNetworkServiceProviderCmd.class);
        cmdList.add(UpdatePhysicalNetworkCmd.class);
        cmdList.add(UpdateStorageNetworkIpRangeCmd.class);
        cmdList.add(DedicateGuestVlanRangeCmd.class);
        cmdList.add(ListDedicatedGuestVlanRangesCmd.class);
        cmdList.add(ReleaseDedicatedGuestVlanRangeCmd.class);
        cmdList.add(CreateDiskOfferingCmd.class);
        cmdList.add(CloneDiskOfferingCmd.class);
        cmdList.add(CreateServiceOfferingCmd.class);
        cmdList.add(CloneServiceOfferingCmd.class);
        cmdList.add(DeleteDiskOfferingCmd.class);
        cmdList.add(DeleteServiceOfferingCmd.class);
        cmdList.add(IsAccountAllowedToCreateOfferingsWithTagsCmd.class);
        cmdList.add(UpdateDiskOfferingCmd.class);
        cmdList.add(UpdateServiceOfferingCmd.class);
        cmdList.add(CreatePodCmd.class);
        cmdList.add(DeletePodCmd.class);
        cmdList.add(ListPodsByCmd.class);
        cmdList.add(UpdatePodCmd.class);
        cmdList.add(AddRegionCmd.class);
        cmdList.add(RemoveRegionCmd.class);
        cmdList.add(UpdateRegionCmd.class);
        cmdList.add(ListAlertsCmd.class);
        cmdList.add(ListAlertTypesCmd.class);
        cmdList.add(ListCapacityCmd.class);
        cmdList.add(UpdatePodManagementNetworkIpRangeCmd.class);
        cmdList.add(UploadCustomCertificateCmd.class);
        cmdList.add(ConfigureVirtualRouterElementCmd.class);
        cmdList.add(CreateVirtualRouterElementCmd.class);
        cmdList.add(DestroyRouterCmd.class);
        cmdList.add(ListRoutersCmd.class);
        cmdList.add(ListVirtualRouterElementsCmd.class);
        cmdList.add(RebootRouterCmd.class);
        cmdList.add(StartRouterCmd.class);
        cmdList.add(StopRouterCmd.class);
        cmdList.add(UpgradeRouterCmd.class);
        cmdList.add(AddSwiftCmd.class);
        cmdList.add(CancelPrimaryStorageMaintenanceCmd.class);
        cmdList.add(ChangeStoragePoolScopeCmd.class);
        cmdList.add(CreateStoragePoolCmd.class);
        cmdList.add(DeletePoolCmd.class);
        cmdList.add(ListSwiftsCmd.class);
        cmdList.add(ListStoragePoolsCmd.class);
        cmdList.add(ListStorageTagsCmd.class);
        cmdList.add(ListStorageAccessGroupsCmd.class);
        cmdList.add(FindStoragePoolsForMigrationCmd.class);
        cmdList.add(PreparePrimaryStorageForMaintenanceCmd.class);
        cmdList.add(UpdateStoragePoolCmd.class);
        cmdList.add(SyncStoragePoolCmd.class);
        cmdList.add(UpdateStorageCapabilitiesCmd.class);
        cmdList.add(UpdateImageStoreCmd.class);
        cmdList.add(ConfigureStorageAccessCmd.class);
        cmdList.add(DestroySystemVmCmd.class);
        cmdList.add(ListSystemVMsCmd.class);
        cmdList.add(MigrateSystemVMCmd.class);
        cmdList.add(RebootSystemVmCmd.class);
        cmdList.add(StartSystemVMCmd.class);
        cmdList.add(StopSystemVmCmd.class);
        cmdList.add(UpgradeSystemVMCmd.class);
        cmdList.add(PrepareTemplateCmd.class);
        cmdList.add(AddTrafficMonitorCmd.class);
        cmdList.add(AddTrafficTypeCmd.class);
        cmdList.add(DeleteTrafficMonitorCmd.class);
        cmdList.add(DeleteTrafficTypeCmd.class);
        cmdList.add(GenerateUsageRecordsCmd.class);
        cmdList.add(ListUsageRecordsCmd.class);
        cmdList.add(RemoveRawUsageRecordsCmd.class);
        cmdList.add(ListTrafficMonitorsCmd.class);
        cmdList.add(ListTrafficTypeImplementorsCmd.class);
        cmdList.add(ListTrafficTypesCmd.class);
        cmdList.add(ListUsageTypesCmd.class);
        cmdList.add(UpdateTrafficTypeCmd.class);
        cmdList.add(CreateUserCmd.class);
        cmdList.add(DeleteUserCmd.class);
        cmdList.add(DisableUserCmd.class);
        cmdList.add(EnableUserCmd.class);
        cmdList.add(GetUserCmd.class);
        cmdList.add(ListUsersCmd.class);
        cmdList.add(LockUserCmd.class);
        cmdList.add(MoveUserCmd.class);
        cmdList.add(RegisterUserKeysCmd.class);
        cmdList.add(UpdateUserCmd.class);
        cmdList.add(CreateVlanIpRangeCmd.class);
        cmdList.add(UpdateVlanIpRangeCmd.class);
        cmdList.add(DeleteVlanIpRangeCmd.class);
        cmdList.add(ListVlanIpRangesCmd.class);
        cmdList.add(DedicatePublicIpRangeCmd.class);
        cmdList.add(ReleasePublicIpRangeCmd.class);
        cmdList.add(AssignVMCmd.class);
        cmdList.add(MigrateVMCmd.class);
        cmdList.add(MigrateVirtualMachineWithVolumeCmd.class);
        cmdList.add(RecoverVMCmd.class);
        cmdList.add(CreatePrivateGatewayCmd.class);
        cmdList.add(CreateVPCOfferingCmd.class);
        cmdList.add(CloneVPCOfferingCmd.class);
        cmdList.add(DeletePrivateGatewayCmd.class);
        cmdList.add(DeleteVPCOfferingCmd.class);
        cmdList.add(UpdateVPCOfferingCmd.class);
        cmdList.add(CreateZoneCmd.class);
        cmdList.add(DeleteZoneCmd.class);
        cmdList.add(MarkDefaultZoneForAccountCmd.class);
        cmdList.add(UpdateZoneCmd.class);
        cmdList.add(AddAccountToProjectCmd.class);
        cmdList.add(AddUserToProjectCmd.class);
        cmdList.add(DeleteAccountFromProjectCmd.class);
        cmdList.add(DeleteUserFromProjectCmd.class);
        cmdList.add(ListAccountsCmd.class);
        cmdList.add(ListProjectAccountsCmd.class);
        cmdList.add(AssociateIPAddrCmd.class);
        cmdList.add(DisassociateIPAddrCmd.class);
        cmdList.add(ReserveIPAddrCmd.class);
        cmdList.add(ReleaseIPAddrCmd.class);
        cmdList.add(ListPublicIpAddressesCmd.class);
        cmdList.add(CreateAutoScalePolicyCmd.class);
        cmdList.add(CreateAutoScaleVmGroupCmd.class);
        cmdList.add(CreateAutoScaleVmProfileCmd.class);
        cmdList.add(CreateConditionCmd.class);
        cmdList.add(DeleteAutoScalePolicyCmd.class);
        cmdList.add(DeleteAutoScaleVmGroupCmd.class);
        cmdList.add(DeleteAutoScaleVmProfileCmd.class);
        cmdList.add(DeleteConditionCmd.class);
        cmdList.add(DisableAutoScaleVmGroupCmd.class);
        cmdList.add(EnableAutoScaleVmGroupCmd.class);
        cmdList.add(ListAutoScalePoliciesCmd.class);
        cmdList.add(ListAutoScaleVmGroupsCmd.class);
        cmdList.add(ListAutoScaleVmProfilesCmd.class);
        cmdList.add(ListConditionsCmd.class);
        cmdList.add(ListCountersCmd.class);
        cmdList.add(UpdateAutoScalePolicyCmd.class);
        cmdList.add(UpdateAutoScaleVmGroupCmd.class);
        cmdList.add(UpdateAutoScaleVmProfileCmd.class);
        cmdList.add(UpdateConditionCmd.class);
        cmdList.add(ListCapabilitiesCmd.class);
        cmdList.add(ListEventsCmd.class);
        cmdList.add(ListEventTypesCmd.class);
        cmdList.add(CreateEgressFirewallRuleCmd.class);
        cmdList.add(CreateFirewallRuleCmd.class);
        cmdList.add(CreatePortForwardingRuleCmd.class);
        cmdList.add(DeleteEgressFirewallRuleCmd.class);
        cmdList.add(DeleteFirewallRuleCmd.class);
        cmdList.add(DeletePortForwardingRuleCmd.class);
        cmdList.add(ListEgressFirewallRulesCmd.class);
        cmdList.add(ListFirewallRulesCmd.class);
        cmdList.add(ListPortForwardingRulesCmd.class);
        cmdList.add(UpdatePortForwardingRuleCmd.class);
        cmdList.add(ListGuestOsCategoriesCmd.class);
        cmdList.add(AddGuestOsCategoryCmd.class);
        cmdList.add(UpdateGuestOsCategoryCmd.class);
        cmdList.add(DeleteGuestOsCategoryCmd.class);
        cmdList.add(ListGuestOsCmd.class);
        cmdList.add(ListGuestOsMappingCmd.class);
        cmdList.add(AddGuestOsCmd.class);
        cmdList.add(AddGuestOsMappingCmd.class);
        cmdList.add(UpdateGuestOsCmd.class);
        cmdList.add(UpdateGuestOsMappingCmd.class);
        cmdList.add(RemoveGuestOsCmd.class);
        cmdList.add(RemoveGuestOsMappingCmd.class);
        cmdList.add(GetHypervisorGuestOsNamesCmd.class);
        cmdList.add(AttachIsoCmd.class);
        cmdList.add(CopyIsoCmd.class);
        cmdList.add(DeleteIsoCmd.class);
        cmdList.add(DetachIsoCmd.class);
        cmdList.add(ExtractIsoCmd.class);
        cmdList.add(ListIsoPermissionsCmd.class);
        cmdList.add(ListIsosCmd.class);
        cmdList.add(RegisterIsoCmd.class);
        cmdList.add(UpdateIsoCmd.class);
        cmdList.add(UpdateIsoPermissionsCmd.class);
        cmdList.add(ListAsyncJobsCmd.class);
        cmdList.add(QueryAsyncJobResultCmd.class);
        cmdList.add(AssignToLoadBalancerRuleCmd.class);
        cmdList.add(CreateLBStickinessPolicyCmd.class);
        cmdList.add(CreateLBHealthCheckPolicyCmd.class);
        cmdList.add(CreateLoadBalancerRuleCmd.class);
        cmdList.add(DeleteLBStickinessPolicyCmd.class);
        cmdList.add(DeleteLBHealthCheckPolicyCmd.class);
        cmdList.add(DeleteLoadBalancerRuleCmd.class);
        cmdList.add(ListLBStickinessPoliciesCmd.class);
        cmdList.add(ListLBHealthCheckPoliciesCmd.class);
        cmdList.add(ListLoadBalancerRuleInstancesCmd.class);
        cmdList.add(ListLoadBalancerRulesCmd.class);
        cmdList.add(RemoveFromLoadBalancerRuleCmd.class);
        cmdList.add(UpdateLoadBalancerRuleCmd.class);
        cmdList.add(CreateIpForwardingRuleCmd.class);
        cmdList.add(DeleteIpForwardingRuleCmd.class);
        cmdList.add(DisableStaticNatCmd.class);
        cmdList.add(EnableStaticNatCmd.class);
        cmdList.add(ListIpForwardingRulesCmd.class);
        cmdList.add(CreateNetworkACLCmd.class);
        cmdList.add(ImportNetworkACLCmd.class);
        cmdList.add(CreateNetworkCmd.class);
        cmdList.add(DeleteNetworkACLCmd.class);
        cmdList.add(DeleteNetworkCmd.class);
        cmdList.add(ListNetworkACLsCmd.class);
        cmdList.add(ListNetworkOfferingsCmd.class);
        cmdList.add(ListNetworkProtocolsCmd.class);
        cmdList.add(ListNetworksCmd.class);
        cmdList.add(RestartNetworkCmd.class);
        cmdList.add(UpdateNetworkCmd.class);
        cmdList.add(CreateNetworkPermissionsCmd.class);
        cmdList.add(ListNetworkPermissionsCmd.class);
        cmdList.add(RemoveNetworkPermissionsCmd.class);
        cmdList.add(ResetNetworkPermissionsCmd.class);
        cmdList.add(ListDiskOfferingsCmd.class);
        cmdList.add(ListServiceOfferingsCmd.class);
        cmdList.add(ActivateProjectCmd.class);
        cmdList.add(CreateProjectCmd.class);
        cmdList.add(DeleteProjectCmd.class);
        cmdList.add(DeleteProjectInvitationCmd.class);
        cmdList.add(ListProjectInvitationsCmd.class);
        cmdList.add(ListProjectsCmd.class);
        cmdList.add(SuspendProjectCmd.class);
        cmdList.add(UpdateProjectCmd.class);
        cmdList.add(UpdateProjectInvitationCmd.class);
        cmdList.add(ListRegionsCmd.class);
        cmdList.add(GetCloudIdentifierCmd.class);
        cmdList.add(ListHypervisorsCmd.class);
        cmdList.add(ListResourceLimitsCmd.class);
        cmdList.add(UpdateResourceCountCmd.class);
        cmdList.add(UpdateResourceLimitCmd.class);
        cmdList.add(AuthorizeSecurityGroupEgressCmd.class);
        cmdList.add(AuthorizeSecurityGroupIngressCmd.class);
        cmdList.add(CreateSecurityGroupCmd.class);
        cmdList.add(DeleteSecurityGroupCmd.class);
        cmdList.add(ListSecurityGroupsCmd.class);
        cmdList.add(RevokeSecurityGroupEgressCmd.class);
        cmdList.add(RevokeSecurityGroupIngressCmd.class);
        cmdList.add(UpdateSecurityGroupCmd.class);
        cmdList.add(CreateSnapshotCmd.class);
        cmdList.add(CreateSnapshotFromVMSnapshotCmd.class);
        cmdList.add(CopySnapshotCmd.class);
        cmdList.add(DeleteSnapshotCmd.class);
        cmdList.add(ExtractSnapshotCmd.class);
        cmdList.add(ArchiveSnapshotCmd.class);
        cmdList.add(CreateSnapshotPolicyCmd.class);
        cmdList.add(UpdateSnapshotPolicyCmd.class);
        cmdList.add(DeleteSnapshotPoliciesCmd.class);
        cmdList.add(ListSnapshotPoliciesCmd.class);
        cmdList.add(ListSnapshotsCmd.class);
        cmdList.add(RevertSnapshotCmd.class);
        cmdList.add(CreateSSHKeyPairCmd.class);
        cmdList.add(DeleteSSHKeyPairCmd.class);
        cmdList.add(ListSSHKeyPairsCmd.class);
        cmdList.add(RegisterSSHKeyPairCmd.class);
        cmdList.add(CreateTagsCmd.class);
        cmdList.add(DeleteTagsCmd.class);
        cmdList.add(ListTagsCmd.class);
        cmdList.add(CopyTemplateCmd.class);
        cmdList.add(CreateTemplateCmd.class);
        cmdList.add(DeleteTemplateCmd.class);
        cmdList.add(ExtractTemplateCmd.class);
        cmdList.add(ListTemplatePermissionsCmd.class);
        cmdList.add(ListTemplatesCmd.class);
        cmdList.add(ListDetailOptionsCmd.class);
        cmdList.add(RegisterTemplateCmd.class);
        cmdList.add(UpdateTemplateCmd.class);
        cmdList.add(UpdateTemplatePermissionsCmd.class);
        cmdList.add(AddNicToVMCmd.class);
        cmdList.add(DeployVMCmd.class);
        cmdList.add(DestroyVMCmd.class);
        cmdList.add(ExpungeVMCmd.class);
        cmdList.add(GetVMPasswordCmd.class);
        cmdList.add(ListVMsCmd.class);
        cmdList.add(ScaleVMCmd.class);
        cmdList.add(RebootVMCmd.class);
        cmdList.add(RemoveNicFromVMCmd.class);
        cmdList.add(ResetVMPasswordCmd.class);
        cmdList.add(ResetVMSSHKeyCmd.class);
        cmdList.add(ResetVMUserDataCmd.class);
        cmdList.add(RestoreVMCmd.class);
        cmdList.add(StartVMCmd.class);
        cmdList.add(StopVMCmd.class);
        cmdList.add(UpdateDefaultNicForVMCmd.class);
        cmdList.add(UpdateVMCmd.class);
        cmdList.add(UpdateVmNicCmd.class);
        cmdList.add(UpgradeVMCmd.class);
        cmdList.add(CreateVMGroupCmd.class);
        cmdList.add(DeleteVMGroupCmd.class);
        cmdList.add(ListVMGroupsCmd.class);
        cmdList.add(UpdateVMGroupCmd.class);
        cmdList.add(AttachVolumeCmd.class);
        cmdList.add(CheckAndRepairVolumeCmd.class);
        cmdList.add(CreateVolumeCmd.class);
        cmdList.add(DeleteVolumeCmd.class);
        cmdList.add(UpdateVolumeCmd.class);
        cmdList.add(DetachVolumeCmd.class);
        cmdList.add(ExtractVolumeCmd.class);
        cmdList.add(ListVolumesCmd.class);
        cmdList.add(MigrateVolumeCmd.class);
        cmdList.add(ResizeVolumeCmd.class);
        cmdList.add(UploadVolumeCmd.class);
        cmdList.add(DestroyVolumeCmd.class);
        cmdList.add(RecoverVolumeCmd.class);
        cmdList.add(ChangeOfferingForVolumeCmd.class);
        cmdList.add(CreateStaticRouteCmd.class);
        cmdList.add(CreateVPCCmd.class);
        cmdList.add(DeleteStaticRouteCmd.class);
        cmdList.add(DeleteVPCCmd.class);
        cmdList.add(ListPrivateGatewaysCmd.class);
        cmdList.add(ListStaticRoutesCmd.class);
        cmdList.add(ListVPCOfferingsCmd.class);
        cmdList.add(ListVPCsCmd.class);
        cmdList.add(RestartVPCCmd.class);
        cmdList.add(UpdateVPCCmd.class);
        cmdList.add(AddVpnUserCmd.class);
        cmdList.add(CreateRemoteAccessVpnCmd.class);
        cmdList.add(CreateVpnConnectionCmd.class);
        cmdList.add(CreateVpnCustomerGatewayCmd.class);
        cmdList.add(CreateVpnGatewayCmd.class);
        cmdList.add(DeleteRemoteAccessVpnCmd.class);
        cmdList.add(DeleteVpnConnectionCmd.class);
        cmdList.add(DeleteVpnCustomerGatewayCmd.class);
        cmdList.add(DeleteVpnGatewayCmd.class);
        cmdList.add(ListRemoteAccessVpnsCmd.class);
        cmdList.add(ListVpnConnectionsCmd.class);
        cmdList.add(ListVpnCustomerGatewaysCmd.class);
        cmdList.add(ListVpnGatewaysCmd.class);
        cmdList.add(ListVpnUsersCmd.class);
        cmdList.add(RemoveVpnUserCmd.class);
        cmdList.add(ResetVpnConnectionCmd.class);
        cmdList.add(UpdateVpnCustomerGatewayCmd.class);
        cmdList.add(ListZonesCmd.class);
        cmdList.add(ListVMSnapshotCmd.class);
        cmdList.add(CreateVMSnapshotCmd.class);
        cmdList.add(RevertToVMSnapshotCmd.class);
        cmdList.add(DeleteVMSnapshotCmd.class);
        cmdList.add(AddIpToVmNicCmd.class);
        cmdList.add(RemoveIpFromVmNicCmd.class);
        cmdList.add(UpdateVmNicIpCmd.class);
        cmdList.add(ListNicsCmd.class);
        cmdList.add(ArchiveAlertsCmd.class);
        cmdList.add(DeleteAlertsCmd.class);
        cmdList.add(ArchiveEventsCmd.class);
        cmdList.add(DeleteEventsCmd.class);
        cmdList.add(CreateGlobalLoadBalancerRuleCmd.class);
        cmdList.add(DeleteGlobalLoadBalancerRuleCmd.class);
        cmdList.add(ListGlobalLoadBalancerRuleCmd.class);
        cmdList.add(UpdateGlobalLoadBalancerRuleCmd.class);
        cmdList.add(AssignToGlobalLoadBalancerRuleCmd.class);
        cmdList.add(RemoveFromGlobalLoadBalancerRuleCmd.class);
        cmdList.add(ListStorageProvidersCmd.class);
        cmdList.add(AddImageStoreCmd.class);
        cmdList.add(AddImageStoreS3CMD.class);
        cmdList.add(ListImageStoresCmd.class);
        cmdList.add(DeleteImageStoreCmd.class);
        cmdList.add(CreateSecondaryStagingStoreCmd.class);
        cmdList.add(ListSecondaryStagingStoresCmd.class);
        cmdList.add(DeleteSecondaryStagingStoreCmd.class);
        cmdList.add(UpdateCloudToUseObjectStoreCmd.class);
        cmdList.add(CreateApplicationLoadBalancerCmd.class);
        cmdList.add(ListApplicationLoadBalancersCmd.class);
        cmdList.add(DeleteApplicationLoadBalancerCmd.class);
        cmdList.add(ConfigureInternalLoadBalancerElementCmd.class);
        cmdList.add(CreateInternalLoadBalancerElementCmd.class);
        cmdList.add(ListInternalLoadBalancerElementsCmd.class);
        cmdList.add(CreateAffinityGroupCmd.class);
        cmdList.add(DeleteAffinityGroupCmd.class);
        cmdList.add(ListAffinityGroupsCmd.class);
        cmdList.add(UpdateVMAffinityGroupCmd.class);
        cmdList.add(ListAffinityGroupTypesCmd.class);
        cmdList.add(CreatePortableIpRangeCmd.class);
        cmdList.add(DeletePortableIpRangeCmd.class);
        cmdList.add(ListPortableIpRangesCmd.class);
        cmdList.add(ListDeploymentPlannersCmd.class);
        cmdList.add(ReleaseHostReservationCmd.class);
        cmdList.add(ScaleSystemVMCmd.class);
        cmdList.add(AddResourceDetailCmd.class);
        cmdList.add(RemoveResourceDetailCmd.class);
        cmdList.add(ListResourceDetailsCmd.class);
        cmdList.add(StopInternalLBVMCmd.class);
        cmdList.add(StartInternalLBVMCmd.class);
        cmdList.add(ListInternalLBVMsCmd.class);
        cmdList.add(ListNetworkIsolationMethodsCmd.class);
        cmdList.add(CreateNetworkACLListCmd.class);
        cmdList.add(DeleteNetworkACLListCmd.class);
        cmdList.add(ListNetworkACLListsCmd.class);
        cmdList.add(ReplaceNetworkACLListCmd.class);
        cmdList.add(UpdateNetworkACLItemCmd.class);
        cmdList.add(MoveNetworkAclItemCmd.class);
        cmdList.add(CleanVMReservationsCmd.class);
        cmdList.add(UpgradeRouterTemplateCmd.class);
        cmdList.add(UploadSslCertCmd.class);
        cmdList.add(DeleteSslCertCmd.class);
        cmdList.add(ListSslCertsCmd.class);
        cmdList.add(AssignCertToLoadBalancerCmd.class);
        cmdList.add(RemoveCertFromLoadBalancerCmd.class);
        cmdList.add(GenerateAlertCmd.class);
        cmdList.add(ListOvsElementsCmd.class);
        cmdList.add(ConfigureOvsElementCmd.class);
        cmdList.add(GetVMUserDataCmd.class);
        cmdList.add(UpdateEgressFirewallRuleCmd.class);
        cmdList.add(UpdateFirewallRuleCmd.class);
        cmdList.add(UpdateNetworkACLListCmd.class);
        cmdList.add(UpdateApplicationLoadBalancerCmd.class);
        cmdList.add(UpdateIPAddrCmd.class);
        cmdList.add(UpdateRemoteAccessVpnCmd.class);
        cmdList.add(UpdateVpnConnectionCmd.class);
        cmdList.add(UpdateVpnGatewayCmd.class);
        cmdList.add(ListQuarantinedIpsCmd.class);
        cmdList.add(UpdateQuarantinedIpCmd.class);
        cmdList.add(RemoveQuarantinedIpCmd.class);
        // separated admin commands
        cmdList.add(ListAccountsCmdByAdmin.class);
        cmdList.add(ListZonesCmdByAdmin.class);
        cmdList.add(ListTemplatesCmdByAdmin.class);
        cmdList.add(CreateTemplateCmdByAdmin.class);
        cmdList.add(CopyTemplateCmdByAdmin.class);
        cmdList.add(RegisterTemplateCmdByAdmin.class);
        cmdList.add(ListTemplatePermissionsCmdByAdmin.class);
        cmdList.add(ListSnapshotsCmdByAdmin.class);
        cmdList.add(RegisterIsoCmdByAdmin.class);
        cmdList.add(CopyIsoCmdByAdmin.class);
        cmdList.add(ListIsosCmdByAdmin.class);
        cmdList.add(AttachIsoCmdByAdmin.class);
        cmdList.add(DetachIsoCmdByAdmin.class);
        cmdList.add(ListIsoPermissionsCmdByAdmin.class);
        cmdList.add(UpdateVMAffinityGroupCmdByAdmin.class);
        cmdList.add(AddNicToVMCmdByAdmin.class);
        cmdList.add(RemoveNicFromVMCmdByAdmin.class);
        cmdList.add(UpdateDefaultNicForVMCmdByAdmin.class);
        cmdList.add(ListLoadBalancerRuleInstancesCmdByAdmin.class);
        cmdList.add(DeployVMCmdByAdmin.class);
        cmdList.add(DestroyVMCmdByAdmin.class);
        cmdList.add(RebootVMCmdByAdmin.class);
        cmdList.add(ResetVMPasswordCmdByAdmin.class);
        cmdList.add(ResetVMSSHKeyCmdByAdmin.class);
        cmdList.add(RestoreVMCmdByAdmin.class);
        cmdList.add(ScaleVMCmdByAdmin.class);
        cmdList.add(StartVMCmdByAdmin.class);
        cmdList.add(StopVMCmdByAdmin.class);
        cmdList.add(UpdateVMCmdByAdmin.class);
        cmdList.add(UpgradeVMCmdByAdmin.class);
        cmdList.add(RevertToVMSnapshotCmdByAdmin.class);
        cmdList.add(ListVMsCmdByAdmin.class);
        cmdList.add(AttachVolumeCmdByAdmin.class);
        cmdList.add(CreateVolumeCmdByAdmin.class);
        cmdList.add(DetachVolumeCmdByAdmin.class);
        cmdList.add(MigrateVolumeCmdByAdmin.class);
        cmdList.add(ResizeVolumeCmdByAdmin.class);
        cmdList.add(UpdateVolumeCmdByAdmin.class);
        cmdList.add(UploadVolumeCmdByAdmin.class);
        cmdList.add(ListVolumesCmdByAdmin.class);
        cmdList.add(DestroyVolumeCmdByAdmin.class);
        cmdList.add(RecoverVolumeCmdByAdmin.class);
        cmdList.add(AssociateIPAddrCmdByAdmin.class);
        cmdList.add(ListPublicIpAddressesCmdByAdmin.class);
        cmdList.add(CreateNetworkCmdByAdmin.class);
        cmdList.add(UpdateNetworkCmdByAdmin.class);
        cmdList.add(ListNetworksCmdByAdmin.class);
        cmdList.add(CreateVPCCmdByAdmin.class);
        cmdList.add(ListVPCsCmdByAdmin.class);
        cmdList.add(UpdateVPCCmdByAdmin.class);
        cmdList.add(CreatePrivateGatewayByAdminCmd.class);
        cmdList.add(ListPrivateGatewaysCmdByAdminCmd.class);
        cmdList.add(UpdateLBStickinessPolicyCmd.class);
        cmdList.add(UpdateLBHealthCheckPolicyCmd.class);
        cmdList.add(GetUploadParamsForTemplateCmd.class);
        cmdList.add(GetUploadParamsForVolumeCmd.class);
        cmdList.add(MigrateNetworkCmd.class);
        cmdList.add(MigrateVPCCmd.class);
        cmdList.add(AcquirePodIpCmdByAdmin.class);
        cmdList.add(ReleasePodIpCmdByAdmin.class);
        cmdList.add(CreateManagementNetworkIpRangeCmd.class);
        cmdList.add(DeleteManagementNetworkIpRangeCmd.class);
        cmdList.add(UploadTemplateDirectDownloadCertificateCmd.class);
        cmdList.add(RevokeTemplateDirectDownloadCertificateCmd.class);
        cmdList.add(ListTemplateDirectDownloadCertificatesCmd.class);
        cmdList.add(ProvisionTemplateDirectDownloadCertificateCmd.class);
        cmdList.add(ListMgmtsCmd.class);
        cmdList.add(RemoveManagementServerCmd.class);
        cmdList.add(GetUploadParamsForIsoCmd.class);
        cmdList.add(GetRouterHealthCheckResultsCmd.class);
        cmdList.add(StartRollingMaintenanceCmd.class);
        cmdList.add(MigrateSecondaryStorageDataCmd.class);
        cmdList.add(MigrateResourcesToAnotherSecondaryStorageCmd.class);
        cmdList.add(UploadResourceIconCmd.class);
        cmdList.add(DeleteResourceIconCmd.class);
        cmdList.add(ListResourceIconCmd.class);
        cmdList.add(PatchSystemVMCmd.class);
        cmdList.add(ListGuestVlansCmd.class);
        cmdList.add(AssignVolumeCmd.class);
        cmdList.add(ListSecondaryStorageSelectorsCmd.class);
        cmdList.add(CreateSecondaryStorageSelectorCmd.class);
        cmdList.add(UpdateSecondaryStorageSelectorCmd.class);
        cmdList.add(RemoveSecondaryStorageSelectorCmd.class);
        cmdList.add(ListAffectedVmsForStorageScopeChangeCmd.class);
        cmdList.add(ListGuiThemesCmd.class);
        cmdList.add(UpdateGuiThemeCmd.class);
        cmdList.add(CreateGuiThemeCmd.class);
        cmdList.add(RemoveGuiThemeCmd.class);

        // Out-of-band management APIs for admins
        cmdList.add(EnableOutOfBandManagementForHostCmd.class);
        cmdList.add(DisableOutOfBandManagementForHostCmd.class);
        cmdList.add(EnableOutOfBandManagementForClusterCmd.class);
        cmdList.add(DisableOutOfBandManagementForClusterCmd.class);
        cmdList.add(EnableOutOfBandManagementForZoneCmd.class);
        cmdList.add(DisableOutOfBandManagementForZoneCmd.class);
        cmdList.add(ConfigureOutOfBandManagementCmd.class);
        cmdList.add(IssueOutOfBandManagementPowerActionCmd.class);
        cmdList.add(ChangeOutOfBandManagementPasswordCmd.class);

        cmdList.add(GetUserKeysCmd.class);

        // Console Session APIs
        cmdList.add(CreateConsoleEndpointCmd.class);
        cmdList.add(ListConsoleSessionsCmd.class);

        cmdList.add(DeleteUserKeysCmd.class);
        cmdList.add(ListUserKeysCmd.class);
        cmdList.add(ListUserKeyRulesCmd.class);

        //user data APIs
        cmdList.add(RegisterUserDataCmd.class);
        cmdList.add(DeleteUserDataCmd.class);
        cmdList.add(ListUserDataCmd.class);
        cmdList.add(LinkUserDataToTemplateCmd.class);
        cmdList.add(RegisterCniConfigurationCmd.class);
        cmdList.add(ListCniConfigurationCmd.class);
        cmdList.add(DeleteCniConfigurationCmd.class);

        //object store APIs
        cmdList.add(AddObjectStoragePoolCmd.class);
        cmdList.add(ListObjectStoragePoolsCmd.class);
        cmdList.add(UpdateObjectStoragePoolCmd.class);
        cmdList.add(DeleteObjectStoragePoolCmd.class);
        cmdList.add(CreateBucketCmd.class);
        cmdList.add(UpdateBucketCmd.class);
        cmdList.add(DeleteBucketCmd.class);
        cmdList.add(ListBucketsCmd.class);

        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return ManagementServer.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {exposeCloudStackVersionInApiXmlResponse, exposeCloudStackVersionInApiListCapabilities, vmPasswordLength, sshKeyLength, humanReadableSizes, customCsIdentifier};
    }

    protected class EventPurgeTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            try {
                final GlobalLock lock = GlobalLock.getInternLock("EventPurge");
                if (lock == null) {
                    logger.debug("Couldn't get the global lock");
                    return;
                }
                if (!lock.lock(30)) {
                    logger.debug("Couldn't lock the db");
                    return;
                }
                try {
                    final Calendar purgeCal = Calendar.getInstance();
                    purgeCal.add(Calendar.DAY_OF_YEAR, -_purgeDelay);
                    final Date purgeTime = purgeCal.getTime();
                    logger.debug("Deleting events older than: " + purgeTime);
                    final List<EventVO> oldEvents = _eventDao.listOlderEvents(purgeTime);
                    logger.debug("Found " + oldEvents.size() + " events to be purged");
                    for (final EventVO event : oldEvents) {
                        _eventDao.expunge(event.getId());
                    }
                } catch (final Exception e) {
                    logger.error("Exception ", e);
                } finally {
                    lock.unlock();
                }
            } catch (final Exception e) {
                logger.error("Exception ", e);
            }
        }
    }

    protected class AlertPurgeTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            try {
                final GlobalLock lock = GlobalLock.getInternLock("AlertPurge");
                if (lock == null) {
                    logger.debug("Couldn't get the global lock");
                    return;
                }
                if (!lock.lock(30)) {
                    logger.debug("Couldn't lock the db");
                    return;
                }
                try {
                    final Calendar purgeCal = Calendar.getInstance();
                    purgeCal.add(Calendar.DAY_OF_YEAR, -_alertPurgeDelay);
                    final Date purgeTime = purgeCal.getTime();
                    logger.debug("Deleting alerts older than: " + purgeTime);
                    final List<AlertVO> oldAlerts = _alertDao.listOlderAlerts(purgeTime);
                    logger.debug("Found " + oldAlerts.size() + " events to be purged");
                    for (final AlertVO alert : oldAlerts) {
                        _alertDao.expunge(alert.getId());
                    }
                } catch (final Exception e) {
                    logger.error("Exception ", e);
                } finally {
                    lock.unlock();
                }
            } catch (final Exception e) {
                logger.error("Exception ", e);
            }
        }
    }

    @Override
    public Pair<List<? extends VirtualMachine>, Integer> searchForSystemVm(final ListSystemVMsCmd cmd) {
        return systemVmOperationsService.searchForSystemVm(cmd);
    }

    @Override
    public VirtualMachine.Type findSystemVMTypeById(final long instanceId) {
        return systemVmLifecycleService.findSystemVMTypeById(instanceId);
    }

    @Override
    @ActionEvent(eventType = "", eventDescription = "", async = true)
    public VirtualMachine startSystemVM(final long vmId) {
        return systemVmLifecycleService.startSystemVM(vmId);
    }

    @Override
    @ActionEvent(eventType = "", eventDescription = "", async = true)
    public VMInstanceVO stopSystemVM(final StopSystemVmCmd cmd) throws ResourceUnavailableException, ConcurrentOperationException {
        return systemVmLifecycleService.stopSystemVM(cmd);
    }

    @Override
    public VMInstanceVO rebootSystemVM(final RebootSystemVmCmd cmd) {
        return systemVmLifecycleService.rebootSystemVM(cmd);
    }

    @Override
    @ActionEvent(eventType = "", eventDescription = "", async = true)
    public VMInstanceVO destroySystemVM(final DestroySystemVmCmd cmd) {
        return systemVmLifecycleService.destroySystemVM(cmd);
    }

    private String signRequest(final String request, final String key) {
        try {
            logger.info("Request: " + request);
            logger.info("Key: " + key);

            if (key != null && request != null) {
                final Mac mac = Mac.getInstance("HmacSHA1");
                final SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "HmacSHA1");
                mac.init(keySpec);
                mac.update(request.getBytes());
                final byte[] encryptedBytes = mac.doFinal();
                return new String(Base64.encodeBase64(encryptedBytes));
            }
        } catch (final Exception ex) {
            logger.error("unable to sign request", ex);
        }
        return null;
    }

    @Override
    public ArrayList<String> getCloudIdentifierResponse(final long userId) {
        final Account caller = getCaller();

        // verify that user exists
        User user = _accountMgr.getUserIncludingRemoved(userId);
        if (user == null || user.getRemoved() != null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find active user of specified id");
            ex.addProxyObject(String.valueOf(userId), "userId");
            throw ex;
        }

        // check permissions
        _accountMgr.checkAccess(caller, null, true, _accountMgr.getAccount(user.getAccountId()));

        String cloudIdentifier = _configDao.getValue("cloud.identifier");
        if (cloudIdentifier == null) {
            cloudIdentifier = "";
        }

        String signature = "";
        try {
            // get the user obj to get their secret key
            user = _accountMgr.getActiveUser(userId);
            ApiKeyPairVO latestKeypair = ApiDBUtils.searchForLatestUserKeyPair(user.getId());

            if (latestKeypair == null) {
                throw new InvalidParameterValueException(String.format("No API keypair for user [%s]. Please generate it.", user.getUsername()));
            }

            final String secretKey = latestKeypair.getSecretKey();
            signature = signRequest(cloudIdentifier, secretKey);
        } catch (final Exception e) {
            logger.warn("Exception whilst creating a signature:" + e);
        }

        final ArrayList<String> cloudParams = new ArrayList<>();
        cloudParams.add(cloudIdentifier);
        cloudParams.add(signature);

        return cloudParams;
    }

    @Override
    public Map<String, Object> listCapabilities(final ListCapabilitiesCmd cmd) {
        return capabilitiesService.listCapabilities(cmd);
    }

    @Override
    public GuestOSVO getGuestOs(final Long guestOsId) {
        return guestOsManagementService.getGuestOs(guestOsId);
    }

    @Override
    public GuestOSHypervisorVO getGuestOsHypervisor(final Long guestOsHypervisorId) {
        return guestOsManagementService.getGuestOsHypervisor(guestOsHypervisorId);
    }

    @Override
    public InstanceGroupVO updateVmGroup(final UpdateVMGroupCmd cmd) {
        final Account caller = getCaller();
        final Long groupId = cmd.getId();
        final String groupName = cmd.getGroupName();

        // Verify input parameters
        final InstanceGroupVO group = _vmGroupDao.findById(groupId);
        if (group == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("unable to find an instance group with specified groupId");
            ex.addProxyObject(groupId.toString(), "groupId");
            throw ex;
        }

        _accountMgr.checkAccess(caller, null, true, group);

        // Check if name is already in use by this account (exclude this group)
        final boolean isNameInUse = _vmGroupDao.isNameInUse(group.getAccountId(), groupName);

        if (isNameInUse && !group.getName().equals(groupName)) {
            throw new InvalidParameterValueException("Unable to update instance group, a group with name " + groupName + " already exists for account");
        }

        if (groupName != null) {
            _vmGroupDao.updateVmGroup(groupId, groupName);
        }

        return _vmGroupDao.findById(groupId);
    }

    @Override
    public String getVersion() {
        return capabilitiesService.getVersion();
    }

    @Override
    @DB
    public String uploadCertificate(final UploadCustomCertificateCmd cmd) {
        if (cmd.getPrivateKey() != null && cmd.getAlias() != null) {
            throw new InvalidParameterValueException("Can't change the alias for private key certification");
        }

        if (cmd.getPrivateKey() == null) {
            if (cmd.getAlias() == null) {
                throw new InvalidParameterValueException("alias can't be empty, if it's a certification chain");
            }

            if (cmd.getCertIndex() == null) {
                throw new InvalidParameterValueException("index can't be empty, if it's a certification chain");
            }
        }

        final String certificate = cmd.getCertificate();
        final String key = cmd.getPrivateKey();
        String domainSuffix = cmd.getDomainSuffix();

        validateCertificate(certificate, key, domainSuffix);

        if (cmd.getPrivateKey() != null) {
            _ksMgr.saveCertificate(ConsoleProxyManager.CERTIFICATE_NAME, certificate, key, domainSuffix);

            // Reboot ssvm here since private key is present - meaning server cert being passed
            final List<SecondaryStorageVmVO> alreadyRunning = _secStorageVmDao.getSecStorageVmListInStates(null, State.Running, State.Migrating, State.Starting);
            for (final SecondaryStorageVmVO ssVmVm : alreadyRunning) {
                _secStorageVmMgr.rebootSecStorageVm(ssVmVm.getId());
            }
        } else {
            _ksMgr.saveCertificate(cmd.getAlias(), certificate, cmd.getCertIndex(), cmd.getDomainSuffix());
        }

        _consoleProxyMgr.setManagementState(ConsoleProxyManagementState.ResetSuspending);
        return "Certificate has been successfully updated, if its the server certificate we would reboot all "
        + "running console proxy VMs and secondary storage VMs to propagate the new certificate, "
        + "please give a few minutes for console access and storage services service to be up and working again";
    }

    private void validateCertificate(String certificate, String key, String domainSuffix) {
        if (key != null) {
            Pair<Boolean, String> result = _ksMgr.validateCertificate(certificate, key, domainSuffix);
            if (!result.first()) {
                throw new InvalidParameterValueException(String.format("Failed to pass certificate validation check with error: %s", result.second()));
            }
        } else {
            try {
                logger.debug("Trying to validate the root certificate format");
                CertificateHelper.buildCertificate(certificate);
            } catch (CertificateException e) {
                String errorMsg = String.format("Failed to pass certificate validation check with error: Certificate validation failed due to exception: %s", e.getMessage());
                logger.error(errorMsg);
                throw new InvalidParameterValueException(errorMsg);
            }
        }
    }

    @Override
    public List<String> getHypervisors(final Long zoneId) {
        final List<String> result = new ArrayList<>();
        final String hypers = _configDao.getValue(Config.HypervisorList.key());
        final String[] hypervisors = hypers.split(",");

        if (zoneId != null) {
            if (zoneId == -1L) {
                final List<DataCenterVO> zones = _dcDao.listAll();

                for (final String hypervisor : hypervisors) {
                    int hyperCount = 0;
                    for (final DataCenterVO zone : zones) {
                        final List<ClusterVO> clusters = _clusterDao.listByDcHyType(zone.getId(), hypervisor);
                        if (!clusters.isEmpty()) {
                            hyperCount++;
                        }
                    }
                    if (hyperCount == zones.size()) {
                        result.add(hypervisor);
                    }
                }
            } else {
                final List<ClusterVO> clustersForZone = _clusterDao.listByZoneId(zoneId);
                for (final ClusterVO cluster : clustersForZone) {
                    result.add(cluster.getHypervisorType().getHypervisorDisplayName());
                }
            }

        } else {
            return Arrays.asList(hypervisors);
        }
        return result;
    }

    @Override
    public SSHKeyPair createSSHKeyPair(final CreateSSHKeyPairCmd cmd) {
        return sshKeyPairService.createSshKeyPair(cmd);
    }

    @Override
    public boolean deleteSSHKeyPair(final DeleteSSHKeyPairCmd cmd) {
        return sshKeyPairService.deleteSshKeyPair(cmd);
    }

    @Override
    public Pair<List<? extends SSHKeyPair>, Integer> listSSHKeyPairs(final ListSSHKeyPairsCmd cmd) {
        return sshKeyPairService.listSshKeyPairs(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_REGISTER_SSH_KEYPAIR, eventDescription = "Registering SSH keypair", async = true)
    public SSHKeyPair registerSSHKeyPair(final RegisterSSHKeyPairCmd cmd) {
        final Account owner = getOwner(cmd);
        checkForKeyByName(cmd, owner);
        checkForKeyByPublicKey(cmd, owner);

        final String name = cmd.getName();
        String key = cmd.getPublicKey();

        final String publicKey = getPublicKeyFromKeyKeyMaterial(key);
        final String fingerprint = getFingerprint(publicKey);

        return sshKeyPairService.saveSshKeyPair(name, fingerprint, publicKey, null, owner);
    }

    @Override
    public boolean deleteUserData(final DeleteUserDataCmd cmd) {
        return userDataRegistryService.deleteUserData(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_DELETE_CNI_CONFIG, eventDescription = "CNI Configuration deletion")
    public boolean deleteCniConfiguration(DeleteCniConfigurationCmd cmd) {
        return userDataRegistryService.deleteCniConfiguration(cmd);
    }

    @Override
    public Pair<List<? extends UserData>, Integer> listUserDatas(final ListUserDataCmd cmd, final boolean forCks) {
        return userDataRegistryService.listUserDatas(cmd, forCks);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_REGISTER_CNI_CONFIG, eventDescription = "registering CNI configuration", async = true)
    public UserData registerCniConfiguration(RegisterCniConfigurationCmd cmd) {
        return userDataRegistryService.registerCniConfiguration(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_REGISTER_USER_DATA, eventDescription = "registering userdata", async = true)
    public UserData registerUserData(final RegisterUserDataCmd cmd) {
        return userDataRegistryService.registerUserData(cmd);
    }

    /**
     * Delegating wrapper. Retained on the god class because
     * {@link #registerSSHKeyPair(RegisterSSHKeyPairCmd)} calls
     * {@link #getPublicKeyFromKeyKeyMaterial(String)} on {@code this}, and
     * existing tests verify the invocation count via a {@code @Spy} on
     * {@link ManagementServerImpl}.
     */
    private void checkForKeyByPublicKey(final RegisterSSHKeyPairCmd cmd, final Account owner) throws InvalidParameterValueException {
        sshKeyPairService.checkForExistingKeyByPublicKey(owner, getPublicKeyFromKeyKeyMaterial(cmd.getPublicKey()));
    }

    /**
     * Delegating wrapper. Retained on the god class because existing tests
     * stub this via {@code Mockito.doNothing().when(spy).checkForKeyByName(...)}.
     */
    protected void checkForKeyByName(final RegisterSSHKeyPairCmd cmd, final Account owner) throws InvalidParameterValueException {
        sshKeyPairService.checkForExistingKeyByName(cmd, owner);
    }

    /**
     * Delegating wrapper around {@link SshKeyPairService#computeFingerprint(String)}.
     */
    private String getFingerprint(final String publicKey) {
        return sshKeyPairService.computeFingerprint(publicKey);
    }

    /**
     * Delegating wrapper. Retained on the god class because existing tests
     * verify it is invoked on the {@code @Spy} a specific number of times.
     */
    protected String getPublicKeyFromKeyKeyMaterial(final String key) throws InvalidParameterValueException {
        return sshKeyPairService.extractPublicKey(key);
    }

    /**
     * @param cmd
     * @return
     */
    protected Account getOwner(final RegisterSSHKeyPairCmd cmd) {
        final Account caller = getCaller();

        return _accountMgr.finalizeOwner(caller, cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
    }

    /**
     * @return
     */
    protected Account getCaller() {
        return CallContext.current().getCallingAccount();
    }

    @Override
    public String getVMPassword(GetVMPasswordCmd cmd) {
        return hostCredentialsService.getVMPassword(cmd);
    }

    /**
     * This method updates the password of all hosts in a given cluster.
     */
    @Override
    @DB
    public boolean updateClusterPassword(final UpdateHostPasswordCmd command) {
        return hostCredentialsService.updateClusterPassword(command);
    }

    @Override
    @DB
    public boolean updateHostPassword(final UpdateHostPasswordCmd cmd) {
        return hostCredentialsService.updateHostPassword(cmd);
    }

    @Override
    public String[] listEventTypes() {
        return auditTrailService.listEventTypes();
    }

    @Override
    public Pair<List<? extends HypervisorCapabilities>, Integer> listHypervisorCapabilities(final Long id, final HypervisorType hypervisorType, final String keyword, final Long startIndex,
            final Long pageSizeVal) {
        return hypervisorCapabilitiesService.listHypervisorCapabilities(id, hypervisorType, keyword, startIndex, pageSizeVal);
    }

    @Override
    public HypervisorCapabilities updateHypervisorCapabilities(UpdateHypervisorCapabilitiesCmd cmd) {
        return hypervisorCapabilitiesService.updateHypervisorCapabilities(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_UPGRADE, eventDescription = "Upgrading system VM", async = true)
    public VirtualMachine upgradeSystemVM(final ScaleSystemVMCmd cmd) throws ResourceUnavailableException, ManagementServerException, VirtualMachineMigrationException, ConcurrentOperationException {
        return systemVmOperationsService.upgradeSystemVM(cmd);
    }

    @Override
    public VirtualMachine upgradeSystemVM(final UpgradeSystemVMCmd cmd) {
        return systemVmOperationsService.upgradeSystemVM(cmd);
    }

    private void enableAdminUser(final String password) {
        String encodedPassword = null;

        final UserVO adminUser = _userDao.getUser(2);
        if (adminUser == null) {
            final String msg = "CANNOT find admin user";
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
        if (adminUser.getState() == Account.State.DISABLED) {
            // This means its a new account, set the password using the
            // authenticator

            for (final UserAuthenticator authenticator : _userPasswordEncoders) {
                encodedPassword = authenticator.encode(password);
                if (encodedPassword != null) {
                    break;
                }
            }

            adminUser.setPassword(encodedPassword);
            adminUser.setState(Account.State.ENABLED);
            _userDao.persist(adminUser);
            logger.info("Admin user enabled");
        }

    }

    @Override
    public List<String> listDeploymentPlanners() {
        final List<String> plannersAvailable = new ArrayList<>();
        for (final DeploymentPlanner planner : _planners) {
            plannersAvailable.add(planner.getName());
        }

        return plannersAvailable;
    }

    @Override
    public void cleanupVMReservations() {
        if (logger.isDebugEnabled()) {
            logger.debug("Processing cleanupVMReservations");
        }

        _dpMgr.cleanupVMReservations();
    }

    @Override
    public Pair<Boolean, String> patchSystemVM(PatchSystemVMCmd cmd) {
        return systemVmOperationsService.patchSystemVM(cmd);
    }

    public Pair<Boolean, String> updateSystemVM(VMInstanceVO systemVM, boolean forced) {
        return systemVmOperationsService.updateSystemVM(systemVM, forced);
    }

    public List<StoragePoolAllocator> getStoragePoolAllocators() {
        return _storagePoolAllocators;
    }

    @Inject
    public void setStoragePoolAllocators(final List<StoragePoolAllocator> storagePoolAllocators) {
        _storagePoolAllocators = storagePoolAllocators;
    }

    public LockControllerListener getLockControllerListener() {
        return _lockControllerListener;
    }

    public void setLockControllerListener(final LockControllerListener lockControllerListener) {
        _lockControllerListener = lockControllerListener;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_MANAGEMENT_SERVER_REMOVE, eventDescription = "removing Management Server")
    public boolean removeManagementServer(RemoveManagementServerCmd cmd) {
        final Long id = cmd.getId();
        ManagementServerHostVO managementServer = managementServerHostDao.findById(id);

        if (managementServer == null) {
            throw new InvalidParameterValueException(String.format("Unable to find a Management Server with ID equal to [%s].", id));
        }

        if (!ManagementServerHost.State.Down.equals(managementServer.getState())) {
            throw new InvalidParameterValueException(String.format("Unable to remove Management Server with ID [%s]. It can only be removed when it is in the [%s] state, however currently it is in the [%s] state.", managementServer.getUuid(), ManagementServerHost.State.Down.name(), managementServer.getState().name()));
        }

        managementServerHostDao.remove(id);
        return true;

    }

    @Override
    public Answer getExternalVmConsole(VirtualMachine vm, Host host) {
        return extensionsManager.getInstanceConsole(vm, host);
    }

}
