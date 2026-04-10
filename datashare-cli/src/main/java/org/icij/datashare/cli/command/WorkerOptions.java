package org.icij.datashare.cli.command;

import org.icij.datashare.cli.TaskRepositoryType;
import org.icij.datashare.tasks.RoutingStrategy;
import picocli.CommandLine.Option;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.*;

/**
 * Options specific to the worker run subcommand.
 */
public class WorkerOptions {

    @Option(names = {"--taskWorkers"}, description = "Number of task workers", defaultValue = "1")
    String taskWorkers;

    @Option(names = {"--taskRoutingStrategy"}, description = "Task routing strategy", defaultValue = "UNIQUE")
    RoutingStrategy taskRoutingStrategy;

    @Option(names = {"--taskRoutingKey"}, description = "Task routing key")
    String taskRoutingKey;

    @Option(names = {"--pollingInterval"}, description = "Queue polling interval", defaultValue = "60")
    String pollingInterval;

    @Option(names = {"--taskRepositoryType"}, description = "Task repository type", defaultValue = "DATABASE")
    TaskRepositoryType taskRepositoryType;

    @Option(names = {"--taskManagerPollingIntervalMilliseconds"}, description = "Task manager polling interval ms", defaultValue = "5000")
    int taskManagerPollingIntervalMilliseconds;

    @Option(names = {"--taskProgressUpdateIntervalSeconds"}, description = "Task progress update interval seconds", defaultValue = "10.0")
    double taskProgressUpdateIntervalSeconds;

    /** Converts the parsed worker option fields into a Properties map for the rest of the application. */
    public Properties toProperties() {
        Properties props = new Properties();
        DatashareOptions.putIfNotNull(props, TASK_WORKERS_OPT, taskWorkers);
        DatashareOptions.putIfNotNull(props, TASK_ROUTING_STRATEGY_OPT, taskRoutingStrategy);
        DatashareOptions.putIfNotNull(props, TASK_ROUTING_KEY_OPT, taskRoutingKey);
        DatashareOptions.putIfNotNull(props, POLLING_INTERVAL_SECONDS_OPT, pollingInterval);
        DatashareOptions.putIfNotNull(props, TASK_REPOSITORY_OPT, taskRepositoryType);
        DatashareOptions.put(props, TASK_MANAGER_POLLING_INTERVAL_OPT, taskManagerPollingIntervalMilliseconds);
        DatashareOptions.put(props, TASK_PROGRESS_INTERVAL_OPT, taskProgressUpdateIntervalSeconds);
        return props;
    }

}
