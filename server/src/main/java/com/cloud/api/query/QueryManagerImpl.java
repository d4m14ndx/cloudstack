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
package com.cloud.api.query;

import static com.cloud.vm.VmDetailConstants.SSH_PUBLIC_KEY;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import com.cloud.network.PublicIpQuarantine;
import com.cloud.network.dao.PublicIpQuarantineDao;
import com.cloud.network.vo.PublicIpQuarantineVO;
import com.cloud.user.UserVO;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.RoleVO;
import org.apache.cloudstack.acl.dao.RoleDao;
import com.cloud.dc.Pod;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.org.Cluster;
import com.cloud.server.ManagementService;
import com.cloud.storage.dao.StoragePoolAndAccessGroupMapDao;

import com.cloud.vm.UserVmManager;
import org.apache.cloudstack.affinity.AffinityGroupResponse;
import org.apache.cloudstack.affinity.AffinityGroupVMMapVO;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ResourceDetail;
import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.command.admin.account.ListAccountsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.cluster.ListClustersCmd;
import org.apache.cloudstack.api.command.admin.domain.ListDomainsCmd;
import org.apache.cloudstack.api.command.admin.domain.ListDomainsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.host.ListHostTagsCmd;
import org.apache.cloudstack.api.command.admin.host.ListHostsCmd;
import org.apache.cloudstack.api.command.admin.internallb.ListInternalLBVMsCmd;
import org.apache.cloudstack.api.command.admin.management.ListMgmtsCmd;
import org.apache.cloudstack.api.command.admin.pod.ListPodsByCmd;
import org.apache.cloudstack.api.command.admin.resource.icon.ListResourceIconCmd;
import org.apache.cloudstack.api.command.admin.router.GetRouterHealthCheckResultsCmd;
import org.apache.cloudstack.api.command.admin.router.ListRoutersCmd;
import org.apache.cloudstack.api.command.admin.snapshot.ListSnapshotsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.storage.ListImageStoresCmd;
import org.apache.cloudstack.api.command.admin.storage.ListObjectStoragePoolsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListSecondaryStagingStoresCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageAccessGroupsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStoragePoolsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageTagsCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.ListSecondaryStorageSelectorsCmd;
import org.apache.cloudstack.api.command.admin.user.ListUsersCmd;
import org.apache.cloudstack.api.command.admin.vm.ListAffectedVmsForStorageScopeChangeCmd;

import org.apache.cloudstack.api.command.user.account.ListAccountsCmd;
import org.apache.cloudstack.api.command.user.account.ListProjectAccountsCmd;
import org.apache.cloudstack.api.command.user.address.ListQuarantinedIpsCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.ListAffinityGroupsCmd;
import org.apache.cloudstack.api.command.user.bucket.ListBucketsCmd;
import org.apache.cloudstack.api.command.user.event.ListEventsCmd;
import org.apache.cloudstack.api.command.user.iso.ListIsosCmd;
import org.apache.cloudstack.api.command.user.job.ListAsyncJobsCmd;
import org.apache.cloudstack.api.command.user.offering.ListDiskOfferingsCmd;
import org.apache.cloudstack.api.command.user.offering.ListServiceOfferingsCmd;
import org.apache.cloudstack.api.command.user.project.ListProjectInvitationsCmd;
import org.apache.cloudstack.api.command.user.project.ListProjectsCmd;
import org.apache.cloudstack.api.command.user.resource.ListDetailOptionsCmd;
import org.apache.cloudstack.api.command.user.securitygroup.ListSecurityGroupsCmd;
import org.apache.cloudstack.api.command.user.snapshot.CopySnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.ListSnapshotsCmd;
import org.apache.cloudstack.api.command.user.tag.ListTagsCmd;
import org.apache.cloudstack.api.command.user.template.ListTemplatesCmd;
import org.apache.cloudstack.api.command.user.vm.ListVMsCmd;
import org.apache.cloudstack.api.command.user.vmgroup.ListVMGroupsCmd;
import org.apache.cloudstack.api.command.user.volume.ListResourceDetailsCmd;
import org.apache.cloudstack.api.command.user.volume.ListVolumesCmd;
import org.apache.cloudstack.api.command.user.zone.ListZonesCmd;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.AsyncJobResponse;
import org.apache.cloudstack.api.response.BucketResponse;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.DetailOptionsResponse;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.DomainRouterResponse;
import org.apache.cloudstack.api.response.EventResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.HostTagResponse;
import org.apache.cloudstack.api.response.ImageStoreResponse;
import org.apache.cloudstack.api.response.InstanceGroupResponse;
import org.apache.cloudstack.api.response.IpQuarantineResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ManagementServerResponse;
import org.apache.cloudstack.api.response.ObjectStoreResponse;
import org.apache.cloudstack.api.response.PodResponse;
import org.apache.cloudstack.api.response.ProjectAccountResponse;
import org.apache.cloudstack.api.response.ProjectInvitationResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.ResourceDetailResponse;
import org.apache.cloudstack.api.response.ResourceIconResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.api.response.RouterHealthCheckResultResponse;
import org.apache.cloudstack.api.response.SecondaryStorageHeuristicsResponse;
import org.apache.cloudstack.api.response.SecurityGroupResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.SnapshotResponse;
import org.apache.cloudstack.api.response.StorageAccessGroupResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.StorageTagResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.UserResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.VirtualMachineResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.backup.BackupOfferingVO;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreCapabilities;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.outofbandmanagement.OutOfBandManagementVO;
import org.apache.cloudstack.outofbandmanagement.dao.OutOfBandManagementDao;
import org.apache.cloudstack.query.QueryService;
import org.apache.cloudstack.resourcedetail.DiskOfferingDetailVO;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.secstorage.HeuristicVO;
import org.apache.cloudstack.secstorage.dao.SecondaryStorageHeuristicDao;
import org.apache.cloudstack.secstorage.heuristics.Heuristic;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.baremetal.BaremetalUtils;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.AccountJoinDao;
import com.cloud.api.query.dao.AsyncJobJoinDao;
import com.cloud.api.query.dao.DataCenterJoinDao;
import com.cloud.api.query.dao.DiskOfferingJoinDao;
import com.cloud.api.query.dao.DomainJoinDao;
import com.cloud.api.query.dao.HostJoinDao;
import com.cloud.api.query.dao.InstanceGroupJoinDao;
import com.cloud.api.query.dao.ProjectAccountJoinDao;
import com.cloud.api.query.dao.ProjectInvitationJoinDao;
import com.cloud.api.query.dao.ProjectJoinDao;
import com.cloud.api.query.dao.ResourceTagJoinDao;
import com.cloud.api.query.dao.SecurityGroupJoinDao;
import com.cloud.api.query.dao.ServiceOfferingJoinDao;
import com.cloud.api.query.dao.SnapshotJoinDao;
import com.cloud.api.query.dao.StoragePoolJoinDao;
import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.api.query.dao.UserAccountJoinDao;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.dao.VolumeJoinDao;
import com.cloud.api.query.vo.AccountJoinVO;
import com.cloud.api.query.vo.AffinityGroupJoinVO;
import com.cloud.api.query.vo.AsyncJobJoinVO;
import com.cloud.api.query.vo.DataCenterJoinVO;
import com.cloud.api.query.vo.DiskOfferingJoinVO;
import com.cloud.api.query.vo.DomainJoinVO;
import com.cloud.api.query.vo.EventJoinVO;
import com.cloud.api.query.vo.HostJoinVO;
import com.cloud.api.query.vo.ImageStoreJoinVO;
import com.cloud.api.query.vo.InstanceGroupJoinVO;
import com.cloud.api.query.vo.ManagementServerJoinVO;
import com.cloud.api.query.vo.ProjectAccountJoinVO;
import com.cloud.api.query.vo.ProjectInvitationJoinVO;
import com.cloud.api.query.vo.ProjectJoinVO;
import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.api.query.vo.SecurityGroupJoinVO;
import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.api.query.vo.SnapshotJoinVO;
import com.cloud.api.query.vo.StoragePoolJoinVO;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.api.query.vo.UserAccountJoinVO;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.api.query.vo.VolumeJoinVO;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.cpu.CPU;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;

import com.cloud.dc.dao.ClusterDao;

import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.Host;
import com.cloud.host.HostTagVO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.as.AutoScaleVmGroupVmMapVO;
import com.cloud.network.as.dao.AutoScaleVmGroupVmMapDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.security.SecurityGroupVMMapVO;
import com.cloud.network.security.dao.SecurityGroupVMMapDao;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;

import com.cloud.projects.Project;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.projects.ProjectInvitation;
import com.cloud.projects.ProjectManager;
import com.cloud.projects.ProjectVO;
import com.cloud.projects.dao.ProjectAccountDao;
import com.cloud.projects.dao.ProjectDao;
import com.cloud.projects.dao.ProjectInvitationDao;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.icon.dao.ResourceIconDao;
import com.cloud.server.ResourceManagerUtil;
import com.cloud.server.ResourceMetaDataService;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.BucketVO;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.TemplateType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.StoragePoolTagVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.BucketDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.DateUtil;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;

import com.cloud.vm.InstanceGroupVMMapVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VmDetailConstants;

import com.cloud.vm.dao.InstanceGroupVMMapDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
public class QueryManagerImpl extends MutualExclusiveIdsManagerBase implements QueryService, Configurable, ResourceIdSupport {


    private static final String ID_FIELD = "id";

    @Inject
    AccountManager accountMgr;

    @Inject
    RoleService roleService;

    @Inject
    ProjectManager _projectMgr;

    @Inject
    DomainDao _domainDao;

    @Inject
    DomainJoinDao _domainJoinDao;

    @Inject
    UserAccountJoinDao _userAccountJoinDao;

    @Inject
    ResourceTagJoinDao _resourceTagJoinDao;

    @Inject
    InstanceGroupJoinDao _vmGroupJoinDao;

    @Inject
    UserVmJoinDao _userVmJoinDao;

    @Inject
    UserVmDao userVmDao;

    @Inject
    VMInstanceDao _vmInstanceDao;

    @Inject
    SecurityGroupJoinDao _securityGroupJoinDao;

    @Inject
    SecurityGroupVMMapDao securityGroupVMMapDao;

    @Inject
    ProjectInvitationJoinDao _projectInvitationJoinDao;

    @Inject
    ProjectJoinDao _projectJoinDao;

    @Inject
    ProjectDao _projectDao;

    @Inject
    ProjectAccountDao _projectAccountDao;

    @Inject
    ProjectAccountJoinDao _projectAccountJoinDao;

    @Inject
    HostJoinDao hostJoinDao;

    @Inject
    VolumeJoinDao _volumeJoinDao;

    @Inject
    AccountDao _accountDao;

    @Inject
    AccountJoinDao _accountJoinDao;

    @Inject
    AsyncJobJoinDao _jobJoinDao;

    @Inject
    StoragePoolJoinDao _poolJoinDao;

    @Inject
    StoragePoolTagsDao _storageTagDao;

    @Inject
    HostTagsDao _hostTagDao;

    @Inject
    DiskOfferingJoinDao _diskOfferingJoinDao;

    @Inject
    DiskOfferingDetailsDao _diskOfferingDetailsDao;

    @Inject
    ServiceOfferingJoinDao _srvOfferingJoinDao;

    @Inject
    ServiceOfferingDao _srvOfferingDao;

    @Inject
    ServiceOfferingDetailsDao _srvOfferingDetailsDao;

    @Inject
    DiskOfferingDao _diskOfferingDao;

    @Inject
    DataCenterJoinDao _dcJoinDao;

    @Inject
    HighAvailabilityManager _haMgr;

    @Inject
    VMTemplateDao _templateDao;

    @Inject
    TemplateJoinDao _templateJoinDao;

    @Inject
    ResourceManager _resourceMgr;
    @Inject
    ResourceMetaDataService _resourceMetaDataMgr;

    @Inject
    ResourceManagerUtil resourceManagerUtil;

    @Inject
    ResourceTagDao resourceTagDao;

    @Inject
    DataStoreManager dataStoreManager;

    @Inject
    ResponseGenerator responseGenerator;

    @Inject
    PrimaryDataStoreDao storagePoolDao;

    @Inject
    StoragePoolDetailsDao _storagePoolDetailsDao;

    @Inject
    ProjectInvitationDao projectInvitationDao;

    @Inject
    VMTemplatePoolDao templatePoolDao;

    @Inject
    SnapshotDataStoreDao snapshotDataStoreDao;

    @Inject
    UserDao userDao;

    @Inject
    VirtualMachineManager virtualMachineManager;

    @Inject
    VolumeDao volumeDao;

    @Inject
    ResourceIconDao resourceIconDao;

    @Inject
    ManagementServerHostDao msHostDao;

    @Inject
    SecondaryStorageHeuristicDao secondaryStorageHeuristicDao;

    @Inject
    NetworkDao networkDao;

    @Inject
    IPAddressDao ipAddressDao;

    @Inject
    NicDao nicDao;

    @Inject
    HostDao hostDao;

    @Inject
    OutOfBandManagementDao outOfBandManagementDao;

    @Inject
    InstanceGroupVMMapDao instanceGroupVMMapDao;

    @Inject
    AffinityGroupVMMapDao affinityGroupVMMapDao;

    @Inject
    VMInstanceDetailsDao vmInstanceDetailsDao;

    @Inject
    SSHKeyPairDao sshKeyPairDao;

    @Inject
    BackupOfferingDao backupOfferingDao;

    @Inject
    AutoScaleVmGroupVmMapDao autoScaleVmGroupVmMapDao;

    @Inject
    SnapshotJoinDao snapshotJoinDao;

    @Inject
    ObjectStoreDao objectStoreDao;

    @Inject
    BucketDao bucketDao;

    @Inject
    EntityManager entityManager;

    @Inject
    PublicIpQuarantineDao publicIpQuarantineDao;

    @Inject
    StoragePoolHostDao storagePoolHostDao;

    @Inject
    ClusterDao clusterDao;

    @Inject
    private StoragePoolAndAccessGroupMapDao storagePoolAndAccessGroupMapDao;

    @Inject
    private ManagementServerQueryService managementServerQueryService;

    @Inject
    private AffinityGroupQueryService affinityGroupQueryService;

    @Inject
    private EventQueryService eventQueryService;

    @Inject
    private SnapshotQueryService snapshotQueryService;

    @Inject
    private DomainQueryService domainQueryService;

    @Inject
    private ImageStoreQueryService imageStoreQueryService;

    @Inject
    protected RouterQueryService routerQueryService;

    @Inject
    protected TemplateQueryService templateQueryService;

    @Inject
    protected ZoneQueryService zoneQueryService;

    @Inject
    public ManagementService managementService;

    @Inject
    DataCenterDao dataCenterDao;

    @Inject
    HostPodDao podDao;

    @Inject
    GuestOSDao guestOSDao;

    @Inject
    ExtensionHelper extensionHelper;

    @Inject
    RoleDao roleDao;

    /*
     * (non-Javadoc)
     *
     * @see
     * com.cloud.api.query.QueryService#searchForUsers(org.apache.cloudstack
     * .api.command.admin.user.ListUsersCmd)
     */
    @Override
    public ListResponse<UserResponse> searchForUsers(ResponseView responseView, ListUsersCmd cmd) throws PermissionDeniedException {
        Pair<List<UserAccountJoinVO>, Integer> result = searchForUsersInternal(cmd);
        ListResponse<UserResponse> response = new ListResponse<>();
        if (CallContext.current().getCallingAccount().getType() == Account.Type.ADMIN) {
            responseView = ResponseView.Full;
        }
        List<UserResponse> userResponses = ViewResponseHelper.createUserResponse(responseView, CallContext.current().getCallingAccount().getDomainId(),
                result.first().toArray(new UserAccountJoinVO[0]));
        response.setResponses(userResponses, result.second());
        return response;
    }

    public ListResponse<UserResponse> searchForUsers(Long domainId, boolean recursive) throws PermissionDeniedException {
        Account caller = CallContext.current().getCallingAccount();

        List<Long> permittedAccounts = new ArrayList<>();

        boolean listAll = true;
        Long id = null;

        if (caller.getType() == Account.Type.NORMAL) {
            id = CallContext.current().getCallingUser().getId();
        }
        Object username = null;
        Object type = null;
        String accountName = null;
        Object state = null;
        String keyword = null;

        Pair<List<UserAccountJoinVO>, Integer> result =  getUserListInternal(caller, permittedAccounts, listAll, id,
                username, type, accountName, state, keyword, null, domainId, recursive, null, null);
        ListResponse<UserResponse> response = new ListResponse<>();
        List<UserResponse> userResponses = ViewResponseHelper.createUserResponse(ResponseView.Restricted, CallContext.current().getCallingAccount().getDomainId(),
                result.first().toArray(new UserAccountJoinVO[0]));
        response.setResponses(userResponses, result.second());
        return response;
    }

    private Pair<List<UserAccountJoinVO>, Integer> searchForUsersInternal(ListUsersCmd cmd) throws PermissionDeniedException {
        Account caller = CallContext.current().getCallingAccount();

        List<Long> permittedAccounts = new ArrayList<>();

        boolean listAll = cmd.listAll();
        Long id = cmd.getId();
        if (caller.getType() == Account.Type.NORMAL) {
            long currentId = CallContext.current().getCallingUser().getId();
            if (id != null && currentId != id) {
                throw new PermissionDeniedException("Calling user is not authorized to see the user requested by id");
            }
            id = currentId;
        }
        Object username = cmd.getUsername();
        Object type = cmd.getAccountType();
        String accountName = cmd.getAccountName();
        Object state = cmd.getState();
        String keyword = cmd.getKeyword();
        String apiKeyAccess = cmd.getApiKeyAccess();
        User.Source userSource = cmd.getUserSource();

        Long domainId = cmd.getDomainId();
        boolean recursive = cmd.isRecursive();
        Long pageSizeVal = cmd.getPageSizeVal();
        Long startIndex = cmd.getStartIndex();

        Filter searchFilter = new Filter(UserAccountJoinVO.class, "id", true, startIndex, pageSizeVal);

        return getUserListInternal(caller, permittedAccounts, listAll, id, username, type, accountName, state, keyword, apiKeyAccess, domainId, recursive, searchFilter, userSource);
    }

    private Pair<List<UserAccountJoinVO>, Integer> getUserListInternal(Account caller, List<Long> permittedAccounts, boolean listAll, Long id, Object username, Object type,
            String accountName, Object state, String keyword, String apiKeyAccess, Long domainId, boolean recursive, Filter searchFilter, User.Source userSource) {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(domainId, recursive, null);
        accountMgr.buildACLSearchParameters(caller, id, accountName, null, permittedAccounts, domainIdRecursiveListProject, listAll, false);
        domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        SearchBuilder<UserAccountJoinVO> sb = _userAccountJoinDao.createSearchBuilder();
        accountMgr.buildACLViewSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        sb.and("username", sb.entity().getUsername(), Op.LIKE);
        if (id != null && id == 1) {
            // system user should NOT be searchable
            List<UserAccountJoinVO> emptyList = new ArrayList<>();
            return new Pair<>(emptyList, 0);
        } else if (id != null) {
            sb.and("id", sb.entity().getId(), Op.EQ);
        } else {
            // this condition is used to exclude system user from the search
            // results
            sb.and("id", sb.entity().getId(), Op.NEQ);
        }

        sb.and("type", sb.entity().getAccountType(), Op.EQ);
        sb.and("domainId", sb.entity().getDomainId(), Op.EQ);
        sb.and("accountName", sb.entity().getAccountName(), Op.EQ);
        sb.and("state", sb.entity().getState(), Op.EQ);
        sb.and("userSource", sb.entity().getSource(), Op.EQ);
        if (apiKeyAccess != null) {
            sb.and("apiKeyAccess", sb.entity().getApiKeyAccess(), Op.EQ);
        }

        if ((accountName == null) && (domainId != null)) {
            sb.and("domainPath", sb.entity().getDomainPath(), Op.LIKE);
        }

        SearchCriteria<UserAccountJoinVO> sc = sb.create();

        // building ACL condition
        accountMgr.buildACLViewSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (keyword != null) {
            SearchCriteria<UserAccountJoinVO> ssc = _userAccountJoinDao.createSearchCriteria();
            ssc.addOr("username", Op.LIKE, "%" + keyword + "%");
            ssc.addOr("firstname", Op.LIKE, "%" + keyword + "%");
            ssc.addOr("lastname", Op.LIKE, "%" + keyword + "%");
            ssc.addOr("email", Op.LIKE, "%" + keyword + "%");
            ssc.addOr("state", Op.LIKE, "%" + keyword + "%");
            ssc.addOr("accountName", Op.LIKE, "%" + keyword + "%");
            if (EnumUtils.isValidEnum(Account.Type.class, keyword.toUpperCase())) {
                ssc.addOr("accountType", Op.EQ, EnumUtils.getEnum(Account.Type.class, keyword.toUpperCase()));
            }

            sc.addAnd("username", Op.SC, ssc);
        }

        if (username != null) {
            sc.setParameters("username", username);
        }

        if (id != null) {
            sc.setParameters("id", id);
        } else {
            // Don't return system user, search builder with NEQ
            sc.setParameters("id", 1);
        }

        if (type != null) {
            sc.setParameters("type", type);
        }

        if (accountName != null) {
            sc.setParameters("accountName", accountName);
            if (domainId != null) {
                sc.setParameters("domainId", domainId);
            }
        } else if (domainId != null) {
            DomainVO domainVO = _domainDao.findById(domainId);
            sc.setParameters("domainPath", domainVO.getPath() + "%");
        }

        if (state != null) {
            sc.setParameters("state", state);
        }

        if (apiKeyAccess != null) {
            try {
                ApiConstants.ApiKeyAccess access = ApiConstants.ApiKeyAccess.valueOf(apiKeyAccess.toUpperCase());
                sc.setParameters("apiKeyAccess", access.toBoolean());
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("ApiKeyAccess value can only be Enabled/Disabled/Inherit");
            }
        }

        if (userSource != null) {
            sc.setParameters("userSource", userSource.toString());
        }

        return _userAccountJoinDao.searchAndCount(sc, searchFilter);
    }

    @Override
    public List<Long> searchForAccessibleUsers() {
        List<Long> permittedAccounts = new ArrayList<>();
        Account callingAccount = CallContext.current().getCallingAccount();
        Filter searchFilter = new Filter(UserAccountJoinVO.class, "id", true);
        List<RoleVO> allowedRoles = roleDao.listAll();
        roleService.removeRolesIfNeeded(allowedRoles);
        List<Long> allowedRolesId = allowedRoles.stream().map(RoleVO::getId).collect(Collectors.toList());

        Pair<List<UserAccountJoinVO>, Integer> usersPair = getUserListInternal(callingAccount, permittedAccounts,
                true, null, null, null, null, null, null, null, callingAccount.getDomainId(), true, searchFilter, null);
        return usersPair.first().stream().filter(userAccount -> {
            if (BaremetalUtils.BAREMETAL_SYSTEM_ACCOUNT_NAME.equals(userAccount.getUsername()) && !accountMgr.isRootAdmin(callingAccount.getId())) {
                return false;
            }

            AccountVO accountVO = _accountDao.findByIdIncludingRemoved(userAccount.getAccountId());
            UserVO userVO = userDao.findByIdIncludingRemoved(userAccount.getId());
            if (ObjectUtils.anyNull(accountVO, userVO)) {
                return false;
            }

            try {
                accountMgr.checkCallerRoleTypeAllowedForUserOrAccountOperations(accountVO, userVO);
            } catch (PermissionDeniedException exception) {
                return false;
            }
            return allowedRolesId.contains(userAccount.getAccountRoleId());
        }).map(UserAccountJoinVO::getId).collect(Collectors.toList());
    }

    @Override
    public ListResponse<EventResponse> searchForEvents(ListEventsCmd cmd) {
        Pair<List<EventJoinVO>, Integer> result = searchForEventsInternal(cmd);
        ListResponse<EventResponse> response = new ListResponse<>();
        List<EventResponse> eventResponses = ViewResponseHelper.createEventResponse(result.first().toArray(new EventJoinVO[0]));
        response.setResponses(eventResponses, result.second());
        return response;
    }

    private Pair<List<EventJoinVO>, Integer> searchForEventsInternal(ListEventsCmd cmd) {
        return eventQueryService.searchForEventsInternal(cmd);
    }

    @Override
    public ListResponse<ResourceTagResponse> listTags(ListTagsCmd cmd) {
        Pair<List<ResourceTagJoinVO>, Integer> tags = listTagsInternal(cmd);
        ListResponse<ResourceTagResponse> response = new ListResponse<>();
        List<ResourceTagResponse> tagResponses = ViewResponseHelper.createResourceTagResponse(false, tags.first().toArray(new ResourceTagJoinVO[0]));
        response.setResponses(tagResponses, tags.second());
        return response;
    }

    private Pair<List<ResourceTagJoinVO>, Integer> listTagsInternal(ListTagsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();
        String key = cmd.getKey();
        String value = cmd.getValue();
        String resourceId = cmd.getResourceId();
        String resourceType = cmd.getResourceType();
        String customerName = cmd.getCustomer();
        boolean listAll = cmd.listAll();
        Long projectId = cmd.getProjectId();

        if (projectId == null && ResourceObjectType.Project.name().equalsIgnoreCase(resourceType) && StringUtils.isNotEmpty(resourceId)) {
            try {
                projectId = Long.parseLong(resourceId);
            } catch (final NumberFormatException e) {
                final ProjectVO project = _projectDao.findByUuidIncludingRemoved(resourceId);
                if (project != null) {
                    projectId = project.getId();
                }
            }
        }

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);

        accountMgr.buildACLSearchParameters(caller, null, cmd.getAccountName(), projectId, permittedAccounts, domainIdRecursiveListProject, listAll, false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        Filter searchFilter = new Filter(ResourceTagJoinVO.class, "resourceType", false, cmd.getStartIndex(), cmd.getPageSizeVal());

        SearchBuilder<ResourceTagJoinVO> sb = _resourceTagJoinDao.createSearchBuilder();
        accountMgr.buildACLViewSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sb.and("key", sb.entity().getKey(), SearchCriteria.Op.EQ);
        sb.and("value", sb.entity().getValue(), SearchCriteria.Op.EQ);

        if (resourceId != null) {
            sb.and("resourceId", sb.entity().getResourceId(), SearchCriteria.Op.EQ);
            sb.and("resourceUuid", sb.entity().getResourceUuid(), SearchCriteria.Op.EQ);
        }

        sb.and("resourceType", sb.entity().getResourceType(), SearchCriteria.Op.EQ);
        sb.and("customer", sb.entity().getCustomer(), SearchCriteria.Op.EQ);

        // now set the SC criteria...
        SearchCriteria<ResourceTagJoinVO> sc = sb.create();
        accountMgr.buildACLViewSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (key != null) {
            sc.setParameters("key", key);
        }

        if (value != null) {
            sc.setParameters("value", value);
        }

        if (resourceId != null) {
            try {
                long rid = Long.parseLong(resourceId);
                sc.setParameters("resourceId", rid);
            } catch (NumberFormatException ex) {
                // internal id instead of resource id is passed
                sc.setParameters("resourceUuid", resourceId);
            }
        }

        if (resourceType != null) {
            sc.setParameters("resourceType", resourceType);
        }

        if (customerName != null) {
            sc.setParameters("customer", customerName);
        }

        return _resourceTagJoinDao.searchAndCount(sc, searchFilter);
    }

    @Override
    public ListResponse<InstanceGroupResponse> searchForVmGroups(ListVMGroupsCmd cmd) {
        Pair<List<InstanceGroupJoinVO>, Integer> groups = searchForVmGroupsInternal(cmd);
        ListResponse<InstanceGroupResponse> response = new ListResponse<>();
        List<InstanceGroupResponse> grpResponses = ViewResponseHelper.createInstanceGroupResponse(groups.first().toArray(new InstanceGroupJoinVO[0]));
        response.setResponses(grpResponses, groups.second());
        return response;
    }

    private Pair<List<InstanceGroupJoinVO>, Integer> searchForVmGroupsInternal(ListVMGroupsCmd cmd) {
        Long id = cmd.getId();
        String name = cmd.getGroupName();
        String keyword = cmd.getKeyword();

        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountMgr.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        Filter searchFilter = new Filter(InstanceGroupJoinVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());

        SearchBuilder<InstanceGroupJoinVO> sb = _vmGroupJoinDao.createSearchBuilder();
        accountMgr.buildACLViewSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);

        SearchCriteria<InstanceGroupJoinVO> sc = sb.create();
        accountMgr.buildACLViewSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (keyword != null) {
            SearchCriteria<InstanceGroupJoinVO> ssc = _vmGroupJoinDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        return _vmGroupJoinDao.searchAndCount(sc, searchFilter);
    }

    @Override
    public ListResponse<UserVmResponse> searchForUserVMs(ListVMsCmd cmd) {
        Pair<List<UserVmJoinVO>, Integer> result = searchForUserVMsInternal(cmd);
        ListResponse<UserVmResponse> response = new ListResponse<>();

        if (cmd.getRetrieveOnlyResourceCount()) {
            response.setResponses(new ArrayList<>(), result.second());
            return response;
        }

        ResponseView respView = ResponseView.Restricted;
        Account caller = CallContext.current().getCallingAccount();
        if (accountMgr.isRootAdmin(caller.getId())) {
            respView = ResponseView.Full;
        }
        List<UserVmResponse> vmResponses = ViewResponseHelper.createUserVmResponse(respView, "virtualmachine", cmd.getDetails(), cmd.getAccumulate(), cmd.getShowUserData(),
                result.first().toArray(new UserVmJoinVO[0]));

        response.setResponses(vmResponses, result.second());
        return response;
    }

    @Override
    public ListResponse<VirtualMachineResponse> listAffectedVmsForStorageScopeChange(ListAffectedVmsForStorageScopeChangeCmd cmd) {
        Long poolId = cmd.getStorageId();
        StoragePoolVO pool = storagePoolDao.findById(poolId);
        if (pool == null) {
            throw new IllegalArgumentException("Unable to find storage pool with ID: " + poolId);
        }

        ListResponse<VirtualMachineResponse> response = new ListResponse<>();
        List<VirtualMachineResponse> responsesList = new ArrayList<>();
        if (pool.getScope() != ScopeType.ZONE) {
            response.setResponses(responsesList, 0);
            return response;
        }

        Pair<List<VMInstanceVO>, Integer> vms = _vmInstanceDao.listByVmsNotInClusterUsingPool(cmd.getClusterIdForScopeChange(), poolId);
        for (VMInstanceVO vm : vms.first()) {
            VirtualMachineResponse resp = new VirtualMachineResponse();
            resp.setObjectName(VirtualMachine.class.getSimpleName().toLowerCase());
            resp.setId(vm.getUuid());
            resp.setVmType(vm.getType().toString());

            UserVmJoinVO userVM = null;
            if (!vm.getType().isUsedBySystem()) {
                userVM = _userVmJoinDao.findById(vm.getId());
            }
            if (userVM != null) {
                if (userVM.getDisplayName() != null) {
                    resp.setVmName(userVM.getDisplayName());
                } else {
                    resp.setVmName(userVM.getName());
                }
            } else {
                resp.setVmName(vm.getInstanceName());
            }

            HostVO host = hostDao.findById(vm.getHostId());
            if (host != null) {
                resp.setHostId(host.getUuid());
                resp.setHostName(host.getName());
                ClusterVO cluster = clusterDao.findById(host.getClusterId());
                if (cluster != null) {
                    resp.setClusterId(cluster.getUuid());
                    resp.setClusterName(cluster.getName());
                }
            }
            responsesList.add(resp);
        }
        response.setResponses(responsesList, vms.second());
        return response;
    }

    private Object getObjectPossibleMethodValue(Object obj, String methodName) {
        Object result = null;

        try {
            Method m = obj.getClass().getMethod(methodName);
            result = m.invoke(obj);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException ignored) {}

        return result;
    }

    private Pair<List<UserVmJoinVO>, Integer> searchForUserVMsInternal(ListVMsCmd cmd) {
        Pair<List<Long>, Integer> vmIdPage = searchForUserVMIdsAndCount(cmd);

        Integer count = vmIdPage.second();
        Long[] idArray = vmIdPage.first().toArray(new Long[0]);

        if (count == 0) {
            return new Pair<>(new ArrayList<>(), count);
        }

        // search vm details by ids
        List<UserVmJoinVO> vms = _userVmJoinDao.searchByIds(idArray);
        return new Pair<>(vms, count);
    }

    private Pair<List<Long>, Integer> searchForUserVMIdsAndCount(ListVMsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();
        boolean listAll = cmd.listAll();
        Long id = cmd.getId();
        Boolean display = cmd.getDisplay();
        String hypervisor = cmd.getHypervisor();
        String state = cmd.getState();
        Long zoneId = cmd.getZoneId();
        Long templateId = cmd.getTemplateId();
        Long serviceOfferingId = cmd.getServiceOfferingId();
        Boolean isHaEnabled = cmd.getHaEnabled();
        String keyword = cmd.getKeyword();
        Long networkId = cmd.getNetworkId();
        Long isoId = cmd.getIsoId();
        String vmHostName = cmd.getName();
        Long hostId = null;
        Long podId = null;
        Long clusterId = null;
        Long groupId = cmd.getGroupId();
        Long vpcId = cmd.getVpcId();
        Long affinityGroupId = cmd.getAffinityGroupId();
        String keyPairName = cmd.getKeyPairName();
        Long securityGroupId = cmd.getSecurityGroupId();
        Long autoScaleVmGroupId = cmd.getAutoScaleVmGroupId();
        Long backupOfferingId = cmd.getBackupOfferingId();
        Long storageId = null;
        StoragePoolVO pool = null;
        Long userId = cmd.getUserId();
        Long userdataId = cmd.getUserdataId();
        Map<String, String> tags = cmd.getTags();
        final CPU.CPUArch arch = cmd.getArch();
        final Long extensionId = cmd.getExtensionId();

        boolean isAdmin = false;
        boolean isRootAdmin = false;

        if (accountMgr.isAdmin(caller.getId())) {
            isAdmin = true;
        }

        if (accountMgr.isRootAdmin(caller.getId())) {
            isRootAdmin = true;
            podId = (Long) getObjectPossibleMethodValue(cmd, "getPodId");
            clusterId = (Long) getObjectPossibleMethodValue(cmd, "getClusterId");
            hostId = (Long) getObjectPossibleMethodValue(cmd, "getHostId");
            storageId = (Long) getObjectPossibleMethodValue(cmd, "getStorageId");
            if (storageId != null) {
                pool = storagePoolDao.findById( storageId);
                if (pool == null) {
                    throw new InvalidParameterValueException("Unable to find specified storage pool");
                }
            }
        }

        if (!VMLeaseManager.InstanceLeaseEnabled.value() && cmd.getOnlyLeasedInstances()) {
            throw new InvalidParameterValueException(" Cannot list leased instances because the Instance Lease feature " +
                    "is disabled, please enable it to list leased instances");
        }

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountMgr.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, listAll, false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        Filter searchFilter = new Filter(UserVmVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());

        List<Long> ids;
        if (cmd.getId() != null) {
            if (cmd.getIds() != null && !cmd.getIds().isEmpty()) {
                throw new InvalidParameterValueException("Specify either id or ids but not both parameters");
            }
            ids = new ArrayList<>();
            ids.add(cmd.getId());
        } else {
            ids = cmd.getIds();
        }

        SearchBuilder<UserVmVO> userVmSearchBuilder = userVmDao.createSearchBuilder();
        userVmSearchBuilder.select(null, Func.DISTINCT, userVmSearchBuilder.entity().getId());
        accountMgr.buildACLSearchBuilder(userVmSearchBuilder, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (HypervisorType.getType(hypervisor) == HypervisorType.None && hypervisor != null) {
            // invalid hypervisor type input
            throw new InvalidParameterValueException("Invalid HypervisorType " + hypervisor);
        }

        if (ids != null && !ids.isEmpty()) {
            userVmSearchBuilder.and("idIN", userVmSearchBuilder.entity().getId(), Op.IN);
        }

        userVmSearchBuilder.and("displayName", userVmSearchBuilder.entity().getDisplayName(), Op.LIKE);
        userVmSearchBuilder.and("stateEQ", userVmSearchBuilder.entity().getState(), Op.EQ);
        userVmSearchBuilder.and("stateNEQ", userVmSearchBuilder.entity().getState(), Op.NEQ);
        userVmSearchBuilder.and("stateNIN", userVmSearchBuilder.entity().getState(), Op.NIN);

        if (hostId != null) {
            userVmSearchBuilder.and("hostId", userVmSearchBuilder.entity().getHostId(), Op.EQ);
        }

        if (zoneId != null) {
            userVmSearchBuilder.and("dataCenterId", userVmSearchBuilder.entity().getDataCenterId(), Op.EQ);
        }

        if (templateId != null) {
            userVmSearchBuilder.and("templateId", userVmSearchBuilder.entity().getTemplateId(), Op.EQ);
        }

        if (userdataId != null) {
            userVmSearchBuilder.and("userdataId", userVmSearchBuilder.entity().getUserDataId(), Op.EQ);
        }

        if (hypervisor != null) {
            userVmSearchBuilder.and("hypervisorType", userVmSearchBuilder.entity().getHypervisorType(), Op.EQ);
        }

        if (vmHostName != null) {
            userVmSearchBuilder.and("name", userVmSearchBuilder.entity().getHostName(), Op.EQ);
        }

        if (serviceOfferingId != null) {
            userVmSearchBuilder.and("serviceOfferingId", userVmSearchBuilder.entity().getServiceOfferingId(), Op.EQ);
        }
        if (display != null) {
            userVmSearchBuilder.and("display", userVmSearchBuilder.entity().isDisplayVm(), Op.EQ);
        }

        if (!isRootAdmin) {
            userVmSearchBuilder.and("displayVm", userVmSearchBuilder.entity().isDisplayVm(), Op.EQ);
        }

        if (isHaEnabled != null) {
            userVmSearchBuilder.and("haEnabled", userVmSearchBuilder.entity().isHaEnabled(), Op.EQ);
        }

        if (isoId != null) {
            userVmSearchBuilder.and("isoId", userVmSearchBuilder.entity().getIsoId(), Op.EQ);
        }

        if (userId != null) {
            userVmSearchBuilder.and("userId", userVmSearchBuilder.entity().getUserId(), Op.EQ);
        }

        if (podId != null) {
            userVmSearchBuilder.and("podId", userVmSearchBuilder.entity().getPodIdToDeployIn(), Op.EQ);
        }

        if (networkId != null || vpcId != null) {
            SearchBuilder<NicVO> nicSearch = nicDao.createSearchBuilder();
            nicSearch.and("networkId", nicSearch.entity().getNetworkId(), Op.EQ);
            nicSearch.and("removed", nicSearch.entity().getRemoved(), Op.NULL);
            if (vpcId != null) {
                SearchBuilder<NetworkVO> networkSearch = networkDao.createSearchBuilder();
                networkSearch.and("vpcId", networkSearch.entity().getVpcId(), Op.EQ);
                nicSearch.join("vpc", networkSearch, networkSearch.entity().getId(), nicSearch.entity().getNetworkId(), JoinBuilder.JoinType.INNER);
            }
            userVmSearchBuilder.join("nic", nicSearch, nicSearch.entity().getInstanceId(), userVmSearchBuilder.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        if (clusterId != null) {
            userVmSearchBuilder.and().op("hostIdIn", userVmSearchBuilder.entity().getHostId(), Op.IN);
            userVmSearchBuilder.or().op("lastHostIdIn", userVmSearchBuilder.entity().getLastHostId(), Op.IN);
            userVmSearchBuilder.and(userVmSearchBuilder.entity().getState(), Op.EQ).values(VirtualMachine.State.Stopped);
            userVmSearchBuilder.cp().cp();
        }

        if (groupId != null && groupId != -1) {
            SearchBuilder<InstanceGroupVMMapVO> instanceGroupSearch = instanceGroupVMMapDao.createSearchBuilder();
            instanceGroupSearch.and("groupId", instanceGroupSearch.entity().getGroupId(), Op.EQ);
            userVmSearchBuilder.join("instanceGroup", instanceGroupSearch, instanceGroupSearch.entity().getInstanceId(), userVmSearchBuilder.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        if (affinityGroupId != null && affinityGroupId != -1) {
            SearchBuilder<AffinityGroupVMMapVO> affinityGroupSearch = affinityGroupVMMapDao.createSearchBuilder();
            affinityGroupSearch.and("affinityGroupId", affinityGroupSearch.entity().getAffinityGroupId(), Op.EQ);
            userVmSearchBuilder.join("affinityGroup", affinityGroupSearch, affinityGroupSearch.entity().getInstanceId(), userVmSearchBuilder.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        if (securityGroupId != null && securityGroupId != -1) {
            SearchBuilder<SecurityGroupVMMapVO> securityGroupSearch = securityGroupVMMapDao.createSearchBuilder();
            securityGroupSearch.and("securityGroupId", securityGroupSearch.entity().getSecurityGroupId(), Op.EQ);
            userVmSearchBuilder.join("securityGroup", securityGroupSearch, securityGroupSearch.entity().getInstanceId(), userVmSearchBuilder.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        if (storageId != null) {
            SearchBuilder<VolumeVO> volumeSearch = volumeDao.createSearchBuilder();
            if (pool.getPoolType().equals(Storage.StoragePoolType.DatastoreCluster)) {
                volumeSearch.and("storagePoolId", volumeSearch.entity().getPoolId(), Op.IN);
            } else {
                volumeSearch.and("storagePoolId", volumeSearch.entity().getPoolId(), Op.EQ);
            }
            userVmSearchBuilder.join("volume", volumeSearch, volumeSearch.entity().getInstanceId(), userVmSearchBuilder.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        if (tags != null && !tags.isEmpty()) {
            SearchBuilder<ResourceTagVO> resourceTagSearch = resourceTagDao.createSearchBuilder();
            resourceTagSearch.and("resourceType", resourceTagSearch.entity().getResourceType(), Op.EQ);
            resourceTagSearch.and().op();
            for (int count = 0; count < tags.size(); count++) {
                if (count == 0) {
                    resourceTagSearch.op("tagKey" + count, resourceTagSearch.entity().getKey(), Op.EQ);
                } else {
                    resourceTagSearch.or().op("tagKey" + count, resourceTagSearch.entity().getKey(), Op.EQ);
                }
                resourceTagSearch.and("tagValue" + count, resourceTagSearch.entity().getValue(), Op.EQ);
                resourceTagSearch.cp();
            }
            resourceTagSearch.cp();

            userVmSearchBuilder.join("tags", resourceTagSearch, resourceTagSearch.entity().getResourceId(), userVmSearchBuilder.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        if (cmd.getOnlyLeasedInstances()) {
            SearchBuilder<VMInstanceDetailVO> leasedInstancesSearch = vmInstanceDetailsDao.createSearchBuilder();
            leasedInstancesSearch.and(leasedInstancesSearch.entity().getName(), SearchCriteria.Op.EQ).values(VmDetailConstants.INSTANCE_LEASE_EXECUTION);
            leasedInstancesSearch.and(leasedInstancesSearch.entity().getValue(), SearchCriteria.Op.EQ).values(VMLeaseManager.LeaseActionExecution.PENDING.name());
            userVmSearchBuilder.join("userVmToLeased", leasedInstancesSearch, leasedInstancesSearch.entity().getResourceId(),
                    userVmSearchBuilder.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        if (cmd.getGpuEnabled() != null) {
            SearchBuilder<ServiceOfferingVO> serviceOfferingSearch = _srvOfferingDao.createSearchBuilder();
            _srvOfferingDao.addCheckForGpuEnabled(serviceOfferingSearch, cmd.getGpuEnabled());

            userVmSearchBuilder.join("serviceOffering", serviceOfferingSearch, serviceOfferingSearch.entity().getId(), userVmSearchBuilder.entity().getServiceOfferingId(), JoinBuilder.JoinType.INNER);
        }

        if (keyPairName != null) {
            SearchBuilder<VMInstanceDetailVO> vmDetailSearchKeys = vmInstanceDetailsDao.createSearchBuilder();
            SearchBuilder<VMInstanceDetailVO> vmDetailSearchVmIds = vmInstanceDetailsDao.createSearchBuilder();
            vmDetailSearchKeys.and(vmDetailSearchKeys.entity().getName(), Op.EQ).values(SSH_PUBLIC_KEY);

            SearchBuilder<SSHKeyPairVO> sshKeyPairSearch = sshKeyPairDao.createSearchBuilder();
            sshKeyPairSearch.and("keyPairName", sshKeyPairSearch.entity().getName(), Op.EQ);

            sshKeyPairSearch.join("keyPairToDetailValueJoin", vmDetailSearchKeys, vmDetailSearchKeys.entity().getValue(), sshKeyPairSearch.entity().getPublicKey(), JoinBuilder.JoinType.INNER);
            userVmSearchBuilder.join("userVmToDetailJoin", vmDetailSearchVmIds, vmDetailSearchVmIds.entity().getResourceId(), userVmSearchBuilder.entity().getId(), JoinBuilder.JoinType.INNER);
            userVmSearchBuilder.join("userVmToKeyPairJoin", sshKeyPairSearch, sshKeyPairSearch.entity().getAccountId(), userVmSearchBuilder.entity().getAccountId(), JoinBuilder.JoinType.INNER);
        }

        if (keyword != null) {
            userVmSearchBuilder.and().op("keywordDisplayName", userVmSearchBuilder.entity().getDisplayName(), Op.LIKE);
            userVmSearchBuilder.or("keywordName", userVmSearchBuilder.entity().getHostName(), Op.LIKE);
            userVmSearchBuilder.or("keywordState", userVmSearchBuilder.entity().getState(), Op.EQ);
            if (isRootAdmin) {
                userVmSearchBuilder.or("keywordInstanceName", userVmSearchBuilder.entity().getInstanceName(), Op.LIKE );
            }

            SearchBuilder<IPAddressVO> ipAddressSearch = ipAddressDao.createSearchBuilder();
            userVmSearchBuilder.join("ipAddressSearch", ipAddressSearch,
                    ipAddressSearch.entity().getAssociatedWithVmId(), userVmSearchBuilder.entity().getId(), JoinBuilder.JoinType.LEFT);

            SearchBuilder<NicVO> nicSearch = nicDao.createSearchBuilder();
            userVmSearchBuilder.join("nicSearch", nicSearch, JoinBuilder.JoinType.LEFT,
                    JoinBuilder.JoinCondition.AND,
                    nicSearch.entity().getInstanceId(), userVmSearchBuilder.entity().getId(),
                    nicSearch.entity().getRemoved(), userVmSearchBuilder.entity().setLong(null));

            userVmSearchBuilder.or("ipAddressSearch", "keywordPublicIpAddress", ipAddressSearch.entity().getAddress(), Op.LIKE);

            userVmSearchBuilder.or("nicSearch", "keywordIpAddress", nicSearch.entity().getIPv4Address(), Op.LIKE);
            userVmSearchBuilder.or("nicSearch", "keywordIp6Address", nicSearch.entity().getIPv6Address(), Op.LIKE);

            userVmSearchBuilder.cp();
        }

        if (backupOfferingId != null) {
            SearchBuilder<BackupOfferingVO> backupOfferingSearch = backupOfferingDao.createSearchBuilder();
            backupOfferingSearch.and("backupOfferingId", backupOfferingSearch.entity().getId(), Op.EQ);
            userVmSearchBuilder.join("backupOffering", backupOfferingSearch, backupOfferingSearch.entity().getId(), userVmSearchBuilder.entity().getBackupOfferingId(), JoinBuilder.JoinType.INNER);
        }

        if (autoScaleVmGroupId != null) {
            SearchBuilder<AutoScaleVmGroupVmMapVO> autoScaleMapSearch = autoScaleVmGroupVmMapDao.createSearchBuilder();
            autoScaleMapSearch.and("autoScaleVmGroupId", autoScaleMapSearch.entity().getVmGroupId(), Op.EQ);
            userVmSearchBuilder.join("autoScaleVmGroup", autoScaleMapSearch, autoScaleMapSearch.entity().getInstanceId(), userVmSearchBuilder.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        Boolean isVnf = cmd.getVnf();
        boolean templateJoinNeeded = ObjectUtils.anyNotNull(isVnf, arch, extensionId);
        if (templateJoinNeeded) {
            SearchBuilder<VMTemplateVO> templateSearch = _templateDao.createSearchBuilder();
            templateSearch.and("templateArch", templateSearch.entity().getArch(), Op.EQ);
            templateSearch.and("templateTypeEQ", templateSearch.entity().getTemplateType(), Op.EQ);
            templateSearch.and("templateTypeNEQ", templateSearch.entity().getTemplateType(), Op.NEQ);
            templateSearch.and("templateExtensionId", templateSearch.entity().getExtensionId(), Op.EQ);

            userVmSearchBuilder.join("vmTemplate", templateSearch, templateSearch.entity().getId(), userVmSearchBuilder.entity().getTemplateId(), JoinBuilder.JoinType.INNER);
        }

        SearchCriteria<UserVmVO> userVmSearchCriteria = userVmSearchBuilder.create();
        accountMgr.buildACLSearchCriteria(userVmSearchCriteria, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (serviceOfferingId != null) {
            userVmSearchCriteria.setParameters("serviceOfferingId", serviceOfferingId);
        }

        if (state != null) {
            if (state.equalsIgnoreCase("present")) {
                userVmSearchCriteria.setParameters("stateNIN", "Destroyed", "Expunging");
            } else {
                userVmSearchCriteria.setParameters("stateEQ", state);
            }
        }

        if (hypervisor != null) {
            userVmSearchCriteria.setParameters("hypervisorType", hypervisor);
        }

        // Don't show Destroyed and Expunging vms to the end user if the AllowUserViewDestroyedVM flag is not set.
        if (!isAdmin && !AllowUserViewDestroyedVM.valueIn(caller.getAccountId())) {
            userVmSearchCriteria.setParameters("stateNIN", "Destroyed", "Expunging");
        }

        if (zoneId != null) {
            userVmSearchCriteria.setParameters("dataCenterId", zoneId);
        }

        if (templateId != null) {
            userVmSearchCriteria.setParameters("templateId", templateId);
        }

        if (userdataId != null) {
            userVmSearchCriteria.setParameters("userdataId", userdataId);
        }

        if (display != null) {
            userVmSearchCriteria.setParameters("display", display);
        }

        if (isHaEnabled != null) {
            userVmSearchCriteria.setParameters("haEnabled", isHaEnabled);
        }

        if (isoId != null) {
            userVmSearchCriteria.setParameters("isoId", isoId);
        }

        if (ids != null && !ids.isEmpty()) {
            userVmSearchCriteria.setParameters("idIN", ids.toArray());
        }

        if (vmHostName != null) {
            userVmSearchCriteria.setParameters("name", vmHostName);
        }

        if (groupId != null && groupId != -1) {
            userVmSearchCriteria.setJoinParameters("instanceGroup","groupId", groupId);
        }

        if (affinityGroupId != null && affinityGroupId != -1) {
            userVmSearchCriteria.setJoinParameters("affinityGroup", "affinityGroupId", affinityGroupId);
        }

        if (securityGroupId != null && securityGroupId != -1) {
            userVmSearchCriteria.setJoinParameters("securityGroup","securityGroupId", securityGroupId);
        }

        if (keyword != null) {
            String keywordMatch = "%" + keyword + "%";
            userVmSearchCriteria.setParameters("keywordDisplayName", keywordMatch);
            userVmSearchCriteria.setParameters("keywordName", keywordMatch);
            userVmSearchCriteria.setParameters("keywordState", keyword);
            userVmSearchCriteria.setParameters("keywordIpAddress", keywordMatch);
            userVmSearchCriteria.setParameters("keywordPublicIpAddress", keywordMatch);
            userVmSearchCriteria.setParameters("keywordIp6Address", keywordMatch);
            if (isRootAdmin) {
                userVmSearchCriteria.setParameters("keywordInstanceName", keywordMatch);
            }
        }

        if (tags != null && !tags.isEmpty()) {
            int count = 0;
            userVmSearchCriteria.setJoinParameters("tags","resourceType", ResourceObjectType.UserVm);
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                userVmSearchCriteria.setJoinParameters("tags", "tagKey" + count, entry.getKey());
                userVmSearchCriteria.setJoinParameters("tags", "tagValue" + count, entry.getValue());
                count++;
            }
        }

        if (keyPairName != null) {
            userVmSearchCriteria.setJoinParameters("userVmToKeyPairJoin", "keyPairName", keyPairName);
        }

        if (networkId != null) {
            userVmSearchCriteria.setJoinParameters("nic", "networkId", networkId);
        }

        if (vpcId != null) {
            userVmSearchCriteria.getJoin("nic").setJoinParameters("vpc", "vpcId", vpcId);
        }

        if (userId != null) {
            userVmSearchCriteria.setParameters("userId", userId);
        }

        if (backupOfferingId != null) {
            userVmSearchCriteria.setJoinParameters("backupOffering", "backupOfferingId", backupOfferingId);
        }

        if (autoScaleVmGroupId != null) {
            userVmSearchCriteria.setJoinParameters("autoScaleVmGroup", "autoScaleVmGroupId", autoScaleVmGroupId);
        }

        if (isVnf != null) {
            if (isVnf) {
                userVmSearchCriteria.setJoinParameters("vmTemplate", "templateTypeEQ", TemplateType.VNF);
            } else {
                userVmSearchCriteria.setJoinParameters("vmTemplate", "templateTypeNEQ", TemplateType.VNF);
            }
        }
        if (arch != null) {
            userVmSearchCriteria.setJoinParameters("vmTemplate", "templateArch", arch);
        }
        if (extensionId != null) {
            userVmSearchCriteria.setJoinParameters("vmTemplate", "templateExtensionId", extensionId);
        }

        if (isRootAdmin) {
            if (podId != null) {
                userVmSearchCriteria.setParameters("podId", podId);
                if (state == null) {
                    userVmSearchCriteria.setParameters("stateNEQ", "Destroyed");
                }
            }

            if (clusterId != null) {
                List<HostJoinVO> hosts = hostJoinDao.findByClusterId(clusterId, Host.Type.Routing);
                if (CollectionUtils.isEmpty(hosts)) {
                    // cluster has no hosts, so we cannot find VMs, cancel search.
                    return new Pair<>(new ArrayList<>(), 0);
                }
                List<Long> hostIds = hosts.stream().map(HostJoinVO::getId).collect(Collectors.toList());
                userVmSearchCriteria.setParameters("hostIdIn", hostIds.toArray());
                userVmSearchCriteria.setParameters("lastHostIdIn", hostIds.toArray());
            }

            if (hostId != null) {
                userVmSearchCriteria.setParameters("hostId", hostId);
            }

            if (storageId != null) {
                if (pool.getPoolType().equals(Storage.StoragePoolType.DatastoreCluster)) {
                    List<StoragePoolVO> childDatastores = storagePoolDao.listChildStoragePoolsInDatastoreCluster(storageId);
                    userVmSearchCriteria.setJoinParameters("volume", "storagePoolId", childDatastores.stream().map(StoragePoolVO::getId).toArray());
                } else {
                    userVmSearchCriteria.setJoinParameters("volume", "storagePoolId", storageId);
                }
            }
        } else {
            userVmSearchCriteria.setParameters("displayVm", 1);
        }

        Pair<List<UserVmVO>, Integer> uniqueVmPair = userVmDao.searchAndDistinctCount(userVmSearchCriteria, searchFilter, new String[]{"vm_instance.id"});
        Integer count = uniqueVmPair.second();

        List<Long> vmIds = uniqueVmPair.first().stream().map(VMInstanceVO::getId).collect(Collectors.toList());
        return new Pair<>(vmIds, count);
    }

    @Override
    public ListResponse<SecurityGroupResponse> searchForSecurityGroups(ListSecurityGroupsCmd cmd) {
        Pair<List<SecurityGroupJoinVO>, Integer> result = searchForSecurityGroupsInternal(cmd);
        ListResponse<SecurityGroupResponse> response = new ListResponse<>();
        List<SecurityGroupResponse> routerResponses = ViewResponseHelper.createSecurityGroupResponses(result.first());
        response.setResponses(routerResponses, result.second());
        return response;
    }

    private Pair<List<SecurityGroupJoinVO>, Integer> searchForSecurityGroupsInternal(ListSecurityGroupsCmd cmd) throws PermissionDeniedException, InvalidParameterValueException {
        Account caller = CallContext.current().getCallingAccount();
        Long instanceId = cmd.getVirtualMachineId();
        String securityGroup = cmd.getSecurityGroupName();
        Long id = cmd.getId();
        Object keyword = cmd.getKeyword();
        List<Long> permittedAccounts = new ArrayList<>();
        Map<String, String> tags = cmd.getTags();

        if (instanceId != null) {
            UserVmVO userVM = userVmDao.findById(instanceId);
            if (userVM == null) {
                throw new InvalidParameterValueException("Unable to list network groups for virtual machine instance " + instanceId + "; instance not found.");
            }
            accountMgr.checkAccess(caller, null, true, userVM);
            return listSecurityGroupRulesByVM(instanceId, cmd.getStartIndex(), cmd.getPageSizeVal());
        }

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountMgr.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        Filter searchFilter = new Filter(SecurityGroupJoinVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<SecurityGroupJoinVO> sb = _securityGroupJoinDao.createSearchBuilder();
        sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct
        // ids
        accountMgr.buildACLViewSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);

        SearchCriteria<SecurityGroupJoinVO> sc = sb.create();
        accountMgr.buildACLViewSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (tags != null && !tags.isEmpty()) {
            SearchCriteria<SecurityGroupJoinVO> tagSc = _securityGroupJoinDao.createSearchCriteria();
            for (String key : tags.keySet()) {
                SearchCriteria<SecurityGroupJoinVO> tsc = _securityGroupJoinDao.createSearchCriteria();
                tsc.addAnd("tagKey", SearchCriteria.Op.EQ, key);
                tsc.addAnd("tagValue", SearchCriteria.Op.EQ, tags.get(key));
                tagSc.addOr("tagKey", SearchCriteria.Op.SC, tsc);
            }
            sc.addAnd("tagKey", SearchCriteria.Op.SC, tagSc);
        }

        if (securityGroup != null) {
            sc.setParameters("name", securityGroup);
        }

        if (keyword != null) {
            SearchCriteria<SecurityGroupJoinVO> ssc = _securityGroupJoinDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("description", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        // search security group together with rules
        Pair<List<SecurityGroupJoinVO>, Integer> uniqueSgPair = _securityGroupJoinDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueSgPair.second();
        if (count == 0) {
            // handle empty result cases
            return uniqueSgPair;
        }

        List<SecurityGroupJoinVO> uniqueSgs = uniqueSgPair.first();
        Long[] sgIds = new Long[uniqueSgs.size()];
        int i = 0;
        for (SecurityGroupJoinVO v : uniqueSgs) {
            sgIds[i++] = v.getId();
        }
        List<SecurityGroupJoinVO> sgs = _securityGroupJoinDao.searchByIds(sgIds);
        return new Pair<>(sgs, count);
    }

    private Pair<List<SecurityGroupJoinVO>, Integer> listSecurityGroupRulesByVM(long vmId, long pageInd, long pageSize) {
        Filter sf = new Filter(SecurityGroupVMMapVO.class, null, true, pageInd, pageSize);
        Pair<List<SecurityGroupVMMapVO>, Integer> sgVmMappingPair = securityGroupVMMapDao.listByInstanceId(vmId, sf);
        Integer count = sgVmMappingPair.second();
        if (count == 0) {
            // handle empty result cases
            return new Pair<>(new ArrayList<>(), count);
        }
        List<SecurityGroupVMMapVO> sgVmMappings = sgVmMappingPair.first();
        Long[] sgIds = new Long[sgVmMappings.size()];
        int i = 0;
        for (SecurityGroupVMMapVO sgVm : sgVmMappings) {
            sgIds[i++] = sgVm.getSecurityGroupId();
        }
        List<SecurityGroupJoinVO> sgs = _securityGroupJoinDao.searchByIds(sgIds);
        return new Pair<>(sgs, count);
    }

    @Override
    public ListResponse<DomainRouterResponse> searchForRouters(ListRoutersCmd cmd) {
        return routerQueryService.searchForRouters(cmd);
    }

    @Override
    public ListResponse<DomainRouterResponse> searchForInternalLbVms(ListInternalLBVMsCmd cmd) {
        return routerQueryService.searchForInternalLbVms(cmd);
    }

    @Override
    public ListResponse<ProjectResponse> listProjects(ListProjectsCmd cmd) {
        Pair<List<ProjectJoinVO>, Integer> projects = listProjectsInternal(cmd);
        ListResponse<ProjectResponse> response = new ListResponse<>();
        List<ProjectResponse> projectResponses = ViewResponseHelper.createProjectResponse(cmd.getDetails(), projects.first().toArray(new ProjectJoinVO[0]));
        response.setResponses(projectResponses, projects.second());
        return response;
    }

    private Pair<List<ProjectJoinVO>, Integer> listProjectsInternal(ListProjectsCmd cmd) {

        Long id = cmd.getId();
        String name = cmd.getName();
        String displayText = cmd.getDisplayText();
        String state = cmd.getState();
        String accountName = cmd.getAccountName();
        String username = cmd.getUsername();
        Long domainId = cmd.getDomainId();
        String keyword = cmd.getKeyword();
        Long startIndex = cmd.getStartIndex();
        Long pageSize = cmd.getPageSizeVal();
        boolean listAll = cmd.listAll();
        boolean isRecursive = cmd.isRecursive();
        cmd.getTags();


        Account caller = CallContext.current().getCallingAccount();
        User user = CallContext.current().getCallingUser();
        Long accountId = null;
        Long userId = null;
        String path = null;

        Filter searchFilter = new Filter(ProjectJoinVO.class, "id", false, startIndex, pageSize);
        SearchBuilder<ProjectJoinVO> sb = _projectJoinDao.createSearchBuilder();
        sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct
        // ids

        if (accountMgr.isAdmin(caller.getId())) {
            if (domainId != null) {
                DomainVO domain = _domainDao.findById(domainId);
                if (domain == null) {
                    throw new InvalidParameterValueException("Domain id=" + domainId + " doesn't exist in the system");
                }

                accountMgr.checkAccess(caller, domain);

                if (accountName != null) {
                    Account owner = accountMgr.getActiveAccountByName(accountName, domainId);
                    if (owner == null) {
                        throw new InvalidParameterValueException("Unable to find account " + accountName + " in domain " + domainId);
                    }
                    accountId = owner.getId();
                }
                if (StringUtils.isNotEmpty(username)) {
                    User owner = userDao.getUserByName(username, domainId);
                    if (owner == null) {
                        throw new InvalidParameterValueException("Unable to find user " + username + " in domain " + domainId);
                    }
                    userId = owner.getId();
                    if (accountName == null) {
                        accountId = owner.getAccountId();
                    }
                }
            } else { // domainId == null
                if (accountName != null) {
                    throw new InvalidParameterValueException("could not find account " + accountName + " because domain is not specified");
                }
                if (StringUtils.isNotEmpty(username)) {
                    throw new InvalidParameterValueException("could not find user " + username + " because domain is not specified");
                }
            }
        } else {
            if (accountName != null && !accountName.equals(caller.getAccountName())) {
                throw new PermissionDeniedException("Can't list account " + accountName + " projects; unauthorized");
            }

            if (domainId != null && !domainId.equals(caller.getDomainId())) {
                throw new PermissionDeniedException("Can't list domain ID = " + domainId + " projects; unauthorized");
            }

            if (StringUtils.isNotEmpty(username) && !username.equals(user.getUsername())) {
                throw new PermissionDeniedException("Can't list user " + username + " projects; unauthorized");
            }

            accountId = caller.getId();
            userId = user.getId();
        }

        if (domainId == null && accountId == null && (accountMgr.isNormalUser(caller.getId()) || !listAll)) {
            accountId = caller.getId();
            userId = user.getId();
        } else if (accountMgr.isDomainAdmin(caller.getId()) || (isRecursive && !listAll)) {
            DomainVO domain = _domainDao.findById(caller.getDomainId());
            path = domain.getPath();
        }

        if (path != null) {
            sb.and("domainPath", sb.entity().getDomainPath(), SearchCriteria.Op.LIKE);
        }

        if (accountId != null) {
            if (userId == null) {
                sb.and().op("accountId", sb.entity().getAccountId(), SearchCriteria.Op.EQ);
                sb.and("userIdNull", sb.entity().getUserId(), Op.NULL);
                sb.cp();
            } else {
                sb.and("accountId", sb.entity().getAccountId(), SearchCriteria.Op.EQ);
            }
        }

        if (userId != null) {
            sb.and().op("userId", sb.entity().getUserId(), Op.EQ);
            sb.or("userIdNull", sb.entity().getUserId(), Op.NULL);
            sb.cp();
        }

        SearchCriteria<ProjectJoinVO> sc = sb.create();

        if (id != null) {
            sc.addAnd("id", Op.EQ, id);
        }

        if (domainId != null && !isRecursive) {
            sc.addAnd("domainId", Op.EQ, domainId);
        }

        if (name != null) {
            sc.addAnd("name", Op.EQ, name);
        }

        if (displayText != null) {
            sc.addAnd("displayText", Op.EQ, displayText);
        }

        if (accountId != null) {
            sc.setParameters("accountId", accountId);
        }

        if (userId != null) {
            sc.setParameters("userId", userId);
        }

        if (state != null) {
            sc.addAnd("state", Op.EQ, state);
        }

        if (keyword != null) {
            SearchCriteria<ProjectJoinVO> ssc = _projectJoinDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("displayText", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (path != null) {
            sc.setParameters("domainPath", path);
        }

        // search distinct projects to get count
        Pair<List<ProjectJoinVO>, Integer> uniquePrjPair = _projectJoinDao.searchAndCount(sc, searchFilter);
        Integer count = uniquePrjPair.second();
        if (count == 0) {
            // handle empty result cases
            return uniquePrjPair;
        }
        List<ProjectJoinVO> uniquePrjs = uniquePrjPair.first();
        Long[] prjIds = new Long[uniquePrjs.size()];
        int i = 0;
        for (ProjectJoinVO v : uniquePrjs) {
            prjIds[i++] = v.getId();
        }
        List<ProjectJoinVO> prjs = _projectJoinDao.searchByIds(prjIds);
        return new Pair<>(prjs, count);
    }

    @Override
    public ListResponse<ProjectInvitationResponse> listProjectInvitations(ListProjectInvitationsCmd cmd) {
        Pair<List<ProjectInvitationJoinVO>, Integer> invites = listProjectInvitationsInternal(cmd);
        ListResponse<ProjectInvitationResponse> response = new ListResponse<>();
        List<ProjectInvitationResponse> projectInvitationResponses = ViewResponseHelper.createProjectInvitationResponse(invites.first().toArray(new ProjectInvitationJoinVO[0]));

        response.setResponses(projectInvitationResponses, invites.second());
        return response;
    }

    public Pair<List<ProjectInvitationJoinVO>, Integer> listProjectInvitationsInternal(ListProjectInvitationsCmd cmd) {
        Long id = cmd.getId();
        Long projectId = cmd.getProjectId();
        String accountName = cmd.getAccountName();
        Long domainId = cmd.getDomainId();
        String state = cmd.getState();
        boolean activeOnly = cmd.isActiveOnly();
        Long startIndex = cmd.getStartIndex();
        Long pageSizeVal = cmd.getPageSizeVal();
        Long userId = cmd.getUserId();
        boolean isRecursive = cmd.isRecursive();
        boolean listAll = cmd.listAll();

        Account caller = CallContext.current().getCallingAccount();
        User callingUser = CallContext.current().getCallingUser();
        List<Long> permittedAccounts = new ArrayList<>();

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(domainId, isRecursive, null);
        accountMgr.buildACLSearchParameters(caller, id, accountName, projectId, permittedAccounts, domainIdRecursiveListProject, listAll, true);
        domainId = domainIdRecursiveListProject.first();
        isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        Filter searchFilter = new Filter(ProjectInvitationJoinVO.class, "id", true, startIndex, pageSizeVal);
        SearchBuilder<ProjectInvitationJoinVO> sb = _projectInvitationJoinDao.createSearchBuilder();
        accountMgr.buildACLViewSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        ProjectInvitation invitation = projectInvitationDao.findByUserIdProjectId(callingUser.getId(), callingUser.getAccountId(), projectId == null ? -1 : projectId);
        sb.and("projectId", sb.entity().getProjectId(), SearchCriteria.Op.EQ);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);
        sb.and("created", sb.entity().getCreated(), SearchCriteria.Op.GT);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);

        SearchCriteria<ProjectInvitationJoinVO> sc = sb.create();
        accountMgr.buildACLViewSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (projectId != null) {
            sc.setParameters("projectId", projectId);
        }

        if (invitation != null) {
            sc.setParameters("userId", invitation.getForUserId());
        } else if (userId != null) {
            sc.setParameters("userId", userId);
        }

        if (state != null) {
            sc.setParameters("state", state);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (activeOnly) {
            sc.setParameters("state", ProjectInvitation.State.Pending);
            sc.setParameters("created", new Date((DateUtil.currentGMTTime().getTime()) - _projectMgr.getInvitationTimeout()));
        }

        Pair<List<ProjectInvitationJoinVO>, Integer> projectInvitations = _projectInvitationJoinDao.searchAndCount(sc, searchFilter);
        List<ProjectInvitationJoinVO> invitations = projectInvitations.first();
        invitations = invitations.stream().filter(invite -> invite.getUserId() == null || Long.parseLong(invite.getUserId()) == callingUser.getId()).collect(Collectors.toList());
        return new Pair<>(invitations, invitations.size());


    }

    @Override
    public ListResponse<ProjectAccountResponse> listProjectAccounts(ListProjectAccountsCmd cmd) {
        Pair<List<ProjectAccountJoinVO>, Integer> projectAccounts = listProjectAccountsInternal(cmd);
        ListResponse<ProjectAccountResponse> response = new ListResponse<>();
        List<ProjectAccountResponse> projectResponses = ViewResponseHelper.createProjectAccountResponse(projectAccounts.first().toArray(new ProjectAccountJoinVO[0]));
        response.setResponses(projectResponses, projectAccounts.second());
        return response;
    }

    public Pair<List<ProjectAccountJoinVO>, Integer> listProjectAccountsInternal(ListProjectAccountsCmd cmd) {
        long projectId = cmd.getProjectId();
        String accountName = cmd.getAccountName();
        Long userId = cmd.getUserId();
        String role = cmd.getRole();
        Long startIndex = cmd.getStartIndex();
        Long pageSizeVal = cmd.getPageSizeVal();
        Long projectRoleId = cmd.getProjectRoleId();
        // long projectId, String accountName, String role, Long startIndex,
        // Long pageSizeVal) {
        Account caller = CallContext.current().getCallingAccount();
        User callingUser = CallContext.current().getCallingUser();
        // check that the project exists
        Project project = _projectDao.findById(projectId);

        if (project == null) {
            throw new InvalidParameterValueException("Unable to find the project id=" + projectId);
        }

        // verify permissions - only accounts belonging to the project can list
        // project's account
        if (!accountMgr.isAdmin(caller.getId()) && _projectAccountDao.findByProjectIdUserId(projectId, callingUser.getAccountId(), callingUser.getId()) == null &&
        _projectAccountDao.findByProjectIdAccountId(projectId, caller.getAccountId()) == null) {
            throw new PermissionDeniedException("Account " + caller + " is not authorized to list users of the project id=" + projectId);
        }

        Filter searchFilter = new Filter(ProjectAccountJoinVO.class, "id", false, startIndex, pageSizeVal);
        SearchBuilder<ProjectAccountJoinVO> sb = _projectAccountJoinDao.createSearchBuilder();
        sb.and("accountRole", sb.entity().getAccountRole(), Op.EQ);
        sb.and("projectId", sb.entity().getProjectId(), Op.EQ);

        if (accountName != null) {
            sb.and("accountName", sb.entity().getAccountName(), Op.EQ);
        }

        if (userId != null) {
            sb.and("userId", sb.entity().getUserId(), Op.EQ);
        }
        SearchCriteria<ProjectAccountJoinVO> sc = sb.create();

        sc.setParameters("projectId", projectId);

        if (role != null) {
            sc.setParameters("accountRole", role);
        }

        if (accountName != null) {
            sc.setParameters("accountName", accountName);
        }

        if (projectRoleId != null) {
            sc.setParameters("projectRoleId", projectRoleId);
        }

        if (userId != null) {
            sc.setParameters("userId", userId);
        }

        return _projectAccountJoinDao.searchAndCount(sc, searchFilter);
    }

    protected void updateHostsExtensions(final List<HostResponse> hostResponses) {
        if (CollectionUtils.isEmpty(hostResponses)) {
            return;
        }
        Map<Long, Extension> clusterIdExtensionMap = new HashMap<>();
        for  (HostResponse response : hostResponses) {
            if (!Hypervisor.HypervisorType.External.getHypervisorDisplayName().equals(response.getHypervisor())) {
                continue;
            }
            Extension extension = clusterIdExtensionMap.computeIfAbsent(response.getClusterInternalId(),
                    id -> extensionHelper.getExtensionForCluster(id));
            if (extension == null) {
                continue;
            }
            response.setExtensionId(extension.getUuid());
            response.setExtensionName(extension.getName());
        }
    }

    @Override
    public ListResponse<HostResponse> searchForServers(ListHostsCmd cmd) {
        // FIXME: do we need to support list hosts with VmId, maybe we should
        // create another command just for this
        // Right now it is handled separately outside this QueryService
        logger.debug(">>>Searching for hosts>>>");
        Pair<List<HostJoinVO>, Integer> hosts = searchForServersInternal(cmd);
        ListResponse<HostResponse> response = new ListResponse<>();
        logger.debug(">>>Generating Response>>>");
        List<HostResponse> hostResponses = ViewResponseHelper.createHostResponse(cmd.getDetails(), hosts.first().toArray(new HostJoinVO[0]));
        updateHostsExtensions(hostResponses);
        response.setResponses(hostResponses, hosts.second());
        return response;
    }

    private ListResponse<HostResponse> searchForServersWithMinimalResponse(ListHostsCmd cmd) {
        logger.debug(">>>Searching for hosts>>>");
        Pair<List<HostJoinVO>, Integer> hosts = searchForServersInternal(cmd);
        ListResponse<HostResponse> response = new ListResponse<>();
        logger.debug(">>>Generating Response>>>");
        List<HostResponse> hostResponses = ViewResponseHelper.createMinimalHostResponse(hosts.first().toArray(new HostJoinVO[0]));
        response.setResponses(hostResponses, hosts.second());
        return response;
    }

    public Pair<List<HostJoinVO>, Integer> searchForServersInternal(ListHostsCmd cmd) {
        Pair<List<Long>, Integer> serverIdPage = searchForServerIdsAndCount(cmd);

        Integer count = serverIdPage.second();
        Long[] idArray = serverIdPage.first().toArray(new Long[0]);

        if (count == 0) {
            return new Pair<>(new ArrayList<>(), count);
        }

        List<HostJoinVO> servers = hostJoinDao.searchByIds(idArray);
        return new Pair<>(servers, count);
    }
    public Pair<List<Long>, Integer> searchForServerIdsAndCount(ListHostsCmd cmd) {
        Long zoneId = accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), cmd.getZoneId());
        Object name = cmd.getHostName();
        Object type = cmd.getType();
        Object state = cmd.getState();
        Object pod = cmd.getPodId();
        Object cluster = cmd.getClusterId();
        Object id = cmd.getId();
        Object keyword = cmd.getKeyword();
        Object outOfBandManagementEnabled = cmd.isOutOfBandManagementEnabled();
        Object powerState = cmd.getHostOutOfBandManagementPowerState();
        Object resourceState = cmd.getResourceState();
        Boolean haHosts = cmd.getHaHost();
        Long startIndex = cmd.getStartIndex();
        Long pageSize = cmd.getPageSizeVal();
        Hypervisor.HypervisorType hypervisorType = cmd.getHypervisor();
        Long msId = cmd.getManagementServerId();
        final CPU.CPUArch arch = cmd.getArch();
        String storageAccessGroup = cmd.getStorageAccessGroup();
        String version = cmd.getVersion();

        Filter searchFilter = new Filter(HostVO.class, "id", Boolean.TRUE, startIndex, pageSize);

        SearchBuilder<HostVO> hostSearchBuilder = hostDao.createSearchBuilder();
        hostSearchBuilder.select(null, Func.DISTINCT, hostSearchBuilder.entity().getId()); // select distinct
        // ids
        hostSearchBuilder.and("id", hostSearchBuilder.entity().getId(), SearchCriteria.Op.EQ);
        hostSearchBuilder.and("name", hostSearchBuilder.entity().getName(), SearchCriteria.Op.EQ);
        hostSearchBuilder.and("type", hostSearchBuilder.entity().getType(), SearchCriteria.Op.EQ);
        hostSearchBuilder.and("status", hostSearchBuilder.entity().getStatus(), SearchCriteria.Op.EQ);
        hostSearchBuilder.and("dataCenterId", hostSearchBuilder.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        hostSearchBuilder.and("podId", hostSearchBuilder.entity().getPodId(), SearchCriteria.Op.EQ);
        hostSearchBuilder.and("clusterId", hostSearchBuilder.entity().getClusterId(), SearchCriteria.Op.EQ);
        hostSearchBuilder.and("resourceState", hostSearchBuilder.entity().getResourceState(), SearchCriteria.Op.EQ);
        hostSearchBuilder.and("hypervisor_type", hostSearchBuilder.entity().getHypervisorType(), SearchCriteria.Op.EQ);
        hostSearchBuilder.and("mgmt_server_id", hostSearchBuilder.entity().getManagementServerId(), SearchCriteria.Op.EQ);
        hostSearchBuilder.and("arch", hostSearchBuilder.entity().getArch(), SearchCriteria.Op.EQ);
        if (storageAccessGroup != null) {
            hostSearchBuilder.and().op("storageAccessGroupExact", hostSearchBuilder.entity().getStorageAccessGroups(), Op.EQ);
            hostSearchBuilder.or("storageAccessGroupPrefix", hostSearchBuilder.entity().getStorageAccessGroups(), Op.LIKE);
            hostSearchBuilder.or("storageAccessGroupSuffix", hostSearchBuilder.entity().getStorageAccessGroups(), Op.LIKE);
            hostSearchBuilder.or("storageAccessGroupMiddle", hostSearchBuilder.entity().getStorageAccessGroups(), Op.LIKE);
            hostSearchBuilder.cp();
        }
        hostSearchBuilder.and("version", hostSearchBuilder.entity().getVersion(), SearchCriteria.Op.EQ);

        if (keyword != null) {
            hostSearchBuilder.and().op("keywordName", hostSearchBuilder.entity().getName(), SearchCriteria.Op.LIKE);
            hostSearchBuilder.or("keywordStatus", hostSearchBuilder.entity().getStatus(), SearchCriteria.Op.LIKE);
            hostSearchBuilder.or("keywordType", hostSearchBuilder.entity().getType(), SearchCriteria.Op.LIKE);
            hostSearchBuilder.or("keywordVersion", hostSearchBuilder.entity().getVersion(), SearchCriteria.Op.LIKE);
            hostSearchBuilder.cp();
        }

        if (outOfBandManagementEnabled != null || powerState != null) {
            SearchBuilder<OutOfBandManagementVO> oobmSearch = outOfBandManagementDao.createSearchBuilder();
            oobmSearch.and("oobmEnabled", oobmSearch.entity().isEnabled(), SearchCriteria.Op.EQ);
            oobmSearch.and("powerState", oobmSearch.entity().getPowerState(), SearchCriteria.Op.EQ);

            hostSearchBuilder.join("oobmSearch", oobmSearch, hostSearchBuilder.entity().getId(), oobmSearch.entity().getHostId(), JoinBuilder.JoinType.INNER);
        }

        String haTag = _haMgr.getHaTag();
        if (haHosts != null && haTag != null && !haTag.isEmpty()) {
            SearchBuilder<HostTagVO> hostTagSearchBuilder = _hostTagDao.createSearchBuilder();
            if (haHosts) {
                hostTagSearchBuilder.and("tag", hostTagSearchBuilder.entity().getTag(), SearchCriteria.Op.EQ);
            } else {
                hostTagSearchBuilder.and().op("tag", hostTagSearchBuilder.entity().getTag(), Op.NEQ);
                hostTagSearchBuilder.or("tagNull", hostTagSearchBuilder.entity().getTag(), Op.NULL);
                hostTagSearchBuilder.cp();
            }
            hostSearchBuilder.join("hostTagSearch", hostTagSearchBuilder, hostSearchBuilder.entity().getId(), hostTagSearchBuilder.entity().getHostId(), JoinBuilder.JoinType.LEFT);
        }

        SearchCriteria<HostVO> sc = hostSearchBuilder.create();

        if (keyword != null) {
            sc.setParameters("keywordName", "%" + keyword + "%");
            sc.setParameters("keywordStatus", "%" + keyword + "%");
            sc.setParameters("keywordType", "%" + keyword + "%");
            sc.setParameters("keywordVersion", "%" + keyword + "%");
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }
        if (type != null) {
            sc.setParameters("type", type);
        }
        if (state != null) {
            sc.setParameters("status", state);
        }
        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }
        if (pod != null) {
            sc.setParameters("podId", pod);
        }
        if (cluster != null) {
            sc.setParameters("clusterId", cluster);
        }

        if (outOfBandManagementEnabled != null) {
            sc.setJoinParameters("oobmSearch", "oobmEnabled", outOfBandManagementEnabled);
        }

        if (powerState != null) {
            sc.setJoinParameters("oobmSearch", "powerState", powerState);
        }

        if (resourceState != null) {
            sc.setParameters("resourceState", resourceState);
        }

        if (haHosts != null && haTag != null && !haTag.isEmpty()) {
            sc.setJoinParameters("hostTagSearch", "tag", haTag);
        }

        if (hypervisorType != HypervisorType.None && hypervisorType != HypervisorType.Any) {
            sc.setParameters("hypervisor_type", hypervisorType);
        }

        if (msId != null) {
            ManagementServerHostVO msHost = msHostDao.findById(msId);
            if (msHost != null) {
                sc.setParameters("mgmt_server_id", msHost.getMsid());
            }
        }

        if (arch != null) {
            sc.setParameters("arch", arch);
        }

        if (storageAccessGroup != null) {
            sc.setParameters("storageAccessGroupExact", storageAccessGroup);
            sc.setParameters("storageAccessGroupPrefix", storageAccessGroup + ",%");
            sc.setParameters("storageAccessGroupSuffix", "%," + storageAccessGroup);
            sc.setParameters("storageAccessGroupMiddle", "%," + storageAccessGroup + ",%");
        }

        if (version != null) {
            sc.setParameters("version", version);
        }

        Pair<List<HostVO>, Integer> uniqueHostPair = hostDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueHostPair.second();
        List<Long> hostIds = uniqueHostPair.first().stream().map(HostVO::getId).collect(Collectors.toList());
        return new Pair<>(hostIds, count);
    }

    @Override
    public ListResponse<VolumeResponse> searchForVolumes(ListVolumesCmd cmd) {
        Pair<List<VolumeJoinVO>, Integer> result = searchForVolumesInternal(cmd);
        ListResponse<VolumeResponse> response = new ListResponse<>();

        if (cmd.getRetrieveOnlyResourceCount()) {
            response.setResponses(new ArrayList<>(), result.second());
            return response;
        }

        ResponseView respView = cmd.getResponseView();
        Account account = CallContext.current().getCallingAccount();
        if (accountMgr.isRootAdmin(account.getAccountId())) {
            respView = ResponseView.Full;
        }

        List<VolumeResponse> volumeResponses = ViewResponseHelper.createVolumeResponse(respView, result.first().toArray(new VolumeJoinVO[0]));

        for (VolumeResponse vr : volumeResponses) {
            String poolId = vr.getStoragePoolId();
            if (poolId == null) {
                continue;
            }

            DataStore store = dataStoreManager.getPrimaryDataStore(poolId);
            if (store == null) {
                continue;
            }

            DataStoreDriver driver = store.getDriver();
            if (driver == null) {
                continue;
            }

            Map<String, String> caps = driver.getCapabilities();
            if (caps != null) {
                boolean quiescevm = Boolean.parseBoolean(caps.get(DataStoreCapabilities.VOLUME_SNAPSHOT_QUIESCEVM.toString()));
                vr.setNeedQuiescevm(quiescevm);

                boolean supportsStorageSnapshot = Boolean.parseBoolean(caps.get(DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT.toString()));
                vr.setSupportsStorageSnapshot(supportsStorageSnapshot);
            }
        }
        response.setResponses(volumeResponses, result.second());
        return response;
    }

    private Pair<List<VolumeJoinVO>, Integer> searchForVolumesInternal(ListVolumesCmd cmd) {
        Pair<List<Long>, Integer> volumeIdPage = searchForVolumeIdsAndCount(cmd);

        Integer count = volumeIdPage.second();
        Long[] idArray = volumeIdPage.first().toArray(new Long[0]);

        if (count == 0) {
            return new Pair<>(new ArrayList<>(), count);
        }

        List<VolumeJoinVO> vms = _volumeJoinDao.searchByIds(idArray);
        return new Pair<>(vms, count);
    }
    private Pair<List<Long>, Integer> searchForVolumeIdsAndCount(ListVolumesCmd cmd) {

        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();

        Long id = cmd.getId();
        Long vmInstanceId = cmd.getVirtualMachineId();
        String name = cmd.getVolumeName();
        String keyword = cmd.getKeyword();
        String type = cmd.getType();
        Map<String, String> tags = cmd.getTags();
        String storageId = cmd.getStorageId();
        Long clusterId = cmd.getClusterId();
        Long serviceOfferingId = cmd.getServiceOfferingId();
        Long diskOfferingId = cmd.getDiskOfferingId();
        Boolean display = cmd.getDisplay();
        String state = cmd.getState();
        boolean shouldListSystemVms = shouldListSystemVms(cmd, caller.getId());

        Long zoneId = cmd.getZoneId();
        Long podId = cmd.getPodId();

        List<Long> ids = getIdsListFromCmd(cmd.getId(), cmd.getIds());

        if (diskOfferingId == null && serviceOfferingId != null) {
            ServiceOfferingVO serviceOffering = _srvOfferingDao.findById(serviceOfferingId);
            if (serviceOffering != null) {
                diskOfferingId = serviceOffering.getDiskOfferingId();
            }
        }

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountMgr.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        Filter searchFilter = new Filter(VolumeVO.class, "created", false, cmd.getStartIndex(), cmd.getPageSizeVal());

        SearchBuilder<VolumeVO> volumeSearchBuilder = volumeDao.createSearchBuilder();
        volumeSearchBuilder.select(null, Func.DISTINCT, volumeSearchBuilder.entity().getId()); // select distinct
        accountMgr.buildACLSearchBuilder(volumeSearchBuilder, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (CollectionUtils.isNotEmpty(ids)) {
            volumeSearchBuilder.and("idIN", volumeSearchBuilder.entity().getId(), SearchCriteria.Op.IN);
        }

        volumeSearchBuilder.and("name", volumeSearchBuilder.entity().getName(), SearchCriteria.Op.EQ);
        volumeSearchBuilder.and("volumeType", volumeSearchBuilder.entity().getVolumeType(), SearchCriteria.Op.LIKE);
        volumeSearchBuilder.and("uuid", volumeSearchBuilder.entity().getUuid(), SearchCriteria.Op.NNULL);
        volumeSearchBuilder.and("instanceId", volumeSearchBuilder.entity().getInstanceId(), SearchCriteria.Op.EQ);
        volumeSearchBuilder.and("dataCenterId", volumeSearchBuilder.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        if (cmd.isEncrypted() != null) {
            if (cmd.isEncrypted()) {
                volumeSearchBuilder.and("encryptFormat", volumeSearchBuilder.entity().getEncryptFormat(), SearchCriteria.Op.NNULL);
            } else {
                volumeSearchBuilder.and("encryptFormat", volumeSearchBuilder.entity().getEncryptFormat(), SearchCriteria.Op.NULL);
            }
        }

        if (keyword != null) {
            volumeSearchBuilder.and().op("keywordName", volumeSearchBuilder.entity().getName(), SearchCriteria.Op.LIKE);
            volumeSearchBuilder.or("keywordVolumeType", volumeSearchBuilder.entity().getVolumeType(), SearchCriteria.Op.LIKE);
            volumeSearchBuilder.or("keywordState", volumeSearchBuilder.entity().getState(), SearchCriteria.Op.LIKE);
            volumeSearchBuilder.cp();
        }

        StoragePoolVO poolVO = null;
        if (storageId != null) {
            poolVO = storagePoolDao.findByUuid(storageId);
            if (poolVO == null) {
                throw new InvalidParameterValueException("Unable to find storage pool by uuid " + storageId);
            } else if (poolVO.getPoolType() == Storage.StoragePoolType.DatastoreCluster) {
                volumeSearchBuilder.and("storageId", volumeSearchBuilder.entity().getPoolId(), SearchCriteria.Op.IN);
            } else {
                volumeSearchBuilder.and("storageId", volumeSearchBuilder.entity().getPoolId(), SearchCriteria.Op.EQ);
            }
        }

        if (clusterId != null || podId != null) {
            SearchBuilder<StoragePoolVO> storagePoolSearch = storagePoolDao.createSearchBuilder();
            storagePoolSearch.and("clusterId", storagePoolSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
            storagePoolSearch.and("podId", storagePoolSearch.entity().getPodId(), SearchCriteria.Op.EQ);
            volumeSearchBuilder.join("storagePoolSearch", storagePoolSearch, storagePoolSearch.entity().getId(), volumeSearchBuilder.entity().getPoolId(), JoinBuilder.JoinType.INNER);
        }

        volumeSearchBuilder.and("diskOfferingId", volumeSearchBuilder.entity().getDiskOfferingId(), SearchCriteria.Op.EQ);
        volumeSearchBuilder.and("display", volumeSearchBuilder.entity().isDisplayVolume(), SearchCriteria.Op.EQ);
        volumeSearchBuilder.and("state", volumeSearchBuilder.entity().getState(), SearchCriteria.Op.EQ);
        volumeSearchBuilder.and("stateNEQ", volumeSearchBuilder.entity().getState(), SearchCriteria.Op.NEQ);

        // Need to test thoroughly
        if (!shouldListSystemVms) {
            SearchBuilder<VMInstanceVO> vmSearch = _vmInstanceDao.createSearchBuilder();
            SearchBuilder<ServiceOfferingVO> serviceOfferingSearch = _srvOfferingDao.createSearchBuilder();
            vmSearch.and().op("svmType", vmSearch.entity().getType(), SearchCriteria.Op.NIN);
            vmSearch.or("vmSearchNulltype", vmSearch.entity().getType(), SearchCriteria.Op.NULL);
            vmSearch.cp();

            serviceOfferingSearch.and().op("systemUse", serviceOfferingSearch.entity().isSystemUse(), SearchCriteria.Op.NEQ);
            serviceOfferingSearch.or("serviceOfferingSearchNulltype", serviceOfferingSearch.entity().isSystemUse(), SearchCriteria.Op.NULL);
            serviceOfferingSearch.cp();

            vmSearch.join("serviceOfferingSearch", serviceOfferingSearch, serviceOfferingSearch.entity().getId(), vmSearch.entity().getServiceOfferingId(), JoinBuilder.JoinType.LEFT);

            volumeSearchBuilder.join("vmSearch", vmSearch, vmSearch.entity().getId(), volumeSearchBuilder.entity().getInstanceId(), JoinBuilder.JoinType.LEFT);

        }

        if (MapUtils.isNotEmpty(tags)) {
            SearchBuilder<ResourceTagVO> resourceTagSearch = resourceTagDao.createSearchBuilder();
            resourceTagSearch.and("resourceType", resourceTagSearch.entity().getResourceType(), Op.EQ);
            resourceTagSearch.and().op();
            for (int count = 0; count < tags.size(); count++) {
                if (count == 0) {
                    resourceTagSearch.op("tagKey" + count, resourceTagSearch.entity().getKey(), Op.EQ);
                } else {
                    resourceTagSearch.or().op("tagKey" + count, resourceTagSearch.entity().getKey(), Op.EQ);
                }
                resourceTagSearch.and("tagValue" + count, resourceTagSearch.entity().getValue(), Op.EQ);
                resourceTagSearch.cp();
            }
            resourceTagSearch.cp();

            volumeSearchBuilder.join("tags", resourceTagSearch, resourceTagSearch.entity().getResourceId(), volumeSearchBuilder.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        // now set the SC criteria...
        SearchCriteria<VolumeVO> sc = volumeSearchBuilder.create();
        accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (keyword != null) {
            sc.setParameters("keywordName", "%" + keyword + "%");
            sc.setParameters("keywordVolumeType", "%" + keyword + "%");
            sc.setParameters("keywordState", "%" + keyword + "%");
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (display != null) {
            sc.setParameters("display", display);
        }

        setIdsListToSearchCriteria(sc, ids);

        if (!shouldListSystemVms) {
            sc.setJoinParameters("vmSearch", "svmType", VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm, VirtualMachine.Type.DomainRouter);
            sc.getJoin("vmSearch").setJoinParameters("serviceOfferingSearch", "systemUse", 1);
        }

        if (MapUtils.isNotEmpty(tags)) {
            int count = 0;
            sc.setJoinParameters("tags", "resourceType", ResourceObjectType.Volume);
            for (Map.Entry<String, String> entry  : tags.entrySet()) {
                sc.setJoinParameters("tags", "tagKey" + count, entry.getKey());
                sc.setJoinParameters("tags", "tagValue" + count, entry.getValue());
                count++;
            }
        }

        if (diskOfferingId != null) {
            sc.setParameters("diskOfferingId", diskOfferingId);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (type != null) {
            sc.setParameters("volumeType", "%" + type + "%");
        }
        if (vmInstanceId != null) {
            sc.setParameters("instanceId", vmInstanceId);
        }
        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }

        if (storageId != null) {
            if (poolVO.getPoolType() == Storage.StoragePoolType.DatastoreCluster) {
                List<StoragePoolVO> childDataStores = storagePoolDao.listChildStoragePoolsInDatastoreCluster(poolVO.getId());
                sc.setParameters("storageId", childDataStores.stream().map(StoragePoolVO::getId).toArray());
            } else {
                sc.setParameters("storageId", poolVO.getId());
            }
        }

        if (clusterId != null) {
            sc.setJoinParameters("storagePoolSearch", "clusterId", clusterId);
        }
        if (podId != null) {
            sc.setJoinParameters("storagePoolSearch", "podId", podId);
        }

        if (state != null) {
            sc.setParameters("state", state);
        } else if (!accountMgr.isAdmin(caller.getId())) {
            sc.setParameters("stateNEQ", Volume.State.Expunged);
        }

        // search Volume details by ids
        Pair<List<VolumeVO>, Integer> uniqueVolPair = volumeDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueVolPair.second();
        List<Long> vmIds = uniqueVolPair.first().stream().map(VolumeVO::getId).collect(Collectors.toList());
        return new Pair<>(vmIds, count);
    }

    private boolean shouldListSystemVms(ListVolumesCmd cmd, Long callerId) {
        return Boolean.TRUE.equals(cmd.getListSystemVms()) && accountMgr.isRootAdmin(callerId);
    }

    @Override
    public ListResponse<DomainResponse> searchForDomains(ListDomainsCmd cmd) {
        Pair<List<DomainJoinVO>, Integer> result = searchForDomainsInternal(cmd);
        ListResponse<DomainResponse> response = new ListResponse<>();

        ResponseView respView = ResponseView.Restricted;
        if (cmd instanceof ListDomainsCmdByAdmin) {
            respView = ResponseView.Full;
        }

        List<DomainResponse> domainResponses = ViewResponseHelper.createDomainResponse(respView, cmd.getDetails(), result.first());
        response.setResponses(domainResponses, result.second());
        return response;
    }

    private Pair<List<DomainJoinVO>, Integer> searchForDomainsInternal(ListDomainsCmd cmd) {
        return domainQueryService.searchForDomainsInternal(cmd);
    }

    @Override
    public ListResponse<AccountResponse> searchForAccounts(ListAccountsCmd cmd) {
        Pair<List<AccountJoinVO>, Integer> result = searchForAccountsInternal(cmd);
        ListResponse<AccountResponse> response = new ListResponse<>();

        ResponseView respView = ResponseView.Restricted;
        if (cmd instanceof ListAccountsCmdByAdmin) {
            respView = ResponseView.Full;
        }

        List<AccountResponse> accountResponses = ViewResponseHelper.createAccountResponse(respView, cmd.getDetails(), result.first().toArray(new AccountJoinVO[0]));
        response.setResponses(accountResponses, result.second());
        return response;
    }

    private Pair<List<AccountJoinVO>, Integer> searchForAccountsInternal(ListAccountsCmd cmd) {

        Pair<List<Long>, Integer> accountIdPage = searchForAccountIdsAndCount(cmd);

        Integer count = accountIdPage.second();
        Long[] idArray = accountIdPage.first().toArray(new Long[0]);

        if (count == 0) {
            return new Pair<>(new ArrayList<>(), count);
        }

        List<AccountJoinVO> accounts = _accountJoinDao.searchByIds(idArray);
        return new Pair<>(accounts, count);
    }

    private Pair<List<Long>, Integer> searchForAccountIdsAndCount(ListAccountsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long domainId = cmd.getDomainId();
        Long accountId = cmd.getId();
        String accountName = cmd.getSearchName();
        boolean isRecursive = cmd.isRecursive();
        boolean listAll = cmd.listAll();
        boolean callerIsAdmin = accountMgr.isAdmin(caller.getId());
        Account account;
        Domain domain = null;

        // if "domainid" specified, perform validation
        if (domainId != null) {
            // ensure existence...
            domain = _domainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Domain id=" + domainId + " doesn't exist");
            }
            // ... and check access rights.
            accountMgr.checkAccess(caller, domain);
        }

        // if no "id" specified...
        if (accountId == null) {
            // listall only has significance if they are an admin
            boolean isDomainListAllAllowed = AllowUserViewAllDomainAccounts.valueIn(caller.getDomainId());
            if ((listAll && callerIsAdmin) || isDomainListAllAllowed) {
                // if no domain id specified, use caller's domain
                if (domainId == null) {
                    domainId = caller.getDomainId();
                }
                // mark recursive
                isRecursive = true;
            } else if (!callerIsAdmin || domainId == null) {
                accountId = caller.getAccountId();
            }
        } else if (domainId != null && accountName != null) {
            // if they're looking for an account by name
            account = _accountDao.findActiveAccount(accountName, domainId);
            if (account == null || account.getId() == Account.ACCOUNT_ID_SYSTEM) {
                throw new InvalidParameterValueException("Unable to find account by name " + accountName + " in domain " + domainId);
            }
            accountMgr.checkAccess(caller, null, true, account);
        } else {
            // if they specified an "id"...
            if (domainId == null) {
                account = _accountDao.findById(accountId);
            } else {
                account = _accountDao.findActiveAccountById(accountId, domainId);
            }
            if (account == null || account.getId() == Account.ACCOUNT_ID_SYSTEM) {
                throw new InvalidParameterValueException("Unable to find account by id " + accountId + (domainId == null ? "" : " in domain " + domainId));
            }
            accountMgr.checkAccess(caller, null, true, account);
        }

        Filter searchFilter = new Filter(AccountVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());

        Object type = cmd.getAccountType();
        Object state = cmd.getState();
        Object isCleanupRequired = cmd.isCleanupRequired();
        Object keyword = cmd.getKeyword();
        String apiKeyAccess = cmd.getApiKeyAccess();

        SearchBuilder<AccountVO> accountSearchBuilder = _accountDao.createSearchBuilder();
        accountSearchBuilder.select(null, Func.DISTINCT, accountSearchBuilder.entity().getId()); // select distinct
        accountSearchBuilder.and("accountName", accountSearchBuilder.entity().getAccountName(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("domainId", accountSearchBuilder.entity().getDomainId(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("id", accountSearchBuilder.entity().getId(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("type", accountSearchBuilder.entity().getType(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("state", accountSearchBuilder.entity().getState(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("needsCleanup", accountSearchBuilder.entity().getNeedsCleanup(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("typeNEQ", accountSearchBuilder.entity().getType(), SearchCriteria.Op.NEQ);
        accountSearchBuilder.and("idNEQ", accountSearchBuilder.entity().getId(), SearchCriteria.Op.NEQ);
        accountSearchBuilder.and("type2NEQ", accountSearchBuilder.entity().getType(), SearchCriteria.Op.NEQ);
        if (apiKeyAccess != null) {
            accountSearchBuilder.and("apiKeyAccess", accountSearchBuilder.entity().getApiKeyAccess(), Op.EQ);
        }

        if (domainId != null && isRecursive) {
            SearchBuilder<DomainVO> domainSearch = _domainDao.createSearchBuilder();
            domainSearch.and("path", domainSearch.entity().getPath(), SearchCriteria.Op.LIKE);
            accountSearchBuilder.join("domainSearch", domainSearch, domainSearch.entity().getId(), accountSearchBuilder.entity().getDomainId(), JoinBuilder.JoinType.INNER);
        }

        if (keyword != null) {
            accountSearchBuilder.and().op("keywordAccountName", accountSearchBuilder.entity().getAccountName(), SearchCriteria.Op.LIKE);
            accountSearchBuilder.or("keywordState", accountSearchBuilder.entity().getState(), SearchCriteria.Op.LIKE);
            accountSearchBuilder.cp();
        }

        SearchCriteria<AccountVO> sc = accountSearchBuilder.create();

        // don't return account of type project to the end user
        sc.setParameters("typeNEQ", Account.Type.PROJECT);

        // don't return system account...
        sc.setParameters("idNEQ", Account.ACCOUNT_ID_SYSTEM);

        // do not return account of type domain admin to the end user
        if (!callerIsAdmin) {
            sc.setParameters("type2NEQ", Account.Type.DOMAIN_ADMIN);
        }

        if (keyword != null) {
            sc.setParameters("keywordAccountName", "%" + keyword + "%");
            sc.setParameters("keywordState", "%" + keyword + "%");
        }

        if (type != null) {
            sc.setParameters("type", type);
        }

        if (state != null) {
            sc.setParameters("state", state);
        }

        if (isCleanupRequired != null) {
            sc.setParameters("needsCleanup", isCleanupRequired);
        }

        if (accountName != null) {
            sc.setParameters("accountName", accountName);
        }

        if (accountId != null) {
            sc.setParameters("id", accountId);
        }

        if (domainId != null) {
            if (isRecursive) {
                // will happen if no "domainid" was specified in the request...
                if (domain == null) {
                    domain = _domainDao.findById(domainId);
                }
                sc.setJoinParameters("domainSearch", "path", domain.getPath() + "%");
            } else {
                sc.setParameters("domainId", domainId);
            }
        }

        if (apiKeyAccess != null) {
            try {
                ApiConstants.ApiKeyAccess access = ApiConstants.ApiKeyAccess.valueOf(apiKeyAccess.toUpperCase());
                sc.setParameters("apiKeyAccess", access.toBoolean());
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("ApiKeyAccess value can only be Enabled/Disabled/Inherit");
            }
        }

        Pair<List<AccountVO>, Integer> uniqueAccountPair = _accountDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueAccountPair.second();
        List<Long> accountIds = uniqueAccountPair.first().stream().map(AccountVO::getId).collect(Collectors.toList());
        return new Pair<>(accountIds, count);
    }

    @Override
    public ListResponse<AsyncJobResponse> searchForAsyncJobs(ListAsyncJobsCmd cmd) {
        Pair<List<AsyncJobJoinVO>, Integer> result = searchForAsyncJobsInternal(cmd);
        ListResponse<AsyncJobResponse> response = new ListResponse<>();
        List<AsyncJobResponse> jobResponses = ViewResponseHelper.createAsyncJobResponse(result.first().toArray(new AsyncJobJoinVO[0]));
        response.setResponses(jobResponses, result.second());
        return response;
    }

    private Pair<List<AsyncJobJoinVO>, Integer> searchForAsyncJobsInternal(ListAsyncJobsCmd cmd) {

        Account caller = CallContext.current().getCallingAccount();

        List<Long> permittedAccounts = new ArrayList<>();

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountMgr.buildACLSearchParameters(caller, null, cmd.getAccountName(), null, permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        Filter searchFilter = new Filter(AsyncJobJoinVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<AsyncJobJoinVO> sb = _jobJoinDao.createSearchBuilder();
        sb.and("instanceTypeNEQ", sb.entity().getInstanceType(), SearchCriteria.Op.NEQ);
        sb.and("accountIdIN", sb.entity().getAccountId(), SearchCriteria.Op.IN);
        boolean accountJoinIsDone = false;
        if (permittedAccounts.isEmpty() && domainId != null) {
            sb.and("domainId", sb.entity().getDomainId(), SearchCriteria.Op.EQ);
            sb.and("path", sb.entity().getDomainPath(), SearchCriteria.Op.LIKE);
            accountJoinIsDone = true;
        }

        if (listProjectResourcesCriteria != null) {

            if (listProjectResourcesCriteria == Project.ListProjectResourcesCriteria.ListProjectResourcesOnly) {
                sb.and("type", sb.entity().getAccountType(), SearchCriteria.Op.EQ);
            } else if (listProjectResourcesCriteria == Project.ListProjectResourcesCriteria.SkipProjectResources) {
                sb.and("type", sb.entity().getAccountType(), SearchCriteria.Op.NEQ);
            }

            if (!accountJoinIsDone) {
                sb.and("domainId", sb.entity().getDomainId(), SearchCriteria.Op.EQ);
                sb.and("path", sb.entity().getDomainPath(), SearchCriteria.Op.LIKE);
            }
        }

        if (cmd.getManagementServerId() != null) {
            sb.and("executingMsid", sb.entity().getExecutingMsid(), SearchCriteria.Op.EQ);
        }

        Object keyword = cmd.getKeyword();
        Object startDate = cmd.getStartDate();

        SearchCriteria<AsyncJobJoinVO> sc = sb.create();
        sc.setParameters("instanceTypeNEQ", AsyncJobVO.PSEUDO_JOB_INSTANCE_TYPE);
        if (listProjectResourcesCriteria != null) {
            sc.setParameters("type", Account.Type.PROJECT);
        }

        if (!permittedAccounts.isEmpty()) {
            sc.setParameters("accountIdIN", permittedAccounts.toArray());
        } else if (domainId != null) {
            DomainVO domain = _domainDao.findById(domainId);
            if (isRecursive) {
                sc.setParameters("path", domain.getPath() + "%");
            } else {
                sc.setParameters("domainId", domainId);
            }
        }

        if (keyword != null) {
            sc.addAnd("cmd", SearchCriteria.Op.LIKE, "%" + keyword + "%");
        }

        if (startDate != null) {
            sc.addAnd("created", SearchCriteria.Op.GTEQ, startDate);
        }

        if (cmd.getManagementServerId() != null) {
            ManagementServerHostVO msHost = msHostDao.findById(cmd.getManagementServerId());
            sc.setParameters("executingMsid", msHost.getMsid());
        }

        if (cmd.getResourceType() != null) {
            ApiCommandResourceType resourceType = getResourceType(cmd.getResourceType());
            sc.addAnd("instanceType", SearchCriteria.Op.EQ, resourceType.toString());

            final String resourceId = getResourceUuid(cmd.getResourceId());
            if (resourceId != null) {
                sc.addAnd("instanceUuid", SearchCriteria.Op.EQ, resourceId);
            }
        } else if (cmd.getResourceId() != null) {
            throw new InvalidParameterValueException(String.format("%s parameter must be used with %s parameter", ApiConstants.RESOURCE_ID, ApiConstants.RESOURCE_TYPE));
        }

        return _jobJoinDao.searchAndCount(sc, searchFilter);
    }

    @Override
    public ListResponse<StoragePoolResponse> searchForStoragePools(ListStoragePoolsCmd cmd) {
        Pair<List<StoragePoolJoinVO>, Integer> result = (ScopeType.HOST.name().equalsIgnoreCase(cmd.getScope()) && cmd.getHostId() != null) ?
                searchForLocalStorages(cmd) : searchForStoragePoolsInternal(cmd);
        return createStoragesPoolResponse(result, cmd.getCustomStats());
    }

    private Pair<List<StoragePoolJoinVO>, Integer> searchForLocalStorages(ListStoragePoolsCmd cmd) {
        long id = cmd.getHostId();
        List<StoragePoolHostVO> localstoragePools = storagePoolHostDao.listByHostId(id);
        Long[] poolIds = new Long[localstoragePools.size()];
        int i = 0;
        for(StoragePoolHostVO localstoragePool : localstoragePools) {
            StoragePool storagePool = storagePoolDao.findById(localstoragePool.getPoolId());
            if (storagePool != null && storagePool.isLocal()) {
                poolIds[i++] = localstoragePool.getPoolId();
            }
        }
        List<StoragePoolJoinVO> pools = _poolJoinDao.searchByIds(poolIds);
        return new Pair<>(pools, pools.size());
    }

    private void setPoolResponseNFSMountOptions(StoragePoolResponse poolResponse, Long poolId) {
        if (Storage.StoragePoolType.NetworkFilesystem.toString().equals(poolResponse.getType()) &&
                HypervisorType.KVM.toString().equals(poolResponse.getHypervisor())) {
            StoragePoolDetailVO detail = _storagePoolDetailsDao.findDetail(poolId, ApiConstants.NFS_MOUNT_OPTIONS);
            if (detail != null) {
                poolResponse.setNfsMountOpts(detail.getValue());
            }
        }
    }

    private ListResponse<StoragePoolResponse> createStoragesPoolResponse(Pair<List<StoragePoolJoinVO>, Integer> storagePools, boolean getCustomStats) {
        ListResponse<StoragePoolResponse> response = new ListResponse<>();

        List<StoragePoolResponse> poolResponses = ViewResponseHelper.createStoragePoolResponse(getCustomStats, storagePools.first().toArray(new StoragePoolJoinVO[0]));
        Map<String, Long> poolUuidToIdMap = storagePools.first().stream().collect(Collectors.toMap(StoragePoolJoinVO::getUuid, StoragePoolJoinVO::getId, (a, b) -> a));
        for (StoragePoolResponse poolResponse : poolResponses) {
            Long poolId = poolUuidToIdMap.get(poolResponse.getId());
            DataStore store = dataStoreManager.getPrimaryDataStore(poolResponse.getId());

            if (store != null) {
                addPoolDetailsAndCapabilities(poolResponse, store, poolId);
            }

            setPoolResponseNFSMountOptions(poolResponse, poolId);
        }

        response.setResponses(poolResponses, storagePools.second());
        return response;
    }

    private void addPoolDetailsAndCapabilities(StoragePoolResponse poolResponse, DataStore store, Long poolId) {
        Map<String, String> details = _storagePoolDetailsDao.listDetailsKeyPairs(store.getId(), true);
        poolResponse.setDetails(details);

        DataStoreDriver driver = store.getDriver();
        if (ObjectUtils.anyNull(driver, driver.getCapabilities())) {
            return;
        }

        Map<String, String> caps = driver.getCapabilities();
        if (Storage.StoragePoolType.NetworkFilesystem.toString().equals(poolResponse.getType()) && HypervisorType.VMware.toString().equals(poolResponse.getHypervisor())) {
            StoragePoolDetailVO detail = _storagePoolDetailsDao.findDetail(poolId, Storage.Capability.HARDWARE_ACCELERATION.toString());
            if (detail != null) {
                caps.put(Storage.Capability.HARDWARE_ACCELERATION.toString(), detail.getValue());
            }
        }
        poolResponse.setCaps(caps);
    }

    private ListResponse<StoragePoolResponse> searchForStoragePoolsWithMinimalResponse(ListStoragePoolsCmd cmd) {
        Pair<List<StoragePoolJoinVO>, Integer> result = searchForStoragePoolsInternal(cmd);
        ListResponse<StoragePoolResponse> response = new ListResponse<>();

        List<StoragePoolResponse> poolResponses = ViewResponseHelper.createMinimalStoragePoolResponse(result.first().toArray(new StoragePoolJoinVO[0]));
        response.setResponses(poolResponses, result.second());
        return response;
    }

    private Pair<List<StoragePoolJoinVO>, Integer> searchForStoragePoolsInternal(ListStoragePoolsCmd cmd) {
        ScopeType scopeType = ScopeType.validateAndGetScopeType(cmd.getScope());
        StoragePoolStatus status = StoragePoolStatus.validateAndGetStatus(cmd.getStatus());

        Long zoneId = accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), cmd.getZoneId());
        Long id = cmd.getId();
        String name = cmd.getStoragePoolName();
        String path = cmd.getPath();
        Long pod = cmd.getPodId();
        Long cluster = cmd.getClusterId();
        Long host = cmd.getHostId();
        String address = cmd.getIpAddress();
        String keyword = cmd.getKeyword();

        Long startIndex = cmd.getStartIndex();
        Long pageSize = cmd.getPageSizeVal();
        String storageAccessGroup = cmd.getStorageAccessGroup();

        Filter searchFilter = new Filter(StoragePoolVO.class, "id", Boolean.TRUE, startIndex, pageSize);

        Pair<List<Long>, Integer> uniquePoolPair = storagePoolDao.searchForIdsAndCount(id, name, zoneId, path, pod,
                cluster, host, address, scopeType, status, keyword, storageAccessGroup, searchFilter);

        List<StoragePoolJoinVO> storagePools = _poolJoinDao.searchByIds(uniquePoolPair.first().toArray(new Long[0]));

        return new Pair<>(storagePools, uniquePoolPair.second());
    }

    @Override
    public ListResponse<StorageTagResponse> searchForStorageTags(ListStorageTagsCmd cmd) {
        Pair<List<StoragePoolTagVO>, Integer> result = searchForStorageTagsInternal();
        ListResponse<StorageTagResponse> response = new ListResponse<>();
        List<StorageTagResponse> tagResponses = ViewResponseHelper.createStorageTagResponse(result.first().toArray(new StoragePoolTagVO[0]));

        response.setResponses(tagResponses, result.second());

        return response;
    }

    @Override
    public ListResponse<StorageAccessGroupResponse> searchForStorageAccessGroups(ListStorageAccessGroupsCmd cmd) {
        String name = cmd.getName();
        String keyword = cmd.getKeyword();
        Set<String> storageAccessGroups = new HashSet<>();

        addStorageAccessGroups(storageAccessGroups, storagePoolAndAccessGroupMapDao.listDistinctStorageAccessGroups(name, keyword));
        addStorageAccessGroups(storageAccessGroups, hostDao.listDistinctStorageAccessGroups(name, keyword));
        addStorageAccessGroups(storageAccessGroups, clusterDao.listDistinctStorageAccessGroups(name, keyword));
        addStorageAccessGroups(storageAccessGroups, podDao.listDistinctStorageAccessGroups(name, keyword));
        addStorageAccessGroups(storageAccessGroups, dataCenterDao.listDistinctStorageAccessGroups(name, keyword));

        if (StringUtils.isNotEmpty(name) && storageAccessGroups.contains(name)) {
            storageAccessGroups = Collections.singleton(name);
        }

        if (StringUtils.isNotEmpty(keyword)) {
            storageAccessGroups = storageAccessGroups.stream()
                    .filter(group -> group.contains(keyword))
                    .collect(Collectors.toSet());
        }

        List<StorageAccessGroupResponse> responseList = buildStorageAccessGroupResponses(storageAccessGroups, name);

        ListResponse<StorageAccessGroupResponse> response = new ListResponse<>();
        response.setResponses(responseList, storageAccessGroups.size());
        return response;
    }

    private void addStorageAccessGroups(Set<String> storageAccessGroups, List<String> groups) {
        for (String group : groups) {
            if (group != null && !group.isEmpty()) {
                storageAccessGroups.addAll(Arrays.asList(group.split(",")));
            }
        }
    }

    private List<StorageAccessGroupResponse> buildStorageAccessGroupResponses(
            Set<String> storageAccessGroups, String name) {
        List<StorageAccessGroupResponse> responseList = new ArrayList<>();

        for (String sag : storageAccessGroups) {
            StorageAccessGroupResponse sagResponse = new StorageAccessGroupResponse();
            sagResponse.setName(sag);
            sagResponse.setObjectName(ApiConstants.STORAGE_ACCESS_GROUP);

            if (StringUtils.isNotBlank(name)) {
                fetchStorageAccessGroupResponse(sagResponse, name);
            }

            responseList.add(sagResponse);
        }
        return responseList;
    }

    private void fetchStorageAccessGroupResponse(StorageAccessGroupResponse sagResponse, String name) {
        sagResponse.setHostResponseList(searchForServersWithMinimalResponse(new ListHostsCmd(name)));
        sagResponse.setZoneResponseList(listDataCentersWithMinimalResponse(new ListZonesCmd(name)));
        sagResponse.setPodResponseList(fetchPodsByStorageAccessGroup(name));
        sagResponse.setClusterResponseList(fetchClustersByStorageAccessGroup(name));
        sagResponse.setStoragePoolResponseList(searchForStoragePoolsWithMinimalResponse(new ListStoragePoolsCmd(name)));
    }

    private ListResponse<PodResponse> fetchPodsByStorageAccessGroup(String name) {
        ListPodsByCmd listPodsByCmd = new ListPodsByCmd(name);
        Pair<List<? extends Pod>, Integer> podResponsePair = managementService.searchForPods(listPodsByCmd);
        List<PodResponse> podResponses = podResponsePair.first().stream()
                .map(pod -> {
                    PodResponse podResponse = responseGenerator.createMinimalPodResponse(pod);
                    podResponse.setObjectName("pod");
                    return podResponse;
                }).collect(Collectors.toList());

        ListResponse<PodResponse> podResponse = new ListResponse<>();
        podResponse.setResponses(podResponses, podResponsePair.second());
        return podResponse;
    }

    private ListResponse<ClusterResponse> fetchClustersByStorageAccessGroup(String name) {
        ListClustersCmd listClustersCmd = new ListClustersCmd(name);
        Pair<List<? extends Cluster>, Integer> clusterResponsePair = managementService.searchForClusters(listClustersCmd);
        List<ClusterResponse> clusterResponses = clusterResponsePair.first().stream()
                .map(cluster -> {
                    ClusterResponse clusterResponse = responseGenerator.createMinimalClusterResponse(cluster);
                    clusterResponse.setObjectName("cluster");
                    return clusterResponse;
                }).collect(Collectors.toList());

        ListResponse<ClusterResponse> clusterResponse = new ListResponse<>();
        clusterResponse.setResponses(clusterResponses, clusterResponsePair.second());
        return clusterResponse;
    }

    private Pair<List<StoragePoolTagVO>, Integer> searchForStorageTagsInternal() {
        Filter searchFilter = new Filter(StoragePoolTagVO.class, "id", Boolean.TRUE, null, null);

        SearchBuilder<StoragePoolTagVO> sb = _storageTagDao.createSearchBuilder();

        sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct

        SearchCriteria<StoragePoolTagVO> sc = sb.create();

        // search storage tag details by ids
        Pair<List<StoragePoolTagVO>, Integer> uniqueTagPair = _storageTagDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueTagPair.second();

        if (count == 0) {
            return uniqueTagPair;
        }

        List<StoragePoolTagVO> uniqueTags = uniqueTagPair.first();
        Long[] vrIds = new Long[uniqueTags.size()];
        int i = 0;

        for (StoragePoolTagVO v : uniqueTags) {
            vrIds[i++] = v.getId();
        }

        List<StoragePoolTagVO> vrs = _storageTagDao.searchByIds(vrIds);

        return new Pair<>(vrs, count);
    }

    @Override
    public ListResponse<HostTagResponse> searchForHostTags(ListHostTagsCmd cmd) {
        Pair<List<HostTagVO>, Integer> result = searchForHostTagsInternal();
        ListResponse<HostTagResponse> response = new ListResponse<>();
        List<HostTagResponse> tagResponses = ViewResponseHelper.createHostTagResponse(result.first().toArray(new HostTagVO[0]));

        response.setResponses(tagResponses, result.second());

        return response;
    }

    private Pair<List<HostTagVO>, Integer> searchForHostTagsInternal() {
        Filter searchFilter = new Filter(HostTagVO.class, "id", Boolean.TRUE, null, null);

        SearchBuilder<HostTagVO> sb = _hostTagDao.createSearchBuilder();

        sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct

        SearchCriteria<HostTagVO> sc = sb.create();

        // search host tag details by ids
        Pair<List<HostTagVO>, Integer> uniqueTagPair = _hostTagDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueTagPair.second();

        if (count == 0) {
            return uniqueTagPair;
        }

        List<HostTagVO> uniqueTags = uniqueTagPair.first();
        Long[] vrIds = new Long[uniqueTags.size()];
        int i = 0;

        for (HostTagVO v : uniqueTags) {
            vrIds[i++] = v.getId();
        }

        List<HostTagVO> vrs = _hostTagDao.searchByIds(vrIds);

        return new Pair<>(vrs, count);
    }

    @Override
    public ListResponse<ImageStoreResponse> searchForImageStores(ListImageStoresCmd cmd) {
        Pair<List<ImageStoreJoinVO>, Integer> result = searchForImageStoresInternal(cmd);
        ListResponse<ImageStoreResponse> response = new ListResponse<>();

        List<ImageStoreResponse> poolResponses = ViewResponseHelper.createImageStoreResponse(result.first().toArray(new ImageStoreJoinVO[0]));
        response.setResponses(poolResponses, result.second());
        return response;
    }

    private Pair<List<ImageStoreJoinVO>, Integer> searchForImageStoresInternal(ListImageStoresCmd cmd) {
        return imageStoreQueryService.searchForImageStoresInternal(cmd);
    }

    @Override
    public ListResponse<ImageStoreResponse> searchForSecondaryStagingStores(ListSecondaryStagingStoresCmd cmd) {
        Pair<List<ImageStoreJoinVO>, Integer> result = searchForCacheStoresInternal(cmd);
        ListResponse<ImageStoreResponse> response = new ListResponse<>();

        List<ImageStoreResponse> poolResponses = ViewResponseHelper.createImageStoreResponse(result.first().toArray(new ImageStoreJoinVO[0]));
        response.setResponses(poolResponses, result.second());
        return response;
    }

    private Pair<List<ImageStoreJoinVO>, Integer> searchForCacheStoresInternal(ListSecondaryStagingStoresCmd cmd) {
        return imageStoreQueryService.searchForCacheStoresInternal(cmd);
    }

    @Override
    public ListResponse<DiskOfferingResponse> searchForDiskOfferings(ListDiskOfferingsCmd cmd) {
        Pair<List<DiskOfferingJoinVO>, Integer> result = searchForDiskOfferingsInternal(cmd);
        ListResponse<DiskOfferingResponse> response = new ListResponse<>();
        List<DiskOfferingResponse> offeringResponses = ViewResponseHelper.createDiskOfferingResponses(cmd.getVirtualMachineId(), result.first());
        response.setResponses(offeringResponses, result.second());
        return response;
    }

    private Pair<List<DiskOfferingJoinVO>, Integer> searchForDiskOfferingsInternal(ListDiskOfferingsCmd cmd) {
        Ternary<List<Long>, Integer, String[]> diskOfferingIdPage = searchForDiskOfferingsIdsAndCount(cmd);

        Integer count = diskOfferingIdPage.second();
        Long[] idArray = diskOfferingIdPage.first().toArray(new Long[0]);
        String[] requiredTagsArray = diskOfferingIdPage.third();

        if (count == 0) {
            return new Pair<>(new ArrayList<>(), count);
        }

        List<DiskOfferingJoinVO> diskOfferings = _diskOfferingJoinDao.searchByIds(idArray);

        if (requiredTagsArray.length != 0) {
            ListIterator<DiskOfferingJoinVO> iteratorForTagsChecking = diskOfferings.listIterator();
            while (iteratorForTagsChecking.hasNext()) {
                DiskOfferingJoinVO offering = iteratorForTagsChecking.next();
                String offeringTags = offering.getTags();
                String[] offeringTagsArray = (offeringTags == null || offeringTags.isEmpty()) ? new String[0] : offeringTags.split(",");
                if (!CollectionUtils.isSubCollection(Arrays.asList(requiredTagsArray), Arrays.asList(offeringTagsArray))) {
                    iteratorForTagsChecking.remove();
                    count--;
                }
            }
        }
        return new Pair<>(diskOfferings, count);
    }

    private Ternary<List<Long>, Integer, String[]> searchForDiskOfferingsIdsAndCount(ListDiskOfferingsCmd cmd) {
        // Note
        // The list method for offerings is being modified in accordance with
        // discussion with Will/Kevin
        // For now, we will be listing the following based on the usertype
        // 1. For root, we will list all offerings
        // 2. For domainAdmin and regular users, we will list everything in
        // their domains+parent domains ... all the way
        // till
        // root

        Account account = CallContext.current().getCallingAccount();
        Object name = cmd.getDiskOfferingName();
        Object id = cmd.getId();
        Object keyword = cmd.getKeyword();
        Long domainId = cmd.getDomainId();
        boolean isRootAdmin = accountMgr.isRootAdmin(account.getAccountId());
        Long projectId = cmd.getProjectId();
        String accountName = cmd.getAccountName();
        boolean isRecursive = cmd.isRecursive();
        Long zoneId = cmd.getZoneId();
        Long volumeId = cmd.getVolumeId();
        Long storagePoolId = cmd.getStoragePoolId();
        Boolean encrypt = cmd.getEncrypt();
        String storageType = cmd.getStorageType();
        DiskOffering.State state = cmd.getState();
        final Long vmId = cmd.getVirtualMachineId();

        Filter searchFilter = new Filter(DiskOfferingVO.class, "sortKey", SortKeyAscending.value(), cmd.getStartIndex(), cmd.getPageSizeVal());
        searchFilter.addOrderBy(DiskOfferingVO.class, "id", true);
        SearchBuilder<DiskOfferingVO> diskOfferingSearch = _diskOfferingDao.createSearchBuilder();
        diskOfferingSearch.select(null, Func.DISTINCT, diskOfferingSearch.entity().getId()); // select distinct

        diskOfferingSearch.and("computeOnly", diskOfferingSearch.entity().isComputeOnly(), Op.EQ);

        if (state != null) {
            diskOfferingSearch.and("state", diskOfferingSearch.entity().getState(), Op.EQ);
        }

        // Keeping this logic consistent with domain specific zones
        // if a domainId is provided, we just return the disk offering
        // associated with this domain
        if (domainId != null && accountName == null) {
            if (accountMgr.isRootAdmin(account.getId()) || isPermissible(account.getDomainId(), domainId)) {
                // check if the user's domain == do's domain || user's domain is
                // a child of so's domain for non-root users
                SearchBuilder<DiskOfferingDetailVO> domainDetailsSearch = _diskOfferingDetailsDao.createSearchBuilder();
                domainDetailsSearch.and("domainId", domainDetailsSearch.entity().getValue(), Op.EQ);

                diskOfferingSearch.join("domainDetailsSearch", domainDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                        diskOfferingSearch.entity().getId(), domainDetailsSearch.entity().getResourceId(),
                        domainDetailsSearch.entity().getName(), diskOfferingSearch.entity().setString(ApiConstants.DOMAIN_ID));

                if (!isRootAdmin) {
                    diskOfferingSearch.and("displayOffering", diskOfferingSearch.entity().getDisplayOffering(), Op.EQ);
                }

                SearchCriteria<DiskOfferingVO> sc = diskOfferingSearch.create();
                sc.setParameters("computeOnly", false);
                if (state != null) {
                    sc.setParameters("state", state);
                }

                sc.setJoinParameters("domainDetailsSearch", "domainId", domainId);

                Pair<List<DiskOfferingVO>, Integer> uniquePairs = _diskOfferingDao.searchAndCount(sc, searchFilter);
                List<Long> idsArray = uniquePairs.first().stream().map(DiskOfferingVO::getId).collect(Collectors.toList());
                return new Ternary<>(idsArray, uniquePairs.second(), new String[0]);
            } else {
                throw new PermissionDeniedException("The account:" + account.getAccountName() + " does not fall in the same domain hierarchy as the disk offering");
            }
        }

        // For non-root users, only return all offerings for the user's domain,
        // and everything above till root
        if ((accountMgr.isNormalUser(account.getId()) || accountMgr.isDomainAdmin(account.getId())) || account.getType() == Account.Type.RESOURCE_DOMAIN_ADMIN) {
            if (isRecursive) { // domain + all sub-domains
                if (account.getType() == Account.Type.NORMAL) {
                    throw new InvalidParameterValueException("Only ROOT admins and Domain admins can list disk offerings with isrecursive=true");
                }
            }
        }

        if (volumeId != null && storagePoolId != null) {
            throw new InvalidParameterValueException("Both volume ID and storage pool ID are not allowed at the same time");
        }

        if (keyword != null) {
            diskOfferingSearch.and().op("keywordDisplayText", diskOfferingSearch.entity().getDisplayText(), Op.LIKE);
            diskOfferingSearch.or("keywordName", diskOfferingSearch.entity().getName(), Op.LIKE);
            diskOfferingSearch.cp();
        }

        if (id != null) {
            diskOfferingSearch.and("id", diskOfferingSearch.entity().getId(), Op.EQ);
        }

        if (name != null) {
            diskOfferingSearch.and("name", diskOfferingSearch.entity().getName(), Op.EQ);
        }

        if (encrypt != null) {
            diskOfferingSearch.and("encrypt", diskOfferingSearch.entity().getEncrypt(), Op.EQ);
        }

        if (storageType != null || zoneId != null) {
            diskOfferingSearch.and("useLocalStorage", diskOfferingSearch.entity().isUseLocalStorage(), Op.EQ);
        }

        if (zoneId != null) {
            SearchBuilder<DiskOfferingDetailVO> zoneDetailSearch = _diskOfferingDetailsDao.createSearchBuilder();
            zoneDetailSearch.and().op("zoneId", zoneDetailSearch.entity().getValue(), Op.EQ);
            zoneDetailSearch.or("zoneIdNull", zoneDetailSearch.entity().getId(), Op.NULL);
            zoneDetailSearch.cp();

            diskOfferingSearch.join("zoneDetailSearch", zoneDetailSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    diskOfferingSearch.entity().getId(), zoneDetailSearch.entity().getResourceId(),
                    zoneDetailSearch.entity().getName(), diskOfferingSearch.entity().setString(ApiConstants.ZONE_ID));
        }

        DiskOffering currentDiskOffering = null;
        Volume volume = null;
        if (volumeId != null) {
            volume = volumeDao.findById(volumeId);
            if (volume == null) {
                throw new InvalidParameterValueException(String.format("Unable to find a volume with specified id %s", volumeId));
            }
            currentDiskOffering = _diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId());
            if (!currentDiskOffering.isComputeOnly() && currentDiskOffering.getDiskSizeStrictness()) {
                diskOfferingSearch.and().op("diskSize", diskOfferingSearch.entity().getDiskSize(), Op.EQ);
                diskOfferingSearch.or("customized", diskOfferingSearch.entity().isCustomized(), Op.EQ);
                diskOfferingSearch.cp();
            }
            diskOfferingSearch.and("idNEQ", diskOfferingSearch.entity().getId(), Op.NEQ);
            diskOfferingSearch.and("diskSizeStrictness", diskOfferingSearch.entity().getDiskSizeStrictness(), Op.EQ);
        }

        account = accountMgr.finalizeOwner(account, accountName, domainId, projectId);
        if (!Account.Type.ADMIN.equals(account.getType())) {
            SearchBuilder<DiskOfferingDetailVO> domainDetailsSearch = _diskOfferingDetailsDao.createSearchBuilder();
            domainDetailsSearch.and().op("domainIdIN", domainDetailsSearch.entity().getValue(), Op.IN);
            domainDetailsSearch.or("domainIdNull", domainDetailsSearch.entity().getId(), Op.NULL);
            domainDetailsSearch.cp();

            diskOfferingSearch.join("domainDetailsSearch", domainDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    diskOfferingSearch.entity().getId(), domainDetailsSearch.entity().getResourceId(),
                    domainDetailsSearch.entity().getName(), diskOfferingSearch.entity().setString(ApiConstants.DOMAIN_ID));
        }

        SearchCriteria<DiskOfferingVO> sc = diskOfferingSearch.create();

        sc.setParameters("computeOnly", false);

        if (state != null) {
            sc.setParameters("state", state);
        }

        if (keyword != null) {
            sc.setParameters("keywordDisplayText", "%" + keyword + "%");
            sc.setParameters("keywordName", "%" + keyword + "%");
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (encrypt != null) {
            sc.setParameters("encrypt", encrypt);
        }

        if (storageType != null) {
            if (storageType.equalsIgnoreCase(ServiceOffering.StorageType.local.toString())) {
                sc.setParameters("useLocalStorage", true);

            } else if (storageType.equalsIgnoreCase(ServiceOffering.StorageType.shared.toString())) {
                sc.setParameters("useLocalStorage", false);
            }
        }

        if (zoneId != null) {
            sc.setJoinParameters("zoneDetailSearch", "zoneId", zoneId);

            DataCenterJoinVO zone = _dcJoinDao.findById(zoneId);
            if (DataCenter.Type.Edge.equals(zone.getType())) {
                sc.setParameters("useLocalStorage", true);
            }
        }

        if (volumeId != null) {
            if (!currentDiskOffering.isComputeOnly() && currentDiskOffering.getDiskSizeStrictness()) {
                sc.setParameters("diskSize", volume.getSize());
                sc.setParameters("customized", true);
            }
            sc.setParameters("idNEQ", currentDiskOffering.getId());
            sc.setParameters("diskSizeStrictness", currentDiskOffering.getDiskSizeStrictness());
        }

        // Filter offerings that are not associated with caller's domain
        if (!Account.Type.ADMIN.equals(account.getType())) {
            Domain callerDomain = _domainDao.findById(account.getDomainId());
            List<Long> domainIds = findRelatedDomainIds(callerDomain, isRecursive);

            sc.setJoinParameters("domainDetailsSearch", "domainIdIN", domainIds.toArray());
        }

        if (vmId != null) {
            UserVmVO vm = userVmDao.findById(vmId);
            if (vm == null) {
                throw new InvalidParameterValueException("Unable to find the VM instance with the specified ID");
            }
            if (!isRootAdmin) {
                accountMgr.checkAccess(account, null, false, vm);
            }
        }

        Pair<List<DiskOfferingVO>, Integer> uniquePairs = _diskOfferingDao.searchAndCount(sc, searchFilter);
        String[] requiredTagsArray = new String[0];
        if (CollectionUtils.isNotEmpty(uniquePairs.first()) && VolumeApiServiceImpl.MatchStoragePoolTagsWithDiskOffering.valueIn(zoneId)) {
            if (volumeId != null) {
                requiredTagsArray = currentDiskOffering.getTagsArray();
            } else if (storagePoolId != null) {
                requiredTagsArray = _storageTagDao.getStoragePoolTags(storagePoolId).toArray(new String[0]);
            }
        }
        List<Long> idsArray = uniquePairs.first().stream().map(DiskOfferingVO::getId).collect(Collectors.toList());

        return new Ternary<>(idsArray, uniquePairs.second(), requiredTagsArray);
    }

    private void useStorageType(SearchCriteria<?> sc, String storageType) {
        if (storageType != null) {
            if (storageType.equalsIgnoreCase(ServiceOffering.StorageType.local.toString())) {
                sc.addAnd("useLocalStorage", Op.EQ, true);

            } else if (storageType.equalsIgnoreCase(ServiceOffering.StorageType.shared.toString())) {
                sc.addAnd("useLocalStorage", Op.EQ, false);
            }
        }
    }

    private List<Long> findRelatedDomainIds(Domain domain, boolean isRecursive) {
        List<Long> domainIds = new ArrayList<>(_domainDao.getDomainParentIds(domain.getId()));
        if (isRecursive) {
            List<Long> childrenIds = _domainDao.getDomainChildrenIds(domain.getPath());
            if (childrenIds != null && !childrenIds.isEmpty()) {
                domainIds.addAll(childrenIds);
            }
        }
        return domainIds;
    }

    @Override
    public ListResponse<ServiceOfferingResponse> searchForServiceOfferings(ListServiceOfferingsCmd cmd) {
        Pair<List<ServiceOfferingJoinVO>, Integer> result = searchForServiceOfferingsInternal(cmd);
        result.first();
        ListResponse<ServiceOfferingResponse> response = new ListResponse<>();
        List<ServiceOfferingResponse> offeringResponses = ViewResponseHelper.createServiceOfferingResponse(result.first().toArray(new ServiceOfferingJoinVO[0]));
        response.setResponses(offeringResponses, result.second());
        return response;
    }

    protected List<String> getHostTagsFromTemplateForServiceOfferingsListing(Account caller, Long templateId) {
        List<String> hostTags = new ArrayList<>();
        if (templateId == null) {
            return hostTags;
        }
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(templateId);
        if (template == null) {
            throw new InvalidParameterValueException("Unable to find template with the specified ID");
        }
        if (caller.getType() != Account.Type.ADMIN) {
            accountMgr.checkAccess(caller, null, false, template);
        }
        if (StringUtils.isNotEmpty(template.getTemplateTag())) {
            hostTags.add(template.getTemplateTag());
        }
        return hostTags;
    }

    private Pair<List<ServiceOfferingJoinVO>, Integer> searchForServiceOfferingsInternal(ListServiceOfferingsCmd cmd) {
        Pair<List<Long>, Integer> offeringIdPage = searchForServiceOfferingIdsAndCount(cmd);

        Integer count = offeringIdPage.second();
        Long[] idArray = offeringIdPage.first().toArray(new Long[0]);

        if (count == 0) {
            return new Pair<>(new ArrayList<>(), count);
        }

        List<ServiceOfferingJoinVO> srvOfferings = _srvOfferingJoinDao.searchByIds(idArray);
        return new Pair<>(srvOfferings, count);
    }

    private Pair<List<Long>, Integer> searchForServiceOfferingIdsAndCount(ListServiceOfferingsCmd cmd) {
        // Note
        // The filteredOfferings method for offerings is being modified in accordance with
        // discussion with Will/Kevin
        // For now, we will be listing the following based on the usertype
        // 1. For root, we will filteredOfferings all offerings
        // 2. For domainAdmin and regular users, we will filteredOfferings everything in
        // their domains+parent domains ... all the way
        // till
        // root
        Account caller = CallContext.current().getCallingAccount();
        Long projectId = cmd.getProjectId();
        String accountName = cmd.getAccountName();
        Object name = cmd.getServiceOfferingName();
        Object id = cmd.getId();
        Object keyword = cmd.getKeyword();
        Long vmId = cmd.getVirtualMachineId();
        Long domainId = cmd.getDomainId();
        Boolean isSystem = cmd.getIsSystem();
        String vmTypeStr = cmd.getSystemVmType();
        ServiceOfferingVO currentVmOffering = null;
        DiskOfferingVO diskOffering = null;
        boolean isRecursive = cmd.isRecursive();
        Long zoneId = cmd.getZoneId();
        Integer cpuNumber = cmd.getCpuNumber();
        Integer memory = cmd.getMemory();
        Integer cpuSpeed = cmd.getCpuSpeed();
        Boolean encryptRoot = cmd.getEncryptRoot();
        String storageType = cmd.getStorageType();
        ServiceOffering.State state = cmd.getState();
        final Long vgpuProfileId = cmd.getVgpuProfileId();
        final Boolean gpuEnabled = cmd.getGpuEnabled();

        final Account owner = accountMgr.finalizeOwner(caller, accountName, domainId, projectId);

        if (!accountMgr.isRootAdmin(caller.getId()) && isSystem) {
            throw new InvalidParameterValueException("Only ROOT admins can access system offerings.");
        }

        // Keeping this logic consistent with domain specific zones
        // if a domainId is provided, we just return the so associated with this
        // domain
        if (domainId != null && !accountMgr.isRootAdmin(caller.getId())) {
            // check if the user's domain == so's domain || user's domain is a
            // child of so's domain
            if (!isPermissible(owner.getDomainId(), domainId)) {
                throw new PermissionDeniedException("The account:" + owner.getAccountName() + " does not fall in the same domain hierarchy as the service offering");
            }
        }

        VMInstanceVO vmInstance = null;
        if (vmId != null) {
            vmInstance = _vmInstanceDao.findById(vmId);
            if ((vmInstance == null) || (vmInstance.getRemoved() != null)) {
                InvalidParameterValueException ex = new InvalidParameterValueException("unable to find a virtual machine with specified id");
                ex.addProxyObject(vmId.toString(), "vmId");
                throw ex;
            }
            accountMgr.checkAccess(owner, null, true, vmInstance);
        }

        Filter searchFilter = new Filter(ServiceOfferingVO.class, "sortKey", SortKeyAscending.value(), cmd.getStartIndex(), cmd.getPageSizeVal());
        searchFilter.addOrderBy(ServiceOfferingVO.class, "id", true);

        SearchBuilder<ServiceOfferingVO> serviceOfferingSearch = _srvOfferingDao.createSearchBuilder();
        serviceOfferingSearch.select(null, Func.DISTINCT, serviceOfferingSearch.entity().getId()); // select distinct

        if (state != null) {
            serviceOfferingSearch.and("state", serviceOfferingSearch.entity().getState(), Op.EQ);
        }

        if (vgpuProfileId != null) {
            serviceOfferingSearch.and("vgpuProfileId", serviceOfferingSearch.entity().getVgpuProfileId(), Op.EQ);
        }

        if (gpuEnabled != null) {
            _srvOfferingDao.addCheckForGpuEnabled(serviceOfferingSearch, gpuEnabled);
        }

        if (vmId != null) {
            currentVmOffering = _srvOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
            diskOffering = _diskOfferingDao.findByIdIncludingRemoved(currentVmOffering.getDiskOfferingId());
            if (!currentVmOffering.isDynamic()) {
                serviceOfferingSearch.and("idNEQ", serviceOfferingSearch.entity().getId(), SearchCriteria.Op.NEQ);
            }

            if (currentVmOffering.getDiskOfferingStrictness()) {
                serviceOfferingSearch.and("diskOfferingId", serviceOfferingSearch.entity().getDiskOfferingId(), SearchCriteria.Op.EQ);
            }
            serviceOfferingSearch.and("diskOfferingStrictness", serviceOfferingSearch.entity().getDiskOfferingStrictness(), SearchCriteria.Op.EQ);

            // In case vm is running return only offerings greater than equal to current offering compute and offering's dynamic scalability should match
            if (vmInstance.getState() == VirtualMachine.State.Running) {
                Integer vmCpu = currentVmOffering.getCpu();
                Integer vmMemory = currentVmOffering.getRamSize();
                Integer vmSpeed = currentVmOffering.getSpeed();
                if ((vmCpu == null || vmMemory == null || vmSpeed == null) && VirtualMachine.Type.User.equals(vmInstance.getType())) {
                    UserVmVO userVmVO = userVmDao.findById(vmId);
                    userVmDao.loadDetails(userVmVO);
                    Map<String, String> details = userVmVO.getDetails();
                    vmCpu = NumbersUtil.parseInt(details.get(ApiConstants.CPU_NUMBER), 0);
                    if (vmSpeed == null) {
                        vmSpeed = NumbersUtil.parseInt(details.get(ApiConstants.CPU_SPEED), 0);
                    }
                    vmMemory = NumbersUtil.parseInt(details.get(ApiConstants.MEMORY), 0);
                }
                if (vmCpu != null && vmCpu > 0) {
                    /*
                            (service_offering.cpu >= ?)
                             OR (
                                service_offering.cpu IS NULL
                                AND (maxComputeDetailsSearch.value IS NULL OR  maxComputeDetailsSearch.value >= ?)
                            )
                     */
                    SearchBuilder<ServiceOfferingDetailsVO> maxComputeDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();

                    serviceOfferingSearch.join("maxComputeDetailsSearch", maxComputeDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                            serviceOfferingSearch.entity().getId(), maxComputeDetailsSearch.entity().getResourceId(),
                            maxComputeDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.MAX_CPU_NUMBER));

                    serviceOfferingSearch.and().op("vmCpu", serviceOfferingSearch.entity().getCpu(), Op.GTEQ);
                    serviceOfferingSearch.or().op("vmCpuNull", serviceOfferingSearch.entity().getCpu(), Op.NULL);
                    serviceOfferingSearch.and().op("maxComputeDetailsSearch", "vmMaxComputeNull", maxComputeDetailsSearch.entity().getValue(), Op.NULL);
                    serviceOfferingSearch.or("maxComputeDetailsSearch", "vmMaxComputeGTEQ", maxComputeDetailsSearch.entity().getValue(), Op.GTEQ).cp();

                    serviceOfferingSearch.cp().cp();

                }
                if (vmSpeed != null && vmSpeed > 0) {
                    serviceOfferingSearch.and().op("speedNULL", serviceOfferingSearch.entity().getSpeed(), Op.NULL);
                    serviceOfferingSearch.or("speedGTEQ", serviceOfferingSearch.entity().getSpeed(), Op.GTEQ);
                    serviceOfferingSearch.cp();
                }
                if (vmMemory != null && vmMemory > 0) {
                    /*
                        (service_offering.ram_size >= ?)
                        OR (
                          service_offering.ram_size IS NULL
                          AND (max_memory_details.value IS NULL OR max_memory_details.value >= ?)
                        )
                     */
                    SearchBuilder<ServiceOfferingDetailsVO> maxMemoryDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();

                    serviceOfferingSearch.join("maxMemoryDetailsSearch", maxMemoryDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                            serviceOfferingSearch.entity().getId(), maxMemoryDetailsSearch.entity().getResourceId(),
                            maxMemoryDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString("maxmemory"));

                    serviceOfferingSearch.and().op("vmMemory", serviceOfferingSearch.entity().getRamSize(), Op.GTEQ);
                    serviceOfferingSearch.or().op("vmMemoryNull", serviceOfferingSearch.entity().getRamSize(), Op.NULL);
                    serviceOfferingSearch.and().op("maxMemoryDetailsSearch", "vmMaxMemoryNull", maxMemoryDetailsSearch.entity().getValue(), Op.NULL);
                    serviceOfferingSearch.or("maxMemoryDetailsSearch", "vmMaxMemoryGTEQ", maxMemoryDetailsSearch.entity().getValue(), Op.GTEQ).cp();

                    serviceOfferingSearch.cp().cp();
                }
                serviceOfferingSearch.and("dynamicScalingEnabled", serviceOfferingSearch.entity().isDynamicScalingEnabled(), SearchCriteria.Op.EQ);
            }
        }

        if ((accountMgr.isNormalUser(owner.getId()) || accountMgr.isDomainAdmin(owner.getId())) || owner.getType() == Account.Type.RESOURCE_DOMAIN_ADMIN) {
            // For non-root users.
            if (isSystem) {
                throw new InvalidParameterValueException("Only root admins can access system's offering");
            }
            if (isRecursive) { // domain + all sub-domains
                if (owner.getType() == Account.Type.NORMAL) {
                    throw new InvalidParameterValueException("Only ROOT admins and Domain admins can list service offerings with isrecursive=true");
                }
            }
        } else {
            // for root users
            if (owner.getDomainId() != 1 && isSystem) { // NON ROOT admin
                throw new InvalidParameterValueException("Non ROOT admins cannot access system's offering");
            }
            if (domainId != null && accountName == null) {
                SearchBuilder<ServiceOfferingDetailsVO> srvOffrDomainDetailSearch = _srvOfferingDetailsDao.createSearchBuilder();
                srvOffrDomainDetailSearch.and("domainId", srvOffrDomainDetailSearch.entity().getValue(), Op.EQ);
                serviceOfferingSearch.join("domainDetailSearch", srvOffrDomainDetailSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                        serviceOfferingSearch.entity().getId(), srvOffrDomainDetailSearch.entity().getResourceId(),
                        srvOffrDomainDetailSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.DOMAIN_ID));
            }
        }

        if (keyword != null) {
            serviceOfferingSearch.and().op("keywordName", serviceOfferingSearch.entity().getName(), SearchCriteria.Op.LIKE);
            serviceOfferingSearch.or("keywordDisplayText", serviceOfferingSearch.entity().getDisplayText(), SearchCriteria.Op.LIKE);
            serviceOfferingSearch.cp();
        }

        if (id != null) {
            serviceOfferingSearch.and("id", serviceOfferingSearch.entity().getId(), SearchCriteria.Op.EQ);
        }

        if (isSystem != null) {
            // note that for non-root users, isSystem is always false when
            // control comes to here
            serviceOfferingSearch.and("systemUse", serviceOfferingSearch.entity().isSystemUse(), SearchCriteria.Op.EQ);
        }

        if (name != null) {
            serviceOfferingSearch.and("name", serviceOfferingSearch.entity().getName(), SearchCriteria.Op.EQ);
        }

        if (vmTypeStr != null) {
            serviceOfferingSearch.and("svmType", serviceOfferingSearch.entity().getVmType(), SearchCriteria.Op.EQ);
        }
        DataCenterJoinVO zone = null;
        if (zoneId != null) {
            SearchBuilder<ServiceOfferingDetailsVO> srvOffrZoneDetailSearch = _srvOfferingDetailsDao.createSearchBuilder();
            srvOffrZoneDetailSearch.and().op("zoneId", srvOffrZoneDetailSearch.entity().getValue(), Op.EQ);
            srvOffrZoneDetailSearch.or("idNull", srvOffrZoneDetailSearch.entity().getId(), Op.NULL);
            srvOffrZoneDetailSearch.cp();

            serviceOfferingSearch.join("ZoneDetailSearch", srvOffrZoneDetailSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    serviceOfferingSearch.entity().getId(), srvOffrZoneDetailSearch.entity().getResourceId(),
                    srvOffrZoneDetailSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.ZONE_ID));
            zone = _dcJoinDao.findById(zoneId);
        }

        if (encryptRoot != null || vmId != null || (zone != null && DataCenter.Type.Edge.equals(zone.getType()))) {
            SearchBuilder<DiskOfferingVO> diskOfferingSearch = _diskOfferingDao.createSearchBuilder();
            diskOfferingSearch.and("useLocalStorage", diskOfferingSearch.entity().isUseLocalStorage(), SearchCriteria.Op.EQ);
            diskOfferingSearch.and("encrypt", diskOfferingSearch.entity().getEncrypt(), SearchCriteria.Op.EQ);

            if (diskOffering != null) {
                List<String> storageTags = com.cloud.utils.StringUtils.csvTagsToList(diskOffering.getTags());
                if (!storageTags.isEmpty() && VolumeApiServiceImpl.MatchStoragePoolTagsWithDiskOffering.value()) {
                    for (String tag : storageTags) {
                        diskOfferingSearch.and("storageTag" + tag, diskOfferingSearch.entity().getTags(), Op.FIND_IN_SET);
                    }
                }
            }

            serviceOfferingSearch.join("diskOfferingSearch", diskOfferingSearch, JoinBuilder.JoinType.INNER, JoinBuilder.JoinCondition.AND,
                    serviceOfferingSearch.entity().getDiskOfferingId(), diskOfferingSearch.entity().getId(),
                    serviceOfferingSearch.entity().setString("Active"), diskOfferingSearch.entity().getState());
        }

        if (cpuNumber != null) {
            SearchBuilder<ServiceOfferingDetailsVO> maxComputeDetailsSearch = (SearchBuilder<ServiceOfferingDetailsVO>) serviceOfferingSearch.getJoinSB("maxComputeDetailsSearch");
            if (maxComputeDetailsSearch == null) {
                maxComputeDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();
                serviceOfferingSearch.join("maxComputeDetailsSearch", maxComputeDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                        serviceOfferingSearch.entity().getId(), maxComputeDetailsSearch.entity().getResourceId(),
                        maxComputeDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.MAX_CPU_NUMBER));
            }

            SearchBuilder<ServiceOfferingDetailsVO> minComputeDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();

            serviceOfferingSearch.join("minComputeDetailsSearch", minComputeDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    serviceOfferingSearch.entity().getId(), minComputeDetailsSearch.entity().getResourceId(),
                    minComputeDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.MIN_CPU_NUMBER));

            /*
                (min_cpu IS NULL AND cpu IS NULL AND max_cpu IS NULL)
                OR (cpu = X)
                OR (min_cpu <= X AND max_cpu >= X)

                AND (
                    (min_compute_details.value is NULL AND cpu is NULL)
                    OR (min_compute_details.value is NULL AND cpu >= X)
                    OR min_compute_details.value >= X
                    OR (
                        ((min_compute_details.value is NULL AND cpu <= X) OR min_compute_details.value <= X)
                        AND ((max_compute_details.value is NULL AND cpu >= X) OR max_compute_details.value >= X)
                    )
                )
             */
            serviceOfferingSearch.and().op().op("minComputeDetailsSearch", "cpuConstraintMinComputeNull", minComputeDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("cpuConstraintNull", serviceOfferingSearch.entity().getCpu(), Op.NULL).cp();

            serviceOfferingSearch.or().op("minComputeDetailsSearch", "cpuConstraintMinComputeNull", minComputeDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("cpuNumber", serviceOfferingSearch.entity().getCpu(), Op.GTEQ).cp();
            serviceOfferingSearch.or("cpuNumber", serviceOfferingSearch.entity().getCpu(), Op.GTEQ);

            serviceOfferingSearch.or().op().op();
            serviceOfferingSearch.op("minComputeDetailsSearch", "cpuConstraintMinComputeNull", minComputeDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("cpuNumber", serviceOfferingSearch.entity().getCpu(), Op.LTEQ).cp();
            serviceOfferingSearch.or("minComputeDetailsSearch", "cpuNumber", minComputeDetailsSearch.entity().getValue(), Op.LTEQ).cp();
            serviceOfferingSearch.and().op().op("maxComputeDetailsSearch", "cpuConstraintMaxComputeNull", maxComputeDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("cpuNumber", serviceOfferingSearch.entity().getCpu(), Op.GTEQ).cp();
            serviceOfferingSearch.or("maxComputeDetailsSearch", "cpuNumber", maxComputeDetailsSearch.entity().getValue(), Op.GTEQ).cp();
            serviceOfferingSearch.cp().cp();
        }

        if (memory != null) {
            SearchBuilder<ServiceOfferingDetailsVO> maxMemoryDetailsSearch = (SearchBuilder<ServiceOfferingDetailsVO>) serviceOfferingSearch.getJoinSB("maxMemoryDetailsSearch");
            if (maxMemoryDetailsSearch == null) {
                maxMemoryDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();
                serviceOfferingSearch.join("maxMemoryDetailsSearch", maxMemoryDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                        serviceOfferingSearch.entity().getId(), maxMemoryDetailsSearch.entity().getResourceId(),
                        maxMemoryDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString("maxmemory"));
            }

            SearchBuilder<ServiceOfferingDetailsVO> minMemoryDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();

            serviceOfferingSearch.join("minMemoryDetailsSearch", minMemoryDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    serviceOfferingSearch.entity().getId(), minMemoryDetailsSearch.entity().getResourceId(),
                    minMemoryDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString("minmemory"));

            /*
                (min_ram_size IS NULL AND ram_size IS NULL AND max_ram_size IS NULL)
                OR (ram_size = X)
                OR (min_ram_size <= X AND max_ram_size >= X)
             */

            serviceOfferingSearch.and().op().op("minMemoryDetailsSearch", "memoryConstraintMinMemoryNull", minMemoryDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("memoryConstraintNull", serviceOfferingSearch.entity().getRamSize(), Op.NULL).cp();

            serviceOfferingSearch.or().op("minMemoryDetailsSearch", "memoryConstraintMinMemoryNull", minMemoryDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("memory", serviceOfferingSearch.entity().getRamSize(), Op.GTEQ).cp();
            serviceOfferingSearch.or("memory", serviceOfferingSearch.entity().getRamSize(), Op.GTEQ);

            serviceOfferingSearch.or().op().op();
            serviceOfferingSearch.op("minMemoryDetailsSearch", "memoryConstraintMinMemoryNull", minMemoryDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("memory", serviceOfferingSearch.entity().getRamSize(), Op.LTEQ).cp();
            serviceOfferingSearch.or("minMemoryDetailsSearch", "memory", minMemoryDetailsSearch.entity().getValue(), Op.LTEQ).cp();
            serviceOfferingSearch.and().op().op("maxMemoryDetailsSearch", "memoryConstraintMaxMemoryNull", maxMemoryDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("memory", serviceOfferingSearch.entity().getRamSize(), Op.GTEQ).cp();
            serviceOfferingSearch.or("maxMemoryDetailsSearch", "memory", maxMemoryDetailsSearch.entity().getValue(), Op.GTEQ).cp();
            serviceOfferingSearch.cp().cp();
        }

        if (cpuSpeed != null) {
            serviceOfferingSearch.and().op("speedNull", serviceOfferingSearch.entity().getSpeed(), Op.NULL);
            serviceOfferingSearch.or("speedGTEQ", serviceOfferingSearch.entity().getSpeed(), Op.GTEQ);
            serviceOfferingSearch.cp();
        }

        // Filter offerings that are not associated with caller's domain
        // Fetch the offering ids from the details table since theres no smart way to filter them in the join ... yet!
        if (owner.getType() != Account.Type.ADMIN) {
            SearchBuilder<ServiceOfferingDetailsVO> srvOffrDomainDetailSearch = _srvOfferingDetailsDao.createSearchBuilder();
            srvOffrDomainDetailSearch.and().op("domainIdIN", srvOffrDomainDetailSearch.entity().getValue(), Op.IN);
            srvOffrDomainDetailSearch.or("idNull", srvOffrDomainDetailSearch.entity().getValue(), Op.NULL);
            srvOffrDomainDetailSearch.cp();
            serviceOfferingSearch.join("domainDetailSearchNormalUser", srvOffrDomainDetailSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    serviceOfferingSearch.entity().getId(), srvOffrDomainDetailSearch.entity().getResourceId(),
                    srvOffrDomainDetailSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.DOMAIN_ID));
        }

        List<String> hostTags = new ArrayList<>();
        if (currentVmOffering != null) {
            hostTags.addAll(com.cloud.utils.StringUtils.csvTagsToList(currentVmOffering.getHostTag()));
            if (UserVmManager.AllowDifferentHostTagsOfferingsForVmScale.value()) {
                addVmCurrentClusterHostTags(vmInstance, hostTags);
            }
        }

        if (!hostTags.isEmpty()) {
            serviceOfferingSearch.and().op("hostTag", serviceOfferingSearch.entity().getHostTag(), Op.NULL);
            serviceOfferingSearch.or();
            boolean flag = true;
            for(String tag : hostTags) {
                if (flag) {
                    flag = false;
                    serviceOfferingSearch.op("hostTag" + tag, serviceOfferingSearch.entity().getHostTag(), Op.FIND_IN_SET);
                } else {
                    serviceOfferingSearch.or("hostTag" + tag, serviceOfferingSearch.entity().getHostTag(), Op.FIND_IN_SET);
                }
            }
            serviceOfferingSearch.cp().cp();
        }

        SearchCriteria<ServiceOfferingVO> sc = serviceOfferingSearch.create();
        if (state != null) {
            sc.setParameters("state", state);
        }

        if (vgpuProfileId != null) {
            sc.setParameters("vgpuProfileId", vgpuProfileId);
        }

        if (vmId != null) {
            if (!currentVmOffering.isDynamic()) {
                sc.setParameters("idNEQ", currentVmOffering.getId());
            }

            if (currentVmOffering.getDiskOfferingStrictness()) {
                sc.setParameters("diskOfferingId", currentVmOffering.getDiskOfferingId());
                sc.setParameters("diskOfferingStrictness", true);
            } else {
                sc.setParameters("diskOfferingStrictness", false);
            }

            boolean isRootVolumeUsingLocalStorage = virtualMachineManager.isRootVolumeOnLocalStorage(vmId);

            // 1. Only return offerings with the same storage type than the storage pool where the VM's root volume is allocated
            sc.setJoinParameters("diskOfferingSearch", "useLocalStorage", isRootVolumeUsingLocalStorage);

            // 2.In case vm is running return only offerings greater than equal to current offering compute and offering's dynamic scalability should match
            if (vmInstance.getState() == VirtualMachine.State.Running) {
                Integer vmCpu = currentVmOffering.getCpu();
                Integer vmMemory = currentVmOffering.getRamSize();
                Integer vmSpeed = currentVmOffering.getSpeed();
                if ((vmCpu == null || vmMemory == null || vmSpeed == null) && VirtualMachine.Type.User.equals(vmInstance.getType())) {
                    UserVmVO userVmVO = userVmDao.findById(vmId);
                    userVmDao.loadDetails(userVmVO);
                    Map<String, String> details = userVmVO.getDetails();
                    vmCpu = NumbersUtil.parseInt(details.get(ApiConstants.CPU_NUMBER), 0);
                    if (vmSpeed == null) {
                        vmSpeed = NumbersUtil.parseInt(details.get(ApiConstants.CPU_SPEED), 0);
                    }
                    vmMemory = NumbersUtil.parseInt(details.get(ApiConstants.MEMORY), 0);
                }
                if (vmCpu != null && vmCpu > 0) {
                    sc.setParameters("vmCpu", vmCpu);
                    sc.setParameters("vmMaxComputeGTEQ", vmCpu);
                }
                if (vmSpeed != null && vmSpeed > 0) {
                    sc.setParameters("speedGTEQ", vmSpeed);
                }
                if (vmMemory != null && vmMemory > 0) {
                    sc.setParameters("vmMemory", vmMemory);
                    sc.setParameters("vmMaxMemoryGTEQ", vmMemory);
                }
                sc.setParameters("dynamicScalingEnabled", currentVmOffering.isDynamicScalingEnabled());
            }
        }

        if ((!accountMgr.isNormalUser(caller.getId()) && !accountMgr.isDomainAdmin(caller.getId())) && caller.getType() != Account.Type.RESOURCE_DOMAIN_ADMIN) {
            if (domainId != null && accountName == null) {
                sc.setJoinParameters("domainDetailSearch", "domainId", domainId);
            }
        }

        if (keyword != null) {
            sc.setParameters("keywordName", "%" + keyword + "%");
            sc.setParameters("keywordDisplayText", "%" + keyword + "%");
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (isSystem != null) {
            // note that for non-root users, isSystem is always false when
            // control comes to here
            sc.setParameters("systemUse", isSystem);
        }

        if (encryptRoot != null) {
            sc.setJoinParameters("diskOfferingSearch", "encrypt", encryptRoot);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (vmTypeStr != null) {
            sc.setParameters("svmType", vmTypeStr);
        }

        useStorageType(sc, storageType);

        if (zoneId != null) {
            sc.setJoinParameters("ZoneDetailSearch", "zoneId", zoneId);

            if (DataCenter.Type.Edge.equals(zone.getType())) {
                sc.setJoinParameters("diskOfferingSearch", "useLocalStorage", true);
            }
        }

        if (cpuNumber != null) {
            sc.setParameters("cpuNumber", cpuNumber);
        }

        if (memory != null) {
            sc.setParameters("memory", memory);
        }

        if (cpuSpeed != null) {
            sc.setParameters("speedGTEQ", cpuSpeed);
        }

        if (owner.getType() != Account.Type.ADMIN) {
            Domain callerDomain = _domainDao.findById(owner.getDomainId());
            List<Long> domainIds = findRelatedDomainIds(callerDomain, isRecursive);

            sc.setJoinParameters("domainDetailSearchNormalUser", "domainIdIN", domainIds.toArray());
        }

        if (diskOffering != null) {
            List<String> storageTags = com.cloud.utils.StringUtils.csvTagsToList(diskOffering.getTags());
            if (!storageTags.isEmpty() && VolumeApiServiceImpl.MatchStoragePoolTagsWithDiskOffering.value()) {
                for (String tag : storageTags) {
                    sc.setJoinParameters("diskOfferingSearch", "storageTag" + tag, tag);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(hostTags)) {
            for (String tag : hostTags) {
                sc.setParameters("hostTag" + tag, tag);
            }
        }

        Pair<List<ServiceOfferingVO>, Integer> uniquePair = _srvOfferingDao.searchAndCount(sc, searchFilter);
        Integer count = uniquePair.second();
        List<Long> offeringIds = uniquePair.first().stream().map(ServiceOfferingVO::getId).collect(Collectors.toList());
        return new Pair<>(offeringIds, count);
    }

    protected void addVmCurrentClusterHostTags(VMInstanceVO vmInstance, List<String> hostTags) {
        if (vmInstance == null) {
            return;
        }
        Long hostId = vmInstance.getHostId() == null ? vmInstance.getLastHostId() : vmInstance.getHostId();
        if (hostId == null) {
            return;
        }
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            logger.warn("Unable to find host with id " + hostId);
            return;
        }
        List<String> clusterTags = _hostTagDao.listByClusterId(host.getClusterId());
        if (CollectionUtils.isEmpty(clusterTags)) {
            logger.debug("No host tags defined for hosts in the cluster " + host.getClusterId());
            return;
        }
        Set<String> existingTagsSet = new HashSet<>(hostTags);
        clusterTags.stream()
                .filter(tag -> !existingTagsSet.contains(tag))
                .forEach(hostTags::add);
    }

    @Override
    public ListResponse<ZoneResponse> listDataCenters(ListZonesCmd cmd) {
        return zoneQueryService.listDataCenters(cmd);
    }

    ListResponse<ZoneResponse> listDataCentersWithMinimalResponse(ListZonesCmd cmd) {
        return zoneQueryService.listDataCentersWithMinimalResponse(cmd);
    }

    // This method is used for permissions check for both disk and service
    // offerings
    private boolean isPermissible(Long accountDomainId, Long offeringDomainId) {

        if (accountDomainId.equals(offeringDomainId)) {
            return true; // account and service offering in same domain
        }

        DomainVO domainRecord = _domainDao.findById(accountDomainId);

        if (domainRecord != null) {
            while (true) {
                if (domainRecord.getId() == offeringDomainId) {
                    return true;
                }

                // try and move on to the next domain
                if (domainRecord.getParent() != null) {
                    domainRecord = _domainDao.findById(domainRecord.getParent());
                } else {
                    break;
                }
            }
        }

        return false;
    }

    @Override
    public ListResponse<TemplateResponse> listTemplates(ListTemplatesCmd cmd) {
        return templateQueryService.listTemplates(cmd);
    }

    protected void applyPublicTemplateSharingRestrictions(SearchCriteria<TemplateJoinVO> sc, Account caller) {
        templateQueryService.applyPublicTemplateSharingRestrictions(sc, caller);
    }

    protected void addDomainIdToSetIfDomainDoesNotShareTemplates(long domainId, Account account, Set<Long> unsharableDomainIds) {
        templateQueryService.addDomainIdToSetIfDomainDoesNotShareTemplates(domainId, account, unsharableDomainIds);
    }

    protected boolean checkIfDomainSharesTemplates(Long domainId) {
        return templateQueryService.checkIfDomainSharesTemplates(domainId);
    }

    @Override
    public ListResponse<TemplateResponse> listIsos(ListIsosCmd cmd) {
        return templateQueryService.listIsos(cmd);
    }

    @Override
    public DetailOptionsResponse listDetailOptions(ListDetailOptionsCmd cmd) {
        return templateQueryService.listDetailOptions(cmd);
    }

    @Override
    public ListResponse<ResourceIconResponse> listResourceIcons(ListResourceIconCmd cmd) {
        return templateQueryService.listResourceIcons(cmd);
    }

    @Override
    public ListResponse<AffinityGroupResponse> searchForAffinityGroups(ListAffinityGroupsCmd cmd) {
        Pair<List<AffinityGroupJoinVO>, Integer> result = searchForAffinityGroupsInternal(cmd);
        ListResponse<AffinityGroupResponse> response = new ListResponse<>();
        List<AffinityGroupResponse> agResponses = ViewResponseHelper.createAffinityGroupResponses(result.first());
        response.setResponses(agResponses, result.second());
        return response;
    }

    public Pair<List<AffinityGroupJoinVO>, Integer> searchForAffinityGroupsInternal(ListAffinityGroupsCmd cmd) {
        return affinityGroupQueryService.searchForAffinityGroupsInternal(cmd);
    }

    @Override
    public List<ResourceDetailResponse> listResourceDetails(ListResourceDetailsCmd cmd) {
        String key = cmd.getKey();
        Boolean forDisplay = cmd.getDisplay();
        ResourceTag.ResourceObjectType resourceType = cmd.getResourceType();
        String resourceIdStr = cmd.getResourceId();
        String value = cmd.getValue();
        Long resourceId = null;

        //Validation - 1.1 - resourceId and value can't be null.
        if (resourceIdStr == null && value == null) {
            throw new InvalidParameterValueException("Insufficient parameters passed for listing by resourceId OR key,value pair. Please check your params and try again.");
        }

        //Validation - 1.2 - Value has to be passed along with key.
        if (value != null && key == null) {
            throw new InvalidParameterValueException("Listing by (key, value) but key is null. Please check the params and try again");
        }

        //Validation - 1.3
        if (resourceIdStr != null) {
            resourceId = resourceManagerUtil.getResourceId(resourceIdStr, resourceType, true);
        }

        List<? extends ResourceDetail> detailList = new ArrayList<>();
        ResourceDetail requestedDetail = null;

        if (key == null) {
            detailList = _resourceMetaDataMgr.getDetailsList(resourceId, resourceType, forDisplay);
        } else if (value == null) {
            requestedDetail = _resourceMetaDataMgr.getDetail(resourceId, resourceType, key);
            if (requestedDetail != null && forDisplay != null && requestedDetail.isDisplay() != forDisplay) {
                requestedDetail = null;
            }
        } else {
            detailList = _resourceMetaDataMgr.getDetails(resourceType, key, value, forDisplay);
        }

        List<ResourceDetailResponse> responseList = new ArrayList<>();
        if (requestedDetail != null) {
            ResourceDetailResponse detailResponse = createResourceDetailsResponse(requestedDetail, resourceType);
            responseList.add(detailResponse);
        } else {
            for (ResourceDetail detail : detailList) {
                ResourceDetailResponse detailResponse = createResourceDetailsResponse(detail, resourceType);
                responseList.add(detailResponse);
            }
        }

        return responseList;
    }

    protected ResourceDetailResponse createResourceDetailsResponse(ResourceDetail requestedDetail, ResourceTag.ResourceObjectType resourceType) {
        ResourceDetailResponse resourceDetailResponse = new ResourceDetailResponse();
        resourceDetailResponse.setResourceId(resourceManagerUtil.getUuid(String.valueOf(requestedDetail.getResourceId()), resourceType));
        resourceDetailResponse.setName(requestedDetail.getName());
        resourceDetailResponse.setValue(requestedDetail.getValue());
        resourceDetailResponse.setForDisplay(requestedDetail.isDisplay());
        resourceDetailResponse.setResourceType(resourceType.toString());
        resourceDetailResponse.setObjectName("resourcedetail");
        return resourceDetailResponse;
    }

    @Override
    public ListResponse<ManagementServerResponse> listManagementServers(ListMgmtsCmd cmd) {
        ListResponse<ManagementServerResponse> response = new ListResponse<>();
        Pair<List<ManagementServerJoinVO>, Integer> result = managementServerQueryService.listManagementServersInternal(cmd);
        List<ManagementServerResponse> hostResponses = new ArrayList<>();

        for (ManagementServerJoinVO host : result.first()) {
            ManagementServerResponse hostResponse = managementServerQueryService.createManagementServerResponse(host, cmd.getPeers());
            hostResponses.add(hostResponse);
        }

        response.setResponses(hostResponses);
        return response;
    }

    @Override
    public List<RouterHealthCheckResultResponse> listRouterHealthChecks(GetRouterHealthCheckResultsCmd cmd) {
        logger.info("Executing health check command " + cmd);
        return routerQueryService.listRouterHealthChecks(cmd);
    }

    @Override
    public ListResponse<SecondaryStorageHeuristicsResponse> listSecondaryStorageSelectors(ListSecondaryStorageSelectorsCmd cmd) {
        ListResponse<SecondaryStorageHeuristicsResponse> response = new ListResponse<>();
        Pair<List<HeuristicVO>, Integer> result = listSecondaryStorageSelectorsInternal(cmd.getZoneId(), cmd.getType(), cmd.isShowRemoved());
        List<SecondaryStorageHeuristicsResponse> listOfSecondaryStorageHeuristicsResponses = new ArrayList<>();

        for (Heuristic heuristic : result.first()) {
            SecondaryStorageHeuristicsResponse secondaryStorageHeuristicsResponse = responseGenerator.createSecondaryStorageSelectorResponse(heuristic);
            listOfSecondaryStorageHeuristicsResponses.add(secondaryStorageHeuristicsResponse);
        }

        response.setResponses(listOfSecondaryStorageHeuristicsResponses);
        return response;
    }

    private Pair<List<HeuristicVO>, Integer> listSecondaryStorageSelectorsInternal(Long zoneId, String type, boolean showRemoved) {
        SearchBuilder<HeuristicVO> searchBuilder = secondaryStorageHeuristicDao.createSearchBuilder();

        searchBuilder.and("zoneId", searchBuilder.entity().getZoneId(), SearchCriteria.Op.EQ);
        searchBuilder.and("type", searchBuilder.entity().getType(), SearchCriteria.Op.EQ);

        searchBuilder.done();

        SearchCriteria<HeuristicVO> searchCriteria = searchBuilder.create();
        searchCriteria.setParameters("zoneId", zoneId);
        searchCriteria.setParametersIfNotNull("type", type);

        return secondaryStorageHeuristicDao.searchAndCount(searchCriteria, null, showRemoved);
    }

    @Override
    public ListResponse<IpQuarantineResponse> listQuarantinedIps(ListQuarantinedIpsCmd cmd) {
        ListResponse<IpQuarantineResponse> response = new ListResponse<>();
        Pair<List<PublicIpQuarantineVO>, Integer> result = listQuarantinedIpsInternal(cmd.isShowRemoved(), cmd.isShowInactive());
        List<IpQuarantineResponse> ipsQuarantinedResponses = new ArrayList<>();

        for (PublicIpQuarantine quarantinedIp : result.first()) {
            IpQuarantineResponse ipsInQuarantineResponse = responseGenerator.createQuarantinedIpsResponse(quarantinedIp);
            ipsQuarantinedResponses.add(ipsInQuarantineResponse);
        }

        response.setResponses(ipsQuarantinedResponses);
        return response;
    }

    /**
     * It lists the quarantine IPs that the caller account is allowed to see by filtering the domain path of the caller account.
     * Furthermore, it lists inactive and removed quarantined IPs according to the command parameters.
     */
    private Pair<List<PublicIpQuarantineVO>, Integer> listQuarantinedIpsInternal(boolean showRemoved, boolean showInactive) {
        String callingAccountDomainPath = _domainDao.findById(CallContext.current().getCallingAccount().getDomainId()).getPath();

        SearchBuilder<AccountJoinVO> filterAllowedOnly = _accountJoinDao.createSearchBuilder();
        filterAllowedOnly.and("path", filterAllowedOnly.entity().getDomainPath(), SearchCriteria.Op.LIKE);

        SearchBuilder<PublicIpQuarantineVO> listAllPublicIpsInQuarantineAllowedToTheCaller = publicIpQuarantineDao.createSearchBuilder();
        listAllPublicIpsInQuarantineAllowedToTheCaller.join("listQuarantinedJoin", filterAllowedOnly,
                listAllPublicIpsInQuarantineAllowedToTheCaller.entity().getPreviousOwnerId(),
                filterAllowedOnly.entity().getId(), JoinBuilder.JoinType.INNER);

        if (!showInactive) {
            listAllPublicIpsInQuarantineAllowedToTheCaller.and("endDate", listAllPublicIpsInQuarantineAllowedToTheCaller.entity().getEndDate(), SearchCriteria.Op.GT);
        }

        filterAllowedOnly.done();
        listAllPublicIpsInQuarantineAllowedToTheCaller.done();

        SearchCriteria<PublicIpQuarantineVO> searchCriteria = listAllPublicIpsInQuarantineAllowedToTheCaller.create();
        searchCriteria.setJoinParameters("listQuarantinedJoin", "path", callingAccountDomainPath + "%");
        searchCriteria.setParametersIfNotNull("endDate", new Date());

        return publicIpQuarantineDao.searchAndCount(searchCriteria, null, showRemoved);
    }

    public ListResponse<SnapshotResponse> listSnapshots(ListSnapshotsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Pair<List<SnapshotJoinVO>, Integer> result = searchForSnapshotsWithParams(cmd.getId(), cmd.getIds(),
                cmd.getVolumeId(), cmd.getSnapshotName(), cmd.getKeyword(), cmd.getTags(),
                cmd.getSnapshotType(), cmd.getIntervalType(), cmd.getZoneId(), cmd.getLocationType(),
                cmd.isShowUnique(), cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId(), cmd.getStoragePoolId(),
                cmd.getImageStoreId(), cmd.getStartIndex(), cmd.getPageSizeVal(), cmd.listAll(), cmd.isRecursive(), caller);
        ListResponse<SnapshotResponse> response = new ListResponse<>();
        ResponseView respView = ResponseView.Restricted;
        if (cmd instanceof ListSnapshotsCmdByAdmin) {
            respView = ResponseView.Full;
        }
        List<SnapshotResponse> templateResponses = ViewResponseHelper.createSnapshotResponse(respView, cmd.isShowUnique(), result.first().toArray(new SnapshotJoinVO[0]));
        response.setResponses(templateResponses, result.second());
        return response;
    }

    @Override
    public SnapshotResponse listSnapshot(CopySnapshotCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        List<Long> zoneIds = cmd.getDestinationZoneIds();
        Long zoneId = null;
        String location = null;
        if (CollectionUtils.isNotEmpty(zoneIds)) {
            zoneId = zoneIds.get(0);
            location = Snapshot.LocationType.SECONDARY.name();
        } else {
            location = cmd.getSnapshot().getLocationType() != null ? cmd.getSnapshot().getLocationType().name() : null;
        }
        Pair<List<SnapshotJoinVO>, Integer> result = searchForSnapshotsWithParams(cmd.getId(), null,
                null, null, null, null,
                null, null, zoneId, location,
                false, null, null, null, null, null,
                null, null, true, false, caller);
        ResponseView respView = ResponseView.Restricted;
        if (CallContext.current().getCallingAccount().getType() == Account.Type.ADMIN) {
            respView = ResponseView.Full;
        }
        List<SnapshotResponse> templateResponses = ViewResponseHelper.createSnapshotResponse(respView, false, result.first().get(0));
        return templateResponses.get(0);
    }



    private Pair<List<SnapshotJoinVO>, Integer> searchForSnapshotsWithParams(final Long id, List<Long> ids,
            final Long volumeId, final String name, final String keyword, final Map<String, String> tags,
            final String snapshotTypeStr, final String intervalTypeStr, final Long zoneId, final String locationTypeStr,
            final boolean isShowUnique, final String accountName, Long domainId, final Long projectId, final Long storagePoolId, final Long imageStoreId,
            final Long startIndex, final Long pageSize, final boolean listAll, final boolean isRecursive, final Account caller) {
        return snapshotQueryService.searchForSnapshotsWithParams(id, ids, volumeId, name, keyword, tags,
                snapshotTypeStr, intervalTypeStr, zoneId, locationTypeStr, isShowUnique,
                accountName, domainId, projectId, storagePoolId, imageStoreId,
                startIndex, pageSize, listAll, isRecursive, caller);
    }

    public ListResponse<ObjectStoreResponse> searchForObjectStores(ListObjectStoragePoolsCmd cmd) {
        Pair<List<ObjectStoreVO>, Integer> result = searchForObjectStoresInternal(cmd);
        ListResponse<ObjectStoreResponse> response = new ListResponse<>();

        List<ObjectStoreResponse> poolResponses = ViewResponseHelper.createObjectStoreResponse(result.first().toArray(new ObjectStoreVO[0]));
        response.setResponses(poolResponses, result.second());
        return response;
    }

    private Pair<List<ObjectStoreVO>, Integer> searchForObjectStoresInternal(ListObjectStoragePoolsCmd cmd) {

        Object id = cmd.getId();
        Object name = cmd.getStoreName();
        String provider = cmd.getProvider();
        Object keyword = cmd.getKeyword();
        Long startIndex = cmd.getStartIndex();
        Long pageSize = cmd.getPageSizeVal();

        Filter searchFilter = new Filter(ObjectStoreVO.class, "id", Boolean.TRUE, startIndex, pageSize);

        SearchBuilder<ObjectStoreVO> sb = objectStoreDao.createSearchBuilder();
        sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct
        // ids
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("provider", sb.entity().getProviderName(), SearchCriteria.Op.EQ);

        SearchCriteria<ObjectStoreVO> sc = sb.create();

        if (keyword != null) {
            SearchCriteria<ObjectStoreVO> ssc = objectStoreDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("providerName", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (provider != null) {
            sc.setParameters("provider", provider);
        }

        // search Store details by ids
        Pair<List<ObjectStoreVO>, Integer> uniqueStorePair = objectStoreDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueStorePair.second();
        if (count == 0) {
            // empty result
            return uniqueStorePair;
        }
        List<ObjectStoreVO> uniqueStores = uniqueStorePair.first();
        Long[] osIds = new Long[uniqueStores.size()];
        int i = 0;
        for (ObjectStoreVO v : uniqueStores) {
            osIds[i++] = v.getId();
        }
        List<ObjectStoreVO> objectStores = objectStoreDao.searchByIds(osIds);
        return new Pair<>(objectStores, count);
    }


    @Override
    public ListResponse<BucketResponse> searchForBuckets(ListBucketsCmd listBucketsCmd) {
        List<BucketVO> buckets = searchForBucketsInternal(listBucketsCmd);
        List<BucketResponse> bucketResponses = new ArrayList<>();
        for (BucketVO bucket : buckets) {
            bucketResponses.add(responseGenerator.createBucketResponse(bucket));
        }
        ListResponse<BucketResponse> response = new ListResponse<>();
        response.setResponses(bucketResponses, bucketResponses.size());
        return response;
    }

    private List<BucketVO> searchForBucketsInternal(ListBucketsCmd cmd) {

        Long id = cmd.getId();
        String name = cmd.getBucketName();
        String keyword = cmd.getKeyword();
        Long startIndex = cmd.getStartIndex();
        Long pageSize = cmd.getPageSizeVal();
        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();

        // Verify parameters
        if (id != null) {
            BucketVO bucket = bucketDao.findById(id);
            if (bucket != null) {
                accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, true, bucket);
            }
        }

        List<Long> ids = getIdsListFromCmd(cmd.getId(), cmd.getIds());

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(),
                cmd.isRecursive(), null);
        accountMgr.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        Filter searchFilter = new Filter(BucketVO.class, "id", Boolean.TRUE, startIndex, pageSize);

        SearchBuilder<BucketVO> sb = bucketDao.createSearchBuilder();
        accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        sb.select(null, Func.DISTINCT, sb.entity().getId()); // select distinct
        // ids
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);

        SearchCriteria<BucketVO> sc = sb.create();
        accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (keyword != null) {
            SearchCriteria<BucketVO> ssc = bucketDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("state", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        setIdsListToSearchCriteria(sc, ids);

        // search Volume details by ids
        Pair<List<BucketVO>, Integer> uniqueBktPair = bucketDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueBktPair.second();
        if (count == 0) {
            // empty result
            return uniqueBktPair.first();
        }
        List<BucketVO> uniqueBkts = uniqueBktPair.first();
        Long[] bktIds = new Long[uniqueBkts.size()];
        int i = 0;
        for (BucketVO b : uniqueBkts) {
            bktIds[i++] = b.getId();
        }

        return bucketDao.searchByIds(bktIds);
    }

    @Override
    public String getConfigComponentName() {
        return QueryService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {AllowUserViewDestroyedVM, UserVMDeniedDetails, UserVMReadOnlyDetails, SortKeyAscending,
                AllowUserViewAllDomainAccounts, AllowUserViewAllDataCenters, SharePublicTemplatesWithOtherDomains, ReturnVmStatsOnVmList};
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public AccountManager getAccountManager() {
        return accountMgr;
    }
}
