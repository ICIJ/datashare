package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.DEL_API_KEY_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "delete", mixinStandardHelpOptions = true, description = {
        "Revoke and remove the API key for a user.",
        "",
        "Examples:",
        "  datashare api-key delete alice"
})
public class ApiKeyDeleteCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", description = "Username")
    String user;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        props.setProperty(MODE_OPT, Mode.CLI.name());
        props.setProperty(DEL_API_KEY_OPT, user);
        return props;
    }
}
