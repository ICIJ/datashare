package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.util.Properties;
import static org.icij.datashare.cli.DatashareCliOptions.API_KEY_HASH_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEL_API_KEY_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "delete", mixinStandardHelpOptions = true,
        description = {"Revoke and remove the API keys of a user, or only one of them.", "", "Examples:",
                "  datashare api-key delete alice", "  datashare api-key delete alice --key <hash>"})
public class ApiKeyDeleteCommand implements Runnable, DatashareSubcommand {
    @Parameters(index = "0", description = "Username")
    String user;
    @Option(names = {"--key"}, description = "Hash of the one key to delete, as printed by api-key list.")
    String key;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        DatashareOptions.put(props, DEL_API_KEY_OPT, user);
        DatashareOptions.putIfNotNull(props, API_KEY_HASH_OPT, key);
        return props;
    }
}
