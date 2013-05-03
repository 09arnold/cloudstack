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
package com.cloud.vm;

import javax.persistence.Column;
import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.PrimaryKeyJoinColumn;
import javax.persistence.Table;

import com.cloud.async.AsyncJobVO;

@Entity
@Table(name="vm_work_job")
@DiscriminatorValue(value="VmWork")
@PrimaryKeyJoinColumn(name="id")
public class VmWorkJobVO extends AsyncJobVO {

	public enum Step {
        Prepare,
        Starting,
        Started,
        Release,
        Done,
        Migrating,
        Reconfiguring
    }
	
    @Column(name="step")
    Step step;
    
    @Column(name="vm_type")
    @Enumerated(value=EnumType.STRING)
    VirtualMachine.Type vmType;

    @Column(name="vm_instance_id")
    long vmInstanceId;

    public VmWorkJobVO() {
    }
    
    public Step getStep() {
    	return step;
    }
    
    public void setStep(Step step) {
    	this.step = step;
    }
    
    public VirtualMachine.Type getVmType() {
    	return vmType;
    }
    
    public void setVmType(VirtualMachine.Type vmType) {
    	this.vmType = vmType;
    }
    
    public long getVmInstanceId() {
    	return vmInstanceId;
    }
    
    public void setVmInstanceId(long vmInstanceId) {
    	this.vmInstanceId = vmInstanceId;
    }
}
