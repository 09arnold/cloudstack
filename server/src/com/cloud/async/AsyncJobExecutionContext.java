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
package com.cloud.async;

import javax.inject.Inject;

public class AsyncJobExecutionContext  {
	private AsyncJob _job;
	
	@Inject private  AsyncJobManager _jobMgr; 
	
	private static ThreadLocal<AsyncJobExecutionContext> s_currentExectionContext = new ThreadLocal<AsyncJobExecutionContext>();

	public AsyncJobExecutionContext() {
	}
	
	public AsyncJobExecutionContext(AsyncJob job) {
		_job = job;
	}
	
	public SyncQueueItem getSyncSource() {
		return _job.getSyncSource();
	}
	
	public AsyncJob getJob() {
		return _job;
	}
	
	public void setJob(AsyncJob job) {
		_job = job;
	}
	
    public void completeAsyncJob(int jobStatus, int resultCode, Object resultObject) {
    	assert(_job != null);
    	_jobMgr.completeAsyncJob(_job.getId(), jobStatus, resultCode, resultObject);
    }
    
    public void updateAsyncJobStatus(int processStatus, Object resultObject) {
    	assert(_job != null);
    	_jobMgr.updateAsyncJobStatus(_job.getId(), processStatus, resultObject);
    }
    
    public void updateAsyncJobAttachment(String instanceType, Long instanceId) {
    	assert(_job != null);
    	_jobMgr.updateAsyncJobAttachment(_job.getId(), instanceType, instanceId);
    }
	
	public void logJobJournal(AsyncJob.JournalType journalType, String 
	    journalText, String journalObjJson) {
		assert(_job != null);
		_jobMgr.logJobJournal(_job.getId(), journalType, journalText, journalObjJson);
	}
	
	public static AsyncJobExecutionContext getCurrentExecutionContext() {
		return s_currentExectionContext.get();
	}
	
	public static void setCurrentExecutionContext(AsyncJobExecutionContext currentContext) {
		s_currentExectionContext.set(currentContext);
	}
}
