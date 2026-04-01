package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "start", mixinStandardHelpOptions = true, description = {
        "Start the Datashare web application server.",
        "",
        "Examples:",
        "  datashare app start",
        "  datashare app start --mode SERVER --port 9090",
        "  datashare app start --mode SERVER --oauthClientId myapp",
        "  datashare --dataDir /data/docs --elasticsearchAddress http://elastic:9200 app start"
})
public class AppServeCommand implements Runnable, DatashareSubcommand {

    @Option(names = {"--mode"}, defaultValue = "LOCAL",
            paramLabel = "LOCAL|SERVER|EMBEDDED|NER",
            description = "Server run mode (default: LOCAL)")
    Mode mode;

    @Mixin
    ServerOptions serverOptions = new ServerOptions();

    @Override
    public void run() {
        // Properties will be collected by DatashareCommand
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = serverOptions.toProperties();
        props.setProperty(MODE_OPT, mode.name());
        return props;
    }
}
