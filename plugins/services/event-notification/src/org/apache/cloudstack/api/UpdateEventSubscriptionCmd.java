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

package org.apache.cloudstack.api;

import com.cloud.api.BaseAsyncCmd;
import com.cloud.api.Implementation;
import com.cloud.api.ServerApiException;
import com.cloud.exception.*;

@Implementation(description="Deletes a subscriber for the events", responseObject=EventSubscriptionResponse.class)
public class UpdateEventSubscriptionCmd extends BaseAsyncCmd {

    @Override
    public void execute() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException, ConcurrentOperationException, ResourceAllocationException, NetworkRuleConflictException {

    }

    @Override
    public String getCommandName() {
        return null;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return null;
    }

    @Override
    public String getEventDescription() {
        return null;
    }
}