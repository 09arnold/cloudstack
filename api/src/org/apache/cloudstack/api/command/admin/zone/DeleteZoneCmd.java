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
package org.apache.cloudstack.api.command.admin.zone;

import org.apache.log4j.Logger;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Implementation;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import com.cloud.api.response.SuccessResponse;
import com.cloud.api.response.ZoneResponse;
import com.cloud.user.Account;
import com.cloud.user.UserContext;

@Implementation(description="Deletes a Zone.", responseObject=SuccessResponse.class)
public class DeleteZoneCmd extends BaseCmd {
    public static final Logger s_logger = Logger.getLogger(DeleteZoneCmd.class.getName());

    private static final String s_name = "deletezoneresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    //@IdentityMapper(entityTableName="data_center")
    @Parameter(name=ApiConstants.ID, type=CommandType.LONG, required=true, description="the ID of the Zone", entityType=ZoneResponse.class)
    private Long id;


    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }


    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute(){
        UserContext.current().setEventDetails("Zone Id: "+getId());
        boolean result = _configService.deleteZone(this);
        if (result) {
            SuccessResponse response = new SuccessResponse(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(BaseCmd.INTERNAL_ERROR, "Failed to delete zone");
        }
    }
}
