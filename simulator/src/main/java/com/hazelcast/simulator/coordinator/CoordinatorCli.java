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
package com.hazelcast.simulator.coordinator;

import com.hazelcast.simulator.common.AgentsFile;
import com.hazelcast.simulator.common.FailureType;
import com.hazelcast.simulator.common.SimulatorProperties;
import com.hazelcast.simulator.common.TestCase;
import com.hazelcast.simulator.common.TestSuite;
import com.hazelcast.simulator.coordinator.deployment.WorkerConfigurationConverter;
import com.hazelcast.simulator.protocol.registry.ComponentRegistry;
import com.hazelcast.simulator.protocol.registry.TargetType;
import com.hazelcast.simulator.testcontainer.TestPhase;
import com.hazelcast.simulator.utils.CommandLineExitException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.log4j.Logger;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.simulator.common.FailureType.fromPropertyValue;
import static com.hazelcast.simulator.common.SimulatorProperties.PROPERTIES_FILE_NAME;
import static com.hazelcast.simulator.common.TestSuite.loadTestSuite;
import static com.hazelcast.simulator.coordinator.WorkerParameters.initClientHzConfig;
import static com.hazelcast.simulator.coordinator.WorkerParameters.initMemberHzConfig;
import static com.hazelcast.simulator.utils.CliUtils.initOptionsWithHelp;
import static com.hazelcast.simulator.utils.CloudProviderUtils.isLocal;
import static com.hazelcast.simulator.utils.FileUtils.fileAsText;
import static com.hazelcast.simulator.utils.FileUtils.getConfigurationFile;
import static com.hazelcast.simulator.utils.FileUtils.getFileAsTextFromWorkingDirOrBaseDir;
import static com.hazelcast.simulator.utils.FileUtils.getFileOrExit;
import static com.hazelcast.simulator.utils.FileUtils.getSimulatorHome;
import static com.hazelcast.simulator.utils.SimulatorUtils.loadComponentRegister;
import static com.hazelcast.simulator.utils.SimulatorUtils.loadSimulatorProperties;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

final class CoordinatorCli {

    static final int DEFAULT_DURATION_SECONDS = 60;
    static final int DEFAULT_WARMUP_DURATION_SECONDS = 0;

    private static final Logger LOGGER = Logger.getLogger(CoordinatorCli.class);

    private final OptionParser parser = new OptionParser();

    private final OptionSpec<Integer> workerVmStartupDelayMsSpec = parser.accepts("workerVmStartupDelayMs",
            "Amount of time in milliseconds to wait between starting up the next member. This is useful to prevent"
                    + "duplicate connection issues.")
            .withRequiredArg().ofType(Integer.class).defaultsTo(0);

    private final OptionSpec<String> durationSpec = parser.accepts("duration",
            "Amount of time to execute the RUN phase per test, e.g. 10s, 1m, 2h or 3d.")
            .withRequiredArg().ofType(String.class).defaultsTo(format("%ds", DEFAULT_DURATION_SECONDS));

    private final OptionSpec<String> warmupDurationSpec = parser.accepts("warmupDuration",
            "Amount of time to execute the warmup per test, e.g. 10s, 1m, 2h or 3d.")
            .withRequiredArg().ofType(String.class).defaultsTo(format("%ds", DEFAULT_WARMUP_DURATION_SECONDS));

    private final OptionSpec waitForTestCaseSpec = parser.accepts("waitForTestCaseCompletion",
            "Wait for the TestCase to finish its RUN phase. Can be combined with --duration to limit runtime.");

    private final OptionSpec<String> overridesSpec = parser.accepts("overrides",
            "Properties that override the properties in a given test-case, e.g. --overrides"
                    + " \"threadcount=20,writeProb=0.2\". This makes it easy to parametrize a test.")
            .withRequiredArg().ofType(String.class).defaultsTo("");

    private final OptionSpec<Integer> memberWorkerCountSpec = parser.accepts("memberWorkerCount",
            "Number of cluster member Worker JVMs. If no value is specified and no mixed members are specified,"
                    + " then the number of cluster members will be equal to the number of machines in the agents file.")
            .withRequiredArg().ofType(Integer.class).defaultsTo(-1);

    private final OptionSpec<Integer> clientWorkerCountSpec = parser.accepts("clientWorkerCount",
            "Number of cluster client Worker JVMs.")
            .withRequiredArg().ofType(Integer.class).defaultsTo(0);

    private final OptionSpec<Integer> dedicatedMemberMachinesSpec = parser.accepts("dedicatedMemberMachines",
            "Controls the number of dedicated member machines. For example when there are 4 machines,"
                    + " 2 members and 9 clients with 1 dedicated member machine defined, then"
                    + " 1 machine gets the 2 members and the 3 remaining machines get 3 clients each.")
            .withRequiredArg().ofType(Integer.class).defaultsTo(0);

    private final OptionSpec<TargetType> targetTypeSpec = parser.accepts("targetType",
            format("Defines the type of Workers which execute the RUN phase."
                    + " The type PREFER_CLIENT selects client Workers if they are available, member Workers otherwise."
                    + " List of allowed types: %s", TargetType.getIdsAsString()))
            .withRequiredArg().ofType(TargetType.class).defaultsTo(TargetType.PREFER_CLIENT);

    private final OptionSpec<Integer> targetCountSpec = parser.accepts("targetCount",
            "Defines the number of Workers which execute the RUN phase. The value 0 selects all Workers.")
            .withRequiredArg().ofType(Integer.class).defaultsTo(0);

    private final OptionSpec<Boolean> autoCreateHzInstanceSpec = parser.accepts("autoCreateHzInstances",
            "Auto create Hazelcast instances.")
            .withRequiredArg().ofType(Boolean.class).defaultsTo(true);

    private final OptionSpec<String> workerClassPathSpec = parser.accepts("workerClassPath",
            "A file/directory containing the classes/jars/resources that are going to be uploaded to the agents."
                    + " Use ';' as separator for multiple entries. The wildcard '*' can also be used.")
            .withRequiredArg().ofType(String.class);

    private final OptionSpec<String> testSuiteIdSpec = parser.accepts("testSuiteId",
            "Defines the ID of the TestSuite. If not set the actual date will be used.")
            .withRequiredArg().ofType(String.class);

    private final OptionSpec monitorPerformanceSpec = parser.accepts("monitorPerformance",
            "If defined performance of tests is tracked.");

    private final OptionSpec<Boolean> verifyEnabledSpec = parser.accepts("verifyEnabled",
            "Defines if tests are verified.")
            .withRequiredArg().ofType(Boolean.class).defaultsTo(true);

    private final OptionSpec<Boolean> failFastSpec = parser.accepts("failFast",
            "Defines if the TestSuite should fail immediately when a test from a TestSuite fails instead of continuing.")
            .withRequiredArg().ofType(Boolean.class).defaultsTo(true);

    private final OptionSpec<String> tolerableFailureSpec = parser.accepts("tolerableFailure",
            format("Defines if tests should not fail when given failure is detected. List of known failures: %s",
                    FailureType.getIdsAsString()))
            .withRequiredArg().ofType(String.class).defaultsTo("workerTimeout");

    private final OptionSpec parallelSpec = parser.accepts("parallel",
            "If defined tests are run in parallel.");

    private final OptionSpec<TestPhase> syncToTestPhaseSpec = parser.accepts("syncToTestPhase",
            format("Defines the last TestPhase which is synchronized between all parallel running tests."
                    + " Use --syncToTestPhase %s to synchronize all test phases."
                    + " List of defined test phases: %s", TestPhase.getLastTestPhase(), TestPhase.getIdsAsString()))
            .withRequiredArg().ofType(TestPhase.class).defaultsTo(TestPhase.getLastTestPhase());

    private final OptionSpec<String> workerVmOptionsSpec = parser.accepts("workerVmOptions",
            "Member Worker JVM options (quotes can be used).")
            .withRequiredArg().ofType(String.class).defaultsTo("-XX:+HeapDumpOnOutOfMemoryError");

    private final OptionSpec<String> clientWorkerVmOptionsSpec = parser.accepts("clientWorkerVmOptions",
            "Client Worker JVM options (quotes can be used).")
            .withRequiredArg().ofType(String.class).defaultsTo("-XX:+HeapDumpOnOutOfMemoryError");

    private final OptionSpec<String> agentsFileSpec = parser.accepts("agentsFile",
            "The file containing the list of Agent machines.")
            .withRequiredArg().ofType(String.class).defaultsTo(AgentsFile.NAME);

    private final OptionSpec<String> propertiesFileSpec = parser.accepts("propertiesFile",
            format("The file containing the simulator properties. If no file is explicitly configured,"
                            + " first the working directory is checked for a file '%s'."
                            + " All missing properties are always loaded from SIMULATOR_HOME/conf/%s",
                    PROPERTIES_FILE_NAME, PROPERTIES_FILE_NAME))
            .withRequiredArg().ofType(String.class);

    private final OptionSpec<Integer> workerStartupTimeoutSpec = parser.accepts("workerStartupTimeout",
            "The startup timeout in seconds for a Worker.")
            .withRequiredArg().ofType(Integer.class).defaultsTo(60);

    private final OptionSpec<String> licenseKeySpec = parser.accepts("licenseKey",
            "Sets the license key for Hazelcast Enterprise Edition.")
            .withRequiredArg().ofType(String.class);

    private final OptionSpec skipDownloadSpec = parser.accepts("skipDownload",
            "Prevents downloading of the created worker artifacts.");

    private CoordinatorCli() {
    }

    static Coordinator init(String[] args) {
        Coordinator.logHeader();

        CoordinatorCli cli = new CoordinatorCli();
        OptionSet options = initOptionsWithHelp(cli.parser, args);

        TestSuite testSuite = getTestSuite(cli, options);

        SimulatorProperties simulatorProperties = loadSimulatorProperties(options, cli.propertiesFileSpec);

        ComponentRegistry componentRegistry = getComponentRegistry(cli, options, testSuite, simulatorProperties);

        CoordinatorParameters coordinatorParameters = new CoordinatorParameters(
                simulatorProperties,
                options.valueOf(cli.workerClassPathSpec),
                options.valueOf(cli.verifyEnabledSpec),
                options.has(cli.parallelSpec),
                options.valueOf(cli.targetTypeSpec),
                options.valueOf(cli.targetCountSpec),
                options.valueOf(cli.syncToTestPhaseSpec),
                options.valueOf(cli.workerVmStartupDelayMsSpec),
                options.has(cli.skipDownloadSpec),
                getConfigurationFile("after-completion.sh").getAbsolutePath()
        );

        int defaultHzPort = simulatorProperties.getHazelcastPort();
        String licenseKey = options.valueOf(cli.licenseKeySpec);

        WorkerParameters workerParameters = new WorkerParameters(
                simulatorProperties,
                options.valueOf(cli.autoCreateHzInstanceSpec),
                options.valueOf(cli.workerStartupTimeoutSpec),
                options.valueOf(cli.workerVmOptionsSpec),
                options.valueOf(cli.clientWorkerVmOptionsSpec),
                initMemberHzConfig(loadMemberHzConfig(), componentRegistry, defaultHzPort, licenseKey, simulatorProperties),
                initClientHzConfig(loadClientHzConfig(), componentRegistry, defaultHzPort, licenseKey),
                loadLog4jConfig(),
                loadWorkerScript(simulatorProperties.get("VENDOR")),
                options.has(cli.monitorPerformanceSpec)
        );

        WorkerConfigurationConverter workerConfigurationConverter = new WorkerConfigurationConverter(defaultHzPort, licenseKey,
                workerParameters, simulatorProperties, componentRegistry);

        ClusterLayoutParameters clusterLayoutParameters = new ClusterLayoutParameters(
                loadClusterConfig(),
                workerConfigurationConverter,
                options.valueOf(cli.memberWorkerCountSpec),
                options.valueOf(cli.clientWorkerCountSpec),
                options.valueOf(cli.dedicatedMemberMachinesSpec),
                componentRegistry.agentCount()
        );
        if (clusterLayoutParameters.getDedicatedMemberMachineCount() < 0) {
            throw new CommandLineExitException("--dedicatedMemberMachines can't be smaller than 0");
        }

        return new Coordinator(testSuite, componentRegistry, coordinatorParameters, workerParameters, clusterLayoutParameters);
    }

    private static TestSuite getTestSuite(CoordinatorCli cli, OptionSet options) {
        int durationSeconds = getDurationSeconds(options, cli.durationSpec, cli);
        boolean hasWaitForTestCase = options.has(cli.waitForTestCaseSpec);
        if (!options.has(cli.durationSpec) && hasWaitForTestCase) {
            durationSeconds = 0;
        }


        TestSuite testSuite = loadTestSuite(getTestSuiteFile(options), options.valueOf(cli.overridesSpec),
                options.valueOf(cli.testSuiteIdSpec));
        testSuite.setDurationSeconds(durationSeconds);
        testSuite.setWarmupDurationSeconds(getDurationSeconds(options, cli.warmupDurationSpec, cli));
        testSuite.setWaitForTestCase(hasWaitForTestCase);
        testSuite.setFailFast(options.valueOf(cli.failFastSpec));
        testSuite.setTolerableFailures(fromPropertyValue(options.valueOf(cli.tolerableFailureSpec)));

        // if the coordinator is not monitoring performance, we don't care for measuring latencies.
        if (!options.has(cli.monitorPerformanceSpec)) {
            for (TestCase testCase : testSuite.getTestCaseList()) {
                testCase.setProperty("measureLatency", "false");
            }
        }

        return testSuite;
    }

    private static ComponentRegistry getComponentRegistry(CoordinatorCli cli, OptionSet options, TestSuite testSuite,
                                                          SimulatorProperties simulatorProperties) {
        ComponentRegistry componentRegistry;
        if (isLocal(simulatorProperties)) {
            componentRegistry = new ComponentRegistry();
            componentRegistry.addAgent("localhost", "localhost");
        } else {
            componentRegistry = loadComponentRegister(getAgentsFile(cli, options));
        }
        componentRegistry.addTests(testSuite);
        return componentRegistry;
    }

    private static File getTestSuiteFile(OptionSet options) {
        File testSuiteFile;

        List testsuiteFiles = options.nonOptionArguments();
        if (testsuiteFiles.size() > 1) {
            throw new CommandLineExitException(format("Too many TestSuite files specified: %s", testsuiteFiles));
        } else if (testsuiteFiles.size() == 1) {
            testSuiteFile = new File((String) testsuiteFiles.get(0));
        } else {
            testSuiteFile = new File("test.properties");
        }

        LOGGER.info("Loading TestSuite file: " + testSuiteFile.getAbsolutePath());
        if (!testSuiteFile.exists()) {
            throw new CommandLineExitException(format("TestSuite file '%s' not found", testSuiteFile));
        }
        return testSuiteFile;
    }

    private static File getAgentsFile(CoordinatorCli cli, OptionSet options) {
        File file = getFileOrExit(cli.agentsFileSpec, options, "Agents file");
        LOGGER.info("Loading Agents file: " + file.getAbsolutePath());
        return file;
    }

    private static String loadMemberHzConfig() {
        File file = getConfigurationFile("hazelcast.xml");
        LOGGER.info("Loading Hazelcast member configuration: " + file.getAbsolutePath());
        return fileAsText(file);
    }

    private static String loadWorkerScript(String vendor) {
        File file = getConfigurationFile("worker-" + vendor + ".sh");
        LOGGER.info("Loading Hazelcast worker script: " + file.getAbsolutePath());
        return fileAsText(file);
    }

    private static String loadClientHzConfig() {
        File file = getConfigurationFile("client-hazelcast.xml");
        LOGGER.info("Loading Hazelcast client configuration: " + file.getAbsolutePath());
        return fileAsText(file);
    }

    private static String loadLog4jConfig() {
        return getFileAsTextFromWorkingDirOrBaseDir(getSimulatorHome(), "worker-log4j.xml", "Log4j configuration for Worker");
    }

    private static String loadClusterConfig() {
        File file = new File("cluster.xml").getAbsoluteFile();
        if (file.exists()) {
            LOGGER.info("Loading cluster configuration: " + file.getAbsolutePath());
            return fileAsText(file.getAbsolutePath());
        } else {
            return null;
        }
    }

    private static int getDurationSeconds(OptionSet options, OptionSpec<String> optionSpec, CoordinatorCli cli) {
        int duration;
        String value = options.valueOf(optionSpec);
        try {
            if (value.endsWith("s")) {
                duration = parseDurationWithoutLastChar(SECONDS, value);
            } else if (value.endsWith("m")) {
                duration = parseDurationWithoutLastChar(MINUTES, value);
            } else if (value.endsWith("h")) {
                duration = parseDurationWithoutLastChar(HOURS, value);
            } else if (value.endsWith("d")) {
                duration = parseDurationWithoutLastChar(DAYS, value);
            } else {
                duration = Integer.parseInt(value);
            }
        } catch (NumberFormatException e) {
            throw new CommandLineExitException(format("Failed to parse duration '%s'", value), e);
        }

        if (duration < 0) {
            throw new CommandLineExitException("duration must be a positive number, but was: " + duration);
        }
        return duration;
    }

    private static int parseDurationWithoutLastChar(TimeUnit timeUnit, String value) {
        String sub = value.substring(0, value.length() - 1);
        return (int) timeUnit.toSeconds(Integer.parseInt(sub));
    }
}
