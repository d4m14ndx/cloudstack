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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

import com.cloud.utils.Pair;

public class ConfigurationValueValidatorTest {

    // ---- validateValueType ----

    @Test
    public void stringAndCharAreAlwaysValid() {
        assertTrue(ConfigurationValueValidator.validateValueType("anything", String.class));
        assertTrue(ConfigurationValueValidator.validateValueType("x", Character.class));
        assertTrue(ConfigurationValueValidator.validateValueType("", String.class));
    }

    @Test
    public void booleanAcceptsTrueOrFalseOnly() {
        assertTrue(ConfigurationValueValidator.validateValueType("true", Boolean.class));
        assertTrue(ConfigurationValueValidator.validateValueType("false", Boolean.class));
        assertFalse(ConfigurationValueValidator.validateValueType("yes", Boolean.class));
        assertFalse(ConfigurationValueValidator.validateValueType("0", Boolean.class));
    }

    @Test
    public void numericTypesParseStrictly() {
        assertTrue(ConfigurationValueValidator.validateValueType("123", Integer.class));
        assertTrue(ConfigurationValueValidator.validateValueType("-5", Integer.class));
        assertFalse(ConfigurationValueValidator.validateValueType("12.5", Integer.class));
        assertTrue(ConfigurationValueValidator.validateValueType("1234567890123", Long.class));
        assertTrue(ConfigurationValueValidator.validateValueType("3.14", Float.class));
        assertTrue(ConfigurationValueValidator.validateValueType("3.14", Double.class));
    }

    @Test
    public void infiniteFloatsAreRejected() {
        assertFalse(ConfigurationValueValidator.validateValueType("1e9999", Float.class));
        assertFalse(ConfigurationValueValidator.validateValueType("1e9999", Double.class));
    }

    @Test
    public void nullAndUnknownTypesReturnFalse() {
        assertFalse(ConfigurationValueValidator.validateValueType(null, Integer.class));
        assertFalse(ConfigurationValueValidator.validateValueType("123", Object.class));
    }

    // ---- validateCommaSeparatedKeyValueConfigWithPositiveIntegerValues ----

    @Test
    public void emptyValueIsAccepted() {
        Pair<Boolean, String> result = ConfigurationValueValidator
                .validateCommaSeparatedKeyValueConfigWithPositiveIntegerValues("");
        assertTrue(result.first());
    }

    @Test
    public void validKeyValuePairsAccepted() {
        Pair<Boolean, String> result = ConfigurationValueValidator
                .validateCommaSeparatedKeyValueConfigWithPositiveIntegerValues("foo=10,bar=20");
        assertTrue(result.first());
    }

    @Test
    public void missingEqualsRejected() {
        Pair<Boolean, String> result = ConfigurationValueValidator
                .validateCommaSeparatedKeyValueConfigWithPositiveIntegerValues("foo10");
        assertFalse(result.first());
        assertTrue(result.second().contains("does not contain '='"));
    }

    @Test
    public void zeroOrNegativeRejected() {
        assertFalse(ConfigurationValueValidator
                .validateCommaSeparatedKeyValueConfigWithPositiveIntegerValues("foo=0").first());
        assertFalse(ConfigurationValueValidator
                .validateCommaSeparatedKeyValueConfigWithPositiveIntegerValues("foo=-5").first());
    }

    @Test
    public void nonIntegerValueRejected() {
        Pair<Boolean, String> result = ConfigurationValueValidator
                .validateCommaSeparatedKeyValueConfigWithPositiveIntegerValues("foo=bar");
        assertFalse(result.first());
        assertTrue(result.second().contains("not a valid integer"));
    }

    // ---- shouldValidateConfigRange ----

    @Test
    public void rangeValidationSkippedForNullValue() {
        Config cfg = mock(Config.class);
        when(cfg.getRange()).thenReturn("0-100");
        assertFalse(ConfigurationValueValidator.shouldValidateConfigRange("x", null, cfg));
    }

    @Test
    public void rangeValidationSkippedWhenConfigIsNull() {
        assertFalse(ConfigurationValueValidator.shouldValidateConfigRange("x", "5", null));
    }

    @Test
    public void rangeValidationSkippedWhenNoRangeDefined() {
        Config cfg = mock(Config.class);
        when(cfg.getRange()).thenReturn(null);
        assertFalse(ConfigurationValueValidator.shouldValidateConfigRange("x", "5", cfg));
    }

    @Test
    public void rangeValidationProceedsWhenAllPresent() {
        Config cfg = mock(Config.class);
        when(cfg.getRange()).thenReturn("0-100");
        assertTrue(ConfigurationValueValidator.shouldValidateConfigRange("x", "5", cfg));
    }

    // ---- validateIfIntValueIsInRange ----

    @Test
    public void intInRangeReturnsNull() {
        assertNull(ConfigurationValueValidator.validateIfIntValueIsInRange("x", "50", "0-100"));
        assertNull(ConfigurationValueValidator.validateIfIntValueIsInRange("x", "0", "0-100"));
        assertNull(ConfigurationValueValidator.validateIfIntValueIsInRange("x", "100", "0-100"));
    }

    @Test
    public void intOutOfRangeReturnsError() {
        String err = ConfigurationValueValidator.validateIfIntValueIsInRange("x", "101", "0-100");
        assertNotNull(err);
        assertTrue(err.contains("0-100"));
    }

    // ---- validateRangeHypervisorList ----

    @Test
    public void validHypervisorsAccepted() {
        assertNull(ConfigurationValueValidator.validateRangeHypervisorList("KVM"));
        assertNull(ConfigurationValueValidator.validateRangeHypervisorList("KVM,VMware"));
    }

    @Test
    public void anyOrUnknownHypervisorRejected() {
        assertNotNull(ConfigurationValueValidator.validateRangeHypervisorList("Any"));
        assertNotNull(ConfigurationValueValidator.validateRangeHypervisorList("NotAHypervisor"));
    }

    // ---- validateRangeDomainName ----

    @Test
    public void validDomainsAccepted() {
        assertNull(ConfigurationValueValidator.validateRangeDomainName("example.com"));
        assertNull(ConfigurationValueValidator.validateRangeDomainName("sub.example.com"));
        assertNull(ConfigurationValueValidator.validateRangeDomainName("*.example.com"));
    }

    @Test
    public void invalidDomainsRejected() {
        assertNotNull(ConfigurationValueValidator.validateRangeDomainName(""));
        assertNotNull(ConfigurationValueValidator.validateRangeDomainName("not a domain"));
    }

    @Test
    public void domainsAtMaxLengthRejected() {
        StringBuilder longDomain = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longDomain.append("abc.");
        }
        longDomain.append("com");
        assertNotNull(ConfigurationValueValidator.validateRangeDomainName(longDomain.toString()));
    }

    // ---- validateRangeOther ----

    @Test
    public void validRangeOtherMatchesCaseInsensitively() {
        assertNull(ConfigurationValueValidator.validateRangeOther("x", "FOO", "foo,bar,baz"));
        assertNull(ConfigurationValueValidator.validateRangeOther("x", "bar", "foo, bar, baz"));
    }

    @Test
    public void invalidRangeOtherRejected() {
        String err = ConfigurationValueValidator.validateRangeOther("x", "qux", "foo,bar,baz");
        assertNotNull(err);
        assertTrue(err.contains("foo,bar,baz"));
    }

    // ---- validateIfStringValueIsInRange dispatching ----

    @Test
    public void stringValueInRangeDispatchesToCorrectValidator() {
        // Hypervisor list: KVM accepted
        assertNull(ConfigurationValueValidator.validateIfStringValueIsInRange("x", "KVM", "hypervisorList"));
        // domainName: valid domain accepted
        assertNull(ConfigurationValueValidator.validateIfStringValueIsInRange("x", "example.com", "domainName"));
        // domainName: invalid rejected with descriptive message
        String err = ConfigurationValueValidator.validateIfStringValueIsInRange("x", "not a domain", "domainName");
        assertNotNull(err);
        assertTrue(err.contains("domain"));
    }

    @Test
    public void multipleRangeOptionsCombinedInErrorMessage() {
        // "bad name" (with space) fails instanceName; not a valid domain either
        String err = ConfigurationValueValidator
                .validateIfStringValueIsInRange("x", "bad name", "instanceName", "domainName");
        assertNotNull(err);
        assertTrue("error should combine alternatives: " + err, err.contains("NOR"));
    }

    // ---- validateRangeInstanceName ----

    @Test
    public void validInstanceNameAccepted() {
        assertNull(ConfigurationValueValidator.validateRangeInstanceName("validname"));
        assertNull(ConfigurationValueValidator.validateRangeInstanceName("instance123"));
    }

    @Test
    public void instanceNameWithHyphenRejected() {
        assertNotNull(ConfigurationValueValidator.validateRangeInstanceName("bad-name"));
    }

    @Test
    public void instanceNameWithSpaceRejected() {
        assertNotNull(ConfigurationValueValidator.validateRangeInstanceName("bad name"));
    }

    @Test
    public void instanceNameWithPlusRejected() {
        assertNotNull(ConfigurationValueValidator.validateRangeInstanceName("bad+name"));
    }

    // ---- validateRangePrivateIp ----

    @Test
    public void siteLocalIpAccepted() {
        assertNull(ConfigurationValueValidator.validateRangePrivateIp("x", "10.0.0.1"));
        assertNull(ConfigurationValueValidator.validateRangePrivateIp("x", "192.168.1.1"));
        assertNull(ConfigurationValueValidator.validateRangePrivateIp("x", "172.16.0.1"));
    }

    @Test
    public void publicIpRejected() {
        assertEquals("a valid site local IP address",
                ConfigurationValueValidator.validateRangePrivateIp("x", "8.8.8.8"));
    }

    // ---- shouldEncryptValue ----

    @Test
    public void hiddenAndSecureCategoriesShouldBeEncrypted() {
        assertTrue(ConfigurationValueValidator.shouldEncryptValue("Hidden"));
        assertTrue(ConfigurationValueValidator.shouldEncryptValue("Secure"));
    }

    @Test
    public void otherCategoriesShouldNotBeEncrypted() {
        assertFalse(ConfigurationValueValidator.shouldEncryptValue("Network"));
        assertFalse(ConfigurationValueValidator.shouldEncryptValue(""));
        assertFalse(ConfigurationValueValidator.shouldEncryptValue(null));
    }

    // ---- maskEventValueIfEncrypted ----

    @Test
    public void maskedWhenConfigIsEncrypted() {
        org.apache.cloudstack.framework.config.impl.ConfigurationVO cfg =
                mock(org.apache.cloudstack.framework.config.impl.ConfigurationVO.class);
        when(cfg.isEncrypted()).thenReturn(true);
        assertEquals("*****", ConfigurationValueValidator.maskEventValueIfEncrypted(cfg, "secret"));
    }

    @Test
    public void passthroughWhenConfigNotEncrypted() {
        org.apache.cloudstack.framework.config.impl.ConfigurationVO cfg =
                mock(org.apache.cloudstack.framework.config.impl.ConfigurationVO.class);
        when(cfg.isEncrypted()).thenReturn(false);
        assertEquals("plain", ConfigurationValueValidator.maskEventValueIfEncrypted(cfg, "plain"));
    }

    @Test
    public void nullValueBecomesEmptyString() {
        assertEquals("", ConfigurationValueValidator.maskEventValueIfEncrypted(null, null));
    }

    // ---- parseConfigurationTypeIntoString ----

    @Test
    public void nullTypeReturnsStringValueType() {
        assertEquals("String", ConfigurationValueValidator.parseConfigurationTypeIntoString(null, null));
    }

    @Test
    public void numericTypesMapToNumberOrDecimal() {
        org.apache.cloudstack.framework.config.impl.ConfigurationVO cfg =
                mock(org.apache.cloudstack.framework.config.impl.ConfigurationVO.class);
        assertEquals("Number", ConfigurationValueValidator.parseConfigurationTypeIntoString(Integer.class, cfg));
        assertEquals("Number", ConfigurationValueValidator.parseConfigurationTypeIntoString(Long.class, cfg));
        assertEquals("Number", ConfigurationValueValidator.parseConfigurationTypeIntoString(Short.class, cfg));
        assertEquals("Decimal", ConfigurationValueValidator.parseConfigurationTypeIntoString(Float.class, cfg));
        assertEquals("Decimal", ConfigurationValueValidator.parseConfigurationTypeIntoString(Double.class, cfg));
        assertEquals("Boolean", ConfigurationValueValidator.parseConfigurationTypeIntoString(Boolean.class, cfg));
    }

    @Test
    public void stringTypeUsesConfigKindWhenAvailable() {
        org.apache.cloudstack.framework.config.impl.ConfigurationVO cfg =
                mock(org.apache.cloudstack.framework.config.impl.ConfigurationVO.class);
        when(cfg.getKind()).thenReturn("WhitelistedIPs");
        assertEquals("WhitelistedIPs",
                ConfigurationValueValidator.parseConfigurationTypeIntoString(String.class, cfg));
    }

    @Test
    public void stringTypeFallsBackToStringWhenKindNull() {
        org.apache.cloudstack.framework.config.impl.ConfigurationVO cfg =
                mock(org.apache.cloudstack.framework.config.impl.ConfigurationVO.class);
        when(cfg.getKind()).thenReturn(null);
        assertEquals("String",
                ConfigurationValueValidator.parseConfigurationTypeIntoString(String.class, cfg));
    }

    // ---- isIpConfigName ----

    @Test
    public void ipConfigNameSuffixesRecognized() {
        assertTrue(ConfigurationValueValidator.isIpConfigName("foo.ip"));
        assertTrue(ConfigurationValueValidator.isIpConfigName("foo.ipaddress"));
        assertTrue(ConfigurationValueValidator.isIpConfigName("foo.iprange"));
    }

    @Test
    public void nonIpConfigNamesIgnored() {
        assertFalse(ConfigurationValueValidator.isIpConfigName("foo.bar"));
        assertFalse(ConfigurationValueValidator.isIpConfigName("foo.ip.bar"));
        assertFalse(ConfigurationValueValidator.isIpConfigName(""));
        assertFalse(ConfigurationValueValidator.isIpConfigName(null));
    }

    // ---- validateIpConfigValue ----

    @Test
    public void validIp4AcceptedForIpConfig() {
        assertNull(ConfigurationValueValidator.validateIpConfigValue("foo.ip", "192.168.1.1"));
        assertNull(ConfigurationValueValidator.validateIpConfigValue("foo.ipaddress", "10.0.0.1"));
    }

    @Test
    public void invalidIp4RejectedForIpConfig() {
        assertNotNull(ConfigurationValueValidator.validateIpConfigValue("foo.ip", "not-an-ip"));
        assertNotNull(ConfigurationValueValidator.validateIpConfigValue("foo.ipaddress", "999.999.999.999"));
    }

    @Test
    public void validIpRangeAccepted() {
        assertNull(ConfigurationValueValidator.validateIpConfigValue("foo.iprange", "10.0.0.1-10.0.0.255"));
    }

    @Test
    public void malformedIpRangeRejected() {
        assertNotNull(ConfigurationValueValidator.validateIpConfigValue("foo.iprange", "10.0.0.1"));
        assertNotNull(ConfigurationValueValidator.validateIpConfigValue("foo.iprange", "10.0.0.1-bad"));
        assertNotNull(ConfigurationValueValidator.validateIpConfigValue("foo.iprange", "1-2-3"));
    }

    @Test
    public void emptyValueSkipsValidation() {
        assertNull(ConfigurationValueValidator.validateIpConfigValue("foo.ip", ""));
    }

    @Test
    public void nonIpConfigSkipped() {
        assertNull(ConfigurationValueValidator.validateIpConfigValue("foo.bar", "garbage"));
    }

    // ---- validateConflictingConfigValue ----

    @Test
    public void kubernetesEtcdNodeStartPortConflictDetected() {
        String err = ConfigurationValueValidator.validateConflictingConfigValue(
                "cloud.kubernetes.etcd.node.start.port", "2222");
        assertNotNull(err);
        assertTrue(err.contains("reserved for Kubernetes"));
    }

    @Test
    public void nonConflictingKubernetesEtcdPortAccepted() {
        assertNull(ConfigurationValueValidator.validateConflictingConfigValue(
                "cloud.kubernetes.etcd.node.start.port", "3333"));
    }

    @Test
    public void unrelatedConfigKeyAccepted() {
        assertNull(ConfigurationValueValidator.validateConflictingConfigValue("any.other.key", "2222"));
    }

    // ---- validateCidrList ----

    @Test
    public void emptyCidrListAccepted() {
        assertNull(ConfigurationValueValidator.validateCidrList("foo", ""));
        assertNull(ConfigurationValueValidator.validateCidrList("foo", null));
    }

    @Test
    public void singleValidIpAccepted() {
        assertNull(ConfigurationValueValidator.validateCidrList("foo", "10.0.0.1"));
    }

    @Test
    public void multipleValidIpv4CidrsAccepted() {
        assertNull(ConfigurationValueValidator.validateCidrList("foo", "10.0.0.0/8,192.168.0.0/16"));
    }

    @Test
    public void invalidCidrEntryRejectedWithSpecificName() {
        String err = ConfigurationValueValidator.validateCidrList("secstorage.allowed.internal.sites", "10.0.0.0/8,bad-entry");
        assertNotNull(err);
        assertTrue("error should name the bad entry: " + err, err.contains("bad-entry"));
        assertTrue("error should name the config: " + err, err.contains("secstorage.allowed.internal.sites"));
    }

    // ---- Static validation set membership ----

    @Test
    public void positiveIntegerConfigsContainsKnownKeys() {
        assertTrue(ConfigurationValueValidator.requiresPositiveInteger("event.purge.interval"));
        assertTrue(ConfigurationValueValidator.requiresPositiveInteger("vm.password.length"));
        assertFalse(ConfigurationValueValidator.requiresPositiveInteger("arbitrary.unknown.key"));
    }

    @Test
    public void weightBasedParametersContainsCapacityThresholds() {
        assertTrue(ConfigurationValueValidator.isWeightBasedParameter("cluster.cpu.allocated.capacity.notificationthreshold"));
        assertFalse(ConfigurationValueValidator.isWeightBasedParameter("arbitrary.unknown.key"));
    }

    @Test
    public void overprovisioningFactorsAreThree() {
        assertEquals(3, ConfigurationValueValidator.OVERPROVISIONING_FACTORS.size());
        assertFalse(ConfigurationValueValidator.isOverprovisioningFactor("arbitrary.unknown.key"));
    }

    @Test
    public void restrictedToDefaultAdminContainsExpectedKeys() {
        assertFalse(ConfigurationValueValidator.isRestrictedToDefaultAdmin("arbitrary.unknown.key"));
        // Verify the set is non-empty without hard-coding fragile key names
        assertTrue(ConfigurationValueValidator.CONFIG_KEYS_ALLOWED_ONLY_FOR_DEFAULT_ADMIN.size() >= 4);
    }
}
