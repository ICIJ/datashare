package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PLUGIN_LIST_OPT;

@Command(name = "list", mixinStandardHelpOptions = true, description = {
        "List available plugins from the registry.",
        "",
        "Examples:",
        "  datashare plugin list",
        "  datashare plugin list ocr"
})
public class PluginListCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", arity = "0..1", defaultValue = "true",
            description = "Optional filter pattern")
    String filter;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        props.setProperty(MODE_OPT, Mode.CLI.name());
        props.setProperty(PLUGIN_LIST_OPT, filter);
        return props;
    }
}
