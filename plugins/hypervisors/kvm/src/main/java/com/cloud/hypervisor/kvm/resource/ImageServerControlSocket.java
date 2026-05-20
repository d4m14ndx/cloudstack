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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.SocketTimeoutException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Communicates with the cloudstack-image-server Unix domain control socket.
 */
public class ImageServerControlSocket {
    private static final Logger LOGGER = LogManager.getLogger(ImageServerControlSocket.class);
    private static final int CONTROL_SOCKET_TIMEOUT_MILLIS = 5000;
    private static final Gson GSON = new GsonBuilder().create();

    private ImageServerControlSocket() {
    }

    static JsonObject sendMessage(String socketPath, Map<String, Object> message) {
        String output;
        try {
            output = sendJson(socketPath, GSON.toJson(message));
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Control socket communication failed for socket [{}].", socketPath, e);
            return null;
        }
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

    static String sendJson(String socketPath, String json) throws IOException {
        UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
             Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONTROL_SOCKET_TIMEOUT_MILLIS);

            if (!channel.connect(socketAddress)) {
                waitFor(channel, selector, SelectionKey.OP_CONNECT, deadlineNanos);
                channel.finishConnect();
            }

            ByteBuffer request = ByteBuffer.wrap((json + "\n").getBytes(StandardCharsets.UTF_8));
            while (request.hasRemaining()) {
                if (channel.write(request) == 0) {
                    waitFor(channel, selector, SelectionKey.OP_WRITE, deadlineNanos);
                }
            }
            channel.shutdownOutput();

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            while (true) {
                int read = channel.read(buffer);
                if (read == -1) {
                    return response.toString(StandardCharsets.UTF_8);
                }
                if (read == 0) {
                    waitFor(channel, selector, SelectionKey.OP_READ, deadlineNanos);
                    continue;
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    byte current = buffer.get();
                    if (current == '\n') {
                        return response.toString(StandardCharsets.UTF_8);
                    }
                    response.write(current);
                }
                buffer.clear();
            }
        }
    }

    private static void waitFor(SocketChannel channel, Selector selector, int operation, long deadlineNanos) throws IOException {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMillis <= 0) {
            throw new SocketTimeoutException("Timed out communicating with image server control socket");
        }

        SelectionKey key = channel.keyFor(selector);
        if (key == null) {
            channel.register(selector, operation);
        } else {
            key.interestOps(operation);
        }

        if (selector.select(remainingMillis) == 0) {
            throw new SocketTimeoutException("Timed out communicating with image server control socket");
        }
        selector.selectedKeys().clear();
    }

    public static boolean registerTransfer(String socketPath, String transferId, Map<String, Object> config) {
        Map<String, Object> message = new HashMap<>();
        message.put("action", "register");
        message.put("transfer_id", transferId);
        message.put("config", config);
        JsonObject response = sendMessage(socketPath, message);
        if (response == null) {
            return false;
        }
        return "ok".equals(response.has("status") ? response.get("status").getAsString() : null);
    }

    public static int unregisterTransfer(String socketPath, String transferId) {
        Map<String, Object> message = new HashMap<>();
        message.put("action", "unregister");
        message.put("transfer_id", transferId);
        JsonObject response = sendMessage(socketPath, message);
        if (response == null) {
            return -1;
        }
        if (!"ok".equals(response.has("status") ? response.get("status").getAsString() : null)) {
            return -1;
        }
        return response.has("active_transfers") ? response.get("active_transfers").getAsInt() : -1;
    }

    public static boolean isReady(String socketPath) {
        Map<String, Object> message = new HashMap<>();
        message.put("action", "status");
        JsonObject response = sendMessage(socketPath, message);
        if (response == null) {
            return false;
        }
        return "ok".equals(response.has("status") ? response.get("status").getAsString() : null);
    }
}
