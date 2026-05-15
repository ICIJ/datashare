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
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_ALLOW_FROM_MASK_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_CREATOR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_DESCRIPTION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_IF_NOT_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_LABEL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_LOGO_URL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_MAINTAINER_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_NO_INDEX_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_PUBLISHER_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_SOURCE_PATH_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_SOURCE_URL_OPT;

@Command(name = "create", mixinStandardHelpOptions = true, description = {
        "Create a Datashare project.",
        "",
        "Examples:",
        "  datashare project create my-project",
        "  datashare project create my-project --label 'My Project' --description 'leak archive'",
        "  datashare project create my-project --source-path /data/my-project --allow-from-mask 10.0.0.0",
        "  datashare project create my-project --no-index --if-not-exists"
})
public class ProjectCreateCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", arity = "0..1", description = "Project name (positional)")
    String namePositional;

    @Option(names = "--name", description = "Project name (alternative to positional)")
    String nameFlag;

    @Option(names = "--label", description = "Display label (default: name)")
    String label;

    @Option(names = "--description", description = "Free-form description")
    String description;

    @Option(names = "--source-path", description = "Filesystem source path (default: /vault/<name>)")
    String sourcePath;

    @Option(names = "--allow-from-mask",
            description = "IP mask for download access (default: *.*.*.*)")
    String allowFromMask;

    @Option(names = "--source-url", description = "URL of the data origin")
    String sourceUrl;

    @Option(names = "--maintainer-name", description = "Maintainer display name")
    String maintainerName;

    @Option(names = "--publisher-name", description = "Publisher display name")
    String publisherName;

    @Option(names = "--logo-url", description = "URL to the project logo")
    String logoUrl;

    @Option(names = "--creator",
            description = "Grant PROJECT_ADMIN on the new project to this user "
                    + "(default: defaultUserName in LOCAL/EMBEDDED mode)")
    String creator;

    @Option(names = "--no-index", description = "Skip Elasticsearch index creation")
    boolean noIndex;

    @Option(names = "--if-not-exists", description = "Idempotent: exit 0 if project exists")
    boolean ifNotExists;

    @Option(names = "--no-input", description = "Disable interactive prompts")
    boolean noInput;

    @Option(names = "--json", description = "Emit JSON result on stdout")
    boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    // Package-visible for test injection; when non-null the TTY check is skipped.
    Prompter prompterOverride;

    private String resolvedName;
    private boolean ready;

    @Override
    public void run() {
        String name = namePositional != null ? namePositional : nameFlag;
        try {
            // Validate supplied values before checking for missing fields so an
            // invalid value exits 5, not 2.
            if (name != null) Validators.projectName(name);
            if (allowFromMask != null) Validators.allowFromMask(allowFromMask);
            if (sourceUrl != null) Validators.uri(sourceUrl);
            if (logoUrl != null) Validators.uri(logoUrl);
            if (creator != null) Validators.login(creator);

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

            this.resolvedName = name;
            this.ready = true;
        } catch (InvalidValueException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            throw new CliExitException(5);
        }
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        if (!ready) {
            return props;
        }
        // The project name is the dispatch marker; siblings carry the remaining
        // fields as typed strings.
        DatashareOptions.put(props, PROJECT_CREATE_OPT, resolvedName);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_LABEL_OPT, label);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_DESCRIPTION_OPT, description);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_SOURCE_PATH_OPT, sourcePath);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_ALLOW_FROM_MASK_OPT, allowFromMask);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_SOURCE_URL_OPT, sourceUrl);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_MAINTAINER_NAME_OPT, maintainerName);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_PUBLISHER_NAME_OPT, publisherName);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_LOGO_URL_OPT, logoUrl);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_CREATOR_OPT, creator);
        DatashareOptions.putIfTrue(props, PROJECT_CREATE_NO_INDEX_OPT, noIndex);
        DatashareOptions.putIfTrue(props, PROJECT_CREATE_IF_NOT_EXISTS_OPT, ifNotExists);
        DatashareOptions.putIfTrue(props, PROJECT_CREATE_JSON_OPT, json);
        return props;
    }
}
