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
package com.cloud.network.as;

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.autoscale.CreateCounterCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListCountersCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceInUseException;
import com.cloud.network.Network;
import com.cloud.network.as.dao.ConditionDao;
import com.cloud.network.as.dao.CounterDao;
import com.cloud.utils.db.Filter;

/**
 * AutoScale counter CRUD — extracted from
 * {@link AutoScaleManagerImpl}.
 *
 * @see CounterService
 */
@Component
public class CounterServiceImpl implements CounterService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private CounterDao counterDao;
    @Inject
    private ConditionDao conditionDao;

    @Override
    public Counter createCounter(CreateCounterCmd cmd) {
        String source = cmd.getSource().toUpperCase();
        String name = cmd.getName();
        String value = cmd.getValue();
        Counter.Source src;
        // Validate Source
        try {
            src = Counter.Source.valueOf(source);
        } catch (Exception ex) {
            throw new InvalidParameterValueException("The Source " + source + " does not exist; Unable to create Counter");
        }

        // Validate Provider
        Network.Provider provider = Network.Provider.getProvider(cmd.getProvider());
        if (provider == null) {
            throw new InvalidParameterValueException("The Provider " + cmd.getProvider() + " does not exist; Unable to create Counter");
        }

        CounterVO counter = null;

        CounterVO existingCounter = counterDao.findByNameProviderValue(name, value, provider.getName());
        if (existingCounter != null) {
            throw new InvalidParameterValueException(String.format("Counter with name %s and value %s already exists. ", name, value));
        }
        logger.debug("Adding Counter " + name);
        counter = counterDao.persist(new CounterVO(src, name, value, provider));

        CallContext.current().setEventDetails(" ID: " + counter.getUuid() + " Name: " + name);
        return counter;
    }

    @Override
    public Counter getCounter(long counterId) {
        return counterDao.findById(counterId);
    }

    @Override
    public List<? extends Counter> listCounters(ListCountersCmd cmd) {
        String name = cmd.getName();
        Long id = cmd.getId();
        String source = cmd.getSource();
        if (source != null) {
            source = source.toUpperCase();
        }
        String providerStr = cmd.getProvider();
        if (providerStr != null) {
            Network.Provider provider = Network.Provider.getProvider(providerStr);
            if (provider == null) {
                throw new InvalidParameterValueException("The Provider " + providerStr + " does not exist; Unable to list Counter");
            }
            providerStr = provider.getName();
        }

        Filter searchFilter = new Filter(CounterVO.class, "created", false, cmd.getStartIndex(), cmd.getPageSizeVal());

        return counterDao.listCounters(id, name, source, providerStr, cmd.getKeyword(), searchFilter);
    }

    @Override
    public boolean deleteCounter(long counterId) throws ResourceInUseException {
        // Verify Counter id
        CounterVO counter = counterDao.findById(counterId);
        if (counter == null) {
            throw new InvalidParameterValueException("Unable to find Counter");
        }

        // Verify if it is used in any Condition

        ConditionVO condition = conditionDao.findByCounterId(counterId);
        if (condition != null) {
            logger.info("Cannot delete counter {} as it is being used in a condition.", counter);
            throw new ResourceInUseException("Counter is in use.");
        }

        boolean success = counterDao.remove(counterId);
        if (success) {
            logger.info("Successfully deleted counter: {}", counter);
        }

        return success;
    }
}
