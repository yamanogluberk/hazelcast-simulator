/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.simulator.coordinator.tasks;

import com.hazelcast.simulator.common.SimulatorProperties;
import com.hazelcast.simulator.protocol.registry.AgentData;
import com.hazelcast.simulator.protocol.registry.ComponentRegistry;
import com.hazelcast.simulator.utils.Bash;
import com.hazelcast.simulator.utils.ThreadSpawner;
import org.apache.log4j.Logger;

import static com.hazelcast.simulator.utils.CloudProviderUtils.isLocal;
import static com.hazelcast.simulator.utils.CommonUtils.getElapsedSeconds;
import static com.hazelcast.simulator.utils.CommonUtils.getSimulatorVersion;
import static java.lang.String.format;

public class ArtifactCleanTask {
    private static final Logger LOGGER = Logger.getLogger(ArtifactCleanTask.class);

    private final ComponentRegistry componentRegistry;
    private final SimulatorProperties simulatorProperties;
    private final Bash bash;

    public ArtifactCleanTask(ComponentRegistry componentRegistry, SimulatorProperties simulatorProperties) {
        this.componentRegistry = componentRegistry;
        this.simulatorProperties = simulatorProperties;
        this.bash = new Bash(simulatorProperties);
    }

    public void run() {
        if (isLocal(simulatorProperties)) {
            //we don't need to clean the local since it is automatically cleaned.
            LOGGER.info("Skipping clean since it is local");
        }

        long started = System.nanoTime();

        LOGGER.info(format("Cleaning Worker homes of %s machines...", componentRegistry.agentCount()));
        cleanRemote();
        long elapsed = getElapsedSeconds(started);
        LOGGER.info(format("Finished cleaning Worker homes of %s machines (%s seconds)",
                componentRegistry.agentCount(), elapsed));
    }

    private void cleanRemote() {
        final String cleanCommand = format("rm -fr hazelcast-simulator-%s/workers/*", getSimulatorVersion());

        ThreadSpawner spawner = new ThreadSpawner("clean", true);
        for (final AgentData agentData : componentRegistry.getAgents()) {
            spawner.spawn(new Runnable() {
                @Override
                public void run() {
                    LOGGER.info(format("Cleaning %s", agentData.getPublicAddress()));
                    bash.ssh(agentData.getPublicAddress(), cleanCommand);
                }
            });
        }
        spawner.awaitCompletion();
    }
}
