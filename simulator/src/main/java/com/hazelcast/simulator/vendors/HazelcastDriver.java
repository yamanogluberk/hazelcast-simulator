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
package com.hazelcast.simulator.vendors;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.XmlClientConfigBuilder;
import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.simulator.agent.workerprocess.WorkerParameters;
import com.hazelcast.simulator.coordinator.ConfigFileTemplate;
import com.hazelcast.simulator.coordinator.registry.AgentData;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hazelcast.simulator.utils.FileUtils.getUserDir;
import static com.hazelcast.simulator.utils.HazelcastUtils.warmupPartitions;
import static java.lang.String.format;

public class HazelcastDriver extends VendorDriver<HazelcastInstance> {
    private static final Logger LOGGER = Logger.getLogger(HazelcastDriver.class);
    private HazelcastInstance hazelcastInstance;

    @Override
    public WorkerParameters loadWorkerParameters(String workerType) {
        Map<String, String> s = new HashMap<String, String>(properties);
        s.remove("CONFIG");

        if ("hazelcast-enterprise".equals(properties.get("VENDOR"))) {
            String licenceKey = properties.get("LICENCE_KEY");
            if (licenceKey == null) {
                throw new IllegalStateException("licenceKey needs to be set with 'hazelcast-enterprise' as vendor");
            }
        }

        WorkerParameters params = new WorkerParameters()
                .setAll(s)
                .set("WORKER_TYPE", workerType)
                .set("file:log4j.xml", loadLog4jConfig());

        if ("member".equals(workerType)) {
            loadMemberWorkerParameters(params);
        } else if ("javaclient".equals(workerType)) {
            loadJavaClientWorkerParameters(params);
        } else if ("litemember".equals(workerType)) {
            loadLiteMemberWorkerParameters(params);
        } else {
            throw new IllegalArgumentException(String.format("Unknown workerType [%s]", workerType));
        }

        return params;
    }

    private void loadJavaClientWorkerParameters(WorkerParameters params) {
        params
                .set("JVM_OPTIONS", clientArgs)
                .set("file:client-hazelcast.xml", initClientHzConfig())
                .set("file:worker.sh", loadWorkerScript("javaclient", properties.get("VENDOR")));
    }

    private void loadMemberWorkerParameters(WorkerParameters params) {
        params
                .set("JVM_OPTIONS", memberArgs)
                .set("file:hazelcast.xml", initMemberHzConfig(false))
                .set("file:worker.sh", loadWorkerScript("member", properties.get("VENDOR")));
    }

    private void loadLiteMemberWorkerParameters(WorkerParameters params) {
        params
                .set("JVM_OPTIONS", clientArgs)
                .set("file:hazelcast.xml", initMemberHzConfig(true))
                .set("file:worker.sh", loadWorkerScript("litemember", properties.get("VENDOR")));
    }

    @Override
    public HazelcastInstance getInstance() {
        return hazelcastInstance;
    }

    public String initMemberHzConfig(boolean liteMember) {
        String config = loadMemberConfig();
        ConfigFileTemplate template = new ConfigFileTemplate(config);

        String licenseKey = properties.get("LICENCE_KEY");
        template.addEnvironment("licenseKey", licenseKey);
        template.addEnvironment(properties);
        //template.withAgents(componentRegistry);

        template.addReplacement("<!--MEMBERS-->",
                createAddressConfig("member", agents, properties.get("HAZELCAST_PORT")));

        if (licenseKey != null) {
            template.addReplacement("<!--LICENSE-KEY-->", format("<license-key>%s</license-key>", licenseKey));
        }

        String manCenterURL = properties.get("MANAGEMENT_CENTER_URL");
        if (!"none".equals(manCenterURL) && (manCenterURL.startsWith("http://") || manCenterURL.startsWith("https://"))) {
            String updateInterval = properties.get("MANAGEMENT_CENTER_UPDATE_INTERVAL");
            String updateIntervalAttr = (updateInterval.isEmpty()) ? "" : " update-interval=\"" + updateInterval + '"';
            template.addReplacement("<!--MANAGEMENT_CENTER_CONFIG-->",
                    format("<management-center enabled=\"true\"%s>%n        %s%n" + "    </management-center>%n",
                            updateIntervalAttr, manCenterURL));
        }

        if (liteMember) {
            template.addReplacement("<!--LITE_MEMBER_CONFIG-->", "<lite-member enabled=\"true\"/>");
        }

        return template.render();
    }

    private String loadMemberConfig() {
        String config = properties.get("CONFIG");
        if (config != null) {
            return config;
        }

        return loadConfiguration("Hazelcast member configuration", "hazelcast.xml");
    }

    public String initClientHzConfig() {
        String config = loadClientConfig();

        ConfigFileTemplate template = new ConfigFileTemplate(config);
        //template.withAgents(componentRegistry);
        String licenseKey = properties.get("LICENCE_KEY");
        template.addEnvironment("licenseKey", licenseKey);
        template.addEnvironment(properties);

        template.addReplacement("<!--MEMBERS-->",
                createAddressConfig("address", agents, properties.get("HAZELCAST_PORT")));
        if (licenseKey != null) {
            template.addReplacement("<!--LICENSE-KEY-->", format("<license-key>%s</license-key>", licenseKey));
        }

        return template.render();
    }

    private String loadClientConfig() {
        String config = properties.get("CONFIG");
        if (config != null) {
            return config;
        }

        return loadConfiguration("Hazelcast client configuration", "client-hazelcast.xml");
    }

    public static String createAddressConfig(String tagName, List<AgentData> agents, String port) {
        StringBuilder members = new StringBuilder();
        for (AgentData agentData : agents) {
            String hostAddress = agentData.getPrivateAddress();
            members.append(format("<%s>%s:%s</%s>%n", tagName, hostAddress, port, tagName));
        }
        return members.toString();
    }

    @Override
    public void createVendorInstance() throws Exception {
        String workerType = properties.get("WORKER_TYPE");

        LOGGER.info(format("%s HazelcastInstance starting", workerType));
        if ("javaclient".equals(workerType)) {
            File configFile = new File(getUserDir(), "client-hazelcast.xml");
            XmlClientConfigBuilder configBuilder = new XmlClientConfigBuilder(configFile);
            ClientConfig clientConfig = configBuilder.build();
            hazelcastInstance = HazelcastClient.newHazelcastClient(clientConfig);
        } else {
            File configFile = new File(getUserDir(), "hazelcast.xml");
            XmlConfigBuilder configBuilder = new XmlConfigBuilder(configFile.getAbsolutePath());
            Config config = configBuilder.build();
            hazelcastInstance = Hazelcast.newHazelcastInstance(config);
        }
        LOGGER.info(format("%s HazelcastInstance started", workerType));
        warmupPartitions(hazelcastInstance);
        LOGGER.info("Warmed up partitions");
    }

    @Override
    public void close() throws IOException {
        LOGGER.info("Stopping HazelcastInstance...");

        if (hazelcastInstance != null) {
            hazelcastInstance.shutdown();
        }
    }
}
