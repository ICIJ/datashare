package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import org.icij.datashare.model.EntityExporter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.util.Properties;
import static org.icij.datashare.cli.DatashareCliOptions.ENTITIES_EXPORT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ENTITIES_EXPORT_OUTPUT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "export", mixinStandardHelpOptions = true, description = {
        "Export the structured entities of the project set with -P, in the native format of their data model.", "",
        "Only a PostgreSQL data source streams the entities: on an embedded database the whole project is read",
        "into memory before the first entity is written.", "",
        "A project that does not exist exports nothing and exits 0, as a project holding nothing does.", "",
        "Examples:", "  datashare -P my-project entities export > my-project.ftm.json",
        "  datashare -P my-project entities export --output my-project.ftm.json"})
public class EntitiesExportCommand implements Runnable, DatashareSubcommand {
    @Option(names = "--model", defaultValue = EntityExporter.DEFAULT_MODEL,
            description = "Data model to export (default: ${DEFAULT-VALUE})")
    String model;
    @Option(names = "--output", description = "File to write instead of stdout")
    String output;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        DatashareOptions.put(props, ENTITIES_EXPORT_OPT, model);
        DatashareOptions.putIfNotNull(props, ENTITIES_EXPORT_OUTPUT_OPT, output);
        return props;
    }
}
