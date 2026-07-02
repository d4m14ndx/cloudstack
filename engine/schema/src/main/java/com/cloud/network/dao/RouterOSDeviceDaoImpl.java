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
package com.cloud.network.dao;

import com.cloud.network.element.RouterOSDeviceVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

public class RouterOSDeviceDaoImpl extends GenericDaoBase<RouterOSDeviceVO, Long> implements RouterOSDeviceDao {

    private final SearchBuilder<RouterOSDeviceVO> networkIdSearch;
    private final SearchBuilder<RouterOSDeviceVO> vpcIdSearch;
    private final SearchBuilder<RouterOSDeviceVO> vmInstanceIdSearch;

    public RouterOSDeviceDaoImpl() {
        networkIdSearch = createSearchBuilder();
        networkIdSearch.and("networkId", networkIdSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        networkIdSearch.done();

        vpcIdSearch = createSearchBuilder();
        vpcIdSearch.and("vpcId", vpcIdSearch.entity().getVpcId(), SearchCriteria.Op.EQ);
        vpcIdSearch.done();

        vmInstanceIdSearch = createSearchBuilder();
        vmInstanceIdSearch.and("vmInstanceId", vmInstanceIdSearch.entity().getVmInstanceId(), SearchCriteria.Op.EQ);
        vmInstanceIdSearch.done();
    }

    @Override
    public RouterOSDeviceVO findByNetworkId(final long networkId) {
        final SearchCriteria<RouterOSDeviceVO> sc = networkIdSearch.create();
        sc.setParameters("networkId", networkId);
        return findOneBy(sc);
    }

    @Override
    public RouterOSDeviceVO findByVpcId(final long vpcId) {
        final SearchCriteria<RouterOSDeviceVO> sc = vpcIdSearch.create();
        sc.setParameters("vpcId", vpcId);
        return findOneBy(sc);
    }

    @Override
    public RouterOSDeviceVO findByVmInstanceId(final long vmInstanceId) {
        final SearchCriteria<RouterOSDeviceVO> sc = vmInstanceIdSearch.create();
        sc.setParameters("vmInstanceId", vmInstanceId);
        return findOneBy(sc);
    }
}
