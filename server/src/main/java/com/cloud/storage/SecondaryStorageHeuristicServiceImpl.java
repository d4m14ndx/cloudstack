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
package com.cloud.storage;

import java.util.Arrays;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.storage.heuristics.CreateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.RemoveSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.UpdateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.jsinterpreter.JsInterpreterHelper;
import org.apache.cloudstack.secstorage.HeuristicVO;
import org.apache.cloudstack.secstorage.dao.SecondaryStorageHeuristicDao;
import org.apache.cloudstack.secstorage.heuristics.Heuristic;
import org.apache.cloudstack.secstorage.heuristics.HeuristicType;
import org.apache.commons.lang3.EnumUtils;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class SecondaryStorageHeuristicServiceImpl implements SecondaryStorageHeuristicService {

    @Inject
    private SecondaryStorageHeuristicDao secondaryStorageHeuristicDao;

    @Inject
    private DataCenterDao dcDao;

    @Inject
    private JsInterpreterHelper jsInterpreterHelper;

    @Override
    public Heuristic createSecondaryStorageHeuristic(CreateSecondaryStorageSelectorCmd cmd) {
        String name = cmd.getName();
        String description = cmd.getDescription();
        long zoneId = cmd.getZoneId();
        String heuristicRule = cmd.getHeuristicRule();
        String type = cmd.getType();
        HeuristicType formattedType = EnumUtils.getEnumIgnoreCase(HeuristicType.class, type);

        if (formattedType == null) {
            throw new IllegalArgumentException(String.format("The given heuristic type [%s] is not valid for creating a new secondary storage selector." +
                    " The valid options are %s.", type, Arrays.asList(HeuristicType.values())));
        }

        HeuristicVO heuristic = secondaryStorageHeuristicDao.findByZoneIdAndType(zoneId, formattedType);

        if (heuristic != null) {
            DataCenterVO dataCenter = dcDao.findById(zoneId);
            throw new CloudRuntimeException(String.format("There is already a heuristic rule in the specified %s with the type [%s].",
                    dataCenter, type));
        }

        validateHeuristicRule(heuristicRule);

        HeuristicVO heuristicVO = new HeuristicVO(name, description, zoneId, formattedType.toString(), heuristicRule);
        return secondaryStorageHeuristicDao.persist(heuristicVO);
    }

    @Override
    public Heuristic updateSecondaryStorageHeuristic(UpdateSecondaryStorageSelectorCmd cmd) {
        long heuristicId = cmd.getId();
        String heuristicRule = cmd.getHeuristicRule();

        HeuristicVO heuristicVO = secondaryStorageHeuristicDao.findById(heuristicId);
        validateHeuristicRule(heuristicRule);
        heuristicVO.setHeuristicRule(heuristicRule);

        return secondaryStorageHeuristicDao.persist(heuristicVO);
    }

    @Override
    public void removeSecondaryStorageHeuristic(RemoveSecondaryStorageSelectorCmd cmd) {
        Long heuristicId = cmd.getId();
        HeuristicVO heuristicVO = secondaryStorageHeuristicDao.findById(heuristicId);

        if (heuristicVO != null) {
            secondaryStorageHeuristicDao.remove(heuristicId);
        } else {
            throw new CloudRuntimeException("Unable to find an active heuristic with the specified UUID.");
        }
    }

    protected void validateHeuristicRule(String heuristicRule) {
        if (StringUtils.isBlank(heuristicRule)) {
            throw new IllegalArgumentException("Unable to create a new secondary storage selector as the given heuristic rule is blank.");
        }
        jsInterpreterHelper.ensureInterpreterEnabledIfParameterProvided(ApiConstants.HEURISTIC_RULE, true);
    }
}
