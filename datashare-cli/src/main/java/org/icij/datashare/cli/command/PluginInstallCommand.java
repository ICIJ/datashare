package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PLUGIN_INSTALL_OPT;

@Command(name = "install", mixinStandardHelpOptions = true, description = {
        "Install a plugin by id, URL, or local file path.",
        "",
        "Examples:",
        "  datashare plugin install datashare-plugin-ner-corenlp",
        "  datashare plugin install https://example.com/myplugin.jar",
        "  datashare --pluginsDir /opt/datashare/plugins plugin install datashare-plugin-ner-corenlp"
})
public class PluginInstallCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", description = "Plugin id, URL, or file path")
    String pluginId;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        props.setProperty(MODE_OPT, Mode.CLI.name());
        props.setProperty(PLUGIN_INSTALL_OPT, pluginId);
        return props;
    }
}
