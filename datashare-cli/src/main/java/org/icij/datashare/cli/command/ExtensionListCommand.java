package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.EXTENSION_LIST_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "list", mixinStandardHelpOptions = true, description = {
        "List available extensions from the registry.",
        "",
        "Examples:",
        "  datashare extension list",
        "  datashare extension list nlp"
})
public class ExtensionListCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", arity = "0..1", defaultValue = "true",
            description = "Optional filter pattern")
    String filter;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        DatashareOptions.put(props, EXTENSION_LIST_OPT, filter);
        return props;
    }
}
