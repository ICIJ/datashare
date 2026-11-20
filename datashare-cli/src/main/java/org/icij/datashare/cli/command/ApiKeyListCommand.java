package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import java.util.Properties;
import static org.icij.datashare.cli.DatashareCliOptions.LIST_API_KEYS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "list", mixinStandardHelpOptions = true,
        description = {"List the API keys of a user: hash, projects (* when unscoped) and expiration, tab-separated.",
                "", "Examples:", "  datashare api-key list alice"})
public class ApiKeyListCommand implements Runnable, DatashareSubcommand {
    @Parameters(index = "0", description = "Username")
    String user;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        DatashareOptions.put(props, LIST_API_KEYS_OPT, user);
        return props;
    }
}
