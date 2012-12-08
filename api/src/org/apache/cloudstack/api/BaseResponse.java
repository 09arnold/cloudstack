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

import org.apache.cloudstack.api.ApiConstants;
import com.cloud.utils.IdentityProxy;
import org.apache.cloudstack.api.ResponseObject;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public abstract class BaseResponse implements ResponseObject {
    private transient String responseName;
    private transient String objectName;

    @Override
    public String getResponseName() {
        return responseName;
    }

    @Override
    public void setResponseName(String responseName) {
        this.responseName = responseName;
    }

    @Override
    public String getObjectName() {
        return objectName;
    }

    @Override
    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    //TODO: TO be replaced by getObjectUuid() after all response refactoring
    public Long getObjectId() {
        return null;
    }

    public String getObjectUuid(){
        return null;
    }

    // For use by list commands with pending async jobs
    //TODO: To be replaced by jobUuid after all response refactoring
    protected IdentityProxy jobId = new IdentityProxy("async_job");

    @SerializedName(ApiConstants.JOB_ID) @Param(description="the UUID of the latest async job acting on this object")
    protected String jobUuid;

    @SerializedName(ApiConstants.JOB_STATUS) @Param(description="the current status of the latest async job acting on this object")
    private Integer jobStatus;

    //TODO: TO be replaced by getter and setters for jobUuid.
    public Long getJobId() {
        return jobId.getValue();
    }

    public void setJobId(Long jobId) {
        this.jobId.setValue(jobId);
    }

    public String getJobUuid() {
        return jobUuid;
    }

    public void setJobUuid(String jobUuid) {
        this.jobUuid = jobUuid;
    }

    public Integer getJobStatus() {
        return jobStatus;
    }

    public void setJobStatus(Integer jobStatus) {
        this.jobStatus = jobStatus;
    }
}
