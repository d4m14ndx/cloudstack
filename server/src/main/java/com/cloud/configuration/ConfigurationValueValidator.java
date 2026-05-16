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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.framework.config.impl.ConfigurationVO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.Pair;
import com.cloud.utils.net.NetUtils;

/**
 * Pure validation utilities for global configuration values.
 *
 * <p>Extracted from {@link ConfigurationManagerImpl} as part of the Phase 4
 * decomposition effort. All methods here are stateless and have no Spring
 * dependencies, so they can be unit tested without bootstrapping the
 * management server context.
 *
 * <p>This class is intentionally a static utility — every method is a pure
 * function of its inputs. Methods that need access to mutable manager state
 * (validation lists, DAO calls, etc.) stay in {@link ConfigurationManagerImpl}.
 */
public final class ConfigurationValueValidator {

    private static final Logger LOG = LogManager.getLogger(ConfigurationValueValidator.class);

    /** Max length of an FQDN per <a href="https://tools.ietf.org/html/rfc1035">RFC 1035</a>, minus reserved room for routing labels. */
    public static final int MAX_DOMAIN_NAME_LENGTH = 238;
    public static final String DOMAIN_NAME_PATTERN = "^((?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.)+[A-Za-z]{1,63}$";

    /** Default starting SSH port for Kubernetes cluster node access. */
    public static final String CLUSTER_NODES_DEFAULT_START_SSH_PORT = "2222";

    /** Config key whose value must not collide with the Kubernetes cluster SSH port range. */
    public static final String KUBERNETES_ETCD_NODE_START_PORT_KEY = "cloud.kubernetes.etcd.node.start.port";

    private ConfigurationValueValidator() {
    }

    /**
     * Validates that the string value can be parsed as the given primitive
     * wrapper type. Strings and Characters are always accepted.
     *
     * @return true if the value is parseable; false otherwise
     */
    public static boolean validateValueType(String value, Class<?> type) {
        if (type == String.class || type == Character.class) {
            return true;
        }
        try {
            if (type == Boolean.class) {
                return "true".equals(value) || "false".equals(value);
            } else if (type == Integer.class) {
                Integer.parseInt(value);
            } else if (type == Long.class) {
                Long.parseLong(value);
            } else if (type == Short.class) {
                Short.parseShort(value);
            } else if (type == Float.class) {
                float floatValue = Float.parseFloat(value);
                return !Float.isInfinite(floatValue);
            } else if (type == Double.class) {
                double doubleValue = Double.parseDouble(value);
                return !Double.isInfinite(doubleValue);
            } else {
                return false;
            }
            return true;
        } catch (NullPointerException | NumberFormatException e) {
            return false;
        }
    }

    /**
     * Validates a comma-separated list of {@code key=positiveInt} pairs.
     *
     * @return ({@code true}, "") on success; ({@code false}, errorMessage) on failure
     */
    public static Pair<Boolean, String> validateCommaSeparatedKeyValueConfigWithPositiveIntegerValues(String value) {
        try {
            if (StringUtils.isNotEmpty(value)) {
                String[] commands = value.split(",");
                for (String raw : commands) {
                    String command = raw.trim();
                    if (!command.contains("=")) {
                        return new Pair<>(false, String.format("Validation failed: Command '%s' does not contain '='.", command));
                    }
                    String[] parts = command.split("=");
                    if (parts.length != 2) {
                        return new Pair<>(false, String.format("Validation failed: Command '%s' is not properly formatted.", command));
                    }
                    String commandName = parts[0].trim();
                    String valueString = parts[1].trim();
                    if (commandName.isEmpty()) {
                        return new Pair<>(false, String.format("Validation failed: Command name is missing in '%s'.", command));
                    }
                    try {
                        int num = Integer.parseInt(valueString);
                        if (num <= 0) {
                            return new Pair<>(false, String.format("Validation failed: The value for command '%s' is not greater than 0. Invalid value: %d", commandName, num));
                        }
                    } catch (NumberFormatException e) {
                        return new Pair<>(false, String.format("Validation failed: The value for command '%s' is not a valid integer. Invalid value: %s", commandName, valueString));
                    }
                }
            }
            return new Pair<>(true, "");
        } catch (Exception e) {
            return new Pair<>(false, String.format("Validation failed: An error occurred while parsing the command string. Error: %s", e.getMessage()));
        }
    }

    /**
     * Whether the given Config-backed value should have its range validated.
     * Range validation is skipped when value is null, the configuration uses
     * ConfigKey instead of Config, or no range is defined.
     */
    public static boolean shouldValidateConfigRange(String name, String value, Config configuration) {
        if (value == null) {
            LOG.debug("Not proceeding with configuration [{}]'s range validation, as its provided value is null.", name);
            return false;
        }
        if (configuration == null) {
            LOG.debug("Not proceeding with configuration [{}]'s range validation, as it uses ConfigKey instead of Config.", name);
            return false;
        }
        if (configuration.getRange() == null) {
            LOG.debug("Not proceeding with configuration [{}]'s range validation, as it does not have a specified range.", name);
            return false;
        }
        LOG.debug("Proceeding with configuration [{}]'s range validation.", name);
        return true;
    }

    /**
     * @param range a "min-max" string (e.g. "0-255")
     * @return null if the value parses as an int within range; an error message otherwise
     */
    public static String validateIfIntValueIsInRange(String name, String value, String range) {
        final String[] options = range.split("-");
        final int min = Integer.parseInt(options[0]);
        final int max = Integer.parseInt(options[1]);
        final int val = Integer.parseInt(value);
        if (val < min || val > max) {
            LOG.error("Invalid value for configuration [{}]. Please enter a value in the range [{}].", name, range);
            return String.format("The provided value is not valid for this configuration. Please enter an integer in the range: [%s]", range);
        }
        return null;
    }

    /**
     * Validates a string value against one or more named range options.
     * Returns null if the value matches any option; an error message otherwise.
     */
    public static String validateIfStringValueIsInRange(String name, String value, String... range) {
        List<String> message = new ArrayList<>();
        for (String rangeOption : range) {
            String errMessage;
            switch (rangeOption) {
                case "privateip":
                    errMessage = validateRangePrivateIp(name, value);
                    break;
                case "hypervisorList":
                    errMessage = validateRangeHypervisorList(value);
                    break;
                case "instanceName":
                    errMessage = validateRangeInstanceName(value);
                    break;
                case "domainName":
                    errMessage = validateRangeDomainName(value);
                    break;
                default:
                    errMessage = validateRangeOther(name, value, rangeOption);
            }
            if (StringUtils.isEmpty(errMessage)) {
                return null;
            }
            message.add(errMessage);
        }
        if (message.size() == 1) {
            return String.format("The provided value is not %s.", message.get(0));
        }
        return String.format("The provided value is neither %s.", String.join(" NOR ", message));
    }

    public static String validateRangePrivateIp(String name, String value) {
        try {
            if (NetUtils.isSiteLocalAddress(value)) {
                return null;
            }
            LOG.error("Value [{}] is not a valid private IP range for configuration [{}].", value, name);
        } catch (final NullPointerException e) {
            LOG.error("Error while parsing IP address for [{}].", name);
        }
        return "a valid site local IP address";
    }

    /**
     * Valid values are XenServer, KVM, VMware, Hyperv, VirtualBox, Parallels,
     * BareMetal, Simulator, LXC. Inputting "Any" or unknown returns an error.
     */
    public static String validateRangeHypervisorList(String value) {
        final String[] hypervisors = value.split(",");
        for (final String hypervisor : hypervisors) {
            HypervisorType type = HypervisorType.getType(hypervisor);
            if (type == HypervisorType.Any || type == HypervisorType.None) {
                return "a valid hypervisor type";
            }
        }
        return null;
    }

    /** Instance names may not contain hyphens, spaces, or plus signs. */
    public static String validateRangeInstanceName(String value) {
        if (NetUtils.verifyInstanceName(value)) {
            return null;
        }
        return "a valid instance name (instance names cannot contain hyphens, spaces or plus signs)";
    }

    /**
     * A leading "*." wildcard is allowed and doesn't count toward the length cap.
     * Max length leaves room for "xxx-xxx-xxx-xxx" prepended at URL build time.
     */
    public static String validateRangeDomainName(String value) {
        String domainName = value;
        if (value.startsWith("*")) {
            domainName = value.substring(2);
        }
        if (domainName.length() >= MAX_DOMAIN_NAME_LENGTH || !domainName.matches(DOMAIN_NAME_PATTERN)) {
            return "a valid domain name";
        }
        return null;
    }

    /**
     * For configurations with a comma-separated list of allowed values, returns
     * null when value matches one of the options (case-insensitively), or an
     * error message otherwise.
     */
    public static String validateRangeOther(String name, String value, String rangeOption) {
        final String[] options = rangeOption.split(",");
        for (final String option : options) {
            if (option.trim().equalsIgnoreCase(value)) {
                return null;
            }
        }
        LOG.error("Invalid value for configuration [{}].", name);
        return String.format("a valid value for this configuration (Options are: [%s])", rangeOption);
    }

    /**
     * Configuration values in these categories are encrypted at rest and masked
     * when emitted as audit events.
     */
    public static boolean shouldEncryptValue(String category) {
        return StringUtils.equalsAny(category, "Hidden", "Secure");
    }

    /**
     * Returns the masked event value if the configuration is marked encrypted;
     * otherwise returns the original value (or empty string if null).
     */
    public static String maskEventValueIfEncrypted(ConfigurationVO config, String value) {
        if (config != null && config.isEncrypted()) {
            return "*****";
        }
        return Objects.requireNonNullElse(value, "");
    }

    /**
     * Validates a comma-separated list of CIDR or IP entries. Used for configs
     * like {@code secstorage.allowed.internal.sites} where the value is a CSV
     * of allowed networks.
     *
     * @return null if all entries are valid IPv4/IPv6 addresses or clean IPv4
     *         CIDRs; an error message naming the first invalid entry otherwise
     */
    public static String validateCidrList(String configName, String value) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        for (String cidr : value.split(",")) {
            if (NetUtils.isValidIp4(cidr) || NetUtils.isValidIp6(cidr)) {
                continue;
            }
            try {
                if (NetUtils.getCleanIp4Cidr(cidr).equals(cidr)) {
                    continue;
                }
            } catch (RuntimeException ignored) {
                // Treat unparseable CIDR as invalid below.
            }
            return String.format("Invalid CIDR %s value specified for the config %s.", cidr, configName);
        }
        return null;
    }

    /**
     * Configurations whose names end with {@code .ip}, {@code .ipaddress}, or
     * {@code .iprange} should hold valid IPv4 values. Returns null if the value
     * is structurally valid for the suffix; an error message otherwise.
     *
     * <p>Callers must first verify the config exists and is of String type —
     * this helper is purely about the value's shape.
     */
    public static String validateIpConfigValue(String configName, String value) {
        if (!isIpConfigName(configName)) {
            return null;
        }
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        boolean valid;
        if (configName.endsWith(".iprange")) {
            valid = false;
            if (value.contains("-")) {
                String[] ips = value.split("-");
                if (ips.length == 2 && NetUtils.isValidIp4(ips[0]) && NetUtils.isValidIp4(ips[1])) {
                    valid = true;
                }
            }
        } else {
            valid = NetUtils.isValidIp4(value);
        }
        return valid ? null : "Invalid IP address value(s) specified for the config value.";
    }

    /**
     * @return true if the config name's suffix indicates it should hold an IP address or range
     */
    public static boolean isIpConfigName(String configName) {
        return configName != null
                && (configName.endsWith(".ip") || configName.endsWith(".ipaddress") || configName.endsWith(".iprange"));
    }

    /**
     * Validates that the value doesn't conflict with reserved ports/ranges.
     * Returns null if no conflict; an error message otherwise.
     */
    public static String validateConflictingConfigValue(String configName, String value) {
        if (KUBERNETES_ETCD_NODE_START_PORT_KEY.equals(configName)
                && CLUSTER_NODES_DEFAULT_START_SSH_PORT.equals(value)) {
            return "This range is reserved for Kubernetes cluster nodes."
                    + "Please choose a value in a higher range would does not conflict with a kubernetes cluster deployed";
        }
        return null;
    }

    /**
     * Maps a Java wrapper class to the configuration value-type string used in
     * the API ({@link Configuration.ValueType}). When the type is a String/Char,
     * defers to the configuration's {@code kind} if one is set.
     */
    public static String parseConfigurationTypeIntoString(Class<?> type, ConfigurationVO cfg) {
        if (type == null) {
            return Configuration.ValueType.String.name();
        }
        if (type == String.class || type == Character.class) {
            if (cfg.getKind() == null) {
                return Configuration.ValueType.String.name();
            }
            return cfg.getKind();
        }
        if (type == Integer.class || type == Long.class || type == Short.class) {
            return Configuration.ValueType.Number.name();
        }
        if (type == Float.class || type == Double.class) {
            return Configuration.ValueType.Decimal.name();
        }
        if (type == Boolean.class) {
            return Configuration.ValueType.Boolean.name();
        }
        return Configuration.ValueType.String.name();
    }
}
