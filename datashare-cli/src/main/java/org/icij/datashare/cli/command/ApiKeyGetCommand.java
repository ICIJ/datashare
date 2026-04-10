package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.GET_API_KEY_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "get", mixinStandardHelpOptions = true, description = {
        "Print the existing API key for a user.",
        "",
        "Examples:",
        "  datashare api-key get alice"
})
public class ApiKeyGetCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", description = "Username")
    String user;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        DatashareOptions.put(props, GET_API_KEY_OPT, user);
        return props;
    }
}
