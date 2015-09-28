package com.hazelcast.simulator.protocol.processors;

import com.hazelcast.simulator.agent.Agent;
import com.hazelcast.simulator.agent.workerjvm.WorkerJvm;
import com.hazelcast.simulator.agent.workerjvm.WorkerJvmLauncher;
import com.hazelcast.simulator.agent.workerjvm.WorkerJvmSettings;
import com.hazelcast.simulator.common.CoordinatorLogger;
import com.hazelcast.simulator.protocol.connector.AgentConnector;
import com.hazelcast.simulator.protocol.core.AddressLevel;
import com.hazelcast.simulator.protocol.core.ResponseType;
import com.hazelcast.simulator.protocol.core.SimulatorAddress;
import com.hazelcast.simulator.protocol.exception.ExceptionLogger;
import com.hazelcast.simulator.protocol.operation.CreateWorkerOperation;
import com.hazelcast.simulator.protocol.operation.IntegrationTestOperation;
import com.hazelcast.simulator.protocol.operation.SimulatorOperation;
import com.hazelcast.simulator.protocol.operation.WorkerIsAliveOperation;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.hazelcast.simulator.protocol.core.ResponseType.SUCCESS;
import static com.hazelcast.simulator.protocol.core.ResponseType.UNSUPPORTED_OPERATION_ON_THIS_PROCESSOR;
import static com.hazelcast.simulator.protocol.operation.OperationType.getOperationType;
import static com.hazelcast.simulator.utils.CommonUtils.sleepMillis;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.whenNew;

@RunWith(PowerMockRunner.class)
@PrepareForTest({AgentOperationProcessor.class, AgentConnector.class})
public class AgentOperationProcessorTest {

    private final ExceptionLogger exceptionLogger = mock(ExceptionLogger.class);

    private AgentOperationProcessor processor;

    @Before
    public void setUp() {
        AgentConnector agentConnector = mock(AgentConnector.class);
        CoordinatorLogger coordinatorLogger = mock(CoordinatorLogger.class);

        Agent agent = mock(Agent.class);
        when(agent.getAgentConnector()).thenReturn(agentConnector);
        when(agent.getCoordinatorLogger()).thenReturn(coordinatorLogger);

        processor = new AgentOperationProcessor(exceptionLogger, agent, null);
    }

    @Test
    public void testProcessOperation_UnsupportedOperation() throws Exception {
        SimulatorOperation operation = new IntegrationTestOperation(IntegrationTestOperation.TEST_DATA);
        ResponseType responseType = processor.processOperation(getOperationType(operation), operation);

        assertEquals(UNSUPPORTED_OPERATION_ON_THIS_PROCESSOR, responseType);
    }

    @Test
    public void testCreateWorkerOperation() throws Exception {
        WorkerJvmLauncher workerJvmLauncher = mock(WorkerJvmLauncher.class);
        whenNew(WorkerJvmLauncher.class).withAnyArguments().thenReturn(workerJvmLauncher);

        List<WorkerJvmSettings> workerJvmSettings = new ArrayList<WorkerJvmSettings>();
        workerJvmSettings.add(mock(WorkerJvmSettings.class));

        SimulatorOperation operation = new CreateWorkerOperation(workerJvmSettings);
        ResponseType responseType = processor.processOperation(getOperationType(operation), operation);

        assertEquals(SUCCESS, responseType);
    }

    @Test
    public void testWorkerIsAliveOperation() {
        SimulatorAddress workerAddress = new SimulatorAddress(AddressLevel.WORKER, 1, 5, 0);
        WorkerJvm workerJvm = new WorkerJvm(workerAddress, "foobar", null);

        long lastSeen = workerJvm.getLastSeen();

        ConcurrentMap<SimulatorAddress, WorkerJvm> workerJVMs = new ConcurrentHashMap<SimulatorAddress, WorkerJvm>();
        workerJVMs.put(workerAddress, workerJvm);

        processor = new AgentOperationProcessor(exceptionLogger, null, workerJVMs);

        sleepMillis(100);

        SimulatorOperation operation = new WorkerIsAliveOperation(workerAddress);
        processor.process(operation);

        assertTrue(workerJvm.getLastSeen() > lastSeen);
    }
}
