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
package org.apache.cloudstack.network.routeros.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;

/**
 * Transport backed by Apache HttpClient 4.5. The RouterOS CHR ships with a
 * self-signed certificate for www-ssl, so TLS verification is disabled; the
 * appliance is only ever addressed on infrastructure networks controlled by
 * the operator and the API port is firewalled to the management server CIDR
 * during provisioning.
 *
 * A single pooled {@link CloseableHttpClient} is shared across all transport
 * instances (and therefore all appliances) for the lifetime of the JVM: a new
 * transport is created for every {@code createApiClient} call and the poll loop
 * during provisioning builds several per iteration, so a per-instance client
 * (never closed) previously leaked connections/threads. Only the per-request
 * authorization header and timeout differ between callers, so both are applied
 * per request rather than per client.
 */
public class ApacheRouterOSHttpTransport implements RouterOSHttpTransport {

    private static volatile CloseableHttpClient sharedClient;

    private final String authorizationHeader;
    private final int timeoutSeconds;

    public ApacheRouterOSHttpTransport(final String username, final String password, final int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        // Initialize the shared client on first use.
        sharedClient();
        final String credentials = username + ":" + (password == null ? "" : password);
        authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static CloseableHttpClient sharedClient() {
        CloseableHttpClient client = sharedClient;
        if (client == null) {
            synchronized (ApacheRouterOSHttpTransport.class) {
                client = sharedClient;
                if (client == null) {
                    try {
                        final SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build();
                        final SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);
                        // The socket factory must ride in the connection manager's registry: a custom
                        // connection manager makes HttpClientBuilder ignore setSSLSocketFactory().
                        final Registry<ConnectionSocketFactory> socketFactories = RegistryBuilder.<ConnectionSocketFactory>create()
                                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                                .register("https", socketFactory)
                                .build();
                        final PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(socketFactories);
                        connectionManager.setMaxTotal(50);
                        connectionManager.setDefaultMaxPerRoute(10);
                        client = HttpClients.custom()
                                .setConnectionManager(connectionManager)
                                .build();
                        sharedClient = client;
                    } catch (NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
                        throw new RouterOSApiException("Failed to initialize TLS context for the RouterOS API client", e);
                    }
                }
            }
        }
        return client;
    }

    @Override
    public Response execute(final Request request) throws IOException {
        final HttpRequestBase httpRequest = buildRequest(request);
        httpRequest.setHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
        httpRequest.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        httpRequest.setConfig(RequestConfig.custom()
                .setConnectTimeout(timeoutSeconds * 1000)
                .setConnectionRequestTimeout(timeoutSeconds * 1000)
                .setSocketTimeout(timeoutSeconds * 1000)
                .build());
        try (CloseableHttpResponse response = sharedClient().execute(httpRequest)) {
            final int status = response.getStatusLine().getStatusCode();
            final String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            return new Response(status, body);
        }
    }

    private HttpRequestBase buildRequest(final Request request) {
        final HttpRequestBase httpRequest;
        switch (request.getMethod()) {
            case "GET":
                httpRequest = new HttpGet(request.getUrl());
                break;
            case "PUT":
                httpRequest = new HttpPut(request.getUrl());
                break;
            case "POST":
                httpRequest = new HttpPost(request.getUrl());
                break;
            case "PATCH":
                httpRequest = new HttpPatch(request.getUrl());
                break;
            case "DELETE":
                httpRequest = new HttpDelete(request.getUrl());
                break;
            default:
                throw new RouterOSApiException("Unsupported HTTP method for the RouterOS API: " + request.getMethod());
        }
        if (request.getBody() != null && httpRequest instanceof HttpEntityEnclosingRequestBase) {
            ((HttpEntityEnclosingRequestBase)httpRequest).setEntity(new StringEntity(request.getBody(), ContentType.APPLICATION_JSON));
        }
        return httpRequest;
    }

    @Override
    public void close() throws IOException {
        // The HTTP client is shared across all transports/appliances for the JVM
        // lifetime (see sharedClient()); an individual transport must not close it.
        // Its pooling connection manager reclaims idle connections on its own.
    }
}
