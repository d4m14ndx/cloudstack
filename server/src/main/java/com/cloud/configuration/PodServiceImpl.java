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
package com.cloud.configuration;

import static com.cloud.configuration.ConfigurationManager.MESSAGE_CREATE_POD_IP_RANGE_EVENT;
import static com.cloud.configuration.ConfigurationManager.MESSAGE_DELETE_POD_IP_RANGE_EVENT;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.admin.network.CreateManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.UpdatePodManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.pod.DeletePodCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.PublishScope;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.capacity.dao.CapacityDao;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterIpAddressVO;
import com.cloud.dc.DataCenterLinkLocalIpAddressVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Pod;
import com.cloud.dc.Vlan;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterIpAddressDao;
import com.cloud.dc.dao.DataCenterLinkLocalIpAddressDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.dao.HostDao;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.org.Grouping;
import com.cloud.org.Grouping.AllocationState;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Pod CRUD and management-network IP-range operations — extracted from
 * {@link ConfigurationManagerImpl}.
 *
 * @see PodService
 */
@Component
public class PodServiceImpl implements PodService {

    protected static final Logger logger = LogManager.getLogger(PodServiceImpl.class);

    /** Sender name used for {@link MessageBus} publications. */
    private static final String SENDER = PodServiceImpl.class.getSimpleName();

    private static final String DefaultForSystemVmsForPodIpRange = "0";
    private static final String DefaultVlanForPodIpRange = Vlan.UNTAGGED;

    @Inject
    private HostPodDao _podDao;
    @Inject
    private DataCenterDao _zoneDao;
    @Inject
    private DataCenterIpAddressDao _privateIpAddressDao;
    @Inject
    private DataCenterLinkLocalIpAddressDao _linkLocalIpAllocDao;
    @Inject
    private VlanDao _vlanDao;
    @Inject
    private CapacityDao _capacityDao;
    @Inject
    private DedicatedResourceDao _dedicatedDao;
    @Inject
    private NetworkModel _networkModel;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private ConfigurationDao _configDao;
    @Inject
    private MessageBus messageBus;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    private IPAddressDao _publicIpAddressDao;
    @Inject
    private VolumeDao _volumeDao;
    @Inject
    private HostDao _hostDao;
    @Inject
    private VMInstanceDao _vmInstanceDao;
    @Inject
    private ClusterDao _clusterDao;

    // ------------------------------------------------------------------
    // PodService contract
    // ------------------------------------------------------------------

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_POD_CREATE, eventDescription = "creating pod", async = false)
    public Pod createPod(final long zoneId, final String name, final String startIp, final String endIp,
                         final String gateway, final String netmask, String allocationState,
                         List<String> storageAccessGroups) {
        final DataCenterVO zone = _zoneDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Please specify a valid zone.");
        }
        final Account account = CallContext.current().getCallingAccount();
        if (Grouping.AllocationState.Disabled == zone.getAllocationState()
                && !_accountMgr.isRootAdmin(account.getId())) {
            throw new PermissionDeniedException(String.format("Cannot perform this operation, Zone is currently disabled: %s", zone));
        }

        String cidr = null;
        if (!DataCenter.Type.Edge.equals(zone.getType())) {
            checkPodRangeParametersBasicsForNonEdgeZone(startIp, endIp, gateway, netmask);
            cidr = NetUtils.ipAndNetMaskToCidr(gateway, netmask);
        } else {
            if (ObjectUtils.anyNotNull(startIp, endIp, gateway, netmask)) {
                throw new InvalidParameterValueException("IP range parameters can not be specified for a pod in an edge zone");
            }
        }

        final Long userId = CallContext.current().getCallingUserId();

        if (allocationState == null) {
            allocationState = Grouping.AllocationState.Enabled.toString();
        }
        return createPod(userId.longValue(), name, zone, gateway, cidr, startIp, endIp, allocationState, false, storageAccessGroups);
    }

    @Override
    @DB
    public HostPodVO createPod(final long userId, final String podName, final DataCenter zone, final String gateway,
                               final String cidr, String startIp, String endIp, final String allocationStateStr,
                               final boolean skipGatewayOverlapCheck, List<String> storageAccessGroups) {
        final String cidrAddress = DataCenter.Type.Edge.equals(zone.getType()) ? "" : getCidrAddress(cidr);
        final int cidrSize = DataCenter.Type.Edge.equals(zone.getType()) ? 0 : getCidrSize(cidr);
        if (DataCenter.Type.Edge.equals(zone.getType())) {
            startIp = null;
            endIp = null;
        }

        // endIp is an optional parameter; if not specified - default it to the
        // end ip of the pod's cidr
        if (StringUtils.isNotEmpty(startIp)) {
            if (endIp == null) {
                endIp = NetUtils.getIpRangeEndIpFromCidr(cidrAddress, cidrSize);
            }
        }

        // Validate new pod settings
        checkPodAttributes(-1, podName, zone, gateway, cidr, startIp, endIp, allocationStateStr, true, skipGatewayOverlapCheck);

        // Create the new pod in the database
        String ipRange = null;
        if (StringUtils.isNotEmpty(startIp)) {
            ipRange = startIp + "-" + endIp + "-" + DefaultForSystemVmsForPodIpRange + "-" + DefaultVlanForPodIpRange;
        }

        final HostPodVO podFinal = new HostPodVO(podName, zone.getId(), StringUtils.defaultIfEmpty(gateway, ""), cidrAddress, cidrSize, ipRange);

        AllocationState allocationState = null;
        if (allocationStateStr != null && !allocationStateStr.isEmpty()) {
            allocationState = AllocationState.valueOf(allocationStateStr);
            podFinal.setAllocationState(allocationState);
        }

        if (CollectionUtils.isNotEmpty(storageAccessGroups)) {
            podFinal.setStorageAccessGroups(String.join(",", storageAccessGroups));
        }

        final String startIpFinal = startIp;
        final String endIpFinal = endIp;
        HostPodVO hostPodVO = Transaction.execute((TransactionCallback<HostPodVO>) status -> {
            final HostPodVO pod = _podDao.persist(podFinal);

            if (StringUtils.isNotEmpty(startIpFinal)) {
                _zoneDao.addPrivateIpAddress(zone.getId(), pod.getId(), startIpFinal, endIpFinal, false, null);
            }

            final String[] linkLocalIpRanges = NetUtils.getLinkLocalIPRange(_configDao.getValue(Config.ControlCidr.key()));
            if (linkLocalIpRanges.length > 1) {
                _zoneDao.addLinkLocalIpAddress(zone.getId(), pod.getId(), linkLocalIpRanges[0], linkLocalIpRanges[1]);
            }

            CallContext.current().putContextParameter(Pod.class, pod.getUuid());

            return pod;
        });

        messageBus.publish(SENDER, MESSAGE_CREATE_POD_IP_RANGE_EVENT, PublishScope.LOCAL, hostPodVO);

        return hostPodVO;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_POD_DELETE, eventDescription = "deleting pod", async = false)
    public boolean deletePod(final DeletePodCmd cmd) {
        final Long podId = cmd.getId();

        // Make sure the pod exists
        if (!validPod(podId)) {
            throw new InvalidParameterValueException("A pod with ID: " + podId + " does not exist.");
        }

        checkIfPodIsDeletable(podId);

        final HostPodVO pod = _podDao.findById(podId);

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                // Delete private ip addresses for the pod if there are any
                final List<DataCenterIpAddressVO> privateIps = _privateIpAddressDao.listByPodIdDcId(podId, pod.getDataCenterId());
                if (!privateIps.isEmpty()) {
                    if (!_privateIpAddressDao.deleteIpAddressByPod(podId)) {
                        throw new CloudRuntimeException(String.format("Failed to cleanup private IP addresses for pod %s", pod));
                    }
                }

                // Delete link local ip addresses for the pod
                final List<DataCenterLinkLocalIpAddressVO> localIps = _linkLocalIpAllocDao.listByPodIdDcId(podId, pod.getDataCenterId());
                if (!localIps.isEmpty()) {
                    if (!_linkLocalIpAllocDao.deleteIpAddressByPod(podId)) {
                        throw new CloudRuntimeException(String.format("Failed to cleanup private IP addresses for pod %s", pod));
                    }
                }

                // Delete vlans associated with the pod
                final List<? extends Vlan> vlans = _networkModel.listPodVlans(podId);
                if (vlans != null && !vlans.isEmpty()) {
                    for (final Vlan vlan : vlans) {
                        _vlanDao.remove(vlan.getId());
                    }
                }

                // Delete corresponding capacity records
                _capacityDao.removeBy(null, null, podId, null, null);

                // Delete the pod
                if (!_podDao.remove(podId)) {
                    throw new CloudRuntimeException(String.format("Failed to delete pod %s", pod));
                }

                // remove from dedicated resources
                final DedicatedResourceVO dr = _dedicatedDao.findByPodId(podId);
                if (dr != null) {
                    _dedicatedDao.remove(dr.getId());
                }

                // Remove comments (if any)
                annotationDao.removeByEntityType(AnnotationService.EntityType.POD.name(), pod.getUuid());
            }
        });

        messageBus.publish(SENDER, MESSAGE_DELETE_POD_IP_RANGE_EVENT, PublishScope.LOCAL, pod);

        return true;
    }

    @Override
    @DB
    public Pod createPodIpRange(final CreateManagementNetworkIpRangeCmd cmd) {

        final Account account = CallContext.current().getCallingAccount();

        if (!_accountMgr.isRootAdmin(account.getId())) {
            throw new PermissionDeniedException(String.format("Cannot perform this operation, Calling Account is not root admin: %s", account));
        }

        final long podId = cmd.getPodId();
        final String gateway = cmd.getGateWay();
        final String netmask = cmd.getNetmask();
        final String startIp = cmd.getStartIp();
        String endIp = cmd.getEndIp();
        final boolean forSystemVms = cmd.isForSystemVms();
        String vlan = cmd.getVlan();
        if (StringUtils.isNotEmpty(vlan) && !vlan.startsWith(BroadcastDomainType.Vlan.scheme())) {
            vlan = BroadcastDomainType.Vlan.toUri(vlan).toString();
        }

        String vlanNumberFromUri = getVlanNumberFromUri(vlan);
        final Integer vlanId = vlanNumberFromUri.equals(Vlan.UNTAGGED) ? null : Integer.parseInt(vlanNumberFromUri);

        final HostPodVO pod = _podDao.findById(podId);

        if (pod == null) {
            throw new InvalidParameterValueException("Unable to find pod by ID: " + podId);
        }

        final long zoneId = pod.getDataCenterId();

        if (!NetUtils.isValidIp4(gateway) && !NetUtils.isValidIp6(gateway)) {
            throw new InvalidParameterValueException("The gateway IP address is invalid.");
        }

        if (!NetUtils.isValidIp4Netmask(netmask)) {
            throw new InvalidParameterValueException("The netmask IP address is invalid.");
        }

        if (endIp == null) {
            endIp = startIp;
        }

        final String cidr = NetUtils.ipAndNetMaskToCidr(gateway, netmask);

        if (!NetUtils.isValidIp4Cidr(cidr)) {
            throw new InvalidParameterValueException("The CIDR is invalid " + cidr);
        }

        final String cidrAddress = pod.getCidrAddress();
        final long cidrSize = pod.getCidrSize();

        // Because each pod has only one Gateway and Netmask.
        if (!gateway.equals(pod.getGateway())) {
            throw new InvalidParameterValueException(String.format("Multiple gateways for the POD: %s are not allowed. The Gateway should be same as the existing Gateway %s", pod, pod.getGateway()));
        }

        if (!netmask.equals(NetUtils.getCidrNetmask(cidrSize))) {
            throw new InvalidParameterValueException(String.format("Multiple subnets for the POD: %s are not allowed. The Netmask should be same as the existing Netmask %s", pod, NetUtils.getCidrNetmask(cidrSize)));
        }

        // Check if the IP range is valid.
        checkIpRange(startIp, endIp, cidrAddress, cidrSize);

        // Check if the IP range overlaps with the public ip.
        checkOverlapPublicIpRange(zoneId, startIp, endIp);

        // Check if the gateway is in the CIDR subnet
        if (!NetUtils.getCidrSubNet(gateway, cidrSize).equalsIgnoreCase(NetUtils.getCidrSubNet(cidrAddress, cidrSize))) {
            throw new InvalidParameterValueException("The gateway is not in the CIDR subnet.");
        }

        if (NetUtils.ipRangesOverlap(startIp, endIp, gateway, gateway)) {
            throw new InvalidParameterValueException("The gateway shouldn't overlap start/end IP addresses");
        }

        final String[] existingPodIpRanges = pod.getDescription().split(",");

        for (String podIpRange : existingPodIpRanges) {
            final String[] existingPodIpRange = podIpRange.split("-");

            if (existingPodIpRange.length > 1) {
                if (!NetUtils.isValidIp4(existingPodIpRange[0]) || !NetUtils.isValidIp4(existingPodIpRange[1])) {
                    continue;
                }
                // Check if the range overlaps with any existing range.
                if (NetUtils.ipRangesOverlap(startIp, endIp, existingPodIpRange[0], existingPodIpRange[1])) {
                    throw new InvalidParameterValueException("The new range overlaps with existing range. Please add a mutually exclusive range.");
                }
            }
        }

        try {
            final String endIpFinal = endIp;

            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) {
                    String ipRange = pod.getDescription();

                    /*
                     * POD Description is refactored to:
                     * <START_IP>-<END_IP>-<FOR_SYSTEM_VMS>-<VLAN>,<START_IP>-<END_IP>-<FOR_SYSTEM_VMS>-<VLAN>,...
                    */
                    String range = startIp + "-" + endIpFinal + "-" + (forSystemVms ? "1" : "0") + "-" + (vlanId == null ? DefaultVlanForPodIpRange : vlanId);
                    if (ipRange != null && !ipRange.isEmpty())
                        ipRange += ("," + range);
                    else
                        ipRange = (range);

                    pod.setDescription(ipRange);

                    HostPodVO lock = null;
                    try {
                        lock = _podDao.acquireInLockTable(podId);

                        if (lock == null) {
                            String msg = String.format("Unable to acquire lock on table to update the IP range of POD: %s, Creation failed.", pod);
                            logger.warn(msg);
                            throw new CloudRuntimeException(msg);
                        }

                        _podDao.update(podId, pod);
                    } finally {
                        if (lock != null) {
                            _podDao.releaseFromLockTable(podId);
                        }
                    }

                    _zoneDao.addPrivateIpAddress(zoneId, pod.getId(), startIp, endIpFinal, forSystemVms, vlanId);
                }
            });
        } catch (final Exception e) {
            logger.error("Unable to create Pod IP range due to {}", e.getMessage(), e);
            throw new CloudRuntimeException("Failed to create Pod IP range. Please contact Cloud Support.");
        }

        messageBus.publish(SENDER, MESSAGE_CREATE_POD_IP_RANGE_EVENT, PublishScope.LOCAL, pod);

        return pod;
    }

    @Override
    @DB
    public void deletePodIpRange(final DeleteManagementNetworkIpRangeCmd cmd) throws ResourceUnavailableException, ConcurrentOperationException {
        final long podId = cmd.getPodId();
        final String startIp = cmd.getStartIp();
        final String endIp = cmd.getEndIp();
        String vlan = cmd.getVlan();
        try {
            vlan = BroadcastDomainType.getValue(vlan);
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("Incorrect vlan " + vlan);
        }

        final HostPodVO pod = _podDao.findById(podId);

        if (pod == null) {
            throw new InvalidParameterValueException("Unable to find pod by id " + podId);
        }

        Ipv4RangeValidator.requireValidIp(startIp, "The start address of the IP range is not a valid IP address.");
        Ipv4RangeValidator.requireValidIp(endIp, "The end address of the IP range is not a valid IP address.");
        Ipv4RangeValidator.requireStartBeforeEnd(startIp, endIp, "The start IP address must have a lower value than the end IP address.");

        for (long ipAddr = NetUtils.ip2Long(startIp); ipAddr <= NetUtils.ip2Long(endIp); ipAddr++) {
            if (_privateIpAddressDao.countIpAddressUsage(NetUtils.long2Ip(ipAddr), podId, pod.getDataCenterId(), true) > 0) {
                throw new CloudRuntimeException("Some IPs of the range has been allocated, so it cannot be deleted.");
            }
        }

        final String[] existingPodIpRanges = pod.getDescription().split(",");

        if (existingPodIpRanges.length == 0) {
            throw new InvalidParameterValueException("The IP range cannot be found. As the existing IP range is empty.");
        }

        final String[] newPodIpRanges = new String[existingPodIpRanges.length - 1];
        int index = existingPodIpRanges.length - 2;
        boolean foundRange = false;

        for (String podIpRange : existingPodIpRanges) {
            final String[] existingPodIpRange = podIpRange.split("-");

            if (existingPodIpRange.length > 1) {
                if (startIp.equals(existingPodIpRange[0]) && endIp.equals(existingPodIpRange[1]) &&
                        (existingPodIpRange.length > 3 ? vlan.equals(existingPodIpRange[3]) : vlan.equals(DefaultVlanForPodIpRange))) {
                    foundRange = true;
                } else if (index >= 0) {
                    newPodIpRanges[index--] = (existingPodIpRange[0] + "-" + existingPodIpRange[1] + "-" +
                            (existingPodIpRange.length > 2 ? existingPodIpRange[2] : DefaultForSystemVmsForPodIpRange) + "-" +
                            (existingPodIpRange.length > 3 ? existingPodIpRange[3] : DefaultVlanForPodIpRange));
                }
            }
        }

        if (!foundRange) {
            throw new InvalidParameterValueException(String.format("The input IP range: %s-%s of pod: %sis not present. Please input an existing range.", startIp, endIp, pod));
        }

        final StringBuilder newPodIpRange = new StringBuilder();
        boolean first = true;
        for (String podIpRange : newPodIpRanges) {
            if (first)
                first = false;
            else
                newPodIpRange.append(",");

            newPodIpRange.append(podIpRange);
        }

        try {
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) {
                    pod.setDescription(newPodIpRange.toString());

                    HostPodVO lock = null;
                    try {
                        lock = _podDao.acquireInLockTable(podId);

                        if (lock == null) {
                            String msg = String.format("Unable to acquire lock on table to update the IP range of POD: %s, Deletion failed.", pod);
                            logger.warn(msg);
                            throw new CloudRuntimeException(msg);
                        }

                        _podDao.update(podId, pod);
                    } finally {
                        if (lock != null) {
                            _podDao.releaseFromLockTable(podId);
                        }
                    }

                    for (long ipAddr = NetUtils.ip2Long(startIp); ipAddr <= NetUtils.ip2Long(endIp); ipAddr++) {
                        if (!_privateIpAddressDao.deleteIpAddressByPodDc(NetUtils.long2Ip(ipAddr), podId, pod.getDataCenterId())) {
                            throw new CloudRuntimeException(String.format("Failed to cleanup private IP address: %s of Pod: %s DC: %s", NetUtils.long2Ip(ipAddr), pod, _zoneDao.findById(pod.getDataCenterId())));
                        }
                    }
                }
            });
        } catch (final Exception e) {
            logger.error("Unable to delete Pod {} IP range due to {}", pod, e.getMessage(), e);
            throw new CloudRuntimeException(String.format("Failed to delete Pod %s IP range. Please contact Cloud Support.", pod));
        }

        messageBus.publish(SENDER, MESSAGE_DELETE_POD_IP_RANGE_EVENT, PublishScope.LOCAL, pod);
    }

    @Override
    @DB
    public void updatePodIpRange(final UpdatePodManagementNetworkIpRangeCmd cmd) throws ConcurrentOperationException {
        final long podId = cmd.getPodId();
        final HostPodVO pod = _podDao.findById(podId);
        if (pod == null) {
            throw new InvalidParameterValueException("Unable to find pod by id: " + podId);
        }

        final String currentStartIP = cmd.getCurrentStartIP();
        final String currentEndIP = cmd.getCurrentEndIP();
        String newStartIP = cmd.getNewStartIP();
        String newEndIP = cmd.getNewEndIP();

        if (newStartIP == null) {
            newStartIP = currentStartIP;
        }

        if (newEndIP == null) {
            newEndIP = currentEndIP;
        }

        if (newStartIP.equals(currentStartIP) && newEndIP.equals(currentEndIP)) {
            throw new InvalidParameterValueException("New starting and ending IP address are the same as current starting and ending IP address");
        }

        final String[] existingPodIpRanges = pod.getDescription().split(",");
        if (existingPodIpRanges.length == 0) {
            throw new InvalidParameterValueException(String.format("The IP range cannot be found in the pod: %s since the existing IP range is empty.", pod));
        }

        verifyIpRangeParameters(currentStartIP, currentEndIP);
        verifyIpRangeParameters(newStartIP, newEndIP);
        checkIpRangeContainsTakenAddresses(pod, currentStartIP, currentEndIP, newStartIP, newEndIP);

        String vlan = verifyPodIpRangeExists(podId, existingPodIpRanges, currentStartIP, currentEndIP, newStartIP, newEndIP);

        List<Long> currentIpRange = listAllIPsWithintheRange(currentStartIP, currentEndIP);
        List<Long> newIpRange = listAllIPsWithintheRange(newStartIP, newEndIP);

        try {
            final String finalNewEndIP = newEndIP;
            final String finalNewStartIP = newStartIP;
            final Integer vlanId = vlan.equals(Vlan.UNTAGGED) ? null : Integer.parseInt(vlan);

            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) {
                    final long zoneId = pod.getDataCenterId();
                    pod.setDescription(pod.getDescription().replace(currentStartIP + "-",
                            finalNewStartIP + "-").replace(currentEndIP, finalNewEndIP));
                    updatePodIpRangeInDb(zoneId, podId, vlanId, pod, newIpRange, currentIpRange);
                }
            });
        } catch (final Exception e) {
            logger.error("Unable to update Pod {} IP range due to {}", pod, e.getMessage(), e);
            throw new CloudRuntimeException(String.format("Failed to update Pod %s IP range. Please contact Cloud Support.", pod));
        }
    }

    // ------------------------------------------------------------------
    // Helpers extracted along with the public flows above
    // ------------------------------------------------------------------

    protected String getVlanNumberFromUri(String vlan) {
        return BroadcastDomainType.parseVlanNumberFromUri(vlan);
    }

    protected void checkPodAttributes(final long podId, final String podName, final DataCenter zone, final String gateway, final String cidr, final String startIp, final String endIp, final String allocationStateStr,
                                      final boolean checkForDuplicates, final boolean skipGatewayOverlapCheck) {
        if (checkForDuplicates) {
            // Check if the pod already exists
            if (validPod(podName, zone.getId())) {
                throw new InvalidParameterValueException(String.format("A pod with name: %s already exists in zone %s. Please specify a different pod name. ", podName, zone));
            }
        }

        if (!DataCenter.Type.Edge.equals(zone.getType())) {
            checkPodAttributesForNonEdgeZone(podId, podName, zone, gateway, cidr, startIp, endIp, skipGatewayOverlapCheck);
        }

        if (allocationStateStr != null && !allocationStateStr.isEmpty()) {
            try {
                Grouping.AllocationState.valueOf(allocationStateStr);
            } catch (final IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve Allocation State '" + allocationStateStr + "' to a supported state");
            }
        }
    }

    protected void checkPodAttributesForNonEdgeZone(final long podId, final String podName, final DataCenter zone, final String gateway,
                                                    final String cidr, final String startIp, final String endIp, final boolean skipGatewayOverlapCheck) {

        String cidrAddress;
        long cidrSize;
        // Get the individual cidrAddress and cidrSize values, if the CIDR is
        // valid. If it's not valid, return an error.
        if (NetUtils.isValidIp4Cidr(cidr)) {
            cidrAddress = getCidrAddress(cidr);
            cidrSize = getCidrSize(cidr);
        } else {
            throw new InvalidParameterValueException("Please enter a valid CIDR for pod: " + podName);
        }

        // Check if the IP range is valid
        checkIpRange(startIp, endIp, cidrAddress, cidrSize);

        // Check if the IP range overlaps with the public ip
        if (StringUtils.isNotEmpty(startIp)) {
            checkOverlapPublicIpRange(zone.getId(), startIp, endIp);
        }

        // Check if the gateway is a valid IP address
        if (!NetUtils.isValidIp4(gateway)) {
            throw new InvalidParameterValueException("The gateway is not a valid IP address.");
        }

        // Check if the gateway is in the CIDR subnet
        if (!NetUtils.getCidrSubNet(gateway, cidrSize).equalsIgnoreCase(NetUtils.getCidrSubNet(cidrAddress, cidrSize))) {
            throw new InvalidParameterValueException("The gateway is not in the CIDR subnet.");
        }

        // Don't allow gateway to overlap with start/endIp
        if (!skipGatewayOverlapCheck) {
            if (NetUtils.ipRangesOverlap(startIp, endIp, gateway, gateway)) {
                throw new InvalidParameterValueException("The gateway shouldn't overlap start/end IP addresses");
            }
        }

        final String checkPodCIDRs = _configDao.getValue("check.pod.cidrs");
        if (checkPodCIDRs == null || checkPodCIDRs.trim().isEmpty() || Boolean.parseBoolean(checkPodCIDRs)) {
            checkPodCidrSubnets(zone.getId(), podId, cidr);
        }
    }

    protected void checkPodRangeParametersBasicsForNonEdgeZone(final String startIp, final String endIp, final String gateway, final String netmask) {
        Ipv4RangeValidator.requireValidIp(startIp, "The start IP is invalid");
        Ipv4RangeValidator.requireValidIpIfPresent(endIp, "The end IP is invalid");
        Ipv4RangeValidator.requireValidIp(gateway, "The gateway is invalid");
        Ipv4RangeValidator.requireValidNetmask(netmask, "The netmask is invalid");
    }

    protected String verifyPodIpRangeExists(long podId, String[] existingPodIpRanges, String currentStartIP,
                                            String currentEndIP, String newStartIP, String newEndIP) {
        boolean foundRange = false;
        String vlan = null;

        for (String podIpRange : existingPodIpRanges) {
            final String[] existingPodIpRange = podIpRange.split("-");

            if (existingPodIpRange.length > 1) {
                if (!NetUtils.isValidIp4(existingPodIpRange[0]) || !NetUtils.isValidIp4(existingPodIpRange[1])) {
                    continue;
                }
                if (currentStartIP.equals(existingPodIpRange[0]) && currentEndIP.equals(existingPodIpRange[1])) {
                    foundRange = true;
                    vlan = existingPodIpRange[3];
                }
                if (!foundRange && NetUtils.ipRangesOverlap(newStartIP, newEndIP, existingPodIpRange[0], existingPodIpRange[1])) {
                    throw new InvalidParameterValueException("The Start and End IP address range: (" + newStartIP + "-" + newEndIP + ") overlap with the pod IP range: " + podIpRange);
                }
            }
        }

        if (!foundRange) {
            throw new InvalidParameterValueException("The input IP range: " + currentStartIP + "-" + currentEndIP + " of pod: " + podId + " is not present. Please input an existing range.");
        }

        return vlan;
    }

    protected void updatePodIpRangeInDb(long zoneId, long podId, Integer vlanId, HostPodVO pod, List<Long> newIpRange, List<Long> currentIpRange) {
        HostPodVO lock = null;
        try {
            lock = _podDao.acquireInLockTable(podId);
            if (lock == null) {
                String msg = String.format("Unable to acquire lock on table to update the IP range of POD: %s, Update failed.", pod);
                logger.warn(msg);
                throw new CloudRuntimeException(msg);
            }
            List<Long> iPaddressesToAdd = new ArrayList<>(newIpRange);
            iPaddressesToAdd.removeAll(currentIpRange);
            if (iPaddressesToAdd.size() > 0) {
                for (Long startIP : iPaddressesToAdd) {
                    _zoneDao.addPrivateIpAddress(zoneId, podId, NetUtils.long2Ip(startIP), NetUtils.long2Ip(startIP), false, vlanId);
                }
            } else {
                currentIpRange.removeAll(newIpRange);
                if (currentIpRange.size() > 0) {
                    for (Long startIP : currentIpRange) {
                        if (!_privateIpAddressDao.deleteIpAddressByPodDc(NetUtils.long2Ip(startIP), podId, zoneId)) {
                            throw new CloudRuntimeException(String.format("Failed to remove private IP address: %s of Pod: %s DC: %s", NetUtils.long2Ip(startIP), pod, _zoneDao.findById(pod.getDataCenterId())));
                        }
                    }
                }
            }
            _podDao.update(podId, pod);
        } catch (final Exception e) {
            logger.error("Unable to update Pod {} IP range due to database error {}", pod, e.getMessage(), e);
            throw new CloudRuntimeException(String.format("Failed to update Pod %s IP range. Please contact Cloud Support.", pod));
        } finally {
            if (lock != null) {
                _podDao.releaseFromLockTable(podId);
            }
        }
    }

    protected List<Long> listAllIPsWithintheRange(String startIp, String endIP) {
        verifyIpRangeParameters(startIp, endIP);
        long startIpLong = NetUtils.ip2Long(startIp);
        long endIpLong = NetUtils.ip2Long(endIP);

        List<Long> listOfIpsinRange = new ArrayList<>();
        while (startIpLong <= endIpLong) {
            listOfIpsinRange.add(startIpLong);
            startIpLong++;
        }
        return listOfIpsinRange;
    }

    protected void verifyIpRangeParameters(String startIP, String endIp) {

        if (StringUtils.isNotEmpty(startIP) && !NetUtils.isValidIp4(startIP)) {
            throw new InvalidParameterValueException("The current start address of the IP range " + startIP + " is not a valid IP address.");
        }

        if (StringUtils.isNotEmpty(endIp) && !NetUtils.isValidIp4(endIp)) {
            throw new InvalidParameterValueException("The current end address of the IP range " + endIp + " is not a valid IP address.");
        }

        if (NetUtils.ip2Long(startIP) > NetUtils.ip2Long(endIp)) {
            throw new InvalidParameterValueException("The start IP address must have a lower value than the end IP address.");
        }
    }

    protected void checkIpRangeContainsTakenAddresses(final HostPodVO pod, final String currentStartIP,
                                                      final String currentEndIP, final String newStartIp, final String newEndIp) {

        List<Long> newIpRange = listAllIPsWithintheRange(newStartIp, newEndIp);
        List<Long> currentIpRange = listAllIPsWithintheRange(currentStartIP, currentEndIP);
        List<Long> takenIpsList = new ArrayList<>();
        final List<DataCenterIpAddressVO> takenIps = _privateIpAddressDao.listIpAddressUsage(pod.getId(), pod.getDataCenterId(), true);

        for (DataCenterIpAddressVO takenIp : takenIps) {
            takenIpsList.add(NetUtils.ip2Long(takenIp.getIpAddress()));
        }

        takenIpsList.retainAll(currentIpRange);
        if (!newIpRange.containsAll(takenIpsList)) {
            throw new InvalidParameterValueException("The IP range does not contain some IP addresses that have "
                    + "already been taken. Please adjust your IP range to include all IP addresses already taken.");
        }
    }

    // ------------------------------------------------------------------
    // Shared CIDR / pod-state helpers (also implemented inside
    // ConfigurationManagerImpl because they are called from zone /
    // edit-pod paths that remain on the manager). Kept local here to
    // avoid a back-reference from the service into the manager.
    // ------------------------------------------------------------------

    protected boolean validPod(final long podId) {
        return _podDao.findById(podId) != null;
    }

    protected boolean validPod(final String podName, final long zoneId) {
        return _podDao.findByName(podName, zoneId) != null;
    }

    protected boolean podHasAllocatedPrivateIPs(final long podId) {
        final HostPodVO pod = _podDao.findById(podId);
        final int count = _privateIpAddressDao.countIPs(podId, pod.getDataCenterId(), true);
        return count > 0;
    }

    protected void checkIfPodIsDeletable(final long podId) {
        final HostPodVO pod = _podDao.findById(podId);

        final String errorMsg = "The pod cannot be deleted because ";

        // Check if there are allocated private IP addresses in the pod
        if (_privateIpAddressDao.countIPs(podId, pod.getDataCenterId(), true) != 0) {
            throw new CloudRuntimeException(errorMsg + "there are private IP addresses allocated in this pod.");
        }

        // Check if there are any non-removed volumes in the pod.
        if (!_volumeDao.findByPod(podId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are storage volumes in this pod.");
        }

        // Check if there are any non-removed hosts in the pod.
        if (!_hostDao.findByPodId(podId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are servers in this pod.");
        }

        // Check if there are any non-removed vms in the pod.
        if (!_vmInstanceDao.listByPodId(podId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are Instances in this pod.");
        }

        // Check if there are any non-removed clusters in the pod.
        if (!_clusterDao.listByPodId(podId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are clusters in this pod.");
        }
    }

    protected String getCidrAddress(final String cidr) {
        final String[] cidrPair = cidr.split("\\/");
        return cidrPair[0];
    }

    protected int getCidrSize(final String cidr) {
        final String[] cidrPair = cidr.split("\\/");
        return Integer.parseInt(cidrPair[1]);
    }

    protected void checkIpRange(final String startIp, final String endIp, final String cidrAddress, final long cidrSize) {
        Ipv4RangeValidator.validateOptionalRangeWithinCidr(startIp, endIp, cidrAddress, cidrSize);
    }

    protected void checkOverlapPublicIpRange(final Long zoneId, final String startIp, final String endIp) {
        final long privateStartIp = NetUtils.ip2Long(startIp);
        final long privateEndIp = NetUtils.ip2Long(endIp);

        final List<IPAddressVO> existingPublicIPs = _publicIpAddressDao.listByDcId(zoneId);
        for (final IPAddressVO publicIPVO : existingPublicIPs) {
            final long publicIP = NetUtils.ip2Long(publicIPVO.getAddress().addr());
            if (publicIP >= privateStartIp && publicIP <= privateEndIp) {
                throw new InvalidParameterValueException("The Start IP and endIP address range overlap with Public IP :" + publicIPVO.getAddress().addr());
            }
        }
    }

    protected void checkPodCidrSubnets(final long dcId, final Long podIdToBeSkipped, final String cidr) {
        long skipPod = 0;
        if (podIdToBeSkipped != null) {
            skipPod = podIdToBeSkipped;
        }
        final HashMap<Long, List<Object>> currentPodCidrSubnets = _podDao.getCurrentPodCidrSubnets(dcId, skipPod);
        final List<Object> newCidrPair = new ArrayList<>();
        newCidrPair.add(0, getCidrAddress(cidr));
        newCidrPair.add(1, (long) getCidrSize(cidr));
        currentPodCidrSubnets.put(-1L, newCidrPair);

        final DataCenterVO dcVo = _zoneDao.findById(dcId);
        final String guestNetworkCidr = dcVo.getGuestNetworkCidr();

        // Guest cidr can be null for Basic zone
        String guestIpNetwork = null;
        Long guestCidrSize = null;
        if (guestNetworkCidr != null) {
            final String[] cidrTuple = guestNetworkCidr.split("\\/");
            guestIpNetwork = NetUtils.getIpRangeStartIpFromCidr(cidrTuple[0], Long.parseLong(cidrTuple[1]));
            guestCidrSize = Long.parseLong(cidrTuple[1]);
        }

        final String zoneName = dcVo.getName();

        // Iterate through all pods in this zone
        for (final Long podId : currentPodCidrSubnets.keySet()) {
            String podName;
            if (podId.longValue() == -1) {
                podName = "newPod";
            } else {
                podName = _podDao.findById(podId).getName();
            }

            final List<Object> cidrPair = currentPodCidrSubnets.get(podId);
            final String cidrAddress = (String) cidrPair.get(0);
            final long cidrSize = ((Long) cidrPair.get(1)).longValue();

            long cidrSizeToUse = -1;
            if (guestCidrSize == null || cidrSize < guestCidrSize) {
                cidrSizeToUse = cidrSize;
            } else {
                cidrSizeToUse = guestCidrSize;
            }

            String cidrSubnet = NetUtils.getCidrSubNet(cidrAddress, cidrSizeToUse);

            if (guestNetworkCidr != null) {
                final String guestSubnet = NetUtils.getCidrSubNet(guestIpNetwork, cidrSizeToUse);
                // Check that cidrSubnet does not equal guestSubnet
                if (cidrSubnet.equals(guestSubnet)) {
                    if (podName.equals("newPod")) {
                        throw new InvalidParameterValueException(
                                "The subnet of the pod you are adding conflicts with the subnet of the Guest IP Network. Please specify a different CIDR.");
                    } else {
                        throw new InvalidParameterValueException(
                                "Warning: The subnet of pod "
                                        + podName
                                        + " in zone "
                                        + zoneName
                                        + " conflicts with the subnet of the Guest IP Network. Please change either the pod's CIDR or the Guest IP Network's subnet, and re-run install-vmops-management.");
                    }
                }
            }

            // Iterate through the rest of the pods
            for (final Long otherPodId : currentPodCidrSubnets.keySet()) {
                if (podId.equals(otherPodId)) {
                    continue;
                }

                final List<Object> otherCidrPair = currentPodCidrSubnets.get(otherPodId);
                final String otherCidrAddress = (String) otherCidrPair.get(0);
                final long otherCidrSize = ((Long) otherCidrPair.get(1)).longValue();

                if (cidrSize < otherCidrSize) {
                    cidrSizeToUse = cidrSize;
                } else {
                    cidrSizeToUse = otherCidrSize;
                }

                cidrSubnet = NetUtils.getCidrSubNet(cidrAddress, cidrSizeToUse);
                final String otherCidrSubnet = NetUtils.getCidrSubNet(otherCidrAddress, cidrSizeToUse);

                if (cidrSubnet.equals(otherCidrSubnet)) {
                    final String otherPodName = _podDao.findById(otherPodId).getName();
                    if (podName.equals("newPod")) {
                        throw new InvalidParameterValueException("The subnet of the pod you are adding conflicts with the subnet of pod " + otherPodName + " in zone " + zoneName
                                + ". Please specify a different CIDR.");
                    } else {
                        throw new InvalidParameterValueException("Warning: The pods " + podName + " and " + otherPodName + " in zone " + zoneName
                                + " have conflicting CIDR subnets. Please change the CIDR of one of these pods.");
                    }
                }
            }
        }
    }
}
