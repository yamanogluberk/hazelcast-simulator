/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
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
package com.hazelcast.stabilizer.agent.workerjvm;

import com.hazelcast.stabilizer.worker.testcommands.TestCommandRequest;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class WorkerJvm {
    public final String id;
    public volatile String memberAddress;
    public Process process;
    public File workerHome;

    public final BlockingQueue<TestCommandRequest> commandQueue = new LinkedBlockingQueue<TestCommandRequest>();

    public WorkerJvm(String id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "WorkerJvm{" +
                "memberAddress='" + memberAddress + '\'' +
                ", id='" + id + '\'' +
                '}';
    }
}
