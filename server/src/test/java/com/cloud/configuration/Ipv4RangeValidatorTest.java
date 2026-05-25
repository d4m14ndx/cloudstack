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

import org.junit.Assert;
import org.junit.Test;

import com.cloud.exception.InvalidParameterValueException;

public class Ipv4RangeValidatorTest {
    @Test
    public void validateOptionalRangeWithinCidrAcceptsEmptyRange() {
        Ipv4RangeValidator.validateOptionalRangeWithinCidr(null, null, "10.1.1.0", 24);
    }

    @Test
    public void validateOptionalRangeWithinCidrRejectsInvalidStartIp() {
        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class,
                () -> Ipv4RangeValidator.validateOptionalRangeWithinCidr("not-an-ip", "10.1.1.20", "10.1.1.0", 24));

        Assert.assertEquals("The start address of the IP range is not a valid IP address.", exception.getMessage());
    }

    @Test
    public void validateOptionalRangeWithinCidrRejectsEndIpOutsideCidr() {
        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class,
                () -> Ipv4RangeValidator.validateOptionalRangeWithinCidr("10.1.1.10", "10.1.2.20", "10.1.1.0", 24));

        Assert.assertEquals("The end address of the IP range is not in the CIDR subnet.", exception.getMessage());
    }

    @Test
    public void validateOptionalRangeWithinCidrRejectsInvertedRange() {
        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class,
                () -> Ipv4RangeValidator.validateOptionalRangeWithinCidr("10.1.1.20", "10.1.1.10", "10.1.1.0", 24));

        Assert.assertEquals("The start IP address must have a lower value than the end IP address.", exception.getMessage());
    }

    @Test
    public void requireValidNetmaskRejectsInvalidNetmaskWithCallerMessage() {
        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class,
                () -> Ipv4RangeValidator.requireValidNetmask("255.255.7.0", "The netmask is invalid"));

        Assert.assertEquals("The netmask is invalid", exception.getMessage());
    }
}
