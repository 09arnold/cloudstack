// Licensed to the Apacohe Software Foundation (ASF) under one
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

import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.ejb.Local;
import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.framework.jobs.impl.OutcomeImpl;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.MessageDispatcher;
import org.apache.cloudstack.framework.messagebus.MessageHandler;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.identity.ManagementServerNode;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.ClusterVMMetaDataSyncAnswer;
import com.cloud.agent.api.ClusterVMMetaDataSyncCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.PingRoutingCommand;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.RebootAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.ScaleVmCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.StopAnswer;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.GPUDeviceTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.agent.manager.allocator.HostAllocator;
import com.cloud.alert.AlertManager;
import com.cloud.capacity.CapacityManager;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Pod;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.AffinityConflictException;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ConnectionException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.gpu.dao.VGPUTypesDao;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.ha.HighAvailabilityManager.WorkType;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.offering.DiskOfferingInfo;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Cluster;
import com.cloud.resource.ResourceManager;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.Volume.Type;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.DateUtil;
import com.cloud.utils.Journal;
import com.cloud.utils.Pair;
import com.cloud.utils.Predicate;
import com.cloud.utils.ReflectionUse;
import com.cloud.utils.StringUtils;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExecutionException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.ItWorkVO.Step;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.VirtualMachine.PowerState;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.UserVmDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotManager;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@Local(value = VirtualMachineManager.class)
public class VirtualMachineManagerImpl extends ManagerBase implements VirtualMachineManager, VmWorkJobHandler, Listener, Configurable {
    private static final Logger s_logger = Logger.getLogger(VirtualMachineManagerImpl.class);

    public static final String VM_WORK_JOB_HANDLER = VirtualMachineManagerImpl.class.getSimpleName();

    private static final String VM_SYNC_ALERT_SUBJECT = "VM state sync alert";

    @Inject
    DataStoreManager dataStoreMgr;
    @Inject
    protected NetworkOrchestrationService _networkMgr;
    @Inject
    protected NetworkModel _networkModel;
    @Inject
    protected AgentManager _agentMgr;
    @Inject
    protected VMInstanceDao _vmDao;
    @Inject
    protected ServiceOfferingDao _offeringDao;
    @Inject
    protected DiskOfferingDao _diskOfferingDao;
    @Inject
    protected VMTemplateDao _templateDao;
    @Inject
    protected DomainDao _domainDao;
    @Inject
    protected ItWorkDao _workDao;
    @Inject
    protected UserVmDao _userVmDao;
    @Inject
    protected CapacityManager _capacityMgr;
    @Inject
    protected NicDao _nicsDao;
    @Inject
    protected HostDao _hostDao;
    @Inject
    protected AlertManager _alertMgr;
    @Inject
    protected GuestOSCategoryDao _guestOsCategoryDao;
    @Inject
    protected GuestOSDao _guestOsDao;
    @Inject
    protected VolumeDao _volsDao;
    @Inject
    protected HighAvailabilityManager _haMgr;
    @Inject
    protected HostPodDao _podDao;
    @Inject
    protected DataCenterDao _dcDao;
    @Inject
    protected ClusterDao _clusterDao;
    @Inject
    protected PrimaryDataStoreDao _storagePoolDao;
    @Inject
    protected HypervisorGuruManager _hvGuruMgr;
    @Inject
    protected NetworkDao _networkDao;
    @Inject
    protected StoragePoolHostDao _poolHostDao;
    @Inject
    protected VMSnapshotDao _vmSnapshotDao;
    @Inject
    protected RulesManager rulesMgr;
    @Inject
    protected AffinityGroupVMMapDao _affinityGroupVMMapDao;
    @Inject
    protected VGPUTypesDao _vgpuTypesDao;
    @Inject
    protected EntityManager _entityMgr;

    @Inject
    ConfigDepot _configDepot;

    protected List<HostAllocator> hostAllocators;

    public List<HostAllocator> getHostAllocators() {
        return hostAllocators;
    }

    public void setHostAllocators(List<HostAllocator> hostAllocators) {
        this.hostAllocators = hostAllocators;
    }

    protected List<StoragePoolAllocator> _storagePoolAllocators;

    @Inject
    protected ResourceManager _resourceMgr;

    @Inject
    protected VMSnapshotManager _vmSnapshotMgr = null;
    @Inject
    protected ClusterDetailsDao _clusterDetailsDao;
    @Inject
    protected UserVmDetailsDao _uservmDetailsDao;

    @Inject
    protected ConfigurationDao _configDao;
    @Inject
    VolumeOrchestrationService volumeMgr;

    @Inject
    DeploymentPlanningManager _dpMgr;

    @Inject
    protected MessageBus _messageBus;
    @Inject
    protected VirtualMachinePowerStateSync _syncMgr;
    @Inject
    protected VmWorkJobDao _workJobDao;
    @Inject
    protected AsyncJobManager _jobMgr;

    VmWorkJobHandlerProxy _jobHandlerProxy = new VmWorkJobHandlerProxy(this);

    Map<VirtualMachine.Type, VirtualMachineGuru> _vmGurus = new HashMap<VirtualMachine.Type, VirtualMachineGuru>();
    protected StateMachine2<State, VirtualMachine.Event, VirtualMachine> _stateMachine;

    static final ConfigKey<Integer> StartRetry = new ConfigKey<Integer>("Advanced", Integer.class, "start.retry", "10",
            "Number of times to retry create and start commands", true);
    static final ConfigKey<Integer> VmOpWaitInterval = new ConfigKey<Integer>("Advanced", Integer.class, "vm.op.wait.interval", "120",
            "Time (in seconds) to wait before checking if a previous operation has succeeded", true);

    static final ConfigKey<Integer> VmOpLockStateRetry = new ConfigKey<Integer>("Advanced", Integer.class, "vm.op.lock.state.retry", "5",
            "Times to retry locking the state of a VM for operations, -1 means forever", true);
    static final ConfigKey<Long> VmOpCleanupInterval = new ConfigKey<Long>("Advanced", Long.class, "vm.op.cleanup.interval", "86400",
            "Interval to run the thread that cleans up the vm operations (in seconds)", false);
    static final ConfigKey<Long> VmOpCleanupWait = new ConfigKey<Long>("Advanced", Long.class, "vm.op.cleanup.wait", "3600",
            "Time (in seconds) to wait before cleanuping up any vm work items", true);
    static final ConfigKey<Long> VmOpCancelInterval = new ConfigKey<Long>("Advanced", Long.class, "vm.op.cancel.interval", "3600",
            "Time (in seconds) to wait before cancelling a operation", false);
    static final ConfigKey<Boolean> VmDestroyForcestop = new ConfigKey<Boolean>("Advanced", Boolean.class, "vm.destroy.forcestop", "false",
            "On destroy, force-stop takes this value ", true);
    static final ConfigKey<Integer> ClusterDeltaSyncInterval = new ConfigKey<Integer>("Advanced", Integer.class, "sync.interval", "60",
            "Cluster Delta sync interval in seconds",
            false);
    static final ConfigKey<Integer> ClusterVMMetaDataSyncInterval = new ConfigKey<Integer>("Advanced", Integer.class, "vmmetadata.sync.interval", "180", "Cluster VM metadata sync interval in seconds",
            false);

    static final ConfigKey<Long> VmJobCheckInterval = new ConfigKey<Long>("Advanced",
            Long.class, "vm.job.check.interval", "3000",
            "Interval in milliseconds to check if the job is complete", false);
    static final ConfigKey<Long> VmJobTimeout = new ConfigKey<Long>("Advanced",
            Long.class, "vm.job.timeout", "600000",
            "Time in milliseconds to wait before attempting to cancel a job", false);
    static final ConfigKey<Integer> VmJobStateReportInterval = new ConfigKey<Integer>("Advanced",
            Integer.class, "vm.job.report.interval", "60",
            "Interval to send application level pings to make sure the connection is still working", false);

    ScheduledExecutorService _executor = null;

    protected long _nodeId;

    @Override
    public void registerGuru(VirtualMachine.Type type, VirtualMachineGuru guru) {
        synchronized (_vmGurus) {
            _vmGurus.put(type, guru);
        }
    }

    @Override
    @DB
    public void allocate(String vmInstanceName, final VirtualMachineTemplate template, ServiceOffering serviceOffering,
            final DiskOfferingInfo rootDiskOfferingInfo, final List<DiskOfferingInfo> dataDiskOfferings,
            final LinkedHashMap<? extends Network, List<? extends NicProfile>> auxiliaryNetworks, DeploymentPlan plan, HypervisorType hyperType)
                    throws InsufficientCapacityException {

        VMInstanceVO vm = _vmDao.findVMByInstanceName(vmInstanceName);
        final Account owner = _entityMgr.findById(Account.class, vm.getAccountId());

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Allocating entries for VM: " + vm);
        }

        vm.setDataCenterId(plan.getDataCenterId());
        if (plan.getPodId() != null) {
            vm.setPodIdToDeployIn(plan.getPodId());
        }
        assert (plan.getClusterId() == null && plan.getPoolId() == null) : "We currently don't support cluster and pool preset yet";
        final VMInstanceVO vmFinal = _vmDao.persist(vm);

                final VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmFinal, template, serviceOffering, null, null);

                Transaction.execute(new TransactionCallbackWithExceptionNoReturn<InsufficientCapacityException>() {
                    @Override
                    public void doInTransactionWithoutResult(TransactionStatus status) throws InsufficientCapacityException {
                        if (s_logger.isDebugEnabled()) {
                            s_logger.debug("Allocating nics for " + vmFinal);
                        }

                        try {
                            _networkMgr.allocate(vmProfile, auxiliaryNetworks);
                        } catch (ConcurrentOperationException e) {
                            throw new CloudRuntimeException("Concurrent operation while trying to allocate resources for the VM", e);
                        }

                        if (s_logger.isDebugEnabled()) {
                            s_logger.debug("Allocating disks for " + vmFinal);
                        }

                        if (template.getFormat() == ImageFormat.ISO) {
                            volumeMgr.allocateRawVolume(Type.ROOT, "ROOT-" + vmFinal.getId(), rootDiskOfferingInfo.getDiskOffering(), rootDiskOfferingInfo.getSize(),
                                    rootDiskOfferingInfo.getMinIops(), rootDiskOfferingInfo.getMaxIops(), vmFinal, template, owner);
                        } else if (template.getFormat() == ImageFormat.BAREMETAL) {
                            // Do nothing
                        } else {
                            volumeMgr.allocateTemplatedVolume(Type.ROOT, "ROOT-" + vmFinal.getId(), rootDiskOfferingInfo.getDiskOffering(), rootDiskOfferingInfo.getSize(),
                                    rootDiskOfferingInfo.getMinIops(), rootDiskOfferingInfo.getMaxIops(), template, vmFinal, owner);
                        }

                        if (dataDiskOfferings != null) {
                            for (DiskOfferingInfo dataDiskOfferingInfo : dataDiskOfferings) {
                                volumeMgr.allocateRawVolume(Type.DATADISK, "DATA-" + vmFinal.getId(), dataDiskOfferingInfo.getDiskOffering(), dataDiskOfferingInfo.getSize(),
                                        dataDiskOfferingInfo.getMinIops(), dataDiskOfferingInfo.getMaxIops(), vmFinal, template, owner);
                            }
                        }
                    }
                });

                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Allocation completed for VM: " + vmFinal);
                }
    }

    @Override
    public void allocate(String vmInstanceName, VirtualMachineTemplate template, ServiceOffering serviceOffering,
            LinkedHashMap<? extends Network, List<? extends NicProfile>> networks, DeploymentPlan plan, HypervisorType hyperType) throws InsufficientCapacityException {
        allocate(vmInstanceName, template, serviceOffering, new DiskOfferingInfo(serviceOffering), new ArrayList<DiskOfferingInfo>(), networks, plan, hyperType);
    }

    private VirtualMachineGuru getVmGuru(VirtualMachine vm) {
        return _vmGurus.get(vm.getType());
    }

    @Override
    public void expunge(String vmUuid) throws ResourceUnavailableException {
        try {
            advanceExpunge(vmUuid);
        } catch (OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation timed out", e);
        } catch (ConcurrentOperationException e) {
            throw new CloudRuntimeException("Concurrent operation ", e);
        }
    }

    @Override
    public void advanceExpunge(String vmUuid) throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        advanceExpunge(vm);
    }

    protected void advanceExpunge(VMInstanceVO vm) throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        if (vm == null || vm.getRemoved() != null) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Unable to find vm or vm is destroyed: " + vm);
            }
            return;
        }

        advanceStop(vm.getUuid(), false);
        vm = _vmDao.findByUuid(vm.getUuid());

        try {
            if (!stateTransitTo(vm, VirtualMachine.Event.ExpungeOperation, vm.getHostId())) {
                s_logger.debug("Unable to destroy the vm because it is not in the correct state: " + vm);
                throw new CloudRuntimeException("Unable to destroy " + vm);

            }
        } catch (NoTransitionException e) {
            s_logger.debug("Unable to destroy the vm because it is not in the correct state: " + vm);
            throw new CloudRuntimeException("Unable to destroy " + vm, e);
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Destroying vm " + vm);
        }

        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);

        HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vm.getHypervisorType());

        s_logger.debug("Cleaning up NICS");
        List<Command> nicExpungeCommands = hvGuru.finalizeExpungeNics(vm, profile.getNics());
        _networkMgr.cleanupNics(profile);

        s_logger.debug("Cleaning up hypervisor data structures (ex. SRs in XenServer) for managed storage");

        List<Command> volumeExpungeCommands = hvGuru.finalizeExpungeVolumes(vm);

        Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();

        if (volumeExpungeCommands != null && volumeExpungeCommands.size() > 0 && hostId != null) {
            Commands cmds = new Commands(Command.OnError.Stop);

            for (Command volumeExpungeCommand : volumeExpungeCommands) {
                cmds.addCommand(volumeExpungeCommand);
            }

            _agentMgr.send(hostId, cmds);

            if (!cmds.isSuccessful()) {
                for (Answer answer : cmds.getAnswers()) {
                    if (!answer.getResult()) {
                        s_logger.warn("Failed to expunge vm due to: " + answer.getDetails());

                        throw new CloudRuntimeException("Unable to expunge " + vm + " due to " + answer.getDetails());
                    }
                }
            }
        }

        if (hostId != null) {
            volumeMgr.disconnectVolumesFromHost(vm.getId(), hostId);
        }

        // Clean up volumes based on the vm's instance id
        volumeMgr.cleanupVolumes(vm.getId());

        VirtualMachineGuru guru = getVmGuru(vm);
        guru.finalizeExpunge(vm);
        //remove the overcommit detials from the uservm details
        _uservmDetailsDao.removeDetails(vm.getId());

        // send hypervisor-dependent commands before removing
        List<Command> finalizeExpungeCommands = hvGuru.finalizeExpunge(vm);
        if (finalizeExpungeCommands != null && finalizeExpungeCommands.size() > 0) {
            if (hostId != null) {
                Commands cmds = new Commands(Command.OnError.Stop);
                for (Command command : finalizeExpungeCommands) {
                    cmds.addCommand(command);
                }
                if (nicExpungeCommands != null) {
                    for (Command command : nicExpungeCommands) {
                        cmds.addCommand(command);
                    }
                }
                _agentMgr.send(hostId, cmds);
                if (!cmds.isSuccessful()) {
                    for (Answer answer : cmds.getAnswers()) {
                        if (!answer.getResult()) {
                            s_logger.warn("Failed to expunge vm due to: " + answer.getDetails());
                            throw new CloudRuntimeException("Unable to expunge " + vm + " due to " + answer.getDetails());
                        }
                    }
                }
            }
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Expunged " + vm);
        }

    }

    @Override
    public boolean start() {
        // TODO, initial delay is hardcoded
        _executor.scheduleAtFixedRate(new CleanupTask(), 5, VmJobStateReportInterval.value(), TimeUnit.SECONDS);
        _executor.scheduleAtFixedRate(new TransitionTask(),  VmOpCleanupInterval.value(), VmOpCleanupInterval.value(), TimeUnit.SECONDS);
        cancelWorkItems(_nodeId);

        // cleanup left over place holder works
        _workJobDao.expungeLeftoverWorkJobs(ManagementServerNode.getManagementServerId());
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public boolean configure(String name, Map<String, Object> xmlParams) throws ConfigurationException {
        ReservationContextImpl.init(_entityMgr);
        VirtualMachineProfileImpl.init(_entityMgr);
        VmWorkMigrate.init(_entityMgr);

        _executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Vm-Operations-Cleanup"));
        _nodeId = ManagementServerNode.getManagementServerId();

        _agentMgr.registerForHostEvents(this, true, true, true);

        _messageBus.subscribe(VirtualMachineManager.Topics.VM_POWER_STATE, MessageDispatcher.getDispatcher(this));

        return true;
    }

    protected VirtualMachineManagerImpl() {
        setStateMachine();
    }

    @Override
    public void start(String vmUuid, Map<VirtualMachineProfile.Param, Object> params) {
        start(vmUuid, params, null, null);
    }

    @Override
    public void start(String vmUuid, Map<VirtualMachineProfile.Param, Object> params, DeploymentPlan planToDeploy, DeploymentPlanner planner) {
        try {
            advanceStart(vmUuid, params, planToDeploy, planner);
        } catch (ConcurrentOperationException e) {
            throw new CloudRuntimeException("Unable to start a VM due to concurrent operation", e).add(VirtualMachine.class, vmUuid);
        } catch (InsufficientCapacityException e) {
            throw new CloudRuntimeException("Unable to start a VM due to insufficient capacity", e).add(VirtualMachine.class, vmUuid);
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("Unable to start a VM due to concurrent operation", e).add(VirtualMachine.class, vmUuid);
        }
    }

    protected boolean checkWorkItems(VMInstanceVO vm, State state) throws ConcurrentOperationException {
        while (true) {
            ItWorkVO vo = _workDao.findByOutstandingWork(vm.getId(), state);
            if (vo == null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Unable to find work for VM: " + vm + " and state: " + state);
                }
                return true;
            }

            if (vo.getStep() == Step.Done) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Work for " + vm + " is " + vo.getStep());
                }
                return true;
            }

            if (vo.getSecondsTaskIsInactive() > VmOpCancelInterval.value()) {
                s_logger.warn("The task item for vm " + vm + " has been inactive for " + vo.getSecondsTaskIsInactive());
                return false;
            }

            try {
                Thread.sleep(VmOpWaitInterval.value()*1000);
            } catch (InterruptedException e) {
                s_logger.info("Waiting for " + vm + " but is interrupted");
                throw new ConcurrentOperationException("Waiting for " + vm + " but is interrupted");
            }
            s_logger.debug("Waiting some more to make sure there's no activity on " + vm);
        }

    }

    @DB
    protected Ternary<VMInstanceVO, ReservationContext, ItWorkVO> changeToStartState(VirtualMachineGuru vmGuru, final VMInstanceVO vm, final User caller,
            final Account account) throws ConcurrentOperationException {
        long vmId = vm.getId();

        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), _nodeId, State.Starting, vm.getType(), vm.getId());
        int retry = VmOpLockStateRetry.value();
        while (retry-- != 0) {
            try {
                final ItWorkVO workFinal = work;
                Ternary<VMInstanceVO, ReservationContext, ItWorkVO> result =
                        Transaction.execute(new TransactionCallbackWithException<Ternary<VMInstanceVO, ReservationContext, ItWorkVO>, NoTransitionException>() {
                            @Override
                            public Ternary<VMInstanceVO, ReservationContext, ItWorkVO> doInTransaction(TransactionStatus status) throws NoTransitionException {
                                Journal journal = new Journal.LogJournal("Creating " + vm, s_logger);
                                ItWorkVO work = _workDao.persist(workFinal);
                                ReservationContextImpl context = new ReservationContextImpl(work.getId(), journal, caller, account);

                                if (stateTransitTo(vm, Event.StartRequested, null, work.getId())) {
                                    if (s_logger.isDebugEnabled()) {
                                        s_logger.debug("Successfully transitioned to start state for " + vm + " reservation id = " + work.getId());
                                    }
                                    return new Ternary<VMInstanceVO, ReservationContext, ItWorkVO>(vm, context, work);
                                }

                                return new Ternary<VMInstanceVO, ReservationContext, ItWorkVO>(null, null, work);
                            }
                        });

                work = result.third();
                if (result.first() != null)
                    return result;
            } catch (NoTransitionException e) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Unable to transition into Starting state due to " + e.getMessage());
                }
            }

            VMInstanceVO instance = _vmDao.findById(vmId);
            if (instance == null) {
                throw new ConcurrentOperationException("Unable to acquire lock on " + vm);
            }

            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Determining why we're unable to update the state to Starting for " + instance + ".  Retry=" + retry);
            }

            State state = instance.getState();
            if (state == State.Running) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("VM is already started: " + vm);
                }
                return null;
            }

            if (state.isTransitional()) {
                if (!checkWorkItems(vm, state)) {
                    throw new ConcurrentOperationException("There are concurrent operations on " + vm);
                } else {
                    continue;
                }
            }

            if (state != State.Stopped) {
                s_logger.debug("VM " + vm + " is not in a state to be started: " + state);
                return null;
            }
        }

        throw new ConcurrentOperationException("Unable to change the state of " + vm);
    }

    protected <T extends VMInstanceVO> boolean changeState(T vm, Event event, Long hostId, ItWorkVO work, Step step) throws NoTransitionException {
        // FIXME: We should do this better.
        Step previousStep = work.getStep();
        _workDao.updateStep(work, step);
        boolean result = false;
        try {
            result = stateTransitTo(vm, event, hostId);
            return result;
        } finally {
            if (!result) {
                _workDao.updateStep(work, previousStep);
            }
        }
    }

    protected boolean areAffinityGroupsAssociated(VirtualMachineProfile vmProfile) {
        VirtualMachine vm = vmProfile.getVirtualMachine();
        long vmGroupCount = _affinityGroupVMMapDao.countAffinityGroupsForVm(vm.getId());

        if (vmGroupCount > 0) {
            return true;
        }
        return false;
    }

    @Override
    public void advanceStart(String vmUuid, Map<VirtualMachineProfile.Param, Object> params, DeploymentPlanner planner)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {
        advanceStart(vmUuid, params, null, planner);
    }

    @Override
    public void advanceStart(String vmUuid, Map<VirtualMachineProfile.Param, Object> params, DeploymentPlan planToDeploy, DeploymentPlanner planner)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {

        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if ( jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance
            VmWorkJobVO placeHolder = null;
            VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateStart(vmUuid, params, planToDeploy, planner);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            Outcome<VirtualMachine> outcome = startVmThroughJobQueue(vmUuid, params, planToDeploy, planner);

            try {
                VirtualMachine vm = outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobResult != null) {
                if (jobResult instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobResult;
                else if (jobResult instanceof ResourceUnavailableException)
                    throw (ResourceUnavailableException)jobResult;
                else if (jobResult instanceof InsufficientCapacityException)
                    throw (InsufficientCapacityException)jobResult;
                else if (jobResult instanceof RuntimeException)
                    throw (RuntimeException)jobResult;
                else if (jobResult instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
            }
        }
    }


    @Override
    public void orchestrateStart(String vmUuid, Map<VirtualMachineProfile.Param, Object> params, DeploymentPlan planToDeploy, DeploymentPlanner planner)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {

        CallContext cctxt = CallContext.current();
        Account account = cctxt.getCallingAccount();
        User caller = cctxt.getCallingUser();

        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        VirtualMachineGuru vmGuru = getVmGuru(vm);

        Ternary<VMInstanceVO, ReservationContext, ItWorkVO> start = changeToStartState(vmGuru, vm, caller, account);
        if (start == null) {
            return;
        }

        vm = start.first();
        ReservationContext ctx = start.second();
        ItWorkVO work = start.third();

        VMInstanceVO startedVm = null;
        ServiceOfferingVO offering = _offeringDao.findById(vm.getId(), vm.getServiceOfferingId());
        VirtualMachineTemplate template = _entityMgr.findById(VirtualMachineTemplate.class, vm.getTemplateId());

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Trying to deploy VM, vm has dcId: " + vm.getDataCenterId() + " and podId: " + vm.getPodIdToDeployIn());
        }
        DataCenterDeployment plan = new DataCenterDeployment(vm.getDataCenterId(), vm.getPodIdToDeployIn(), null, null, null, null, ctx);
        if (planToDeploy != null && planToDeploy.getDataCenterId() != 0) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("advanceStart: DeploymentPlan is provided, using dcId:" + planToDeploy.getDataCenterId() + ", podId: " + planToDeploy.getPodId() +
                        ", clusterId: " + planToDeploy.getClusterId() + ", hostId: " + planToDeploy.getHostId() + ", poolId: " + planToDeploy.getPoolId());
            }
            plan =
                    new DataCenterDeployment(planToDeploy.getDataCenterId(), planToDeploy.getPodId(), planToDeploy.getClusterId(), planToDeploy.getHostId(),
                            planToDeploy.getPoolId(), planToDeploy.getPhysicalNetworkId(), ctx);
        }

        HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vm.getHypervisorType());

        boolean canRetry = true;
        ExcludeList avoids = null;
        try {
            Journal journal = start.second().getJournal();

            if (planToDeploy != null) {
                avoids = planToDeploy.getAvoids();
            }
            if (avoids == null) {
                avoids = new ExcludeList();
            }
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Deploy avoids pods: " + avoids.getPodsToAvoid() + ", clusters: " + avoids.getClustersToAvoid() + ", hosts: " + avoids.getHostsToAvoid());
            }

            boolean planChangedByVolume = false;
            boolean reuseVolume = true;
            DataCenterDeployment originalPlan = plan;

            int retry = StartRetry.value();
            while (retry-- != 0) { // It's != so that it can match -1.

                if (reuseVolume) {
                    // edit plan if this vm's ROOT volume is in READY state already
                    List<VolumeVO> vols = _volsDao.findReadyRootVolumesByInstance(vm.getId());
                    for (VolumeVO vol : vols) {
                        // make sure if the templateId is unchanged. If it is changed,
                        // let planner
                        // reassign pool for the volume even if it ready.
                        Long volTemplateId = vol.getTemplateId();
                        if (volTemplateId != null && volTemplateId.longValue() != template.getId()) {
                            if (s_logger.isDebugEnabled()) {
                                s_logger.debug(vol + " of " + vm + " is READY, but template ids don't match, let the planner reassign a new pool");
                            }
                            continue;
                        }

                        StoragePool pool = (StoragePool)dataStoreMgr.getPrimaryDataStore(vol.getPoolId());
                        if (!pool.isInMaintenance()) {
                            if (s_logger.isDebugEnabled()) {
                                s_logger.debug("Root volume is ready, need to place VM in volume's cluster");
                            }
                            long rootVolDcId = pool.getDataCenterId();
                            Long rootVolPodId = pool.getPodId();
                            Long rootVolClusterId = pool.getClusterId();
                            if (planToDeploy != null && planToDeploy.getDataCenterId() != 0) {
                                Long clusterIdSpecified = planToDeploy.getClusterId();
                                if (clusterIdSpecified != null && rootVolClusterId != null) {
                                    if (rootVolClusterId.longValue() != clusterIdSpecified.longValue()) {
                                        // cannot satisfy the plan passed in to the
                                        // planner
                                        if (s_logger.isDebugEnabled()) {
                                            s_logger.debug("Cannot satisfy the deployment plan passed in since the ready Root volume is in different cluster. volume's cluster: " +
                                                    rootVolClusterId + ", cluster specified: " + clusterIdSpecified);
                                        }
                                        throw new ResourceUnavailableException(
                                                "Root volume is ready in different cluster, Deployment plan provided cannot be satisfied, unable to create a deployment for " +
                                                        vm, Cluster.class, clusterIdSpecified);
                                    }
                                }
                                plan =
                                        new DataCenterDeployment(planToDeploy.getDataCenterId(), planToDeploy.getPodId(), planToDeploy.getClusterId(),
                                                planToDeploy.getHostId(), vol.getPoolId(), null, ctx);
                            } else {
                                plan = new DataCenterDeployment(rootVolDcId, rootVolPodId, rootVolClusterId, null, vol.getPoolId(), null, ctx);
                                if (s_logger.isDebugEnabled()) {
                                    s_logger.debug(vol + " is READY, changing deployment plan to use this pool's dcId: " + rootVolDcId + " , podId: " + rootVolPodId +
                                            " , and clusterId: " + rootVolClusterId);
                                }
                                planChangedByVolume = true;
                            }
                        }
                    }
                }

                Account owner = _entityMgr.findById(Account.class, vm.getAccountId());
                VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vm, template, offering, owner, params);
                DeployDestination dest = null;
                try {
                    dest = _dpMgr.planDeployment(vmProfile, plan, avoids, planner);
                } catch (AffinityConflictException e2) {
                    s_logger.warn("Unable to create deployment, affinity rules associted to the VM conflict", e2);
                    throw new CloudRuntimeException("Unable to create deployment, affinity rules associted to the VM conflict");

                }

                if (dest == null) {
                    if (planChangedByVolume) {
                        plan = originalPlan;
                        planChangedByVolume = false;
                        //do not enter volume reuse for next retry, since we want to look for resorces outside the volume's cluster
                        reuseVolume = false;
                        continue;
                    }
                    throw new InsufficientServerCapacityException("Unable to create a deployment for " + vmProfile, DataCenter.class, plan.getDataCenterId(),
                            areAffinityGroupsAssociated(vmProfile));
                }

                if (dest != null) {
                    avoids.addHost(dest.getHost().getId());
                    journal.record("Deployment found ", vmProfile, dest);
                }

                long destHostId = dest.getHost().getId();
                vm.setPodIdToDeployIn(dest.getPod().getId());
                Long cluster_id = dest.getCluster().getId();
                ClusterDetailsVO cluster_detail_cpu = _clusterDetailsDao.findDetail(cluster_id, "cpuOvercommitRatio");
                ClusterDetailsVO cluster_detail_ram = _clusterDetailsDao.findDetail(cluster_id, "memoryOvercommitRatio");
                //storing the value of overcommit in the vm_details table for doing a capacity check in case the cluster overcommit ratio is changed.
                if (_uservmDetailsDao.findDetail(vm.getId(), "cpuOvercommitRatio") == null &&
                        ((Float.parseFloat(cluster_detail_cpu.getValue()) > 1f || Float.parseFloat(cluster_detail_ram.getValue()) > 1f))) {
                    _uservmDetailsDao.addDetail(vm.getId(), "cpuOvercommitRatio", cluster_detail_cpu.getValue(), true);
                    _uservmDetailsDao.addDetail(vm.getId(), "memoryOvercommitRatio", cluster_detail_ram.getValue(), true);
                } else if (_uservmDetailsDao.findDetail(vm.getId(), "cpuOvercommitRatio") != null) {
                    _uservmDetailsDao.addDetail(vm.getId(), "cpuOvercommitRatio", cluster_detail_cpu.getValue(), true);
                    _uservmDetailsDao.addDetail(vm.getId(), "memoryOvercommitRatio", cluster_detail_ram.getValue(), true);
                }
                vmProfile.setCpuOvercommitRatio(Float.parseFloat(cluster_detail_cpu.getValue()));
                vmProfile.setMemoryOvercommitRatio(Float.parseFloat(cluster_detail_ram.getValue()));
                StartAnswer startAnswer = null;

                try {
                    if (!changeState(vm, Event.OperationRetry, destHostId, work, Step.Prepare)) {
                        throw new ConcurrentOperationException("Unable to update the state of the Virtual Machine");
                    }
                } catch (NoTransitionException e1) {
                    throw new ConcurrentOperationException(e1.getMessage());
                }

                try {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("VM is being created in podId: " + vm.getPodIdToDeployIn());
                    }
                    _networkMgr.prepare(vmProfile, dest, ctx);
                    if (vm.getHypervisorType() != HypervisorType.BareMetal) {
                        volumeMgr.prepare(vmProfile, dest);
                    }
                    //since StorageMgr succeeded in volume creation, reuse Volume for further tries until current cluster has capacity
                    if (!reuseVolume) {
                        reuseVolume = true;
                    }

                    Commands cmds = null;
                    vmGuru.finalizeVirtualMachineProfile(vmProfile, dest, ctx);

                    VirtualMachineTO vmTO = hvGuru.implement(vmProfile);

                    handlePath(vmTO.getDisks(), vm.getHypervisorType());

                    cmds = new Commands(Command.OnError.Stop);

                    cmds.addCommand(new StartCommand(vmTO, dest.getHost(), getExecuteInSequence(vm.getHypervisorType())));


                    vmGuru.finalizeDeployment(cmds, vmProfile, dest, ctx);

                    work = _workDao.findById(work.getId());
                    if (work == null || work.getStep() != Step.Prepare) {
                        throw new ConcurrentOperationException("Work steps have been changed: " + work);
                    }

                    _workDao.updateStep(work, Step.Starting);

                    _agentMgr.send(destHostId, cmds);

                    _workDao.updateStep(work, Step.Started);

                    startAnswer = cmds.getAnswer(StartAnswer.class);
                    if (startAnswer != null && startAnswer.getResult()) {
                        handlePath(vmTO.getDisks(), startAnswer.getIqnToPath());
                        String host_guid = startAnswer.getHost_guid();
                        if (host_guid != null) {
                            HostVO finalHost = _resourceMgr.findHostByGuid(host_guid);
                            if (finalHost == null) {
                                throw new CloudRuntimeException("Host Guid " + host_guid + " doesn't exist in DB, something wrong here");
                            }
                            destHostId = finalHost.getId();
                        }
                        if (vmGuru.finalizeStart(vmProfile, destHostId, cmds, ctx)) {
                            syncDiskChainChange(startAnswer);

                            if (!changeState(vm, Event.OperationSucceeded, destHostId, work, Step.Done)) {
                                throw new ConcurrentOperationException("Unable to transition to a new state.");
                            }

                            // Update GPU device capacity
                            GPUDeviceTO gpuDevice = startAnswer.getVirtualMachine().getGpuDevice();
                            if (gpuDevice != null) {
                                _resourceMgr.updateGPUDetails(destHostId, gpuDevice.getGroupDetails());
                            }

                            startedVm = vm;
                            if (s_logger.isDebugEnabled()) {
                                s_logger.debug("Start completed for VM " + vm);
                            }
                            return;
                        } else {
                            if (s_logger.isDebugEnabled()) {
                                s_logger.info("The guru did not like the answers so stopping " + vm);
                            }

                            StopCommand cmd = new StopCommand(vm, getExecuteInSequence(vm.getHypervisorType()), false);
                            Answer answer = _agentMgr.easySend(destHostId, cmd);
                            if (answer != null && answer instanceof StopAnswer) {
                                StopAnswer stopAns = (StopAnswer)answer;
                                if (vm.getType() == VirtualMachine.Type.User) {
                                    String platform = stopAns.getPlatform();
                                    if (platform != null) {
                                        Map<String,String> vmmetadata = new HashMap<String,String>();
                                        vmmetadata.put(vm.getInstanceName(), platform);
                                        syncVMMetaData(vmmetadata);
                                    }
                                }
                            }

                            if (answer == null || !answer.getResult()) {
                                s_logger.warn("Unable to stop " + vm + " due to " + (answer != null ? answer.getDetails() : "no answers"));
                                _haMgr.scheduleStop(vm, destHostId, WorkType.ForceStop);
                                throw new ExecutionException("Unable to stop " + vm + " so we are unable to retry the start operation");
                            }
                            throw new ExecutionException("Unable to start " + vm + " due to error in finalizeStart, not retrying");
                        }
                    }
                    s_logger.info("Unable to start VM on " + dest.getHost() + " due to " + (startAnswer == null ? " no start answer" : startAnswer.getDetails()));

                } catch (OperationTimedoutException e) {
                    s_logger.debug("Unable to send the start command to host " + dest.getHost());
                    if (e.isActive()) {
                        _haMgr.scheduleStop(vm, destHostId, WorkType.CheckStop);
                    }
                    canRetry = false;
                    throw new AgentUnavailableException("Unable to start " + vm.getHostName(), destHostId, e);
                } catch (ResourceUnavailableException e) {
                    s_logger.info("Unable to contact resource.", e);
                    if (!avoids.add(e)) {
                        if (e.getScope() == Volume.class || e.getScope() == Nic.class) {
                            throw e;
                        } else {
                            s_logger.warn("unexpected ResourceUnavailableException : " + e.getScope().getName(), e);
                            throw e;
                        }
                    }
                } catch (InsufficientCapacityException e) {
                    s_logger.info("Insufficient capacity ", e);
                    if (!avoids.add(e)) {
                        if (e.getScope() == Volume.class || e.getScope() == Nic.class) {
                            throw e;
                        } else {
                            s_logger.warn("unexpected InsufficientCapacityException : " + e.getScope().getName(), e);
                        }
                    }
                } catch (Exception e) {
                    s_logger.error("Failed to start instance " + vm, e);
                    throw new AgentUnavailableException("Unable to start instance due to " + e.getMessage(), destHostId, e);
                } finally {
                    if (startedVm == null && canRetry) {
                        Step prevStep = work.getStep();
                        _workDao.updateStep(work, Step.Release);
                        // If previous step was started/ing && we got a valid answer
                        if ((prevStep == Step.Started || prevStep == Step.Starting) && (startAnswer != null && startAnswer.getResult())) {  //TODO check the response of cleanup and record it in DB for retry
                            cleanup(vmGuru, vmProfile, work, Event.OperationFailed, false);
                        } else {
                            //if step is not starting/started, send cleanup command with force=true
                            cleanup(vmGuru, vmProfile, work, Event.OperationFailed, true);
                        }
                    }
                }
            }
        } finally {
            if (startedVm == null) {
                if (canRetry) {
                    try {
                        changeState(vm, Event.OperationFailed, null, work, Step.Done);
                    } catch (NoTransitionException e) {
                        throw new ConcurrentOperationException(e.getMessage());
                    }
                }
            }

            if (planToDeploy != null) {
                planToDeploy.setAvoids(avoids);
            }
        }

        if (startedVm == null) {
            throw new CloudRuntimeException("Unable to start instance '" + vm.getHostName() + "' (" + vm.getUuid() + "), see management server log for details");
        }
    }

    // for managed storage on KVM, need to make sure the path field of the volume in question is populated with the IQN
    private void handlePath(DiskTO[] disks, HypervisorType hypervisorType) {
        if (hypervisorType != HypervisorType.KVM) {
            return;
        }

        if (disks != null) {
            for (DiskTO disk : disks) {
                Map<String, String> details = disk.getDetails();
                boolean isManaged = details != null && Boolean.parseBoolean(details.get(DiskTO.MANAGED));

                if (isManaged && disk.getPath() == null) {
                    Long volumeId = disk.getData().getId();
                    VolumeVO volume = _volsDao.findById(volumeId);

                    disk.setPath(volume.get_iScsiName());

                    if (disk.getData() instanceof VolumeObjectTO) {
                        VolumeObjectTO volTo = (VolumeObjectTO)disk.getData();

                        volTo.setPath(volume.get_iScsiName());
                    }

                    volume.setPath(volume.get_iScsiName());

                    _volsDao.update(volumeId, volume);
                }
            }
        }
    }

    // for managed storage on XenServer and VMware, need to update the DB with a path if the VDI/VMDK file was newly created
    private void handlePath(DiskTO[] disks, Map<String, String> iqnToPath) {
        if (disks != null && iqnToPath != null) {
            for (DiskTO disk : disks) {
                Map<String, String> details = disk.getDetails();
                boolean isManaged = details != null && Boolean.parseBoolean(details.get(DiskTO.MANAGED));

                if (isManaged) {
                    Long volumeId = disk.getData().getId();
                    VolumeVO volume = _volsDao.findById(volumeId);
                    String iScsiName = volume.get_iScsiName();
                    String path = iqnToPath.get(iScsiName);

                    if (path != null) {
                        volume.setPath(path);

                        _volsDao.update(volumeId, volume);
                    }
                }
            }
        }
    }

    private void syncDiskChainChange(StartAnswer answer) {
        VirtualMachineTO vmSpec = answer.getVirtualMachine();

        for (DiskTO disk : vmSpec.getDisks()) {
            if (disk.getType() != Volume.Type.ISO) {
                VolumeObjectTO vol = (VolumeObjectTO)disk.getData();
                VolumeVO volume = _volsDao.findById(vol.getId());

                // Use getPath() from VolumeVO to get a fresh copy of what's in the DB.
                // Before doing this, in a certain situation, getPath() from VolumeObjectTO
                // returned null instead of an actual path (because it was out of date with the DB).
                volumeMgr.updateVolumeDiskChain(vol.getId(), volume.getPath(), vol.getChainInfo());
            }
        }
    }

    @Override
    public void stop(String vmUuid) throws ResourceUnavailableException {
        try {
            advanceStop(vmUuid, false);
        } catch (OperationTimedoutException e) {
            throw new AgentUnavailableException("Unable to stop vm because the operation to stop timed out", e.getAgentId(), e);
        } catch (ConcurrentOperationException e) {
            throw new CloudRuntimeException("Unable to stop vm because of a concurrent operation", e);
        }
    }


    protected boolean getExecuteInSequence(HypervisorType hypervisorType) {
        if (HypervisorType.KVM == hypervisorType || HypervisorType.LXC == hypervisorType || HypervisorType.XenServer == hypervisorType) {
            return false;
        } else if(HypervisorType.VMware == hypervisorType) {
            Boolean fullClone = HypervisorGuru.VmwareFullClone.value();
            return fullClone;
        } else {
            return ExecuteInSequence.value();
        }
    }

    protected boolean sendStop(VirtualMachineGuru guru, VirtualMachineProfile profile, boolean force, boolean checkBeforeCleanup) {
        VirtualMachine vm = profile.getVirtualMachine();
        StopCommand stop = new StopCommand(vm, getExecuteInSequence(vm.getHypervisorType()), checkBeforeCleanup);
        try {
            Answer answer = _agentMgr.send(vm.getHostId(), stop);
            if (answer != null && answer instanceof StopAnswer) {
                StopAnswer stopAns = (StopAnswer)answer;
                if (vm.getType() == VirtualMachine.Type.User) {
                    String platform = stopAns.getPlatform();
                    if (platform != null) {
                        UserVmVO userVm = _userVmDao.findById(vm.getId());
                        _userVmDao.loadDetails(userVm);
                        userVm.setDetail("platform", platform);
                        _userVmDao.saveDetails(userVm);
                    }
                }

                GPUDeviceTO gpuDevice = stop.getGpuDevice();
                if (gpuDevice != null) {
                    _resourceMgr.updateGPUDetails(vm.getHostId(), gpuDevice.getGroupDetails());
                }
                if (answer == null || !answer.getResult()) {
                    s_logger.debug("Unable to stop VM due to " + answer.getDetails());
                    return false;
                }

                guru.finalizeStop(profile, answer);
            } else {
                s_logger.error("Invalid answer received in response to a StopCommand for " + vm.getInstanceName());
                return false;
            }

        } catch (AgentUnavailableException e) {
            if (!force) {
                return false;
            }
        } catch (OperationTimedoutException e) {
            if (!force) {
                return false;
            }
        }

        return true;
    }

    protected boolean cleanup(VirtualMachineGuru guru, VirtualMachineProfile profile, ItWorkVO work, Event event, boolean cleanUpEvenIfUnableToStop) {
        VirtualMachine vm = profile.getVirtualMachine();
        State state = vm.getState();
        s_logger.debug("Cleaning up resources for the vm " + vm + " in " + state + " state");
        try {
            if (state == State.Starting) {
                Step step = work.getStep();
                if (step == Step.Starting && !cleanUpEvenIfUnableToStop) {
                    s_logger.warn("Unable to cleanup vm " + vm + "; work state is incorrect: " + step);
                    return false;
                }

                if (step == Step.Started || step == Step.Starting || step == Step.Release) {
                    if (vm.getHostId() != null) {
                        if (!sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                            s_logger.warn("Failed to stop vm " + vm + " in " + State.Starting + " state as a part of cleanup process");
                            return false;
                        }
                    }
                }

                if (step != Step.Release && step != Step.Prepare && step != Step.Started && step != Step.Starting) {
                    s_logger.debug("Cleanup is not needed for vm " + vm + "; work state is incorrect: " + step);
                    return true;
                }
            } else if (state == State.Stopping) {
                if (vm.getHostId() != null) {
                    if (!sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                        s_logger.warn("Failed to stop vm " + vm + " in " + State.Stopping + " state as a part of cleanup process");
                        return false;
                    }
                }
            } else if (state == State.Migrating) {
                if (vm.getHostId() != null) {
                    if (!sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                        s_logger.warn("Failed to stop vm " + vm + " in " + State.Migrating + " state as a part of cleanup process");
                        return false;
                    }
                }
                if (vm.getLastHostId() != null) {
                    if (!sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                        s_logger.warn("Failed to stop vm " + vm + " in " + State.Migrating + " state as a part of cleanup process");
                        return false;
                    }
                }
            } else if (state == State.Running) {
                if (!sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                    s_logger.warn("Failed to stop vm " + vm + " in " + State.Running + " state as a part of cleanup process");
                    return false;
                }
            }
        } finally {
            try {
                _networkMgr.release(profile, cleanUpEvenIfUnableToStop);
                s_logger.debug("Successfully released network resources for the vm " + vm);
            } catch (Exception e) {
                s_logger.warn("Unable to release some network resources.", e);
            }

            volumeMgr.release(profile);
            s_logger.debug("Successfully cleanued up resources for the vm " + vm + " in " + state + " state");
        }

        return true;
    }

    @Override
    public void advanceStop(String vmUuid, boolean cleanUpEvenIfUnableToStop)
            throws AgentUnavailableException, OperationTimedoutException, ConcurrentOperationException {

        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance

            VmWorkJobVO placeHolder = null;
            VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateStop(vmUuid, cleanUpEvenIfUnableToStop);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }

        } else {
            Outcome<VirtualMachine> outcome = stopVmThroughJobQueue(vmUuid, cleanUpEvenIfUnableToStop);

            try {
                VirtualMachine vm = outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobResult != null) {
                if (jobResult instanceof AgentUnavailableException)
                    throw (AgentUnavailableException)jobResult;
                else if (jobResult instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobResult;
                else if (jobResult instanceof OperationTimedoutException)
                    throw (OperationTimedoutException)jobResult;
                else if (jobResult instanceof RuntimeException)
                    throw (RuntimeException)jobResult;
                else if (jobResult instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
            }
        }
    }

    private void orchestrateStop(String vmUuid, boolean cleanUpEvenIfUnableToStop) throws AgentUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        advanceStop(vm, cleanUpEvenIfUnableToStop);
    }

    private void advanceStop(VMInstanceVO vm, boolean cleanUpEvenIfUnableToStop) throws AgentUnavailableException, OperationTimedoutException,
    ConcurrentOperationException {
        State state = vm.getState();
        if (state == State.Stopped) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("VM is already stopped: " + vm);
            }
            return;
        }

        if (state == State.Destroyed || state == State.Expunging || state == State.Error) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Stopped called on " + vm + " but the state is " + state);
            }
            return;
        }
        // grab outstanding work item if any
        ItWorkVO work = _workDao.findByOutstandingWork(vm.getId(), vm.getState());
        if (work != null) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Found an outstanding work item for this vm " + vm + " with state:" + vm.getState() + ", work id:" + work.getId());
            }
        }
        Long hostId = vm.getHostId();
        if (hostId == null) {
            if (!cleanUpEvenIfUnableToStop) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("HostId is null but this is not a forced stop, cannot stop vm " + vm + " with state:" + vm.getState());
                }
                throw new CloudRuntimeException("Unable to stop " + vm);
            }
            try {
                stateTransitTo(vm, Event.AgentReportStopped, null, null);
            } catch (NoTransitionException e) {
                s_logger.warn(e.getMessage());
            }
            // mark outstanding work item if any as done
            if (work != null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Updating work item to Done, id:" + work.getId());
                }
                work.setStep(Step.Done);
                _workDao.update(work.getId(), work);
            }
            return;
        }

        VirtualMachineGuru vmGuru = getVmGuru(vm);
        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);

        try {
            if (!stateTransitTo(vm, Event.StopRequested, vm.getHostId())) {
                throw new ConcurrentOperationException("VM is being operated on.");
            }
        } catch (NoTransitionException e1) {
            if (!cleanUpEvenIfUnableToStop) {
                throw new CloudRuntimeException("We cannot stop " + vm + " when it is in state " + vm.getState());
            }
            boolean doCleanup = false;
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Unable to transition the state but we're moving on because it's forced stop");
            }
            if ((state == State.Starting) || (state == State.Migrating) || (state == State.Stopping)) {
                if (work != null) {
                    doCleanup = true;
                } else {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Unable to cleanup VM: " + vm + " ,since outstanding work item is not found");
                    }
                    throw new CloudRuntimeException("Work item not found, We cannot stop " + vm + " when it is in state " + vm.getState());
                }
            }

            if (doCleanup) {
                if (cleanup(vmGuru, new VirtualMachineProfileImpl(vm), work, Event.StopRequested, cleanUpEvenIfUnableToStop)) {
                    try {
                        if (s_logger.isDebugEnabled()) {
                            s_logger.debug("Updating work item to Done, id:" + work.getId());
                        }
                        if (!changeState(vm, Event.AgentReportStopped, null, work, Step.Done)) {
                            throw new CloudRuntimeException("Unable to stop " + vm);
                        }
                    } catch (NoTransitionException e) {
                        s_logger.warn("Unable to cleanup " + vm);
                        throw new CloudRuntimeException("Unable to stop " + vm, e);
                    }
                } else {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Failed to cleanup VM: " + vm);
                    }
                    throw new CloudRuntimeException("Failed to cleanup " + vm + " , current state " + vm.getState());
                }
            }
        }

        if (vm.getState() != State.Stopping) {
            throw new CloudRuntimeException("We cannot proceed with stop VM " + vm + " since it is not in 'Stopping' state, current state: " + vm.getState());
        }

        vmGuru.prepareStop(profile);

        StopCommand stop = new StopCommand(vm, getExecuteInSequence(vm.getHypervisorType()), false);

        boolean stopped = false;
        Answer answer = null;
        try {
            answer = _agentMgr.send(vm.getHostId(), stop);
            if (answer != null) {
                if (answer instanceof StopAnswer) {
                    StopAnswer stopAns = (StopAnswer)answer;
                    if (vm.getType() == VirtualMachine.Type.User) {
                        String platform = stopAns.getPlatform();
                        if (platform != null) {
                            UserVmVO userVm = _userVmDao.findById(vm.getId());
                            _userVmDao.loadDetails(userVm);
                            userVm.setDetail("platform", platform);
                            _userVmDao.saveDetails(userVm);
                        }
                    }
                }
                stopped = answer.getResult();
                if (!stopped) {
                    throw new CloudRuntimeException("Unable to stop the virtual machine due to " + answer.getDetails());
                }
                vmGuru.finalizeStop(profile, answer);
                GPUDeviceTO gpuDevice = stop.getGpuDevice();
                if (gpuDevice != null) {
                    _resourceMgr.updateGPUDetails(vm.getHostId(), gpuDevice.getGroupDetails());
                }
            } else {
                throw new CloudRuntimeException("Invalid answer received in response to a StopCommand on " + vm.instanceName);
            }

        } catch (AgentUnavailableException e) {
            s_logger.warn("Unable to stop vm, agent unavailable: " + e.toString());
        } catch (OperationTimedoutException e) {
            s_logger.warn("Unable to stop vm, operation timed out: " + e.toString());
        } finally {
            if (!stopped) {
                if (!cleanUpEvenIfUnableToStop) {
                    s_logger.warn("Unable to stop vm " + vm);
                    try {
                        stateTransitTo(vm, Event.OperationFailed, vm.getHostId());
                    } catch (NoTransitionException e) {
                        s_logger.warn("Unable to transition the state " + vm);
                    }
                    throw new CloudRuntimeException("Unable to stop " + vm);
                } else {
                    s_logger.warn("Unable to actually stop " + vm + " but continue with release because it's a force stop");
                    vmGuru.finalizeStop(profile, answer);
                }
            }
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug(vm + " is stopped on the host.  Proceeding to release resource held.");
        }

        try {
            _networkMgr.release(profile, cleanUpEvenIfUnableToStop);
            s_logger.debug("Successfully released network resources for the vm " + vm);
        } catch (Exception e) {
            s_logger.warn("Unable to release some network resources.", e);
        }

        try {
            if (vm.getHypervisorType() != HypervisorType.BareMetal) {
                volumeMgr.release(profile);
                s_logger.debug("Successfully released storage resources for the vm " + vm);
            }
        } catch (Exception e) {
            s_logger.warn("Unable to release storage resources.", e);
        }

        try {
            if (work != null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Updating the outstanding work item to Done, id:" + work.getId());
                }
                work.setStep(Step.Done);
                _workDao.update(work.getId(), work);
            }

            if (!stateTransitTo(vm, Event.OperationSucceeded, null)) {
                throw new CloudRuntimeException("unable to stop " + vm);
            }
        } catch (NoTransitionException e) {
            s_logger.warn(e.getMessage());
            throw new CloudRuntimeException("Unable to stop " + vm);
        }
    }

    private void setStateMachine() {
        _stateMachine = VirtualMachine.State.getStateMachine();
    }

    protected boolean stateTransitTo(VMInstanceVO vm, VirtualMachine.Event e, Long hostId, String reservationId) throws NoTransitionException {
        // if there are active vm snapshots task, state change is not allowed

        // Disable this hacking thing, VM snapshot task need to be managed by its orchestartion flow istelf instead of
        // hacking it here at general VM manager
        /*
                if (_vmSnapshotMgr.hasActiveVMSnapshotTasks(vm.getId())) {
                    s_logger.error("State transit with event: " + e + " failed due to: " + vm.getInstanceName() + " has active VM snapshots tasks");
                    return false;
                }
        */
        vm.setReservationId(reservationId);
        return _stateMachine.transitTo(vm, e, new Pair<Long, Long>(vm.getHostId(), hostId), _vmDao);
    }

    @Override
    public boolean stateTransitTo(VirtualMachine vm1, VirtualMachine.Event e, Long hostId) throws NoTransitionException {
        VMInstanceVO vm = (VMInstanceVO)vm1;

        /*
         *  Remove the hacking logic here.
                // if there are active vm snapshots task, state change is not allowed
                if (_vmSnapshotMgr.hasActiveVMSnapshotTasks(vm.getId())) {
                    s_logger.error("State transit with event: " + e + " failed due to: " + vm.getInstanceName() + " has active VM snapshots tasks");
                    return false;
                }
        */

        State oldState = vm.getState();
        if (oldState == State.Starting) {
            if (e == Event.OperationSucceeded) {
                vm.setLastHostId(hostId);
            }
        } else if (oldState == State.Stopping) {
            if (e == Event.OperationSucceeded) {
                vm.setLastHostId(vm.getHostId());
            }
        }
        return _stateMachine.transitTo(vm, e, new Pair<Long, Long>(vm.getHostId(), hostId), _vmDao);
    }

    @Override
    public void destroy(String vmUuid) throws AgentUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        if (vm == null || vm.getState() == State.Destroyed || vm.getState() == State.Expunging || vm.getRemoved() != null) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Unable to find vm or vm is destroyed: " + vm);
            }
            return;
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Destroying vm " + vm);
        }

        advanceStop(vmUuid, VmDestroyForcestop.value());

        if (!_vmSnapshotMgr.deleteAllVMSnapshots(vm.getId(), null)) {
            s_logger.debug("Unable to delete all snapshots for " + vm);
            throw new CloudRuntimeException("Unable to delete vm snapshots for " + vm);
        }

        // reload the vm object from db
        vm = _vmDao.findByUuid(vmUuid);
        try {
            if (!stateTransitTo(vm, VirtualMachine.Event.DestroyRequested, vm.getHostId())) {
                s_logger.debug("Unable to destroy the vm because it is not in the correct state: " + vm);
                throw new CloudRuntimeException("Unable to destroy " + vm);
            }
        } catch (NoTransitionException e) {
            s_logger.debug(e.getMessage());
            throw new CloudRuntimeException("Unable to destroy " + vm, e);
        }
    }

    protected boolean checkVmOnHost(VirtualMachine vm, long hostId) throws AgentUnavailableException, OperationTimedoutException {
        Answer answer = _agentMgr.send(hostId, new CheckVirtualMachineCommand(vm.getInstanceName()));
        if (answer == null || !answer.getResult()) {
            return false;
        }
        if (answer instanceof CheckVirtualMachineAnswer) {
            CheckVirtualMachineAnswer vmAnswer = (CheckVirtualMachineAnswer)answer;
            if (vmAnswer.getState() == PowerState.PowerOff) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void storageMigration(String vmUuid, StoragePool destPool) {
        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance
            VmWorkJobVO placeHolder = null;
            VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateStorageMigration(vmUuid, destPool);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            Outcome<VirtualMachine> outcome = migrateVmStorageThroughJobQueue(vmUuid, destPool);

            try {
                VirtualMachine vm = outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobResult != null) {
                if (jobResult instanceof RuntimeException)
                    throw (RuntimeException)jobResult;
                else if (jobResult instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
            }
        }
    }

    private void orchestrateStorageMigration(String vmUuid, StoragePool destPool) {
        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        try {
            stateTransitTo(vm, VirtualMachine.Event.StorageMigrationRequested, null);
        } catch (NoTransitionException e) {
            s_logger.debug("Unable to migrate vm: " + e.toString());
            throw new CloudRuntimeException("Unable to migrate vm: " + e.toString());
        }

        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        boolean migrationResult = false;
        try {
            migrationResult = volumeMgr.storageMigration(profile, destPool);

            if (migrationResult) {
                //if the vm is migrated to different pod in basic mode, need to reallocate ip

                if (!vm.getPodIdToDeployIn().equals(destPool.getPodId())) {
                    DataCenterDeployment plan = new DataCenterDeployment(vm.getDataCenterId(), destPool.getPodId(), null, null, null, null);
                    VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vm, null, null, null, null);
                    _networkMgr.reallocate(vmProfile, plan);
                }

                //when start the vm next time, don;'t look at last_host_id, only choose the host based on volume/storage pool
                vm.setLastHostId(null);
                vm.setPodIdToDeployIn(destPool.getPodId());
            } else {
                s_logger.debug("Storage migration failed");
            }
        } catch (ConcurrentOperationException e) {
            s_logger.debug("Failed to migration: " + e.toString());
            throw new CloudRuntimeException("Failed to migration: " + e.toString());
        } catch (InsufficientVirtualNetworkCapacityException e) {
            s_logger.debug("Failed to migration: " + e.toString());
            throw new CloudRuntimeException("Failed to migration: " + e.toString());
        } catch (InsufficientAddressCapacityException e) {
            s_logger.debug("Failed to migration: " + e.toString());
            throw new CloudRuntimeException("Failed to migration: " + e.toString());
        } catch (InsufficientCapacityException e) {
            s_logger.debug("Failed to migration: " + e.toString());
            throw new CloudRuntimeException("Failed to migration: " + e.toString());
        } catch (StorageUnavailableException e) {
            s_logger.debug("Failed to migration: " + e.toString());
            throw new CloudRuntimeException("Failed to migration: " + e.toString());
        } finally {
            try {
                stateTransitTo(vm, VirtualMachine.Event.AgentReportStopped, null);
            } catch (NoTransitionException e) {
                s_logger.debug("Failed to change vm state: " + e.toString());
                throw new CloudRuntimeException("Failed to change vm state: " + e.toString());
            }
        }
    }

    @Override
    public void migrate(String vmUuid, long srcHostId, DeployDestination dest)
            throws ResourceUnavailableException, ConcurrentOperationException {

        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance
            VmWorkJobVO placeHolder = null;
            VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateMigrate(vmUuid, srcHostId, dest);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            Outcome<VirtualMachine> outcome = migrateVmThroughJobQueue(vmUuid, srcHostId, dest);

            try {
                VirtualMachine vm = outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobResult != null) {
                if (jobResult instanceof ResourceUnavailableException)
                    throw (ResourceUnavailableException)jobResult;
                else if (jobResult instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobResult;
                else if (jobResult instanceof RuntimeException)
                    throw (RuntimeException)jobResult;
                else if (jobResult instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobResult);

            }
        }
    }

    private void orchestrateMigrate(String vmUuid, long srcHostId, DeployDestination dest) throws ResourceUnavailableException, ConcurrentOperationException {
        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        if (vm == null) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Unable to find the vm " + vmUuid);
            }
            throw new CloudRuntimeException("Unable to find a virtual machine with id " + vmUuid);
        }
        migrate(vm, srcHostId, dest);
    }

    protected void migrate(VMInstanceVO vm, long srcHostId, DeployDestination dest) throws ResourceUnavailableException, ConcurrentOperationException {
        s_logger.info("Migrating " + vm + " to " + dest);

        long dstHostId = dest.getHost().getId();
        Host fromHost = _hostDao.findById(srcHostId);
        if (fromHost == null) {
            s_logger.info("Unable to find the host to migrate from: " + srcHostId);
            throw new CloudRuntimeException("Unable to find the host to migrate from: " + srcHostId);
        }

        if (fromHost.getClusterId().longValue() != dest.getCluster().getId()) {
            List<VolumeVO> volumes = _volsDao.findCreatedByInstance(vm.getId());
            for (VolumeVO volume : volumes) {
                if (!(_storagePoolDao.findById(volume.getPoolId())).getScope().equals(ScopeType.ZONE)) {
                    s_logger.info("Source and destination host are not in same cluster and all volumes are not on zone wide primary store, unable to migrate to host: "
                            + dest.getHost().getId());
                    throw new CloudRuntimeException(
                            "Source and destination host are not in same cluster and all volumes are not on zone wide primary store, unable to migrate to host: "
                                    + dest.getHost().getId());
                }
            }
        }

        VirtualMachineGuru vmGuru = getVmGuru(vm);

        if (vm.getState() != State.Running) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("VM is not Running, unable to migrate the vm " + vm);
            }
            throw new CloudRuntimeException("VM is not Running, unable to migrate the vm currently " + vm + " , current state: " + vm.getState().toString());
        }

        AlertManager.AlertType alertType = AlertManager.AlertType.ALERT_TYPE_USERVM_MIGRATE;
        if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER_MIGRATE;
        } else if (VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY_MIGRATE;
        }

        VirtualMachineProfile vmSrc = new VirtualMachineProfileImpl(vm);
        for (NicProfile nic : _networkMgr.getNicProfiles(vm)) {
            vmSrc.addNic(nic);
        }

        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, null, _offeringDao.findById(vm.getId(), vm.getServiceOfferingId()), null, null);
        _networkMgr.prepareNicForMigration(profile, dest);
        volumeMgr.prepareForMigration(profile, dest);

        VirtualMachineTO to = toVmTO(profile);
        PrepareForMigrationCommand pfmc = new PrepareForMigrationCommand(to);

        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), _nodeId, State.Migrating, vm.getType(), vm.getId());
        work.setStep(Step.Prepare);
        work.setResourceType(ItWorkVO.ResourceType.Host);
        work.setResourceId(dstHostId);
        work = _workDao.persist(work);

        Answer pfma = null;
        try {
            pfma = _agentMgr.send(dstHostId, pfmc);
            if (pfma == null || !pfma.getResult()) {
                String msg = "Unable to prepare for migration due to " + pfma.getDetails();
                pfma = null;
                throw new AgentUnavailableException(msg, dstHostId);
            }
        } catch (OperationTimedoutException e1) {
            throw new AgentUnavailableException("Operation timed out", dstHostId);
        } finally {
            if (pfma == null) {
                _networkMgr.rollbackNicForMigration(vmSrc, profile);
                work.setStep(Step.Done);
                _workDao.update(work.getId(), work);
            }
        }

        vm.setLastHostId(srcHostId);
        try {
            if (vm == null || vm.getHostId() == null || vm.getHostId() != srcHostId || !changeState(vm, Event.MigrationRequested, dstHostId, work, Step.Migrating)) {
                _networkMgr.rollbackNicForMigration(vmSrc, profile);
                s_logger.info("Migration cancelled because state has changed: " + vm);
                throw new ConcurrentOperationException("Migration cancelled because state has changed: " + vm);
            }
        } catch (NoTransitionException e1) {
            _networkMgr.rollbackNicForMigration(vmSrc, profile);
            s_logger.info("Migration cancelled because " + e1.getMessage());
            throw new ConcurrentOperationException("Migration cancelled because " + e1.getMessage());
        }

        boolean migrated = false;
        try {
            boolean isWindows = _guestOsCategoryDao.findById(_guestOsDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");
            MigrateCommand mc = new MigrateCommand(vm.getInstanceName(), dest.getHost().getPrivateIpAddress(), isWindows, to, getExecuteInSequence(vm.getHypervisorType()));
            mc.setHostGuid(dest.getHost().getGuid());

            try {
                Answer ma = _agentMgr.send(vm.getLastHostId(), mc);
                if (ma == null || !ma.getResult()) {
                    throw new CloudRuntimeException("Unable to migrate due to " + ma.getDetails());
                }
            } catch (OperationTimedoutException e) {
                if (e.isActive()) {
                    s_logger.warn("Active migration command so scheduling a restart for " + vm);
                    _haMgr.scheduleRestart(vm, true);
                }
                throw new AgentUnavailableException("Operation timed out on migrating " + vm, dstHostId);
            }

            try {
                if (!changeState(vm, VirtualMachine.Event.OperationSucceeded, dstHostId, work, Step.Started)) {
                    throw new ConcurrentOperationException("Unable to change the state for " + vm);
                }
            } catch (NoTransitionException e1) {
                throw new ConcurrentOperationException("Unable to change state due to " + e1.getMessage());
            }

            try {
                if (!checkVmOnHost(vm, dstHostId)) {
                    s_logger.error("Unable to complete migration for " + vm);
                    try {
                        _agentMgr.send(srcHostId, new Commands(cleanup(vm)), null);
                    } catch (AgentUnavailableException e) {
                        s_logger.error("AgentUnavailableException while cleanup on source host: " + srcHostId);
                    }
                    cleanup(vmGuru, new VirtualMachineProfileImpl(vm), work, Event.AgentReportStopped, true);
                    throw new CloudRuntimeException("Unable to complete migration for " + vm);
                }
            } catch (OperationTimedoutException e) {
                s_logger.debug("Error while checking the vm " + vm + " on host " + dstHostId, e);
            }

            migrated = true;
        } finally {
            if (!migrated) {
                s_logger.info("Migration was unsuccessful.  Cleaning up: " + vm);
                _networkMgr.rollbackNicForMigration(vmSrc, profile);

                _alertMgr.sendAlert(alertType, fromHost.getDataCenterId(), fromHost.getPodId(),
                        "Unable to migrate vm " + vm.getInstanceName() + " from host " + fromHost.getName() + " in zone " + dest.getDataCenter().getName() + " and pod " +
                                dest.getPod().getName(), "Migrate Command failed.  Please check logs.");
                try {
                    _agentMgr.send(dstHostId, new Commands(cleanup(vm)), null);
                } catch (AgentUnavailableException ae) {
                    s_logger.info("Looks like the destination Host is unavailable for cleanup");
                }

                try {
                    stateTransitTo(vm, Event.OperationFailed, srcHostId);
                } catch (NoTransitionException e) {
                    s_logger.warn(e.getMessage());
                }
            } else {
                _networkMgr.commitNicForMigration(vmSrc, profile);
            }

            work.setStep(Step.Done);
            _workDao.update(work.getId(), work);
        }
    }

    private Map<Volume, StoragePool> getPoolListForVolumesForMigration(VirtualMachineProfile profile, Host host, Map<Long, Long> volumeToPool) {
        List<VolumeVO> allVolumes = _volsDao.findUsableVolumesForInstance(profile.getId());
        Map<Volume, StoragePool> volumeToPoolObjectMap = new HashMap<Volume, StoragePool> ();
        for (VolumeVO volume : allVolumes) {
            Long poolId = volumeToPool.get(Long.valueOf(volume.getId()));
            StoragePoolVO pool = _storagePoolDao.findById(poolId);
            StoragePoolVO currentPool = _storagePoolDao.findById(volume.getPoolId());
            DiskOfferingVO diskOffering = _diskOfferingDao.findById(volume.getDiskOfferingId());
            if (pool != null) {
                // Check if pool is accessible from the destination host and disk offering with which the volume was
                // created is compliant with the pool type.
                if (_poolHostDao.findByPoolHost(pool.getId(), host.getId()) == null || pool.isLocal() != diskOffering.getUseLocalStorage()) {
                    // Cannot find a pool for the volume. Throw an exception.
                    throw new CloudRuntimeException("Cannot migrate volume " + volume + " to storage pool " + pool + " while migrating vm to host " + host +
                            ". Either the pool is not accessible from the host or because of the offering with which the volume is created it cannot be placed on " +
                            "the given pool.");
                } else if (pool.getId() == currentPool.getId()) {
                    // If the pool to migrate too is the same as current pool, the volume doesn't need to be migrated.
                } else {
                    volumeToPoolObjectMap.put(volume, pool);
                }
            } else {
                // Find a suitable pool for the volume. Call the storage pool allocator to find the list of pools.
                DiskProfile diskProfile = new DiskProfile(volume, diskOffering, profile.getHypervisorType());
                DataCenterDeployment plan = new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), host.getId(), null, null);
                ExcludeList avoid = new ExcludeList();
                boolean currentPoolAvailable = false;

                List<StoragePool> poolList = new ArrayList<StoragePool>();
                for (StoragePoolAllocator allocator : _storagePoolAllocators) {
                    List<StoragePool> poolListFromAllocator = allocator.allocateToPool(diskProfile, profile, plan, avoid, StoragePoolAllocator.RETURN_UPTO_ALL);
                    if (poolListFromAllocator != null && !poolListFromAllocator.isEmpty()) {
                        poolList.addAll(poolListFromAllocator);
                    }
                }

                if (poolList != null && !poolList.isEmpty()) {
                    // Volume needs to be migrated. Pick the first pool from the list. Add a mapping to migrate the
                    // volume to a pool only if it is required; that is the current pool on which the volume resides
                    // is not available on the destination host.
                    Iterator<StoragePool> iter = poolList.iterator();
                    while (iter.hasNext()) {
                        if (currentPool.getId() == iter.next().getId()) {
                            currentPoolAvailable = true;
                            break;
                        }
                    }

                    if (!currentPoolAvailable) {
                        volumeToPoolObjectMap.put(volume, _storagePoolDao.findByUuid(poolList.get(0).getUuid()));
                    }
                }


                if (!currentPoolAvailable && !volumeToPoolObjectMap.containsKey(volume)) {
                    // Cannot find a pool for the volume. Throw an exception.
                    throw new CloudRuntimeException("Cannot find a storage pool which is available for volume " + volume + " while migrating virtual machine " +
                            profile.getVirtualMachine() + " to host " + host);
                }
            }
        }

        return volumeToPoolObjectMap;
    }

    private <T extends VMInstanceVO> void moveVmToMigratingState(T vm, Long hostId, ItWorkVO work) throws ConcurrentOperationException {
        // Put the vm in migrating state.
        try {
            if (!changeState(vm, Event.MigrationRequested, hostId, work, Step.Migrating)) {
                s_logger.info("Migration cancelled because state has changed: " + vm);
                throw new ConcurrentOperationException("Migration cancelled because state has changed: " + vm);
            }
        } catch (NoTransitionException e) {
            s_logger.info("Migration cancelled because " + e.getMessage());
            throw new ConcurrentOperationException("Migration cancelled because " + e.getMessage());
        }
    }

    private <T extends VMInstanceVO> void moveVmOutofMigratingStateOnSuccess(T vm, Long hostId, ItWorkVO work) throws ConcurrentOperationException {
        // Put the vm in running state.
        try {
            if (!changeState(vm, Event.OperationSucceeded, hostId, work, Step.Started)) {
                s_logger.error("Unable to change the state for " + vm);
                throw new ConcurrentOperationException("Unable to change the state for " + vm);
            }
        } catch (NoTransitionException e) {
            s_logger.error("Unable to change state due to " + e.getMessage());
            throw new ConcurrentOperationException("Unable to change state due to " + e.getMessage());
        }
    }

    @Override
    public void migrateWithStorage(String vmUuid, long srcHostId, long destHostId, Map<Long, Long> volumeToPool)
            throws ResourceUnavailableException, ConcurrentOperationException {

        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance

            VmWorkJobVO placeHolder = null;
            VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateMigrateWithStorage(vmUuid, srcHostId, destHostId, volumeToPool);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }

        } else {
            Outcome<VirtualMachine> outcome = migrateVmWithStorageThroughJobQueue(vmUuid, srcHostId, destHostId, volumeToPool);

            try {
                VirtualMachine vm = outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobException = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobException != null) {
                if (jobException instanceof ResourceUnavailableException)
                    throw (ResourceUnavailableException)jobException;
                else if (jobException instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobException;
                else if (jobException instanceof RuntimeException)
                    throw (RuntimeException)jobException;
                else if (jobException instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobException);
           }
        }
    }

    private void orchestrateMigrateWithStorage(String vmUuid, long srcHostId, long destHostId, Map<Long, Long> volumeToPool) throws ResourceUnavailableException,
    ConcurrentOperationException {

        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        HostVO srcHost = _hostDao.findById(srcHostId);
        HostVO destHost = _hostDao.findById(destHostId);
        VirtualMachineGuru vmGuru = getVmGuru(vm);

        DataCenterVO dc = _dcDao.findById(destHost.getDataCenterId());
        HostPodVO pod = _podDao.findById(destHost.getPodId());
        Cluster cluster = _clusterDao.findById(destHost.getClusterId());
        DeployDestination destination = new DeployDestination(dc, pod, cluster, destHost);

        // Create a map of which volume should go in which storage pool.
        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        Map<Volume, StoragePool> volumeToPoolMap = getPoolListForVolumesForMigration(profile, destHost, volumeToPool);

        // If none of the volumes have to be migrated, fail the call. Administrator needs to make a call for migrating
        // a vm and not migrating a vm with storage.
        if (volumeToPoolMap == null || volumeToPoolMap.isEmpty()) {
            throw new InvalidParameterValueException("Migration of the vm " + vm + "from host " + srcHost + " to destination host " + destHost +
                    " doesn't involve migrating the volumes.");
        }

        AlertManager.AlertType alertType = AlertManager.AlertType.ALERT_TYPE_USERVM_MIGRATE;
        if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER_MIGRATE;
        } else if (VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY_MIGRATE;
        }

        _networkMgr.prepareNicForMigration(profile, destination);
        volumeMgr.prepareForMigration(profile, destination);
        HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vm.getHypervisorType());
        VirtualMachineTO to = hvGuru.implement(profile);

        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), _nodeId, State.Migrating, vm.getType(), vm.getId());
        work.setStep(Step.Prepare);
        work.setResourceType(ItWorkVO.ResourceType.Host);
        work.setResourceId(destHostId);
        work = _workDao.persist(work);

        // Put the vm in migrating state.
        vm.setLastHostId(srcHostId);
        moveVmToMigratingState(vm, destHostId, work);

        boolean migrated = false;
        try {
            // Migrate the vm and its volume.
            volumeMgr.migrateVolumes(vm, to, srcHost, destHost, volumeToPoolMap);

            // Put the vm back to running state.
            moveVmOutofMigratingStateOnSuccess(vm, destHost.getId(), work);

            try {
                if (!checkVmOnHost(vm, destHostId)) {
                    s_logger.error("Vm not found on destination host. Unable to complete migration for " + vm);
                    try {
                        _agentMgr.send(srcHostId, new Commands(cleanup(vm.getInstanceName())), null);
                    } catch (AgentUnavailableException e) {
                        s_logger.error("AgentUnavailableException while cleanup on source host: " + srcHostId);
                    }
                    cleanup(vmGuru, new VirtualMachineProfileImpl(vm), work, Event.AgentReportStopped, true);
                    throw new CloudRuntimeException("VM not found on desintation host. Unable to complete migration for " + vm);
                }
            } catch (OperationTimedoutException e) {
                s_logger.warn("Error while checking the vm " + vm + " is on host " + destHost, e);
            }

            migrated = true;
        } finally {
            if (!migrated) {
                s_logger.info("Migration was unsuccessful.  Cleaning up: " + vm);
                _alertMgr.sendAlert(alertType, srcHost.getDataCenterId(), srcHost.getPodId(),
                        "Unable to migrate vm " + vm.getInstanceName() + " from host " + srcHost.getName() + " in zone " + dc.getName() + " and pod " + dc.getName(),
                        "Migrate Command failed.  Please check logs.");
                try {
                    _agentMgr.send(destHostId, new Commands(cleanup(vm.getInstanceName())), null);
                    stateTransitTo(vm, Event.OperationFailed, srcHostId);
                } catch (AgentUnavailableException e) {
                    s_logger.warn("Looks like the destination Host is unavailable for cleanup.", e);
                } catch (NoTransitionException e) {
                    s_logger.error("Error while transitioning vm from migrating to running state.", e);
                }
            }

            work.setStep(Step.Done);
            _workDao.update(work.getId(), work);
        }
    }

    @Override
    public VirtualMachineTO toVmTO(VirtualMachineProfile profile) {
        HypervisorGuru hvGuru = _hvGuruMgr.getGuru(profile.getVirtualMachine().getHypervisorType());
        VirtualMachineTO to = hvGuru.implement(profile);
        return to;
    }

    protected void cancelWorkItems(long nodeId) {
        GlobalLock scanLock = GlobalLock.getInternLock("vmmgr.cancel.workitem");

        try {
            if (scanLock.lock(3)) {
                try {
                    List<ItWorkVO> works = _workDao.listWorkInProgressFor(nodeId);
                    for (ItWorkVO work : works) {
                        s_logger.info("Handling unfinished work item: " + work);
                        try {
                            VMInstanceVO vm = _vmDao.findById(work.getInstanceId());
                            if (vm != null) {
                                if (work.getType() == State.Starting) {
                                    _haMgr.scheduleRestart(vm, true);
                                    work.setManagementServerId(_nodeId);
                                    work.setStep(Step.Done);
                                    _workDao.update(work.getId(), work);
                                } else if (work.getType() == State.Stopping) {
                                    _haMgr.scheduleStop(vm, vm.getHostId(), WorkType.CheckStop);
                                    work.setManagementServerId(_nodeId);
                                    work.setStep(Step.Done);
                                    _workDao.update(work.getId(), work);
                                } else if (work.getType() == State.Migrating) {
                                    _haMgr.scheduleMigration(vm);
                                    work.setStep(Step.Done);
                                    _workDao.update(work.getId(), work);
                                }
                            }
                        } catch (Exception e) {
                            s_logger.error("Error while handling " + work, e);
                        }
                    }
                } finally {
                    scanLock.unlock();
                }
            }
        } finally {
            scanLock.releaseRef();
        }
    }

    @Override
    public void migrateAway(String vmUuid, long srcHostId) throws InsufficientServerCapacityException {
        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance

            VmWorkJobVO placeHolder = null;
            VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                try {
                    orchestrateMigrateAway(vmUuid, srcHostId, null);
                } catch (InsufficientServerCapacityException e) {
                    s_logger.warn("Failed to deploy vm " + vmUuid + " with original planner, sending HAPlanner");
                    orchestrateMigrateAway(vmUuid, srcHostId, _haMgr.getHAPlanner());
                }
            } finally {
                _workJobDao.expunge(placeHolder.getId());
            }
        } else {
            Outcome<VirtualMachine> outcome = migrateVmAwayThroughJobQueue(vmUuid, srcHostId);

            try {
                VirtualMachine vm = outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobException = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobException != null) {
                if (jobException instanceof InsufficientServerCapacityException)
                    throw (InsufficientServerCapacityException)jobException;
                else if (jobException instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobException;
                else if (jobException instanceof RuntimeException)
                    throw (RuntimeException)jobException;
                else if (jobException instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobException);
            }
        }
    }

    private void orchestrateMigrateAway(String vmUuid, long srcHostId, DeploymentPlanner planner) throws InsufficientServerCapacityException {
        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        if (vm == null) {
            s_logger.debug("Unable to find a VM for " + vmUuid);
            throw new CloudRuntimeException("Unable to find " + vmUuid);
        }

        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);

        Long hostId = vm.getHostId();
        if (hostId == null) {
            s_logger.debug("Unable to migrate because the VM doesn't have a host id: " + vm);
            throw new CloudRuntimeException("Unable to migrate " + vmUuid);
        }

        Host host = _hostDao.findById(hostId);
        Long poolId = null;
        List<VolumeVO> vols = _volsDao.findReadyRootVolumesByInstance(vm.getId());
        for (VolumeVO rootVolumeOfVm : vols) {
            StoragePoolVO rootDiskPool = _storagePoolDao.findById(rootVolumeOfVm.getPoolId());
            if (rootDiskPool != null) {
                poolId = rootDiskPool.getId();
            }
        }

        DataCenterDeployment plan = new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), null, poolId, null);
        ExcludeList excludes = new ExcludeList();
        excludes.addHost(hostId);

        DeployDestination dest = null;
        while (true) {

            try {
                dest = _dpMgr.planDeployment(profile, plan, excludes, planner);
            } catch (AffinityConflictException e2) {
                s_logger.warn("Unable to create deployment, affinity rules associted to the VM conflict", e2);
                throw new CloudRuntimeException("Unable to create deployment, affinity rules associted to the VM conflict");
            }

            if (dest != null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Found destination " + dest + " for migrating to.");
                }
            } else {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Unable to find destination for migrating the vm " + profile);
                }
                throw new InsufficientServerCapacityException("Unable to find a server to migrate to.", host.getClusterId());
            }

            excludes.addHost(dest.getHost().getId());
            try {
                migrate(vm, srcHostId, dest);
                return;
            } catch (ResourceUnavailableException e) {
                s_logger.debug("Unable to migrate to unavailable " + dest);
            } catch (ConcurrentOperationException e) {
                s_logger.debug("Unable to migrate VM due to: " + e.getMessage());
            }

            try {
                advanceStop(vmUuid, true);
                throw new CloudRuntimeException("Unable to migrate " + vm);
            } catch (ResourceUnavailableException e) {
                s_logger.debug("Unable to stop VM due to " + e.getMessage());
                throw new CloudRuntimeException("Unable to migrate " + vm);
            } catch (ConcurrentOperationException e) {
                s_logger.debug("Unable to stop VM due to " + e.getMessage());
                throw new CloudRuntimeException("Unable to migrate " + vm);
            } catch (OperationTimedoutException e) {
                s_logger.debug("Unable to stop VM due to " + e.getMessage());
                throw new CloudRuntimeException("Unable to migrate " + vm);
            }
        }
    }

    protected class CleanupTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            s_logger.trace("VM Operation Thread Running");
            try {
                _workDao.cleanup(VmOpCleanupWait.value());

                // TODO. hard-coded to one hour after job has been completed
                Date cutDate = new Date(new Date().getTime() - 3600000);
                _workJobDao.expungeCompletedWorkJobs(cutDate);
            } catch (Exception e) {
                s_logger.error("VM Operations failed due to ", e);
            }
        }
    }

    @Override
    public boolean isVirtualMachineUpgradable(VirtualMachine vm, ServiceOffering offering) {
        boolean isMachineUpgradable = true;
        for (HostAllocator allocator : hostAllocators) {
            isMachineUpgradable = allocator.isVirtualMachineUpgradable(vm, offering);
            if (isMachineUpgradable)
                continue;
            else
                break;
        }

        return isMachineUpgradable;
    }

    @Override
    public void reboot(String vmUuid, Map<VirtualMachineProfile.Param, Object> params) throws InsufficientCapacityException, ResourceUnavailableException {
        try {
            advanceReboot(vmUuid, params);
        } catch (ConcurrentOperationException e) {
            throw new CloudRuntimeException("Unable to reboot a VM due to concurrent operation", e);
        }
    }

    @Override
    public void advanceReboot(String vmUuid, Map<VirtualMachineProfile.Param, Object> params)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {

        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if ( jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance
            VmWorkJobVO placeHolder = null;
            VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateReboot(vmUuid, params);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            Outcome<VirtualMachine> outcome = rebootVmThroughJobQueue(vmUuid, params);

            try {
                VirtualMachine vm = outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobResult != null) {
                if (jobResult instanceof ResourceUnavailableException)
                    throw (ResourceUnavailableException)jobResult;
                else if (jobResult instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobResult;
                else if (jobResult instanceof InsufficientCapacityException)
                    throw (InsufficientCapacityException)jobResult;
                else if (jobResult instanceof RuntimeException)
                    throw (RuntimeException)jobResult;
                else if (jobResult instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
            }
        }
    }

    private void orchestrateReboot(String vmUuid, Map<VirtualMachineProfile.Param, Object> params) throws InsufficientCapacityException, ConcurrentOperationException,
    ResourceUnavailableException {
        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        DataCenter dc = _entityMgr.findById(DataCenter.class, vm.getDataCenterId());
        Host host = _hostDao.findById(vm.getHostId());
        if (host == null) {
            // Should findById throw an Exception is the host is not found?
            throw new CloudRuntimeException("Unable to retrieve host with id " + vm.getHostId());
        }
        Cluster cluster = _entityMgr.findById(Cluster.class, host.getClusterId());
        Pod pod = _entityMgr.findById(Pod.class, host.getPodId());
        DeployDestination dest = new DeployDestination(dc, pod, cluster, host);

        try {

            Commands cmds = new Commands(Command.OnError.Stop);
            cmds.addCommand(new RebootCommand(vm.getInstanceName()));
            _agentMgr.send(host.getId(), cmds);

            Answer rebootAnswer = cmds.getAnswer(RebootAnswer.class);
            if (rebootAnswer != null && rebootAnswer.getResult()) {
                return;
            }
            s_logger.info("Unable to reboot VM " + vm + " on " + dest.getHost() + " due to " + (rebootAnswer == null ? " no reboot answer" : rebootAnswer.getDetails()));
        } catch (OperationTimedoutException e) {
            s_logger.warn("Unable to send the reboot command to host " + dest.getHost() + " for the vm " + vm + " due to operation timeout", e);
            throw new CloudRuntimeException("Failed to reboot the vm on host " + dest.getHost());
        }
    }

    public Command cleanup(VirtualMachine vm) {
        return new StopCommand(vm, getExecuteInSequence(vm.getHypervisorType()), false);
    }

    public Command cleanup(String vmName) {
        return new StopCommand(vmName, getExecuteInSequence(null), false);

    }


    // this is XenServer specific
    public void syncVMMetaData(Map<String, String> vmMetadatum) {
        if (vmMetadatum == null || vmMetadatum.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : vmMetadatum.entrySet()) {
            String name = entry.getKey();
            String platform = entry.getValue();
            if (platform == null || platform.isEmpty()) {
                continue;
            }
            VMInstanceVO vm = _vmDao.findVMByInstanceName(name);
            if (vm != null && vm.getType() == VirtualMachine.Type.User) {
                boolean changed = false;
                UserVmVO userVm = _userVmDao.findById(vm.getId());
                _userVmDao.loadDetails(userVm);
                if ( userVm.details.containsKey("timeoffset")) {
                    userVm.details.remove("timeoffset");
                    changed = true;
                }
                if (!userVm.details.containsKey("platform") || !userVm.details.get("platform").equals(platform)) {
                    userVm.setDetail("platform",  platform);
                    changed = true;
                }
                String pvdriver = "xenserver56";
                if ( platform.contains("device_id")) {
                    pvdriver = "xenserver61";
                }
                if (!userVm.details.containsKey("hypervisortoolsversion") || !userVm.details.get("hypervisortoolsversion").equals(pvdriver)) {
                    userVm.setDetail("hypervisortoolsversion", pvdriver);
                    changed = true;
                }
                if ( changed ) {
                    _userVmDao.saveDetails(userVm);
                }
            }
        }
    }


    private void ensureVmRunningContext(long hostId, VMInstanceVO vm, Event cause) throws OperationTimedoutException, ResourceUnavailableException,
    NoTransitionException, InsufficientAddressCapacityException {
        VirtualMachineGuru vmGuru = getVmGuru(vm);

        s_logger.debug("VM state is starting on full sync so updating it to running");
        vm = _vmDao.findById(vm.getId());

        // grab outstanding work item if any
        ItWorkVO work = _workDao.findByOutstandingWork(vm.getId(), vm.getState());
        if (work != null) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Found an outstanding work item for this vm " + vm + " in state:" + vm.getState() + ", work id:" + work.getId());
            }
        }

        try {
            stateTransitTo(vm, cause, hostId);
        } catch (NoTransitionException e1) {
            s_logger.warn(e1.getMessage());
        }

        s_logger.debug("VM's " + vm + " state is starting on full sync so updating it to Running");
        vm = _vmDao.findById(vm.getId()); // this should ensure vm has the most
        // up to date info

        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        List<NicVO> nics = _nicsDao.listByVmId(profile.getId());
        for (NicVO nic : nics) {
            Network network = _networkModel.getNetwork(nic.getNetworkId());
            NicProfile nicProfile =
                    new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), null, _networkModel.isSecurityGroupSupportedInNetwork(network),
                            _networkModel.getNetworkTag(profile.getHypervisorType(), network));
            profile.addNic(nicProfile);
        }

        Commands cmds = new Commands(Command.OnError.Stop);
        s_logger.debug("Finalizing commands that need to be send to complete Start process for the vm " + vm);

        if (vmGuru.finalizeCommandsOnStart(cmds, profile)) {
            if (cmds.size() != 0) {
                _agentMgr.send(vm.getHostId(), cmds);
            }

            if (vmGuru.finalizeStart(profile, vm.getHostId(), cmds, null)) {
                stateTransitTo(vm, cause, vm.getHostId());
            } else {
                s_logger.error("Unable to finish finialization for running vm: " + vm);
            }
        } else {
            s_logger.error("Unable to finalize commands on start for vm: " + vm);
        }

        if (work != null) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Updating outstanding work item to Done, id:" + work.getId());
            }
            work.setStep(Step.Done);
            _workDao.update(work.getId(), work);
        }
    }

    @Override
    public boolean isRecurring() {
        return true;
    }

    @Override
    public boolean processAnswers(long agentId, long seq, Answer[] answers) {
        for (final Answer answer : answers) {
            if ( answer instanceof ClusterVMMetaDataSyncAnswer) {
                ClusterVMMetaDataSyncAnswer cvms = (ClusterVMMetaDataSyncAnswer)answer;
                if (!cvms.isExecuted()) {
                    syncVMMetaData(cvms.getVMMetaDatum());
                    cvms.setExecuted();
                }
            }
        }
        return true;
    }

    @Override
    public boolean processTimeout(long agentId, long seq) {
        return true;
    }

    @Override
    public int getTimeout() {
        return -1;
    }

    @Override
    public boolean processCommands(long agentId, long seq, Command[] cmds) {
        boolean processed = false;
        for (Command cmd : cmds) {
            if (cmd instanceof PingRoutingCommand) {
                PingRoutingCommand ping = (PingRoutingCommand)cmd;
                if (ping.getHostVmStateReport() != null) {
                    _syncMgr.processHostVmStatePingReport(agentId, ping.getHostVmStateReport());
                }

                // take the chance to scan VMs that are stuck in transitional states
                // and are missing from the report
                scanStalledVMInTransitionStateOnUpHost(agentId);
                processed = true;
            }
        }
        return processed;
    }

    @Override
    public AgentControlAnswer processControlCommand(long agentId, AgentControlCommand cmd) {
        return null;
    }

    @Override
    public boolean processDisconnect(long agentId, Status state) {
        return true;
    }

    @Override
    public void processConnect(Host agent, StartupCommand cmd, boolean forRebalance) throws ConnectionException {
        if (!(cmd instanceof StartupRoutingCommand)) {
            return;
        }

        if(s_logger.isDebugEnabled())
            s_logger.debug("Received startup command from hypervisor host. host id: " + agent.getId());

        _syncMgr.resetHostSyncState(agent.getId());

        if (forRebalance) {
            s_logger.debug("Not processing listener " + this + " as connect happens on rebalance process");
            return;
        }

        if (forRebalance) {
            s_logger.debug("Not processing listener " + this + " as connect happens on rebalance process");
            return;
        }

        Long clusterId = agent.getClusterId();
        long agentId = agent.getId();

        if (agent.getHypervisorType() == HypervisorType.XenServer) { // only for Xen
            // initiate the cron job
            ClusterVMMetaDataSyncCommand syncVMMetaDataCmd = new ClusterVMMetaDataSyncCommand(ClusterVMMetaDataSyncInterval.value(), clusterId);
            try {
                long seq_no = _agentMgr.send(agentId, new Commands(syncVMMetaDataCmd), this);
                s_logger.debug("Cluster VM metadata sync started with jobid " + seq_no);
            } catch (AgentUnavailableException e) {
                s_logger.fatal("The Cluster VM metadata sync process failed for cluster id " + clusterId + " with ", e);
            }
        }
    }

    protected class TransitionTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            GlobalLock lock = GlobalLock.getInternLock("TransitionChecking");
            if (lock == null) {
                s_logger.debug("Couldn't get the global lock");
                return;
            }

            if (!lock.lock(30)) {
                s_logger.debug("Couldn't lock the db");
                return;
            }
            try {
                scanStalledVMInTransitionStateOnDisconnectedHosts();

                List<VMInstanceVO> instances = _vmDao.findVMInTransition(new Date(new Date().getTime() - (AgentManager.Wait.value() * 1000)), State.Starting, State.Stopping);
                for (VMInstanceVO instance : instances) {
                    State state = instance.getState();
                    if (state == State.Stopping) {
                        _haMgr.scheduleStop(instance, instance.getHostId(), WorkType.CheckStop);
                    } else if (state == State.Starting) {
                        _haMgr.scheduleRestart(instance, true);
                    }
                }
            } catch (Exception e) {
                s_logger.warn("Caught the following exception on transition checking", e);
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public VMInstanceVO findById(long vmId) {
        return _vmDao.findById(vmId);
    }

    @Override
    public void checkIfCanUpgrade(VirtualMachine vmInstance, ServiceOffering newServiceOffering) {
        if (newServiceOffering == null) {
            throw new InvalidParameterValueException("Invalid parameter, newServiceOffering can't be null");
        }

        // Check that the VM is stopped / running
        if (!(vmInstance.getState().equals(State.Stopped) || vmInstance.getState().equals(State.Running))) {
            s_logger.warn("Unable to upgrade virtual machine " + vmInstance.toString() + " in state " + vmInstance.getState());
            throw new InvalidParameterValueException("Unable to upgrade virtual machine " + vmInstance.toString() + " " + " in state " + vmInstance.getState() +
                    "; make sure the virtual machine is stopped/running");
        }

        // Check if the service offering being upgraded to is what the VM is already running with
        if (!newServiceOffering.isDynamic() && vmInstance.getServiceOfferingId() == newServiceOffering.getId()) {
            if (s_logger.isInfoEnabled()) {
                s_logger.info("Not upgrading vm " + vmInstance.toString() + " since it already has the requested " + "service offering (" + newServiceOffering.getName() +
                        ")");
            }

            throw new InvalidParameterValueException("Not upgrading vm " + vmInstance.toString() + " since it already " + "has the requested service offering (" +
                    newServiceOffering.getName() + ")");
        }

        ServiceOfferingVO currentServiceOffering = _offeringDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());

        // Check that the service offering being upgraded to has the same Guest IP type as the VM's current service offering
        // NOTE: With the new network refactoring in 2.2, we shouldn't need the check for same guest IP type anymore.
        /*
         * if (!currentServiceOffering.getGuestIpType().equals(newServiceOffering.getGuestIpType())) { String errorMsg =
         * "The service offering being upgraded to has a guest IP type: " + newServiceOffering.getGuestIpType(); errorMsg +=
         * ". Please select a service offering with the same guest IP type as the VM's current service offering (" +
         * currentServiceOffering.getGuestIpType() + ")."; throw new InvalidParameterValueException(errorMsg); }
         */

        // Check that the service offering being upgraded to has the same storage pool preference as the VM's current service
        // offering
        if (currentServiceOffering.getUseLocalStorage() != newServiceOffering.getUseLocalStorage()) {
            throw new InvalidParameterValueException("Unable to upgrade virtual machine " + vmInstance.toString() +
                    ", cannot switch between local storage and shared storage service offerings.  Current offering " + "useLocalStorage=" +
                    currentServiceOffering.getUseLocalStorage() + ", target offering useLocalStorage=" + newServiceOffering.getUseLocalStorage());
        }

        // if vm is a system vm, check if it is a system service offering, if yes return with error as it cannot be used for user vms
        if (currentServiceOffering.getSystemUse() != newServiceOffering.getSystemUse()) {
            throw new InvalidParameterValueException("isSystem property is different for current service offering and new service offering");
        }

        // Check that there are enough resources to upgrade the service offering
        if (!isVirtualMachineUpgradable(vmInstance, newServiceOffering)) {
            throw new InvalidParameterValueException("Unable to upgrade virtual machine, not enough resources available " + "for an offering of " +
                    newServiceOffering.getCpu() + " cpu(s) at " + newServiceOffering.getSpeed() + " Mhz, and " + newServiceOffering.getRamSize() + " MB of memory");
        }

        // Check that the service offering being upgraded to has storage tags subset of the current service offering storage tags, since volume is not migrated.
        List<String> currentTags = StringUtils.csvTagsToList(currentServiceOffering.getTags());
        List<String> newTags = StringUtils.csvTagsToList(newServiceOffering.getTags());
        if (!currentTags.containsAll(newTags)) {
            throw new InvalidParameterValueException("Unable to upgrade virtual machine; the new service offering " + " should have tags as subset of " +
                    "current service offering tags. Current service offering tags: " + currentTags + "; " + "new service " + "offering tags: " + newTags);
        }
    }

    @Override
    public boolean upgradeVmDb(long vmId, long serviceOfferingId) {
        VMInstanceVO vmForUpdate = _vmDao.createForUpdate();
        vmForUpdate.setServiceOfferingId(serviceOfferingId);
        ServiceOffering newSvcOff = _entityMgr.findById(ServiceOffering.class, serviceOfferingId);
        vmForUpdate.setHaEnabled(newSvcOff.getOfferHA());
        vmForUpdate.setLimitCpuUse(newSvcOff.getLimitCpuUse());
        vmForUpdate.setServiceOfferingId(newSvcOff.getId());
        return _vmDao.update(vmId, vmForUpdate);
    }

    @Override
    public NicProfile addVmToNetwork(VirtualMachine vm, Network network, NicProfile requested)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {

        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance
            VmWorkJobVO placeHolder = null;
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                return orchestrateAddVmToNetwork(vm, network, requested);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            Outcome<VirtualMachine> outcome = addVmToNetworkThroughJobQueue(vm, network, requested);

            try {
                outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution exception", e);
            }

            Object jobException = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobException != null) {
                if (jobException instanceof ResourceUnavailableException)
                    throw (ResourceUnavailableException)jobException;
                else if (jobException instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobException;
                else if (jobException instanceof InsufficientCapacityException)
                    throw (InsufficientCapacityException)jobException;
                else if (jobException instanceof RuntimeException)
                    throw (RuntimeException)jobException;
                else if (jobException instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobException);
                else if (jobException instanceof NicProfile)
                    return (NicProfile)jobException;
            }

            throw new RuntimeException("Unexpected job execution result");
        }
    }

    private NicProfile orchestrateAddVmToNetwork(VirtualMachine vm, Network network, NicProfile requested) throws ConcurrentOperationException, ResourceUnavailableException,
    InsufficientCapacityException {
        CallContext cctx = CallContext.current();

        s_logger.debug("Adding vm " + vm + " to network " + network + "; requested nic profile " + requested);
        VMInstanceVO vmVO = _vmDao.findById(vm.getId());
        ReservationContext context = new ReservationContextImpl(null, null, cctx.getCallingUser(), cctx.getCallingAccount());

        VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmVO, null, null, null, null);

        DataCenter dc = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        Host host = _hostDao.findById(vm.getHostId());
        DeployDestination dest = new DeployDestination(dc, null, null, host);

        //check vm state
        if (vm.getState() == State.Running) {
            //1) allocate and prepare nic
            NicProfile nic = _networkMgr.createNicForVm(network, requested, context, vmProfile, true);

            //2) Convert vmProfile to vmTO
            HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vmProfile.getVirtualMachine().getHypervisorType());
            VirtualMachineTO vmTO = hvGuru.implement(vmProfile);

            //3) Convert nicProfile to NicTO
            NicTO nicTO = toNicTO(nic, vmProfile.getVirtualMachine().getHypervisorType());

            //4) plug the nic to the vm
            s_logger.debug("Plugging nic for vm " + vm + " in network " + network);

            boolean result = false;
            try {
                result = plugNic(network, nicTO, vmTO, context, dest);
                if (result) {
                    s_logger.debug("Nic is plugged successfully for vm " + vm + " in network " + network + ". Vm  is a part of network now");
                    long isDefault = (nic.isDefaultNic()) ? 1 : 0;
                    // insert nic's Id into DB as resource_name
                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vmVO.getAccountId(), vmVO.getDataCenterId(), vmVO.getId(),
                            Long.toString(nic.getId()), network.getNetworkOfferingId(), null, isDefault, VirtualMachine.class.getName(), vmVO.getUuid(), vm.isDisplay());
                    return nic;
                } else {
                    s_logger.warn("Failed to plug nic to the vm " + vm + " in network " + network);
                    return null;
                }
            } finally {
                if (!result) {
                    s_logger.debug("Removing nic " + nic + " from vm " + vmProfile.getVirtualMachine() + " as nic plug failed on the backend");
                    _networkMgr.removeNic(vmProfile, _nicsDao.findById(nic.getId()));
                }
            }
        } else if (vm.getState() == State.Stopped) {
            //1) allocate nic
            return _networkMgr.createNicForVm(network, requested, context, vmProfile, false);
        } else {
            s_logger.warn("Unable to add vm " + vm + " to network  " + network);
            throw new ResourceUnavailableException("Unable to add vm " + vm + " to network, is not in the right state", DataCenter.class, vm.getDataCenterId());
        }
    }

    @Override
    public NicTO toNicTO(NicProfile nic, HypervisorType hypervisorType) {
        HypervisorGuru hvGuru = _hvGuruMgr.getGuru(hypervisorType);

        NicTO nicTO = hvGuru.toNicTO(nic);
        return nicTO;
    }

    @Override
    public boolean removeNicFromVm(VirtualMachine vm, Nic nic)
            throws ConcurrentOperationException, ResourceUnavailableException {

        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance
            VmWorkJobVO placeHolder = null;
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                return orchestrateRemoveNicFromVm(vm, nic);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }

        } else {
            Outcome<VirtualMachine> outcome = removeNicFromVmThroughJobQueue(vm, nic);

            try {
                outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobResult != null) {
                if (jobResult instanceof ResourceUnavailableException)
                    throw (ResourceUnavailableException)jobResult;
                else if (jobResult instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobResult;
                else if (jobResult instanceof RuntimeException)
                    throw (RuntimeException)jobResult;
                else if (jobResult instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
                else if (jobResult instanceof Boolean)
                    return (Boolean)jobResult;
            }

            throw new RuntimeException("Job failed with un-handled exception");
        }
    }

    private boolean orchestrateRemoveNicFromVm(VirtualMachine vm, Nic nic) throws ConcurrentOperationException, ResourceUnavailableException {
        CallContext cctx = CallContext.current();
        VMInstanceVO vmVO = _vmDao.findById(vm.getId());
        NetworkVO network = _networkDao.findById(nic.getNetworkId());
        ReservationContext context = new ReservationContextImpl(null, null, cctx.getCallingUser(), cctx.getCallingAccount());

        VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmVO, null, null, null, null);

        DataCenter dc = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        Host host = _hostDao.findById(vm.getHostId());
        DeployDestination dest = new DeployDestination(dc, null, null, host);
        HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vmProfile.getVirtualMachine().getHypervisorType());
        VirtualMachineTO vmTO = hvGuru.implement(vmProfile);

        // don't delete default NIC on a user VM
        if (nic.isDefaultNic() && vm.getType() == VirtualMachine.Type.User) {
            s_logger.warn("Failed to remove nic from " + vm + " in " + network + ", nic is default.");
            throw new CloudRuntimeException("Failed to remove nic from " + vm + " in " + network + ", nic is default.");
        }

        // if specified nic is associated with PF/LB/Static NAT
        if (rulesMgr.listAssociatedRulesForGuestNic(nic).size() > 0) {
            throw new CloudRuntimeException("Failed to remove nic from " + vm + " in " + network +
                    ", nic has associated Port forwarding or Load balancer or Static NAT rules.");
        }

        NicProfile nicProfile =
                new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), _networkModel.getNetworkRate(network.getId(), vm.getId()),
                        _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vmProfile.getVirtualMachine().getHypervisorType(), network));

        //1) Unplug the nic
        if (vm.getState() == State.Running) {
            NicTO nicTO = toNicTO(nicProfile, vmProfile.getVirtualMachine().getHypervisorType());
            s_logger.debug("Un-plugging nic " + nic + " for vm " + vm + " from network " + network);
            boolean result = unplugNic(network, nicTO, vmTO, context, dest);
            if (result) {
                s_logger.debug("Nic is unplugged successfully for vm " + vm + " in network " + network);
                long isDefault = (nic.isDefaultNic()) ? 1 : 0;
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_REMOVE, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                        Long.toString(nic.getId()), network.getNetworkOfferingId(), null, isDefault, VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplay());
            } else {
                s_logger.warn("Failed to unplug nic for the vm " + vm + " from network " + network);
                return false;
            }
        } else if (vm.getState() != State.Stopped) {
            s_logger.warn("Unable to remove vm " + vm + " from network  " + network);
            throw new ResourceUnavailableException("Unable to remove vm " + vm + " from network, is not in the right state", DataCenter.class, vm.getDataCenterId());
        }

        //2) Release the nic
        _networkMgr.releaseNic(vmProfile, nic);
        s_logger.debug("Successfully released nic " + nic + "for vm " + vm);

        //3) Remove the nic
        _networkMgr.removeNic(vmProfile, nic);
        _nicsDao.expunge(nic.getId());
        return true;
    }

    @Override
    @DB
    public boolean removeVmFromNetwork(VirtualMachine vm, Network network, URI broadcastUri) throws ConcurrentOperationException, ResourceUnavailableException {
        // TODO will serialize on the VM object later to resolve operation conflicts
        return orchestrateRemoveVmFromNetwork(vm, network, broadcastUri);
    }

    @DB
    private boolean orchestrateRemoveVmFromNetwork(VirtualMachine vm, Network network, URI broadcastUri) throws ConcurrentOperationException, ResourceUnavailableException {
        CallContext cctx = CallContext.current();
        VMInstanceVO vmVO = _vmDao.findById(vm.getId());
        ReservationContext context = new ReservationContextImpl(null, null, cctx.getCallingUser(), cctx.getCallingAccount());

        VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmVO, null, null, null, null);

        DataCenter dc = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        Host host = _hostDao.findById(vm.getHostId());
        DeployDestination dest = new DeployDestination(dc, null, null, host);
        HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vmProfile.getVirtualMachine().getHypervisorType());
        VirtualMachineTO vmTO = hvGuru.implement(vmProfile);

        Nic nic = null;
        if (broadcastUri != null) {
            nic = _nicsDao.findByNetworkIdInstanceIdAndBroadcastUri(network.getId(), vm.getId(), broadcastUri.toString());
        } else {
            nic = _networkModel.getNicInNetwork(vm.getId(), network.getId());
        }

        if (nic == null) {
            s_logger.warn("Could not get a nic with " + network);
            return false;
        }

        // don't delete default NIC on a user VM
        if (nic.isDefaultNic() && vm.getType() == VirtualMachine.Type.User) {
            s_logger.warn("Failed to remove nic from " + vm + " in " + network + ", nic is default.");
            throw new CloudRuntimeException("Failed to remove nic from " + vm + " in " + network + ", nic is default.");
        }

        //Lock on nic is needed here
        Nic lock = _nicsDao.acquireInLockTable(nic.getId());
        if (lock == null) {
            //check if nic is still there. Return if it was released already
            if (_nicsDao.findById(nic.getId()) == null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Not need to remove the vm " + vm + " from network " + network + " as the vm doesn't have nic in this network");
                }
                return true;
            }
            throw new ConcurrentOperationException("Unable to lock nic " + nic.getId());
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Lock is acquired for nic id " + lock.getId() + " as a part of remove vm " + vm + " from network " + network);
        }

        try {
            NicProfile nicProfile =
                    new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), _networkModel.getNetworkRate(network.getId(), vm.getId()),
                            _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vmProfile.getVirtualMachine().getHypervisorType(), network));

            //1) Unplug the nic
            if (vm.getState() == State.Running) {
                NicTO nicTO = toNicTO(nicProfile, vmProfile.getVirtualMachine().getHypervisorType());
                s_logger.debug("Un-plugging nic for vm " + vm + " from network " + network);
                boolean result = unplugNic(network, nicTO, vmTO, context, dest);
                if (result) {
                    s_logger.debug("Nic is unplugged successfully for vm " + vm + " in network " + network);
                } else {
                    s_logger.warn("Failed to unplug nic for the vm " + vm + " from network " + network);
                    return false;
                }
            } else if (vm.getState() != State.Stopped) {
                s_logger.warn("Unable to remove vm " + vm + " from network  " + network);
                throw new ResourceUnavailableException("Unable to remove vm " + vm + " from network, is not in the right state", DataCenter.class, vm.getDataCenterId());
            }

            //2) Release the nic
            _networkMgr.releaseNic(vmProfile, nic);
            s_logger.debug("Successfully released nic " + nic + "for vm " + vm);

            //3) Remove the nic
            _networkMgr.removeNic(vmProfile, nic);
            return true;
        } finally {
            if (lock != null) {
                _nicsDao.releaseFromLockTable(lock.getId());
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Lock is released for nic id " + lock.getId() + " as a part of remove vm " + vm + " from network " + network);
                }
            }
        }
    }

    @Override
    public void findHostAndMigrate(String vmUuid, Long newSvcOfferingId, ExcludeList excludes) throws InsufficientCapacityException, ConcurrentOperationException,
    ResourceUnavailableException {

        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        if (vm == null) {
            throw new CloudRuntimeException("Unable to find " + vmUuid);
        }

        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);

        Long srcHostId = vm.getHostId();
        Long oldSvcOfferingId = vm.getServiceOfferingId();
        if (srcHostId == null) {
            throw new CloudRuntimeException("Unable to scale the vm because it doesn't have a host id");
        }
        Host host = _hostDao.findById(srcHostId);
        DataCenterDeployment plan = new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), null, null, null);
        excludes.addHost(vm.getHostId());
        vm.setServiceOfferingId(newSvcOfferingId); // Need to find the destination host based on new svc offering

        DeployDestination dest = null;

        try {
            dest = _dpMgr.planDeployment(profile, plan, excludes, null);
        } catch (AffinityConflictException e2) {
            s_logger.warn("Unable to create deployment, affinity rules associted to the VM conflict", e2);
            throw new CloudRuntimeException("Unable to create deployment, affinity rules associted to the VM conflict");
        }

        if (dest != null) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug(" Found " + dest + " for scaling the vm to.");
            }
        }

        if (dest == null) {
            throw new InsufficientServerCapacityException("Unable to find a server to scale the vm to.", host.getClusterId());
        }

        excludes.addHost(dest.getHost().getId());
        try {
            migrateForScale(vm.getUuid(), srcHostId, dest, oldSvcOfferingId);
        } catch (ResourceUnavailableException e) {
            s_logger.debug("Unable to migrate to unavailable " + dest);
            throw e;
        } catch (ConcurrentOperationException e) {
            s_logger.debug("Unable to migrate VM due to: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public void migrateForScale(String vmUuid, long srcHostId, DeployDestination dest, Long oldSvcOfferingId)
            throws ResourceUnavailableException, ConcurrentOperationException {
        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance
            VmWorkJobVO placeHolder = null;
            VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateMigrateForScale(vmUuid, srcHostId, dest, oldSvcOfferingId);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            Outcome<VirtualMachine> outcome = migrateVmForScaleThroughJobQueue(vmUuid, srcHostId, dest, oldSvcOfferingId);

            try {
                VirtualMachine vm = outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobResult != null) {
                if (jobResult instanceof ResourceUnavailableException)
                    throw (ResourceUnavailableException)jobResult;
                else if (jobResult instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobResult;
                else if (jobResult instanceof RuntimeException)
                    throw (RuntimeException)jobResult;
                else if (jobResult instanceof Throwable)
                    throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
            }
        }
    }

    private void orchestrateMigrateForScale(String vmUuid, long srcHostId, DeployDestination dest, Long oldSvcOfferingId)
            throws ResourceUnavailableException, ConcurrentOperationException {

        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        s_logger.info("Migrating " + vm + " to " + dest);

        vm.getServiceOfferingId();
        long dstHostId = dest.getHost().getId();
        Host fromHost = _hostDao.findById(srcHostId);
        if (fromHost == null) {
            s_logger.info("Unable to find the host to migrate from: " + srcHostId);
            throw new CloudRuntimeException("Unable to find the host to migrate from: " + srcHostId);
        }

        if (fromHost.getClusterId().longValue() != dest.getCluster().getId()) {
            s_logger.info("Source and destination host are not in same cluster, unable to migrate to host: " + dest.getHost().getId());
            throw new CloudRuntimeException("Source and destination host are not in same cluster, unable to migrate to host: " + dest.getHost().getId());
        }

        VirtualMachineGuru vmGuru = getVmGuru(vm);

        long vmId = vm.getId();
        vm = _vmDao.findByUuid(vmUuid);
        if (vm == null) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Unable to find the vm " + vm);
            }
            throw new CloudRuntimeException("Unable to find a virtual machine with id " + vmId);
        }

        if (vm.getState() != State.Running) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("VM is not Running, unable to migrate the vm " + vm);
            }
            throw new CloudRuntimeException("VM is not Running, unable to migrate the vm currently " + vm + " , current state: " + vm.getState().toString());
        }

        AlertManager.AlertType alertType = AlertManager.AlertType.ALERT_TYPE_USERVM_MIGRATE;
        if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER_MIGRATE;
        } else if (VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY_MIGRATE;
        }

        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        _networkMgr.prepareNicForMigration(profile, dest);
        volumeMgr.prepareForMigration(profile, dest);

        VirtualMachineTO to = toVmTO(profile);
        PrepareForMigrationCommand pfmc = new PrepareForMigrationCommand(to);

        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), _nodeId, State.Migrating, vm.getType(), vm.getId());
        work.setStep(Step.Prepare);
        work.setResourceType(ItWorkVO.ResourceType.Host);
        work.setResourceId(dstHostId);
        work = _workDao.persist(work);

        Answer pfma = null;
        try {
            pfma = _agentMgr.send(dstHostId, pfmc);
            if (pfma == null || !pfma.getResult()) {
                String msg = "Unable to prepare for migration due to " + pfma.getDetails();
                pfma = null;
                throw new AgentUnavailableException(msg, dstHostId);
            }
        } catch (OperationTimedoutException e1) {
            throw new AgentUnavailableException("Operation timed out", dstHostId);
        } finally {
            if (pfma == null) {
                work.setStep(Step.Done);
                _workDao.update(work.getId(), work);
            }
        }

        vm.setLastHostId(srcHostId);
        try {
            if (vm == null || vm.getHostId() == null || vm.getHostId() != srcHostId || !changeState(vm, Event.MigrationRequested, dstHostId, work, Step.Migrating)) {
                s_logger.info("Migration cancelled because state has changed: " + vm);
                throw new ConcurrentOperationException("Migration cancelled because state has changed: " + vm);
            }
        } catch (NoTransitionException e1) {
            s_logger.info("Migration cancelled because " + e1.getMessage());
            throw new ConcurrentOperationException("Migration cancelled because " + e1.getMessage());
        }

        boolean migrated = false;
        try {
            boolean isWindows = _guestOsCategoryDao.findById(_guestOsDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");
            MigrateCommand mc = new MigrateCommand(vm.getInstanceName(), dest.getHost().getPrivateIpAddress(), isWindows, to, getExecuteInSequence(vm.getHypervisorType()));
            mc.setHostGuid(dest.getHost().getGuid());

            try {
                Answer ma = _agentMgr.send(vm.getLastHostId(), mc);
                if (ma == null || !ma.getResult()) {
                    s_logger.error("Unable to migrate due to " + ma.getDetails());
                    throw new CloudRuntimeException("Unable to migrate due to " + ma.getDetails());
                }
            } catch (OperationTimedoutException e) {
                if (e.isActive()) {
                    s_logger.warn("Active migration command so scheduling a restart for " + vm);
                    _haMgr.scheduleRestart(vm, true);
                }
                throw new AgentUnavailableException("Operation timed out on migrating " + vm, dstHostId);
            }

            try {
                long newServiceOfferingId = vm.getServiceOfferingId();
                vm.setServiceOfferingId(oldSvcOfferingId); // release capacity for the old service offering only
                if (!changeState(vm, VirtualMachine.Event.OperationSucceeded, dstHostId, work, Step.Started)) {
                    throw new ConcurrentOperationException("Unable to change the state for " + vm);
                }
                vm.setServiceOfferingId(newServiceOfferingId);
            } catch (NoTransitionException e1) {
                throw new ConcurrentOperationException("Unable to change state due to " + e1.getMessage());
            }

            try {
                if (!checkVmOnHost(vm, dstHostId)) {
                    s_logger.error("Unable to complete migration for " + vm);
                    try {
                        _agentMgr.send(srcHostId, new Commands(cleanup(vm.getInstanceName())), null);
                    } catch (AgentUnavailableException e) {
                        s_logger.error("AgentUnavailableException while cleanup on source host: " + srcHostId);
                    }
                    cleanup(vmGuru, new VirtualMachineProfileImpl(vm), work, Event.AgentReportStopped, true);
                    throw new CloudRuntimeException("Unable to complete migration for " + vm);
                }
            } catch (OperationTimedoutException e) {
                s_logger.debug("Error while checking the vm " + vm + " on host " + dstHostId, e);
            }

            migrated = true;
        } finally {
            if (!migrated) {
                s_logger.info("Migration was unsuccessful.  Cleaning up: " + vm);

                _alertMgr.sendAlert(alertType, fromHost.getDataCenterId(), fromHost.getPodId(),
                        "Unable to migrate vm " + vm.getInstanceName() + " from host " + fromHost.getName() + " in zone " + dest.getDataCenter().getName() + " and pod " +
                                dest.getPod().getName(), "Migrate Command failed.  Please check logs.");
                try {
                    _agentMgr.send(dstHostId, new Commands(cleanup(vm.getInstanceName())), null);
                } catch (AgentUnavailableException ae) {
                    s_logger.info("Looks like the destination Host is unavailable for cleanup");
                }

                try {
                    stateTransitTo(vm, Event.OperationFailed, srcHostId);
                } catch (NoTransitionException e) {
                    s_logger.warn(e.getMessage());
                }
            }

            work.setStep(Step.Done);
            _workDao.update(work.getId(), work);
        }
    }

    public boolean plugNic(Network network, NicTO nic, VirtualMachineTO vm, ReservationContext context, DeployDestination dest) throws ConcurrentOperationException,
    ResourceUnavailableException, InsufficientCapacityException {
        boolean result = true;

        VMInstanceVO router = _vmDao.findById(vm.getId());
        if (router.getState() == State.Running) {
            try {
                PlugNicCommand plugNicCmd = new PlugNicCommand(nic, vm.getName(), vm.getType());

                Commands cmds = new Commands(Command.OnError.Stop);
                cmds.addCommand("plugnic", plugNicCmd);
                _agentMgr.send(dest.getHost().getId(), cmds);
                PlugNicAnswer plugNicAnswer = cmds.getAnswer(PlugNicAnswer.class);
                if (!(plugNicAnswer != null && plugNicAnswer.getResult())) {
                    s_logger.warn("Unable to plug nic for vm " + vm.getName());
                    result = false;
                }
            } catch (OperationTimedoutException e) {
                throw new AgentUnavailableException("Unable to plug nic for router " + vm.getName() + " in network " + network, dest.getHost().getId(), e);
            }
        } else {
            s_logger.warn("Unable to apply PlugNic, vm " + router + " is not in the right state " + router.getState());

            throw new ResourceUnavailableException("Unable to apply PlugNic on the backend," + " vm " + vm + " is not in the right state", DataCenter.class,
                    router.getDataCenterId());
        }

        return result;
    }

    public boolean unplugNic(Network network, NicTO nic, VirtualMachineTO vm, ReservationContext context, DeployDestination dest) throws ConcurrentOperationException,
            ResourceUnavailableException {

        boolean result = true;
        VMInstanceVO router = _vmDao.findById(vm.getId());

        if (router.getState() == State.Running) {
            try {
                Commands cmds = new Commands(Command.OnError.Stop);
                UnPlugNicCommand unplugNicCmd = new UnPlugNicCommand(nic, vm.getName());
                cmds.addCommand("unplugnic", unplugNicCmd);
                _agentMgr.send(dest.getHost().getId(), cmds);

                UnPlugNicAnswer unplugNicAnswer = cmds.getAnswer(UnPlugNicAnswer.class);
                if (!(unplugNicAnswer != null && unplugNicAnswer.getResult())) {
                    s_logger.warn("Unable to unplug nic from router " + router);
                    result = false;
                }
            } catch (OperationTimedoutException e) {
                throw new AgentUnavailableException("Unable to unplug nic from rotuer " + router + " from network " + network, dest.getHost().getId(), e);
            }
        } else if (router.getState() == State.Stopped || router.getState() == State.Stopping) {
            s_logger.debug("Vm " + router.getInstanceName() + " is in " + router.getState() + ", so not sending unplug nic command to the backend");
        } else {
            s_logger.warn("Unable to apply unplug nic, Vm " + router + " is not in the right state " + router.getState());

            throw new ResourceUnavailableException("Unable to apply unplug nic on the backend," + " vm " + router + " is not in the right state", DataCenter.class,
                    router.getDataCenterId());
        }

        return result;
    }

    @Override
    public VMInstanceVO reConfigureVm(String vmUuid, ServiceOffering oldServiceOffering,
            boolean reconfiguringOnExistingHost)
            throws ResourceUnavailableException, InsufficientServerCapacityException, ConcurrentOperationException {

        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance
            VmWorkJobVO placeHolder = null;
            VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                return orchestrateReConfigureVm(vmUuid, oldServiceOffering, reconfiguringOnExistingHost);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            Outcome<VirtualMachine> outcome = reconfigureVmThroughJobQueue(vmUuid, oldServiceOffering, reconfiguringOnExistingHost);

            VirtualMachine vm = null;
            try {
                vm = outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Execution excetion", e);
            }

            Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobResult != null) {
                if (jobResult instanceof ResourceUnavailableException)
                    throw (ResourceUnavailableException)jobResult;
                else if (jobResult instanceof ConcurrentOperationException)
                    throw (ConcurrentOperationException)jobResult;
                else if (jobResult instanceof InsufficientServerCapacityException)
                    throw (InsufficientServerCapacityException)jobResult;
                else if (jobResult instanceof Throwable) {
                    s_logger.error("Unhandled exception", (Throwable)jobResult);
                    throw new RuntimeException("Unhandled exception", (Throwable)jobResult);
                }
            }

            return (VMInstanceVO)vm;
        }
    }

    private VMInstanceVO orchestrateReConfigureVm(String vmUuid, ServiceOffering oldServiceOffering, boolean reconfiguringOnExistingHost) throws ResourceUnavailableException,
    ConcurrentOperationException {
        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        long newServiceofferingId = vm.getServiceOfferingId();
        ServiceOffering newServiceOffering = _offeringDao.findById(vm.getId(), newServiceofferingId);
        HostVO hostVo = _hostDao.findById(vm.getHostId());

        Float memoryOvercommitRatio = CapacityManager.MemOverprovisioningFactor.valueIn(hostVo.getClusterId());
        Float cpuOvercommitRatio = CapacityManager.CpuOverprovisioningFactor.valueIn(hostVo.getClusterId());
        long minMemory = (long)(newServiceOffering.getRamSize() / memoryOvercommitRatio);
        ScaleVmCommand reconfigureCmd =
                new ScaleVmCommand(vm.getInstanceName(), newServiceOffering.getCpu(), (int)(newServiceOffering.getSpeed() / cpuOvercommitRatio),
                        newServiceOffering.getSpeed(), minMemory * 1024L * 1024L, newServiceOffering.getRamSize() * 1024L * 1024L, newServiceOffering.getLimitCpuUse());

        Long dstHostId = vm.getHostId();
        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), _nodeId, State.Running, vm.getType(), vm.getId());
        work.setStep(Step.Prepare);
        work.setResourceType(ItWorkVO.ResourceType.Host);
        work.setResourceId(vm.getHostId());
        _workDao.persist(work);
        boolean success = false;
        try {
            if (reconfiguringOnExistingHost) {
                vm.setServiceOfferingId(oldServiceOffering.getId());
                _capacityMgr.releaseVmCapacity(vm, false, false, vm.getHostId()); //release the old capacity
                vm.setServiceOfferingId(newServiceofferingId);
                _capacityMgr.allocateVmCapacity(vm, false); // lock the new capacity
            }

            Answer reconfigureAnswer = _agentMgr.send(vm.getHostId(), reconfigureCmd);
            if (reconfigureAnswer == null || !reconfigureAnswer.getResult()) {
                s_logger.error("Unable to scale vm due to " + (reconfigureAnswer == null ? "" : reconfigureAnswer.getDetails()));
                throw new CloudRuntimeException("Unable to scale vm due to " + (reconfigureAnswer == null ? "" : reconfigureAnswer.getDetails()));
            }

            success = true;
        } catch (OperationTimedoutException e) {
            throw new AgentUnavailableException("Operation timed out on reconfiguring " + vm, dstHostId);
        } catch (AgentUnavailableException e) {
            throw e;
        } finally {
            if (!success) {
                _capacityMgr.releaseVmCapacity(vm, false, false, vm.getHostId()); // release the new capacity
                vm.setServiceOfferingId(oldServiceOffering.getId());
                _capacityMgr.allocateVmCapacity(vm, false); // allocate the old capacity
            }
        }

        return vm;

    }

    @Override
    public String getConfigComponentName() {
        return VirtualMachineManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {ClusterDeltaSyncInterval, StartRetry, VmDestroyForcestop, VmOpCancelInterval, VmOpCleanupInterval, VmOpCleanupWait,
                VmOpLockStateRetry,
                VmOpWaitInterval, ExecuteInSequence, VmJobCheckInterval, VmJobTimeout, VmJobStateReportInterval};
    }

    public List<StoragePoolAllocator> getStoragePoolAllocators() {
        return _storagePoolAllocators;
    }

    @Inject
    public void setStoragePoolAllocators(List<StoragePoolAllocator> storagePoolAllocators) {
        _storagePoolAllocators = storagePoolAllocators;
    }

    //
    // PowerState report handling for out-of-band changes and handling of left-over transitional VM states
    //

    @MessageHandler(topic = Topics.VM_POWER_STATE)
    private void HandlePowerStateReport(String subject, String senderAddress, Object args) {
        assert (args != null);
        Long vmId = (Long)args;

        List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                VirtualMachine.Type.Instance, vmId);
        if (pendingWorkJobs.size() == 0 && !_haMgr.hasPendingHaWork(vmId)) {
            // there is no pending operation job
            VMInstanceVO vm = _vmDao.findById(vmId);
            if (vm != null) {
                switch (vm.getPowerState()) {
                case PowerOn:
                    handlePowerOnReportWithNoPendingJobsOnVM(vm);
                    break;

                case PowerOff:
                case PowerReportMissing:
                    handlePowerOffReportWithNoPendingJobsOnVM(vm);
                    break;

                // PowerUnknown shouldn't be reported, it is a derived
                // VM power state from host state (host un-reachable)
                case PowerUnknown:
                default:
                    assert (false);
                    break;
                }
            } else {
                s_logger.warn("VM " + vmId + " no longer exists when processing VM state report");
            }
        } else {
            s_logger.info("There is pending job or HA tasks working on the VM. vm id: " + vmId + ", postpone power-change report by resetting power-change counters");

            // reset VM power state tracking so that we won't lost signal when VM has
            // been translated to
            _vmDao.resetVmPowerStateTracking(vmId);
        }
    }

    private void handlePowerOnReportWithNoPendingJobsOnVM(VMInstanceVO vm) {
        //
        //    1) handle left-over transitional VM states
        //    2) handle out of band VM live migration
        //    3) handle out of sync stationary states, marking VM from Stopped to Running with
        //       alert messages
        //
        switch (vm.getState()) {
        case Starting:
            s_logger.info("VM " + vm.getInstanceName() + " is at " + vm.getState() + " and we received a power-on report while there is no pending jobs on it");

            try {
                stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (NoTransitionException e) {
                s_logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }

            s_logger.info("VM " + vm.getInstanceName() + " is sync-ed to at Running state according to power-on report from hypervisor");

            // we need to alert admin or user about this risky state transition
            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, "VM " + vm.getHostName() + "(" + vm.getInstanceName()
                            + ") state is sync-ed (Starting -> Running) from out-of-context transition. VM network environment may need to be reset");
            break;

        case Running:
            try {
                if (vm.getHostId() != null && vm.getHostId().longValue() != vm.getPowerHostId().longValue())
                    s_logger.info("Detected out of band VM migration from host " + vm.getHostId() + " to host " + vm.getPowerHostId());
                stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (NoTransitionException e) {
                s_logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }

            break;

        case Stopping:
        case Stopped:
            s_logger.info("VM " + vm.getInstanceName() + " is at " + vm.getState() + " and we received a power-on report while there is no pending jobs on it");

            try {
                stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (NoTransitionException e) {
                s_logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }
            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, "VM " + vm.getHostName() + "(" + vm.getInstanceName() + ") state is sync-ed (" + vm.getState()
                            + " -> Running) from out-of-context transition. VM network environment may need to be reset");

            s_logger.info("VM " + vm.getInstanceName() + " is sync-ed to at Running state according to power-on report from hypervisor");
            break;

        case Destroyed:
        case Expunging:
            s_logger.info("Receive power on report when VM is in destroyed or expunging state. vm: "
                    + vm.getId() + ", state: " + vm.getState());
            break;

        case Migrating:
            s_logger.info("VM " + vm.getInstanceName() + " is at " + vm.getState() + " and we received a power-on report while there is no pending jobs on it");
            try {
                stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (NoTransitionException e) {
                s_logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }
            s_logger.info("VM " + vm.getInstanceName() + " is sync-ed to at Running state according to power-on report from hypervisor");
            break;

        case Error:
        default:
            s_logger.info("Receive power on report when VM is in error or unexpected state. vm: "
                    + vm.getId() + ", state: " + vm.getState());
            break;
        }
    }

    private void handlePowerOffReportWithNoPendingJobsOnVM(VMInstanceVO vm) {

        //    1) handle left-over transitional VM states
        //    2) handle out of sync stationary states, schedule force-stop to release resources
        //
        switch (vm.getState()) {
        case Starting:
        case Stopping:
        case Running:
        case Stopped:
        case Migrating:
            s_logger.info("VM " + vm.getInstanceName() + " is at " + vm.getState() + " and we received a power-off report while there is no pending jobs on it");
            if(vm.isHaEnabled() && vm.getState() == State.Running && vm.getHypervisorType() != HypervisorType.VMware && vm.getHypervisorType() != HypervisorType.Hyperv) {
                s_logger.info("Detected out-of-band stop of a HA enabled VM " + vm.getInstanceName() + ", will schedule restart");
                if(!_haMgr.hasPendingHaWork(vm.getId()))
                    _haMgr.scheduleRestart(vm, true);
                else
                    s_logger.info("VM " + vm.getInstanceName() + " already has an pending HA task working on it");
                return;
            }

            VirtualMachineGuru vmGuru = getVmGuru(vm);
            VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
            sendStop(vmGuru, profile, true, true);

            try {
                stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOffReport, null);
            } catch (NoTransitionException e) {
                s_logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }

            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, "VM " + vm.getHostName() + "(" + vm.getInstanceName() + ") state is sync-ed (" + vm.getState()
                            + " -> Stopped) from out-of-context transition.");

            s_logger.info("VM " + vm.getInstanceName() + " is sync-ed to at Stopped state according to power-off report from hypervisor");

            break;

        case Destroyed:
        case Expunging:
            break;

        case Error:
        default:
            break;
        }
    }

    private void scanStalledVMInTransitionStateOnUpHost(long hostId) {
        //
        // Check VM that is stuck in Starting, Stopping, Migrating states, we won't check
        // VMs in expunging state (this need to be handled specially)
        //
        // checking condition
        //    1) no pending VmWork job
        //    2) on hostId host and host is UP
        //
        // When host is UP, soon or later we will get a report from the host about the VM,
        // however, if VM is missing from the host report (it may happen in out of band changes
        // or from designed behave of XS/KVM), the VM may not get a chance to run the state-sync logic
        //
        // Therefore, we will scan thoses VMs on UP host based on last update timestamp, if the host is UP
        // and a VM stalls for status update, we will consider them to be powered off
        // (which is relatively safe to do so)

        long stallThresholdInMs = VmJobStateReportInterval.value() + (VmJobStateReportInterval.value() >> 1);
        Date cutTime = new Date(DateUtil.currentGMTTime().getTime() - stallThresholdInMs);
        List<Long> mostlikelyStoppedVMs = listStalledVMInTransitionStateOnUpHost(hostId, cutTime);
        for (Long vmId : mostlikelyStoppedVMs) {
            VMInstanceVO vm = _vmDao.findById(vmId);
            assert (vm != null);
            handlePowerOffReportWithNoPendingJobsOnVM(vm);
        }

        List<Long> vmsWithRecentReport = listVMInTransitionStateWithRecentReportOnUpHost(hostId, cutTime);
        for (Long vmId : vmsWithRecentReport) {
            VMInstanceVO vm = _vmDao.findById(vmId);
            assert (vm != null);
            if (vm.getPowerState() == PowerState.PowerOn)
                handlePowerOnReportWithNoPendingJobsOnVM(vm);
            else
                handlePowerOffReportWithNoPendingJobsOnVM(vm);
        }
    }

    private void scanStalledVMInTransitionStateOnDisconnectedHosts() {
        Date cutTime = new Date(DateUtil.currentGMTTime().getTime() - VmOpWaitInterval.value() * 1000);
        List<Long> stuckAndUncontrollableVMs = listStalledVMInTransitionStateOnDisconnectedHosts(cutTime);
        for (Long vmId : stuckAndUncontrollableVMs) {
            VMInstanceVO vm = _vmDao.findById(vmId);

            // We now only alert administrator about this situation
            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, "VM " + vm.getHostName() + "(" + vm.getInstanceName() + ") is stuck in " + vm.getState()
                            + " state and its host is unreachable for too long");
        }
    }

    // VMs that in transitional state without recent power state report
    private List<Long> listStalledVMInTransitionStateOnUpHost(long hostId, Date cutTime) {
        String sql = "SELECT i.* FROM vm_instance as i, host as h WHERE h.status = 'UP' " +
                "AND h.id = ? AND i.power_state_update_time < ? AND i.host_id = h.id " +
                "AND (i.state ='Starting' OR i.state='Stopping' OR i.state='Migrating') " +
                "AND i.id NOT IN (SELECT w.vm_instance_id FROM vm_work_job AS w JOIN async_job AS j ON w.id = j.id WHERE j.job_status = ?)";

        List<Long> l = new ArrayList<Long>();
        TransactionLegacy txn = null;
        try {
            txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB);

            PreparedStatement pstmt = null;
            try {
                pstmt = txn.prepareAutoCloseStatement(sql);

                pstmt.setLong(1, hostId);
                pstmt.setString(2, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), cutTime));
                pstmt.setInt(3, JobInfo.Status.IN_PROGRESS.ordinal());
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    l.add(rs.getLong(1));
                }
            } catch (SQLException e) {
            } catch (Throwable e) {
            }

        } finally {
            if (txn != null)
                txn.close();
        }
        return l;
    }

    // VMs that in transitional state and recently have power state update
    private List<Long> listVMInTransitionStateWithRecentReportOnUpHost(long hostId, Date cutTime) {
        String sql = "SELECT i.* FROM vm_instance as i, host as h WHERE h.status = 'UP' " +
                "AND h.id = ? AND i.power_state_update_time > ? AND i.host_id = h.id " +
                "AND (i.state ='Starting' OR i.state='Stopping' OR i.state='Migrating') " +
                "AND i.id NOT IN (SELECT w.vm_instance_id FROM vm_work_job AS w JOIN async_job AS j ON w.id = j.id WHERE j.job_status = ?)";

        List<Long> l = new ArrayList<Long>();
        TransactionLegacy txn = null;
        try {
            txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB);
            PreparedStatement pstmt = null;
            try {
                pstmt = txn.prepareAutoCloseStatement(sql);

                pstmt.setLong(1, hostId);
                pstmt.setString(2, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), cutTime));
                pstmt.setInt(3, JobInfo.Status.IN_PROGRESS.ordinal());
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    l.add(rs.getLong(1));
                }
            } catch (SQLException e) {
            } catch (Throwable e) {
            }
            return l;
        } finally {
            if (txn != null)
                txn.close();
        }
    }

    private List<Long> listStalledVMInTransitionStateOnDisconnectedHosts(Date cutTime) {
        String sql = "SELECT i.* FROM vm_instance as i, host as h WHERE h.status != 'UP' " +
                "AND i.power_state_update_time < ? AND i.host_id = h.id " +
                "AND (i.state ='Starting' OR i.state='Stopping' OR i.state='Migrating') " +
                "AND i.id NOT IN (SELECT w.vm_instance_id FROM vm_work_job AS w JOIN async_job AS j ON w.id = j.id WHERE j.job_status = ?)";

        List<Long> l = new ArrayList<Long>();
        TransactionLegacy txn = null;
        try {
            txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB);
            PreparedStatement pstmt = null;
            try {
                pstmt = txn.prepareAutoCloseStatement(sql);

                pstmt.setString(1, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), cutTime));
                pstmt.setInt(2, JobInfo.Status.IN_PROGRESS.ordinal());
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    l.add(rs.getLong(1));
                }
            } catch (SQLException e) {
            } catch (Throwable e) {
            }
            return l;
        } finally {
            if (txn != null)
                txn.close();
        }
    }

    //
    // VM operation based on new sync model
    //

    public class VmStateSyncOutcome extends OutcomeImpl<VirtualMachine> {
        private long _vmId;

        public VmStateSyncOutcome(final AsyncJob job, final PowerState desiredPowerState, final long vmId, final Long srcHostIdForMigration) {
            super(VirtualMachine.class, job, VmJobCheckInterval.value(), new Predicate() {
                @Override
                public boolean checkCondition() {
                    AsyncJobVO jobVo = _entityMgr.findById(AsyncJobVO.class, job.getId());
                    assert (jobVo != null);
                    if (jobVo == null || jobVo.getStatus() != JobInfo.Status.IN_PROGRESS)
                        return true;
                    return false;
                }
            }, Topics.VM_POWER_STATE, AsyncJob.Topics.JOB_STATE);
            _vmId = vmId;
        }

        @Override
        protected VirtualMachine retrieve() {
            return _vmDao.findById(_vmId);
        }
    }

    public class VmJobVirtualMachineOutcome extends OutcomeImpl<VirtualMachine> {
        private long _vmId;

        public VmJobVirtualMachineOutcome(final AsyncJob job, final long vmId) {
            super(VirtualMachine.class, job, VmJobCheckInterval.value(), new Predicate() {
                @Override
                public boolean checkCondition() {
                    AsyncJobVO jobVo = _entityMgr.findById(AsyncJobVO.class, job.getId());
                    assert (jobVo != null);
                    if (jobVo == null || jobVo.getStatus() != JobInfo.Status.IN_PROGRESS)
                        return true;

                    return false;
                }
            }, AsyncJob.Topics.JOB_STATE);
            _vmId = vmId;
        }

        @Override
        protected VirtualMachine retrieve() {
            return _vmDao.findById(_vmId);
        }
    }

    //
    // TODO build a common pattern to reduce code duplication in following methods
    // no time for this at current iteration
    //
    public Outcome<VirtualMachine> startVmThroughJobQueue(final String vmUuid,
            final Map<VirtualMachineProfile.Param, Object> params,
            final DeploymentPlan planToDeploy, final DeploymentPlanner planner) {

        final CallContext context = CallContext.current();
        final User callingUser = context.getCallingUser();
        final Account callingAccount = context.getCallingAccount();

        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {
                VmWorkJobVO workJob = null;

                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance,
                            vm.getId(), VmWorkStart.class.getName());

                    if (pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {
                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkStart.class.getName());

                        workJob.setAccountId(callingAccount.getId());
                        workJob.setUserId(callingUser.getId());
                        workJob.setStep(VmWorkJobVO.Step.Starting);
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkStart workInfo = new VmWorkStart(callingUser.getId(), callingAccount.getId(), vm.getId(), VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER);
                        workInfo.setPlan(planToDeploy);
                        if (planner != null) {
                            workInfo.setDeploymentPlanner(planner.getName());
                        }
                        workInfo.setParams(params);
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }

                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmStateSyncOutcome((VmWorkJobVO)result[0],
                VirtualMachine.PowerState.PowerOn, vm.getId(), null);
    }

    public Outcome<VirtualMachine> stopVmThroughJobQueue(final String vmUuid, final boolean cleanup) {
        final CallContext context = CallContext.current();
        final Account account = context.getCallingAccount();
        final User user = context.getCallingUser();

        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {
                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);

                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                            vm.getType(), vm.getId(),
                            VmWorkStop.class.getName());

                    VmWorkJobVO workJob = null;
                    if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {
                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkStop.class.getName());

                        workJob.setAccountId(account.getId());
                        workJob.setUserId(user.getId());
                        workJob.setStep(VmWorkJobVO.Step.Prepare);
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkStop workInfo = new VmWorkStop(user.getId(), account.getId(), vm.getId(), VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, cleanup);
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }

                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmStateSyncOutcome((VmWorkJobVO)result[0],
                VirtualMachine.PowerState.PowerOff, vm.getId(), null);
    }

    public Outcome<VirtualMachine> rebootVmThroughJobQueue(final String vmUuid,
            final Map<VirtualMachineProfile.Param, Object> params) {

        final CallContext context = CallContext.current();
        final Account account = context.getCallingAccount();
        final User user = context.getCallingUser();

        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {
                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                            VirtualMachine.Type.Instance, vm.getId(),
                            VmWorkReboot.class.getName());

                    VmWorkJobVO workJob = null;
                    if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {
                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkReboot.class.getName());

                        workJob.setAccountId(account.getId());
                        workJob.setUserId(user.getId());
                        workJob.setStep(VmWorkJobVO.Step.Prepare);
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkReboot workInfo = new VmWorkReboot(user.getId(), account.getId(), vm.getId(), VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, params);
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }

                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobVirtualMachineOutcome((VmWorkJobVO)result[0],
                vm.getId());
    }

    public Outcome<VirtualMachine> migrateVmThroughJobQueue(final String vmUuid, final long srcHostId, final DeployDestination dest) {
        final CallContext context = CallContext.current();
        final User user = context.getCallingUser();
        final Account account = context.getCallingAccount();

        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {

                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                            VirtualMachine.Type.Instance, vm.getId(),
                            VmWorkMigrate.class.getName());

                    VmWorkJobVO workJob = null;
                    if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {

                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkMigrate.class.getName());

                        workJob.setAccountId(account.getId());
                        workJob.setUserId(user.getId());
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkMigrate workInfo = new VmWorkMigrate(user.getId(), account.getId(), vm.getId(), VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, srcHostId, dest);
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }
                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmStateSyncOutcome((VmWorkJobVO)result[0],
                VirtualMachine.PowerState.PowerOn, vm.getId(), vm.getPowerHostId());
    }

    public Outcome<VirtualMachine> migrateVmAwayThroughJobQueue(final String vmUuid, final long srcHostId) {
        final CallContext context = CallContext.current();
        final User user = context.getCallingUser();
        final Account account = context.getCallingAccount();

        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {
                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                            VirtualMachine.Type.Instance, vm.getId(),
                            VmWorkMigrateAway.class.getName());

                    VmWorkJobVO workJob = null;
                    if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {
                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkMigrateAway.class.getName());

                        workJob.setAccountId(account.getId());
                        workJob.setUserId(user.getId());
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkMigrateAway workInfo = new VmWorkMigrateAway(user.getId(), account.getId(), vm.getId(), VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, srcHostId);
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }
                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmStateSyncOutcome((VmWorkJobVO)result[0],
                VirtualMachine.PowerState.PowerOn, vm.getId(), vm.getPowerHostId());
    }

    public Outcome<VirtualMachine> migrateVmWithStorageThroughJobQueue(
            final String vmUuid, final long srcHostId, final long destHostId,
            final Map<Long, Long> volumeToPool) {

        final CallContext context = CallContext.current();
        final User user = context.getCallingUser();
        final Account account = context.getCallingAccount();

        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {

                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                            VirtualMachine.Type.Instance, vm.getId(),
                            VmWorkMigrateWithStorage.class.getName());

                    VmWorkJobVO workJob = null;
                    if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {

                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkMigrate.class.getName());

                        workJob.setAccountId(account.getId());
                        workJob.setUserId(user.getId());
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkMigrateWithStorage workInfo = new VmWorkMigrateWithStorage(user.getId(), account.getId(), vm.getId(),
                                VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, srcHostId, destHostId, volumeToPool);
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }
                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmStateSyncOutcome((VmWorkJobVO)result[0],
                VirtualMachine.PowerState.PowerOn, vm.getId(), destHostId);
    }

    public Outcome<VirtualMachine> migrateVmForScaleThroughJobQueue(
            final String vmUuid, final long srcHostId, final DeployDestination dest, final Long newSvcOfferingId) {

        final CallContext context = CallContext.current();
        final User user = context.getCallingUser();
        final Account account = context.getCallingAccount();

        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {

                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                            VirtualMachine.Type.Instance, vm.getId(),
                            VmWorkMigrateForScale.class.getName());

                    VmWorkJobVO workJob = null;
                    if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {

                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkMigrate.class.getName());

                        workJob.setAccountId(account.getId());
                        workJob.setUserId(user.getId());
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkMigrateForScale workInfo = new VmWorkMigrateForScale(user.getId(), account.getId(), vm.getId(),
                                VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, srcHostId, dest, newSvcOfferingId);
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }

                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobVirtualMachineOutcome((VmWorkJobVO)result[0], vm.getId());
    }

    public Outcome<VirtualMachine> migrateVmStorageThroughJobQueue(
            final String vmUuid, final StoragePool destPool) {

        final CallContext context = CallContext.current();
        final User user = context.getCallingUser();
        final Account account = context.getCallingAccount();

        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {

                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                            VirtualMachine.Type.Instance, vm.getId(),
                            VmWorkStorageMigration.class.getName());

                    VmWorkJobVO workJob = null;
                    if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {

                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkStorageMigration.class.getName());

                        workJob.setAccountId(account.getId());
                        workJob.setUserId(user.getId());
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkStorageMigration workInfo = new VmWorkStorageMigration(user.getId(), account.getId(), vm.getId(),
                                VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, destPool.getId());
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }

                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobVirtualMachineOutcome((VmWorkJobVO)result[0], vm.getId());
    }

    public Outcome<VirtualMachine> addVmToNetworkThroughJobQueue(
            final VirtualMachine vm, final Network network, final NicProfile requested) {

        final CallContext context = CallContext.current();
        final User user = context.getCallingUser();
        final Account account = context.getCallingAccount();

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {

                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);

                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                            VirtualMachine.Type.Instance, vm.getId(),
                            VmWorkAddVmToNetwork.class.getName());

                    VmWorkJobVO workJob = null;
                    if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {

                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkAddVmToNetwork.class.getName());

                        workJob.setAccountId(account.getId());
                        workJob.setUserId(user.getId());
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkAddVmToNetwork workInfo = new VmWorkAddVmToNetwork(user.getId(), account.getId(), vm.getId(),
                                VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, network.getId(), requested);
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }
                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobVirtualMachineOutcome((VmWorkJobVO)result[0], vm.getId());
    }

    public Outcome<VirtualMachine> removeNicFromVmThroughJobQueue(
            final VirtualMachine vm, final Nic nic) {

        final CallContext context = CallContext.current();
        final User user = context.getCallingUser();
        final Account account = context.getCallingAccount();

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {

                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                            VirtualMachine.Type.Instance, vm.getId(),
                            VmWorkRemoveNicFromVm.class.getName());

                    VmWorkJobVO workJob = null;
                    if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {

                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkRemoveNicFromVm.class.getName());

                        workJob.setAccountId(account.getId());
                        workJob.setUserId(user.getId());
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkRemoveNicFromVm workInfo = new VmWorkRemoveNicFromVm(user.getId(), account.getId(), vm.getId(),
                                VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, nic.getId());
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }
                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobVirtualMachineOutcome((VmWorkJobVO)result[0], vm.getId());
    }

    public Outcome<VirtualMachine> removeVmFromNetworkThroughJobQueue(
            final VirtualMachine vm, final Network network, final URI broadcastUri) {

        final CallContext context = CallContext.current();
        final User user = context.getCallingUser();
        final Account account = context.getCallingAccount();

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {

                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                            VirtualMachine.Type.Instance, vm.getId(),
                            VmWorkRemoveVmFromNetwork.class.getName());

                    VmWorkJobVO workJob = null;
                    if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {

                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkRemoveVmFromNetwork.class.getName());

                        workJob.setAccountId(account.getId());
                        workJob.setUserId(user.getId());
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkRemoveVmFromNetwork workInfo = new VmWorkRemoveVmFromNetwork(user.getId(), account.getId(), vm.getId(),
                                VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, network, broadcastUri);
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }
                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobVirtualMachineOutcome((VmWorkJobVO)result[0], vm.getId());
    }

    public Outcome<VirtualMachine> reconfigureVmThroughJobQueue(
            final String vmUuid, final ServiceOffering newServiceOffering, final boolean reconfiguringOnExistingHost) {

        final CallContext context = CallContext.current();
        final User user = context.getCallingUser();
        final Account account = context.getCallingAccount();

        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        Object[] result = Transaction.execute(new TransactionCallback<Object[]>() {
            @Override
            public Object[] doInTransaction(TransactionStatus status) {

                _vmDao.lockInLockTable(String.valueOf(vm.getId()), Integer.MAX_VALUE);
                try {
                    List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                            VirtualMachine.Type.Instance, vm.getId(),
                            VmWorkReconfigure.class.getName());

                    VmWorkJobVO workJob = null;
                    if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
                        assert (pendingWorkJobs.size() == 1);
                        workJob = pendingWorkJobs.get(0);
                    } else {

                        workJob = new VmWorkJobVO(context.getContextId());

                        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
                        workJob.setCmd(VmWorkReconfigure.class.getName());

                        workJob.setAccountId(account.getId());
                        workJob.setUserId(user.getId());
                        workJob.setVmType(VirtualMachine.Type.Instance);
                        workJob.setVmInstanceId(vm.getId());
                        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

                        // save work context info (there are some duplications)
                        VmWorkReconfigure workInfo = new VmWorkReconfigure(user.getId(), account.getId(), vm.getId(),
                                VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, newServiceOffering.getId(), reconfiguringOnExistingHost);
                        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

                        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
                    }
                    return new Object[] {workJob, new Long(workJob.getId())};
                } finally {
                    _vmDao.unlockFromLockTable(String.valueOf(vm.getId()));
                }
            }
        });

        final long jobId = (Long)result[1];
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(jobId);

        return new VmJobVirtualMachineOutcome((VmWorkJobVO)result[0], vm.getId());
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateStart(VmWorkStart work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);

        orchestrateStart(vm.getUuid(), work.getParams(), work.getPlan(), _dpMgr.getDeploymentPlannerByName(work.getDeploymentPlanner()));
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateStop(VmWorkStop work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);

        orchestrateStop(vm.getUuid(), work.isCleanup());
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateMigrate(VmWorkMigrate work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);

        orchestrateMigrate(vm.getUuid(), work.getSrcHostId(), work.getDeployDestination());
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateMigrateAway(VmWorkMigrateAway work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);

        try {
            orchestrateMigrateAway(vm.getUuid(), work.getSrcHostId(), null);
        } catch (InsufficientServerCapacityException e) {
            s_logger.warn("Failed to deploy vm " + vm.getId() + " with original planner, sending HAPlanner");
            orchestrateMigrateAway(vm.getUuid(), work.getSrcHostId(), _haMgr.getHAPlanner());
        }

        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateMigrateWithStorage(VmWorkMigrateWithStorage work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);
        orchestrateMigrateWithStorage(vm.getUuid(),
                work.getSrcHostId(),
                work.getDestHostId(),
                work.getVolumeToPool());
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateMigrateForScale(VmWorkMigrateForScale work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);
        orchestrateMigrateForScale(vm.getUuid(),
                work.getSrcHostId(),
                work.getDeployDestination(),
                work.getNewServiceOfferringId());
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateReboot(VmWorkReboot work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);
        orchestrateReboot(vm.getUuid(), work.getParams());
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateAddVmToNetwork(VmWorkAddVmToNetwork work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);

        Network network = _networkDao.findById(work.getNetworkId());
        NicProfile nic = orchestrateAddVmToNetwork(vm, network,
                work.getRequestedNicProfile());

        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED, _jobMgr.marshallResultObject(nic));
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateRemoveNicFromVm(VmWorkRemoveNicFromVm work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);
        NicVO nic = _entityMgr.findById(NicVO.class, work.getNicId());
        boolean result = orchestrateRemoveNicFromVm(vm, nic);
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED,
                _jobMgr.marshallResultObject(result));
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateRemoveVmFromNetwork(VmWorkRemoveVmFromNetwork work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);
        boolean result = orchestrateRemoveVmFromNetwork(vm,
                work.getNetwork(), work.getBroadcastUri());
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED,
                _jobMgr.marshallResultObject(result));
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateReconfigure(VmWorkReconfigure work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);

        ServiceOffering newServiceOffering = _offeringDao.findById(vm.getId(), work.getNewServiceOfferingId());

        reConfigureVm(vm.getUuid(), newServiceOffering,
                work.isSameHost());
        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateStorageMigration(VmWorkStorageMigration work) throws Exception {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            s_logger.info("Unable to find vm " + work.getVmId());
        }
        assert (vm != null);
        StoragePool pool = (PrimaryDataStoreInfo)dataStoreMgr.getPrimaryDataStore(work.getDestStoragePoolId());
        orchestrateStorageMigration(vm.getUuid(), pool);

        return new Pair<JobInfo.Status, String>(JobInfo.Status.SUCCEEDED, null);
    }

    @Override
    public Pair<JobInfo.Status, String> handleVmWorkJob(VmWork work) throws Exception {
        return _jobHandlerProxy.handleVmWorkJob(work);
    }

    private VmWorkJobVO createPlaceHolderWork(long instanceId) {
        VmWorkJobVO workJob = new VmWorkJobVO("");

        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_PLACEHOLDER);
        workJob.setCmd("");
        workJob.setCmdInfo("");

        workJob.setAccountId(0);
        workJob.setUserId(0);
        workJob.setStep(VmWorkJobVO.Step.Starting);
        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(instanceId);
        workJob.setInitMsid(ManagementServerNode.getManagementServerId());

        _workJobDao.persist(workJob);

        return workJob;
    }
}
