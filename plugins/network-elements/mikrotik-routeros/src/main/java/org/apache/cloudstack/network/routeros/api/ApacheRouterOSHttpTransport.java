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
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;

/**
 * Transport backed by Apache HttpClient 4.5. The RouterOS CHR ships with a
 * self-signed certificate for www-ssl, so TLS verification is disabled; the
 * appliance is only ever addressed on infrastructure networks controlled by
 * the operator and the API port is firewalled to the management server CIDR
 * during provisioning.
 */
public class ApacheRouterOSHttpTransport implements RouterOSHttpTransport {

    private final CloseableHttpClient httpClient;
    private final String authorizationHeader;

    public ApacheRouterOSHttpTransport(final String username, final String password, final int timeoutSeconds) {
        try {
            final SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build();
            final SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);
            final RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(timeoutSeconds * 1000)
                    .setConnectionRequestTimeout(timeoutSeconds * 1000)
                    .setSocketTimeout(timeoutSeconds * 1000)
                    .build();
            httpClient = HttpClients.custom()
                    .setSSLSocketFactory(socketFactory)
                    .setDefaultRequestConfig(requestConfig)
                    .build();
        } catch (NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
            throw new RouterOSApiException("Failed to initialize TLS context for the RouterOS API client", e);
        }
        final String credentials = username + ":" + (password == null ? "" : password);
        authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Response execute(final Request request) throws IOException {
        final HttpRequestBase httpRequest = buildRequest(request);
        httpRequest.setHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
        httpRequest.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        try (CloseableHttpResponse response = httpClient.execute(httpRequest)) {
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
        httpClient.close();
    }
}
