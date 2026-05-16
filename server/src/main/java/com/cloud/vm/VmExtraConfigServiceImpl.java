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
package com.cloud.vm;

import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.utils.security.ParserUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDetailsDao;

/**
 * Hypervisor-specific extra-config validation and persistence —
 * extracted from {@link UserVmManagerImpl}.
 *
 * @see VmExtraConfigService
 */
@Component
public class VmExtraConfigServiceImpl implements VmExtraConfigService {
    private static final Logger LOG = LogManager.getLogger(VmExtraConfigServiceImpl.class);

    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @Override
    public void addExtraConfig(UserVm vm, String extraConfig) {
        String decodedUrl = decodeExtraConfig(extraConfig);
        HypervisorType hypervisorType = vm.getHypervisorType();

        if (hypervisorType.equals(HypervisorType.XenServer)) {
            persistExtraConfigXenServer(decodedUrl, vm);
        } else if (hypervisorType.equals(HypervisorType.KVM)) {
            persistExtraConfigKvm(decodedUrl, vm);
        } else if (hypervisorType.equals(HypervisorType.VMware)) {
            persistExtraConfigVmware(decodedUrl, vm);
        } else {
            String msg = String.format("This hypervisor %s is not supported for use with this feature", hypervisorType.toString());
            throw new CloudRuntimeException(msg);
        }
    }

    @Override
    public String decodeExtraConfig(String encodeString) {
        try {
            return URLDecoder.decode(encodeString, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new CloudRuntimeException("Failed to provided decode URL string: " + e.getMessage());
        }
    }

    @Override
    public void validateExtraConfig(long accountId, HypervisorType hypervisorType, String extraConfig) {
        if (!UserVmManager.EnableAdditionalVmConfig.valueIn(accountId)) {
            throw new CloudRuntimeException("Additional VM configuration is not enabled for this account");
        }
        if (HypervisorType.KVM.equals(hypervisorType)) {
            validateKvmExtraConfig(extraConfig, accountId);
        }
    }

    @Override
    public void validateKvmExtraConfig(String decodedUrl, long accountId) {
        String[] allowedConfigOptionList = UserVmManagerImpl.KvmAdditionalConfigAllowList.valueIn(accountId).split(",");
        if (!decodedUrl.contains(":")) {
            try {
                DocumentBuilder builder = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder();
                InputSource src = new InputSource();
                src.setCharacterStream(new StringReader(String.format("<config>\n%s\n</config>", decodedUrl)));
                Document doc = builder.parse(src);
                doc.getDocumentElement().normalize();
                NodeList nodeList = doc.getElementsByTagName("*");
                for (int i = 1; i < nodeList.getLength(); i++) {
                    Element element = (Element) nodeList.item(i);
                    boolean isValidConfig = false;
                    String currentConfig = element.getNodeName().trim();
                    for (String tag : allowedConfigOptionList) {
                        if (currentConfig.equals(tag.trim())) {
                            isValidConfig = true;
                        }
                    }
                    if (!isValidConfig) {
                        throw new CloudRuntimeException(String.format("Extra config '%s' is not on the list of allowed keys for KVM hypervisor hosts", currentConfig));
                    }
                }
            } catch (ParserConfigurationException | IOException | SAXException e) {
                throw new CloudRuntimeException("Failed to parse additional XML configuration: " + e.getMessage());
            }
        }
    }

    @Override
    public void persistExtraConfigVmware(String decodedUrl, UserVm vm) {
        boolean isValidConfig = isValidKeyValuePair(decodedUrl);
        if (!isValidConfig) {
            throw new CloudRuntimeException("The passed extra config string " + decodedUrl + "contains an invalid key/value pair pattern");
        }
        String[] extraConfigs = decodedUrl.split("\\r?\\n+");
        String[] allowedKeyList = UserVmManagerImpl.VmwareAdditionalConfigAllowList.value().split(",");
        for (String cfg : extraConfigs) {
            boolean validXenOrVmwareConfiguration = isValidXenOrVmwareConfiguration(cfg, allowedKeyList);
            String[] paramArray = cfg.split("=");
            if (validXenOrVmwareConfiguration && paramArray.length == 2) {
                vmInstanceDetailsDao.addDetail(vm.getId(), paramArray[0].trim(), paramArray[1].trim(), true);
            } else {
                throw new CloudRuntimeException("Extra config " + cfg + " is not on the list of allowed keys for VMware hypervisor hosts.");
            }
        }
    }

    @Override
    public void persistExtraConfigXenServer(String decodedUrl, UserVm vm) {
        boolean isValidConfig = isValidKeyValuePair(decodedUrl);
        if (!isValidConfig) {
            String msg = String.format("The passed extra config string '%s' contains an invalid key/value pair pattern", decodedUrl);
            throw new CloudRuntimeException(msg);
        }
        String[] extraConfigs = decodedUrl.split("\\r?\\n+");
        int i = 1;
        String extraConfigKey = ApiConstants.EXTRA_CONFIG + "-";
        String[] allowedKeyList = UserVmManagerImpl.XenServerAdditionalConfigAllowList.value().split(",");
        for (String cfg : extraConfigs) {
            boolean validXenOrVmwareConfiguration = isValidXenOrVmwareConfiguration(cfg, allowedKeyList);
            if (validXenOrVmwareConfiguration) {
                vmInstanceDetailsDao.addDetail(vm.getId(), extraConfigKey + String.valueOf(i), cfg, true);
                i++;
            } else {
                throw new CloudRuntimeException("Extra config " + cfg + " is not on the list of allowed keys for XenServer hypervisor hosts.");
            }
        }
    }

    @Override
    public void persistExtraConfigKvm(String decodedUrl, UserVm vm) {
        validateKvmExtraConfig(decodedUrl, vm.getAccountId());
        String[] extraConfigs = decodedUrl.split("\n\n");
        int i = 1;
        for (String cfg : extraConfigs) {
            String[] cfgParts = cfg.split("\n");
            String extraConfigKey = ApiConstants.EXTRA_CONFIG;
            String extraConfigValue;
            if (cfgParts[0].matches("\\S+:$")) {
                extraConfigKey += "-" + cfgParts[0].substring(0, cfgParts[0].length() - 1);
                extraConfigValue = cfg.replace(cfgParts[0] + "\n", "");
            } else {
                extraConfigKey += "-" + String.valueOf(i);
                extraConfigValue = cfg;
            }
            vmInstanceDetailsDao.addDetail(vm.getId(), extraConfigKey, extraConfigValue, true);
            i++;
        }
    }

    @Override
    public boolean isValidKeyValuePair(String decodedUrl) {
        Pattern pattern = Pattern.compile("^(?:[\\w-\\s\\.:]*=[\\w-\\s\\.'\":]*(?:\\s+|$))+$");
        Matcher matcher = pattern.matcher(decodedUrl);
        return matcher.matches();
    }

    @Override
    public boolean isValidXenOrVmwareConfiguration(String cfg, String[] allowedKeyList) {
        String[] cfgKeyValuePair = cfg.split("=");
        if (cfgKeyValuePair.length >= 1) {
            for (String allowedKey : allowedKeyList) {
                if (cfgKeyValuePair[0].equalsIgnoreCase(allowedKey.trim())) {
                    return true;
                }
            }
        } else {
            String msg = String.format("An incorrect configuration %s has been passed", cfg);
            throw new CloudRuntimeException(msg);
        }
        return false;
    }
}
