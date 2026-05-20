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

import org.apache.commons.lang3.BooleanUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.EncryptionUtil;
import com.cloud.utils.StringUtils;
import com.cloud.utils.net.NetUtils;

public class NfsPostUploadRequestValidationService {

    private static final String USE_HTTPS_TO_UPLOAD = "useHttpsToUpload";

    protected Logger logger = LogManager.getLogger(NfsPostUploadRequestValidationService.class);

    public void validatePostUploadRequest(String signature, String metadata, String timeout, String hostname, long contentLength, String uuid,
            String useHttpsToUpload, String postUploadPsk, NfsPostUploadService postUploadService) throws InvalidParameterValueException {
        if (StringUtils.isAnyEmpty(signature, metadata, timeout)) {
            postUploadService.updateStateMapWithError(uuid, "signature, metadata and expires are compulsory fields.");
            throw new InvalidParameterValueException("signature, metadata and expires are compulsory fields.");
        }

        if (contentLength <= 0) {
            throw new InvalidParameterValueException("content length is not set in the request or has invalid value.");
        }

        validatePostUploadRequestSignature(signature, hostname, uuid, metadata, timeout, getUploadProtocol(useHttpsToUpload), postUploadPsk, postUploadService);

        DateTime timeoutDateTime = DateTime.parse(timeout, ISODateTimeFormat.dateTime());
        if (timeoutDateTime.isBeforeNow()) {
            postUploadService.updateStateMapWithError(uuid, "request not valid anymore.");
            throw new InvalidParameterValueException("request not valid anymore.");
        }
    }

    protected void validatePostUploadRequestSignature(String signature, String hostname, String uuid, String metadata, String timeout,
            String protocol, String postUploadPsk, NfsPostUploadService postUploadService) {
        logger.trace(String.format("Validating signature [%s] for post upload request [%s].", signature, uuid));
        String fullUrl = String.format("%s://%s/upload/%s", protocol, hostname, uuid);
        String data = String.format("%s%s%s", metadata, fullUrl, timeout);

        String computedSignature = EncryptionUtil.generateSignature(data, postUploadPsk);
        logger.debug(String.format("Computed signature for post upload request [%s] is [%s].", uuid, computedSignature));

        boolean isSignatureValid = computedSignature.equals(signature);
        if (!isSignatureValid) {
            logger.debug(String.format("Signature for post upload request [%s] is invalid.", uuid));
            String errorMsg = "signature validation failed.";
            postUploadService.updateStateMapWithError(uuid, errorMsg);
            throw new InvalidParameterValueException(errorMsg);
        }
        logger.debug(String.format("Signature for post upload request [%s] is valid.", uuid));
    }

    protected String getUploadProtocol(String useHttpsToUpload) {
        if (BooleanUtils.toBoolean(useHttpsToUpload)) {
            logger.debug(String.format("Param [%s] is set to true; therefore, HTTPS is being used.", USE_HTTPS_TO_UPLOAD));
            return NetUtils.HTTPS_PROTO;
        }
        logger.debug(String.format("Param [%s] is set to false; therefore, HTTP is being used.", USE_HTTPS_TO_UPLOAD));
        return NetUtils.HTTP_PROTO;
    }
}
