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
package org.apache.cloudstack.api.user.vm.command;

import org.apache.log4j.Logger;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.IdentityMapper;
import org.apache.cloudstack.api.Implementation;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import com.cloud.api.response.UserVmResponse;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.ServiceOffering;
import com.cloud.user.Account;
import com.cloud.user.UserContext;
import com.cloud.uservm.UserVm;

@Implementation(responseObject=UserVmResponse.class, description="Changes the service offering for a virtual machine. " +
                                                                                            "The virtual machine must be in a \"Stopped\" state for " +
                                                                                            "this command to take effect.")
public class UpgradeVMCmd extends BaseCmd {
    public static final Logger s_logger = Logger.getLogger(UpgradeVMCmd.class.getName());
    private static final String s_name = "changeserviceforvirtualmachineresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @IdentityMapper(entityTableName="vm_instance")
    @Parameter(name=ApiConstants.ID, type=CommandType.LONG, required=true, description="The ID of the virtual machine")
    private Long id;

    @IdentityMapper(entityTableName="disk_offering")
    @Parameter(name=ApiConstants.SERVICE_OFFERING_ID, type=CommandType.LONG, required=true, description="the service offering ID to apply to the virtual machine")
    private Long serviceOfferingId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }

    public static String getResultObjectName() {
        return "virtualmachine";
    }

    @Override
    public long getEntityOwnerId() {
        UserVm userVm = _entityMgr.findById(UserVm.class, getId());
        if (userVm != null) {
            return userVm.getAccountId();
        }

        return Account.ACCOUNT_ID_SYSTEM; // no account info given, parent this command to SYSTEM so ERROR events are tracked
    }

    @Override
    public void execute(){
        UserContext.current().setEventDetails("Vm Id: "+getId());

        ServiceOffering serviceOffering = _configService.getServiceOffering(serviceOfferingId);
        if (serviceOffering == null) {
            throw new InvalidParameterValueException("Unable to find service offering: " + serviceOfferingId);
        }

        UserVm result = _userVmService.upgradeVirtualMachine(this);
        if (result != null){
            UserVmResponse response = _responseGenerator.createUserVmResponse("virtualmachine", result).get(0);
            response.setResponseName(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(BaseCmd.INTERNAL_ERROR, "Failed to upgrade vm");
        }
    }
}
