package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.Prompter;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.cli.Validators.InvalidValueException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_IF_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_KEEP_INDEX_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_NO_INPUT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_YES_OPT;

@Command(name = "delete", mixinStandardHelpOptions = true, description = {
        "Delete a Datashare project (DB, ES index, queues, report map, artifacts).",
        "",
        "Examples:",
        "  datashare project delete my-project --yes",
        "  datashare project delete my-project --yes --keep-index",
        "  datashare project delete missing --if-exists --no-input"
})
public class ProjectDeleteCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", arity = "0..1", description = "Project name (positional)")
    String namePositional;

    @Option(names = "--name", description = "Project name (alternative to positional)")
    String nameFlag;

    @Option(names = {"--yes", "-y"}, description = "Skip the typed-name confirmation")
    boolean yes;

    @Option(names = "--keep-index", description = "Do not drop the Elasticsearch index")
    boolean keepIndex;

    @Option(names = "--if-exists", description = "Idempotent: exit 0 if project missing")
    boolean ifExists;

    @Option(names = "--no-input", description = "Disable interactive prompts (implies --yes)")
    boolean noInput;

    @Option(names = "--json", description = "Emit JSON result on stdout")
    boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    Prompter prompterOverride;

    private String validatedName;

    @Override
    public void run() {
        String name = namePositional != null ? namePositional : nameFlag;
        try {
            if (name != null) Validators.projectName(name);

            if (name == null) {
                if (noInput) {
                    spec.commandLine().getErr().println(
                            "error: --name is required when --no-input is set");
                    throw new CliExitException(2);
                }
                Prompter prompter = prompterOverride != null ? prompterOverride : new Prompter();
                if (prompterOverride == null && !prompter.isInteractive()) {
                    spec.commandLine().getErr().println(
                            "error: --name is required and no TTY available");
                    throw new CliExitException(2);
                }
                try {
                    name = prompter.promptString("Project name", Validators::projectName);
                } catch (Prompter.ValidationFailedException e) {
                    spec.commandLine().getErr().println("error: " + e.getMessage());
                    throw new CliExitException(5);
                }
            }

            this.validatedName = name;
        } catch (InvalidValueException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            throw new CliExitException(5);
        }
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        if (validatedName == null) {
            return props;
        }
        DatashareOptions.put(props, PROJECT_DELETE_OPT, validatedName);
        DatashareOptions.putIfTrue(props, PROJECT_DELETE_YES_OPT, yes);
        DatashareOptions.putIfTrue(props, PROJECT_DELETE_KEEP_INDEX_OPT, keepIndex);
        DatashareOptions.putIfTrue(props, PROJECT_DELETE_IF_EXISTS_OPT, ifExists);
        DatashareOptions.putIfTrue(props, PROJECT_DELETE_NO_INPUT_OPT, noInput);
        DatashareOptions.putIfTrue(props, PROJECT_DELETE_JSON_OPT, json);
        return props;
    }
}
