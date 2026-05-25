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
package com.cloud.api.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.cloudstack.api.response.HostTagResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageTagResponse;
import org.junit.Test;

import com.cloud.utils.Pair;

public class ListResponseBuilderTest {

    @Test
    public void fromPairMapsPairItemsAndPreservesPairCount() {
        List<String> sourceItems = Arrays.asList("fast", "gpu");
        List<StorageTagResponse> mappedResponses = Arrays.asList(new StorageTagResponse(), new StorageTagResponse());
        AtomicReference<List<String>> mapperInput = new AtomicReference<>();

        ListResponse<StorageTagResponse> response = ListResponseBuilder.fromPair(new Pair<>(sourceItems, 9), items -> {
            mapperInput.set(items);
            return mappedResponses;
        });

        assertSame(sourceItems, mapperInput.get());
        assertSame(mappedResponses, response.getResponses());
        assertEquals(Integer.valueOf(9), response.getCount());
    }

    @Test
    public void fromResponsesPreservesExplicitCountWhenItDiffersFromResponseSize() {
        List<HostTagResponse> mappedResponses = Collections.singletonList(new HostTagResponse());

        ListResponse<HostTagResponse> response = ListResponseBuilder.fromResponses(mappedResponses, 3);

        assertSame(mappedResponses, response.getResponses());
        assertEquals(Integer.valueOf(3), response.getCount());
    }
}
