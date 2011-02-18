package com.cloud.api;

import com.cloud.api.response.CreateCmdResponse;
import com.cloud.exception.ResourceAllocationException;

public abstract class BaseAsyncCreateCmd extends BaseAsyncCmd {
    @Parameter(name="id", type=CommandType.LONG)
    private Long id;
    
    public abstract void create() throws ResourceAllocationException;

    public Long getEntityId() {
        return id;
    }

    public void setEntityId(Long id) {
        this.id = id;
    }

    public String getResponse(long jobId, long objectId) {
        CreateCmdResponse response = new CreateCmdResponse();
        response.setJobId(jobId);
        response.setId(objectId);
        response.setResponseName(getCommandName());
        return _responseGenerator.toSerializedString(response, getResponseType());
    }

    public String getCreateEventType() {
        return null;
    }

    public String getCreateEventDescription() {
        return null;
    }
}
