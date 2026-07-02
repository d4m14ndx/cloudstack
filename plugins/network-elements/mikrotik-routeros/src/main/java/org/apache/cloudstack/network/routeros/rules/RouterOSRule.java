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
package org.apache.cloudstack.network.routeros.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One object to write to the RouterOS REST API: a collection path (for example
 * {@code ip/firewall/filter}) plus its parameter map. The parameter map always
 * carries a {@code comment} tag ({@code cs-<kind>-<uuid>}) identifying the
 * CloudStack entity that owns the object.
 */
public class RouterOSRule {

    public static final String PLACE_BEFORE = "place-before";

    private final String path;
    private final Map<String, String> params;

    public RouterOSRule(final String path, final Map<String, String> params) {
        this.path = path;
        this.params = new LinkedHashMap<>(params);
    }

    public String getPath() {
        return path;
    }

    public Map<String, String> getParams() {
        return Collections.unmodifiableMap(params);
    }

    public String getComment() {
        return params.get("comment");
    }

    public String getParam(final String key) {
        return params.get(key);
    }

    /**
     * @return a copy of this rule with the RouterOS {@code place-before}
     * positional hint set, used to keep ACL entries ordered ahead of the
     * default-drop rule.
     */
    public RouterOSRule withPlaceBefore(final String ruleId) {
        final Map<String, String> copy = new LinkedHashMap<>(params);
        copy.put(PLACE_BEFORE, ruleId);
        return new RouterOSRule(path, copy);
    }

    @Override
    public String toString() {
        return path + " " + params;
    }
}
