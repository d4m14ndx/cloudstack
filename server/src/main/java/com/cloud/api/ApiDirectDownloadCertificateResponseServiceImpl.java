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
package com.cloud.api;

import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.api.response.DirectDownloadCertificateHostStatusResponse;
import org.apache.cloudstack.api.response.DirectDownloadCertificateResponse;
import org.apache.cloudstack.direct.download.DirectDownloadCertificate;
import org.apache.cloudstack.direct.download.DirectDownloadCertificateHostMap;
import org.apache.cloudstack.direct.download.DirectDownloadManager;
import org.apache.cloudstack.direct.download.DirectDownloadManager.HostCertificateStatus.CertificateStatus;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenterVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.utils.Pair;
import com.cloud.utils.security.CertificateHelper;

@Component
public class ApiDirectDownloadCertificateResponseServiceImpl implements ApiDirectDownloadCertificateResponseService {

    protected Logger logger = LogManager.getLogger(ApiDirectDownloadCertificateResponseServiceImpl.class);

    @Override
    public void handleCertificateResponse(String certStr, DirectDownloadCertificateResponse response) {
        try {
            Certificate cert = CertificateHelper.buildCertificate(certStr);
            if (cert instanceof X509Certificate) {
                X509Certificate certificate = (X509Certificate) cert;
                response.setVersion(String.valueOf(certificate.getVersion()));
                response.setSubject(certificate.getSubjectDN().toString());
                response.setIssuer(certificate.getIssuerDN().toString());
                response.setSerialNum(certificate.getSerialNumber().toString());
                response.setValidity(String.format("From: [%s] - To: [%s]", certificate.getNotBefore(), certificate.getNotAfter()));
            }
        } catch (CertificateException e) {
            logger.error("Error parsing direct download certificate: " + certStr, e);
        }
    }

    @Override
    public DirectDownloadCertificateResponse createDirectDownloadCertificateResponse(DirectDownloadCertificate certificate) {
        DirectDownloadCertificateResponse response = new DirectDownloadCertificateResponse();
        DataCenterVO datacenter = ApiDBUtils.findZoneById(certificate.getZoneId());
        if (datacenter != null) {
            response.setZoneId(datacenter.getUuid());
            response.setZoneName(datacenter.getName());
        }
        response.setId(certificate.getUuid());
        response.setAlias(certificate.getAlias());
        handleCertificateResponse(certificate.getCertificate(), response);
        response.setHypervisor(certificate.getHypervisorType().getHypervisorDisplayName());
        response.setObjectName("directdownloadcertificate");
        return response;
    }

    @Override
    public List<DirectDownloadCertificateHostStatusResponse> createDirectDownloadCertificateHostMapResponse(List<DirectDownloadCertificateHostMap> hostMappings) {
        if (CollectionUtils.isEmpty(hostMappings)) {
            return new ArrayList<>();
        }
        List<DirectDownloadCertificateHostStatusResponse> responses = new ArrayList<>(hostMappings.size());
        for (DirectDownloadCertificateHostMap map : hostMappings) {
            DirectDownloadCertificateHostStatusResponse response = new DirectDownloadCertificateHostStatusResponse();
            HostVO host = ApiDBUtils.findHostById(map.getHostId());
            if (host != null) {
                response.setHostId(host.getUuid());
                response.setHostName(host.getName());
            }
            response.setStatus(map.isRevoked() ? CertificateStatus.REVOKED.name() : CertificateStatus.UPLOADED.name());
            response.setObjectName("directdownloadcertificatehoststatus");
            responses.add(response);
        }
        return responses;
    }

    protected DirectDownloadCertificateHostStatusResponse getDirectDownloadHostStatusResponseInternal(Host host, CertificateStatus status, String details) {
        DirectDownloadCertificateHostStatusResponse response = new DirectDownloadCertificateHostStatusResponse();
        if (host != null) {
            response.setHostId(host.getUuid());
            response.setHostName(host.getName());
        }
        response.setStatus(status.name());
        response.setDetails(details);
        response.setObjectName("directdownloadcertificatehoststatus");
        return response;
    }

    @Override
    public DirectDownloadCertificateHostStatusResponse createDirectDownloadCertificateHostStatusResponse(DirectDownloadManager.HostCertificateStatus hostStatus) {
        Host host = hostStatus.getHost();
        CertificateStatus status = hostStatus.getStatus();
        return getDirectDownloadHostStatusResponseInternal(host, status, hostStatus.getDetails());
    }

    @Override
    public DirectDownloadCertificateHostStatusResponse createDirectDownloadCertificateProvisionResponse(Long certificateId, Long hostId, Pair<Boolean, String> result) {
        HostVO host = ApiDBUtils.findHostById(hostId);
        CertificateStatus status = result != null && result.first() ? CertificateStatus.UPLOADED : CertificateStatus.FAILED;
        return getDirectDownloadHostStatusResponseInternal(host, status, result != null ? result.second() : "provision certificate failure");
    }
}
