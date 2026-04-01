package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.EXTENSION_DELETE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "delete", mixinStandardHelpOptions = true, description = {
        "Delete an installed extension by id or directory path.",
        "",
        "Examples:",
        "  datashare extension delete datashare-extension-neo4j",
        "  datashare --extensionsDir /opt/datashare/extensions extension delete datashare-extension-neo4j"
})
public class ExtensionDeleteCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", description = "Extension id or base directory")
    String extensionId;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        props.setProperty(MODE_OPT, Mode.CLI.name());
        props.setProperty(EXTENSION_DELETE_OPT, extensionId);
        return props;
    }
}
