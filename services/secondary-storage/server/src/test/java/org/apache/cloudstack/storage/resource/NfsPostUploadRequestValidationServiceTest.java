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
package org.apache.cloudstack.storage.resource;

import org.apache.cloudstack.storage.command.UploadStatusAnswer;
import org.apache.cloudstack.storage.command.UploadStatusCommand;
import org.apache.cloudstack.storage.command.UploadStatusCommand.EntityType;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.EncryptionUtil;
import com.cloud.utils.net.NetUtils;

public class NfsPostUploadRequestValidationServiceTest {

    private static final String HOSTNAME = "hostname";
    private static final String UUID = "uuid";
    private static final String METADATA = "metadata";
    private static final String TIMEOUT = "timeout";
    private static final String PSK = "6HyGMx9Vat7rZw1pMZrM4OlD4FFwLUPznTsFqVFSOIvk0mAWMRCVZ6UCq42gZvhp";
    private static final String PROTOCOL = NetUtils.HTTP_PROTO;
    private static final String EXPECTED_SIGNATURE = "expectedSignature";
    private static final String COMPUTED_SIGNATURE = "computedSignature";

    private final NfsPostUploadRequestValidationService validationService = new NfsPostUploadRequestValidationService();
    private final NfsPostUploadService postUploadService = new NfsPostUploadService();

    @Test(expected = InvalidParameterValueException.class)
    public void validatePostUploadRequestSignatureThrowsExceptionWhenProtocolDiffers() {
        try (MockedStatic<EncryptionUtil> encryptionUtilMock = Mockito.mockStatic(EncryptionUtil.class)) {
            prepareForValidatePostUploadRequestSignatureTests(encryptionUtilMock);

            validationService.validatePostUploadRequestSignature(EXPECTED_SIGNATURE, HOSTNAME, UUID, METADATA, TIMEOUT,
                    NetUtils.HTTPS_PROTO, PSK, postUploadService);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validatePostUploadRequestSignatureThrowsExceptionWhenHostnameDiffers() {
        try (MockedStatic<EncryptionUtil> encryptionUtilMock = Mockito.mockStatic(EncryptionUtil.class)) {
            prepareForValidatePostUploadRequestSignatureTests(encryptionUtilMock);

            validationService.validatePostUploadRequestSignature(EXPECTED_SIGNATURE, "test", UUID, METADATA, TIMEOUT,
                    PROTOCOL, PSK, postUploadService);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validatePostUploadRequestSignatureThrowsExceptionWhenUuidDiffers() {
        try (MockedStatic<EncryptionUtil> encryptionUtilMock = Mockito.mockStatic(EncryptionUtil.class)) {
            prepareForValidatePostUploadRequestSignatureTests(encryptionUtilMock);

            validationService.validatePostUploadRequestSignature(EXPECTED_SIGNATURE, HOSTNAME, "test", METADATA, TIMEOUT,
                    PROTOCOL, PSK, postUploadService);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validatePostUploadRequestSignatureThrowsExceptionWhenMetadataDiffers() {
        try (MockedStatic<EncryptionUtil> encryptionUtilMock = Mockito.mockStatic(EncryptionUtil.class)) {
            prepareForValidatePostUploadRequestSignatureTests(encryptionUtilMock);

            validationService.validatePostUploadRequestSignature(EXPECTED_SIGNATURE, HOSTNAME, UUID, "test", TIMEOUT,
                    PROTOCOL, PSK, postUploadService);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validatePostUploadRequestSignatureThrowsExceptionWhenTimeoutDiffers() {
        try (MockedStatic<EncryptionUtil> encryptionUtilMock = Mockito.mockStatic(EncryptionUtil.class)) {
            prepareForValidatePostUploadRequestSignatureTests(encryptionUtilMock);

            validationService.validatePostUploadRequestSignature(EXPECTED_SIGNATURE, HOSTNAME, UUID, METADATA, "test",
                    PROTOCOL, PSK, postUploadService);
        }
    }

    @Test
    public void validatePostUploadRequestSignatureSucceedsWhenDataIsTheSame() {
        try (MockedStatic<EncryptionUtil> encryptionUtilMock = Mockito.mockStatic(EncryptionUtil.class)) {
            prepareForValidatePostUploadRequestSignatureTests(encryptionUtilMock);

            validationService.validatePostUploadRequestSignature(EXPECTED_SIGNATURE, HOSTNAME, UUID, METADATA, TIMEOUT,
                    PROTOCOL, PSK, postUploadService);
        }
    }

    @Test
    public void validatePostUploadRequestRejectsMissingFieldsAndUpdatesState() {
        try {
            validationService.validatePostUploadRequest(null, METADATA, TIMEOUT, HOSTNAME, 1L, UUID, "false", PSK, postUploadService);
            Assert.fail("Expected missing fields to be rejected");
        } catch (InvalidParameterValueException e) {
            Assert.assertEquals("signature, metadata and expires are compulsory fields.", e.getMessage());
        }

        UploadStatusAnswer answer = postUploadService.execute(new UploadStatusCommand(UUID, EntityType.Template, false));

        Assert.assertEquals(UploadStatusAnswer.UploadStatus.ERROR, answer.getStatus());
        Assert.assertEquals("signature, metadata and expires are compulsory fields.", answer.getDetails());
    }

    @Test
    public void validatePostUploadRequestRejectsExpiredTimeoutAndUpdatesState() {
        String expiredTimeout = DateTime.now().minusMinutes(1).toString(ISODateTimeFormat.dateTime());
        try (MockedStatic<EncryptionUtil> encryptionUtilMock = Mockito.mockStatic(EncryptionUtil.class)) {
            mockSignature(encryptionUtilMock, METADATA, HOSTNAME, UUID, expiredTimeout, PROTOCOL, PSK, EXPECTED_SIGNATURE);

            try {
                validationService.validatePostUploadRequest(EXPECTED_SIGNATURE, METADATA, expiredTimeout, HOSTNAME, 1L, UUID, "false", PSK, postUploadService);
                Assert.fail("Expected expired request to be rejected");
            } catch (InvalidParameterValueException e) {
                Assert.assertEquals("request not valid anymore.", e.getMessage());
            }
        }

        UploadStatusAnswer answer = postUploadService.execute(new UploadStatusCommand(UUID, EntityType.Template, false));

        Assert.assertEquals(UploadStatusAnswer.UploadStatus.ERROR, answer.getStatus());
        Assert.assertEquals("request not valid anymore.", answer.getDetails());
    }

    @Test
    public void getUploadProtocolReturnsHttpsWhenUseHttpsToUploadIsTrue() {
        String result = validationService.getUploadProtocol("true");

        Assert.assertEquals(NetUtils.HTTPS_PROTO, result);
    }

    @Test
    public void getUploadProtocolReturnsHttpWhenUseHttpsToUploadIsFalse() {
        String result = validationService.getUploadProtocol("false");

        Assert.assertEquals(NetUtils.HTTP_PROTO, result);
    }

    private static void prepareForValidatePostUploadRequestSignatureTests(MockedStatic<EncryptionUtil> encryptionUtilMock) {
        encryptionUtilMock.when(() -> EncryptionUtil.generateSignature(Mockito.anyString(), Mockito.anyString())).thenReturn(COMPUTED_SIGNATURE);
        mockSignature(encryptionUtilMock, METADATA, HOSTNAME, UUID, TIMEOUT, PROTOCOL, PSK, EXPECTED_SIGNATURE);
    }

    private static void mockSignature(MockedStatic<EncryptionUtil> encryptionUtilMock, String metadata, String hostname, String uuid,
            String timeout, String protocol, String psk, String signature) {
        String fullUrl = String.format("%s://%s/upload/%s", protocol, hostname, uuid);
        String data = String.format("%s%s%s", metadata, fullUrl, timeout);
        encryptionUtilMock.when(() -> EncryptionUtil.generateSignature(data, psk)).thenReturn(signature);
    }
}
