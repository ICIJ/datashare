package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "run", mixinStandardHelpOptions = true, description = {
        "Start a task worker that processes background jobs from the queue.",
        "",
        "Examples:",
        "  datashare worker run",
        "  datashare worker run --taskWorkers 4",
        "  datashare worker run --taskWorkers 4 --taskRoutingStrategy GROUP",
        "  datashare --elasticsearchAddress http://elastic:9200 --redisAddress redis://redis:6379 worker run --taskWorkers 4"
})
public class WorkerRunCommand implements Runnable, DatashareSubcommand {

    @Mixin
    WorkerOptions workerOptions = new WorkerOptions();

    @Override
    public void run() {
        // Properties will be collected by DatashareCommand
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = workerOptions.toProperties();
        props.setProperty(MODE_OPT, Mode.TASK_WORKER.name());
        return props;
    }
}
