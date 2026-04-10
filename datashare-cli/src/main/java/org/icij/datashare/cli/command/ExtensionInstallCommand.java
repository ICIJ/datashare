package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.EXTENSION_INSTALL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "install", mixinStandardHelpOptions = true, description = {
        "Install an extension by id, URL, or local file path.",
        "",
        "Examples:",
        "  datashare extension install datashare-extension-neo4j",
        "  datashare extension install https://example.com/myext.jar",
        "  datashare --extensionsDir /opt/datashare/extensions extension install datashare-extension-neo4j"
})
public class ExtensionInstallCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", description = "Extension id, URL, or file path")
    String extensionId;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        DatashareOptions.put(props, EXTENSION_INSTALL_OPT, extensionId);
        return props;
    }
}
