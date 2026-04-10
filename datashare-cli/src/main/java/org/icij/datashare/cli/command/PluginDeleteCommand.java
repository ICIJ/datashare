package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PLUGIN_DELETE_OPT;

@Command(name = "delete", mixinStandardHelpOptions = true, description = {
        "Delete an installed plugin by id or directory path.",
        "",
        "Examples:",
        "  datashare plugin delete datashare-plugin-ner-corenlp",
        "  datashare --pluginsDir /opt/datashare/plugins plugin delete datashare-plugin-ner-corenlp"
})
public class PluginDeleteCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", description = "Plugin id or base directory")
    String pluginId;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        DatashareOptions.put(props, PLUGIN_DELETE_OPT, pluginId);
        return props;
    }
}
