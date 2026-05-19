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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.response.DirectDownloadCertificateHostStatusResponse;
import org.apache.cloudstack.api.response.DirectDownloadCertificateResponse;
import org.apache.cloudstack.direct.download.DirectDownloadCertificate;
import org.apache.cloudstack.direct.download.DirectDownloadCertificateHostMap;
import org.apache.cloudstack.direct.download.DirectDownloadManager;
import org.apache.cloudstack.direct.download.DirectDownloadManager.HostCertificateStatus.CertificateStatus;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenterVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.utils.Pair;

@RunWith(MockitoJUnitRunner.class)
public class ApiDirectDownloadCertificateResponseServiceImplTest {

    private static final String CERTIFICATE = "-----BEGIN CERTIFICATE-----\n" +
            "MIIGLTCCBRWgAwIBAgIQOHZRhOAYLowYNcopBvxCdjANBgkqhkiG9w0BAQsFADCB\n" +
            "jzELMAkGA1UEBhMCR0IxGzAZBgNVBAgTEkdyZWF0ZXIgTWFuY2hlc3RlcjEQMA4G\n" +
            "A1UEBxMHU2FsZm9yZDEYMBYGA1UEChMPU2VjdGlnbyBMaW1pdGVkMTcwNQYDVQQD\n" +
            "Ey5TZWN0aWdvIFJTQSBEb21haW4gVmFsaWRhdGlvbiBTZWN1cmUgU2VydmVyIENB\n" +
            "MB4XDTIxMDYxNTAwMDAwMFoXDTIyMDcxNjIzNTk1OVowFzEVMBMGA1UEAwwMKi5h\n" +
            "cGFjaGUub3JnMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4UoHCmK5\n" +
            "XdbyZ++d2BGuX35zZcESvr4K1Hw7ZTbyzMC+uokBKJcng1Hf5ctjUFKCoz7AlWRq\n" +
            "JH5U3vU0y515C0aEE+j0lUHlxMGQD2ut+sJ6BZqcTBl5d8ns1TSckEH31DBDN3Fw\n" +
            "uMLqEWBOjwt1MMT3Z+kR7ekuheJYbYHbJ2VtnKQd4jHmLly+/p+UqaQ6dIvQxq82\n" +
            "ggZIUNWjGKwXS2vKl6O9EDu/QaAX9e059pf3UxAxGtJjeKXWJvt1e96T53+2+kXp\n" +
            "j0/PuyT6F0o+grY08tCJnw7kTB4sE2qfALdwSblvyjBDOYtS4Xj5nycMpd+4Qse4\n" +
            "2+irNBdZ63pqqQIDAQABo4IC+jCCAvYwHwYDVR0jBBgwFoAUjYxexFStiuF36Zv5\n" +
            "mwXhuAGNYeEwHQYDVR0OBBYEFH+9CNXAwWW4+jyizee51r8x4ofHMA4GA1UdDwEB\n" +
            "/wQEAwIFoDAMBgNVHRMBAf8EAjAAMB0GA1UdJQQWMBQGCCsGAQUFBwMBBggrBgEF\n" +
            "BQcDAjBJBgNVHSAEQjBAMDQGCysGAQQBsjEBAgIHMCUwIwYIKwYBBQUHAgEWF2h0\n" +
            "dHBzOi8vc2VjdGlnby5jb20vQ1BTMAgGBmeBDAECATCBhAYIKwYBBQUHAQEEeDB2\n" +
            "ME8GCCsGAQUFBzAChkNodHRwOi8vY3J0LnNlY3RpZ28uY29tL1NlY3RpZ29SU0FE\n" +
            "b21haW5WYWxpZGF0aW9uU2VjdXJlU2VydmVyQ0EuY3J0MCMGCCsGAQUFBzABhhdo\n" +
            "dHRwOi8vb2NzcC5zZWN0aWdvLmNvbTAjBgNVHREEHDAaggwqLmFwYWNoZS5vcmeC\n" +
            "CmFwYWNoZS5vcmcwggF+BgorBgEEAdZ5AgQCBIIBbgSCAWoBaAB2AEalVet1+pEg\n" +
            "MLWiiWn0830RLEF0vv1JuIWr8vxw/m1HAAABehHLqfgAAAQDAEcwRQIgINH3CquJ\n" +
            "zTAprwjdo2cEWkMzpaNoP1SOI4xGl68PF2oCIQC77eD7K6Smx4Fv/z/sTKk21Psb\n" +
            "ZhmVq5YoqhwRKuMgVAB2AEHIyrHfIkZKEMahOglCh15OMYsbA+vrS8do8JBilgb2\n" +
            "AAABehHLqcEAAAQDAEcwRQIhANh++zJa9AE4U0DsHIFq6bW40b1OfGfH8uUdmjEZ\n" +
            "s1jzAiBIRtJeFVmobSnbFKlOr8BGfD2L/hg1rkAgJlKY5oFShgB2ACl5vvCeOTkh\n" +
            "8FZzn2Old+W+V32cYAr4+U1dJlwlXceEAAABehHLqZ4AAAQDAEcwRQIhAOZDfvU8\n" +
            "Hz80I6Iyj2rv8+yWBVq1XVixI8bMykdCO6ADAiAWj8cJ9g1zxko4dJu8ouJf+Pwl\n" +
            "0bbhhuJHhy/f5kiaszANBgkqhkiG9w0BAQsFAAOCAQEAlkdB7FZtVQz39TDNKR4u\n" +
            "I8VQsTH5n4Kg+zVc0pptI7HGUWtp5PjBAEsvJ/G/NQXsjVflQaNPRRd7KNZycZL1\n" +
            "jls6GdVoWVno6O5aLS7cCnb0tTlb8srhb9vdLZkSoCVCZLVjik5s2TLfpLsBKrTP\n" +
            "leVY3n9TBZH+vyKLHt4WHR23Z+74xDsuXunoPGXQVV8ymqTtfohaoM19jP99vjY7\n" +
            "DL/289XjMSfyPFqlpU4JDM7lY/kJSKB/C4eQglT8Sgm0h/kj5hdT2uMJBIQZIJVv\n" +
            "241fAVUPgrYAESOMm2TVA9r1OzeoUNlKw+e3+vjTR6sfDDp/iRKcEVQX4u9+CxZp\n" +
            "9g==\n-----END CERTIFICATE-----";

    private final ApiDirectDownloadCertificateResponseServiceImpl service = new ApiDirectDownloadCertificateResponseServiceImpl();

    @Mock
    private DirectDownloadCertificate certificate;
    @Mock
    private DirectDownloadCertificateHostMap uploadedHostMap;
    @Mock
    private DirectDownloadCertificateHostMap revokedHostMap;
    @Mock
    private DataCenterVO zone;
    @Mock
    private HostVO host;
    @Mock
    private Host statusHost;
    @Mock
    private DirectDownloadManager.HostCertificateStatus hostStatus;

    @Test
    public void handleCertificateResponseParsesX509Fields() {
        DirectDownloadCertificateResponse response = new DirectDownloadCertificateResponse();

        service.handleCertificateResponse(CERTIFICATE, response);

        assertEquals("3", response.getVersion());
        assertEquals("CN=*.apache.org", response.getSubject());
        assertEquals("CN=Sectigo RSA Domain Validation Secure Server CA, O=Sectigo Limited, L=Salford, ST=Greater Manchester, C=GB", response.getIssuer());
        assertTrue(response.getValidity().startsWith("From: ["));
        assertTrue(response.getValidity().contains("2021] - To: ["));
        assertTrue(response.getValidity().endsWith("2022]"));
    }

    @Test
    public void handleCertificateResponseSwallowsInvalidCertificate() {
        DirectDownloadCertificateResponse response = new DirectDownloadCertificateResponse();

        service.handleCertificateResponse("not a certificate", response);

        assertNull(response.getVersion());
        assertNull(response.getSubject());
        assertNull(response.getIssuer());
        assertNull(response.getSerialNum());
        assertNull(response.getValidity());
    }

    @Test
    public void createDirectDownloadCertificateResponsePopulatesCertificateAndZoneFields() {
        when(certificate.getZoneId()).thenReturn(1L);
        when(certificate.getUuid()).thenReturn("certificate-uuid");
        when(certificate.getAlias()).thenReturn("certificate-alias");
        when(certificate.getCertificate()).thenReturn(CERTIFICATE);
        when(certificate.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(zone.getUuid()).thenReturn("zone-uuid");
        when(zone.getName()).thenReturn("zone-name");

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findZoneById(1L)).thenReturn(zone);

            DirectDownloadCertificateResponse response = service.createDirectDownloadCertificateResponse(certificate);

            assertEquals("certificate-uuid", response.getId());
            assertEquals("certificate-alias", response.getAlias());
            assertEquals("zone-uuid", response.getZoneId());
            assertEquals("zone-name", response.getZoneName());
            assertEquals("KVM", response.getHypervisor());
            assertEquals("CN=*.apache.org", response.getSubject());
            assertEquals("directdownloadcertificate", response.getObjectName());
        }
    }

    @Test
    public void createDirectDownloadCertificateResponseKeepsNullZoneFieldsWhenZoneIsNotFound() {
        when(certificate.getZoneId()).thenReturn(1L);
        when(certificate.getUuid()).thenReturn("certificate-uuid");
        when(certificate.getAlias()).thenReturn("certificate-alias");
        when(certificate.getCertificate()).thenReturn("not a certificate");
        when(certificate.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findZoneById(1L)).thenReturn(null);

            DirectDownloadCertificateResponse response = service.createDirectDownloadCertificateResponse(certificate);

            assertEquals("certificate-uuid", response.getId());
            assertNull(response.getZoneId());
            assertNull(response.getZoneName());
        }
    }

    @Test
    public void createDirectDownloadCertificateHostMapResponseReturnsEmptyListForNullAndEmptyMappings() {
        assertTrue(service.createDirectDownloadCertificateHostMapResponse(null).isEmpty());
        assertTrue(service.createDirectDownloadCertificateHostMapResponse(Collections.emptyList()).isEmpty());
    }

    @Test
    public void createDirectDownloadCertificateHostMapResponsePopulatesHostAndStatusFields() {
        when(uploadedHostMap.getHostId()).thenReturn(10L);
        when(uploadedHostMap.isRevoked()).thenReturn(false);
        when(revokedHostMap.getHostId()).thenReturn(11L);
        when(revokedHostMap.isRevoked()).thenReturn(true);
        when(host.getUuid()).thenReturn("host-uuid");
        when(host.getName()).thenReturn("host-name");

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findHostById(10L)).thenReturn(host);
            when(ApiDBUtils.findHostById(11L)).thenReturn(null);

            List<DirectDownloadCertificateHostStatusResponse> responses =
                    service.createDirectDownloadCertificateHostMapResponse(Arrays.asList(uploadedHostMap, revokedHostMap));

            assertEquals(2, responses.size());
            assertEquals("host-uuid", responses.get(0).getHostId());
            assertEquals("host-name", responses.get(0).getHostName());
            assertEquals(CertificateStatus.UPLOADED.name(), responses.get(0).getStatus());
            assertEquals("directdownloadcertificatehoststatus", responses.get(0).getObjectName());
            assertNull(responses.get(1).getHostId());
            assertNull(responses.get(1).getHostName());
            assertEquals(CertificateStatus.REVOKED.name(), responses.get(1).getStatus());
        }
    }

    @Test
    public void createDirectDownloadCertificateHostStatusResponsePopulatesHostStatusAndDetails() {
        when(hostStatus.getHost()).thenReturn(statusHost);
        when(hostStatus.getStatus()).thenReturn(CertificateStatus.SKIPPED);
        when(hostStatus.getDetails()).thenReturn("not running");
        when(statusHost.getUuid()).thenReturn("host-uuid");
        when(statusHost.getName()).thenReturn("host-name");

        DirectDownloadCertificateHostStatusResponse response = service.createDirectDownloadCertificateHostStatusResponse(hostStatus);

        assertEquals("host-uuid", response.getHostId());
        assertEquals("host-name", response.getHostName());
        assertEquals(CertificateStatus.SKIPPED.name(), response.getStatus());
        assertEquals("not running", response.getDetails());
        assertEquals("directdownloadcertificatehoststatus", response.getObjectName());
    }

    @Test
    public void createDirectDownloadCertificateProvisionResponseUsesFailureDefaultsForNullResultAndMissingHost() {
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findHostById(10L)).thenReturn(null);

            DirectDownloadCertificateHostStatusResponse response = service.createDirectDownloadCertificateProvisionResponse(1L, 10L, null);

            assertNull(response.getHostId());
            assertNull(response.getHostName());
            assertEquals(CertificateStatus.FAILED.name(), response.getStatus());
            assertEquals("provision certificate failure", response.getDetails());
            assertEquals("directdownloadcertificatehoststatus", response.getObjectName());
        }
    }

    @Test
    public void createDirectDownloadCertificateProvisionResponseUsesUploadedStatusWhenResultSucceeds() {
        when(host.getUuid()).thenReturn("host-uuid");
        when(host.getName()).thenReturn("host-name");

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findHostById(10L)).thenReturn(host);

            DirectDownloadCertificateHostStatusResponse response =
                    service.createDirectDownloadCertificateProvisionResponse(1L, 10L, new Pair<>(true, "uploaded"));

            assertEquals("host-uuid", response.getHostId());
            assertEquals("host-name", response.getHostName());
            assertEquals(CertificateStatus.UPLOADED.name(), response.getStatus());
            assertEquals("uploaded", response.getDetails());
        }
    }
}
