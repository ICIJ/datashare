package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.CRE_API_KEY_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "create", mixinStandardHelpOptions = true, description = {
        "Generate and store a new API key for a user.",
        "",
        "Examples:",
        "  datashare api-key create alice",
        "  datashare -s /etc/datashare/settings.properties api-key create alice"
})
public class ApiKeyCreateCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", description = "Username")
    String user;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        DatashareOptions.put(props, CRE_API_KEY_OPT, user);
        return props;
    }
}
