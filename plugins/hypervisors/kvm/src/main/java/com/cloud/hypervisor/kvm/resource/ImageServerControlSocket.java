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
package com.cloud.hypervisor.kvm.resource;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Communicates with the cloudstack-image-server control socket via socat.
 */
public class ImageServerControlSocket {
    private static final Logger LOGGER = LogManager.getLogger(ImageServerControlSocket.class);
    static final String CONTROL_SOCKET_PATH = "/var/run/cloudstack/image-server.sock";
    private static final Gson GSON = new GsonBuilder().create();

    private ImageServerControlSocket() {
    }

    static JsonObject sendMessage(Map<String, Object> message) {
        String json = GSON.toJson(message);
        Script script = new Script(LibvirtComputingResource.BASH_SCRIPT_PATH, LOGGER);
        script.add("-c");
        script.add(String.format("echo '%s' | socat -t5 - UNIX-CONNECT:%s",
                json.replace("'", "'\\''"), CONTROL_SOCKET_PATH));
        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = script.execute(parser);
        if (result != null) {
            LOGGER.error("Control socket communication failed: {}", result);
            return null;
        }

        String output = parser.getLines();
        if (output == null || output.trim().isEmpty()) {
            LOGGER.error("Empty response from control socket");
            return null;
        }

        try {
            return JsonParser.parseString(output.trim()).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.error("Failed to parse control socket response: {}", output, e);
            return null;
        }
    }

    public static boolean registerTransfer(String transferId, Map<String, Object> config) {
        Map<String, Object> message = new HashMap<>();
        message.put("action", "register");
        message.put("transfer_id", transferId);
        message.put("config", config);
        JsonObject response = sendMessage(message);
        if (response == null) {
            return false;
        }
        return "ok".equals(response.has("status") ? response.get("status").getAsString() : null);
    }

    public static int unregisterTransfer(String transferId) {
        Map<String, Object> message = new HashMap<>();
        message.put("action", "unregister");
        message.put("transfer_id", transferId);
        JsonObject response = sendMessage(message);
        if (response == null) {
            return -1;
        }
        if (!"ok".equals(response.has("status") ? response.get("status").getAsString() : null)) {
            return -1;
        }
        return response.has("active_transfers") ? response.get("active_transfers").getAsInt() : -1;
    }

    public static boolean isReady() {
        Map<String, Object> message = new HashMap<>();
        message.put("action", "status");
        JsonObject response = sendMessage(message);
        if (response == null) {
            return false;
        }
        return "ok".equals(response.has("status") ? response.get("status").getAsString() : null);
    }
}
