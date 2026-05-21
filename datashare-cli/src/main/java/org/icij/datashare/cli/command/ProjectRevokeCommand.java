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
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_REVOKE_IF_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_REVOKE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_REVOKE_NO_INPUT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_REVOKE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_REVOKE_USER_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_REVOKE_YES_OPT;

@Command(name = "revoke", mixinStandardHelpOptions = true, description = {
        "Revoke all project roles for a user (membership-level revoke).",
        "",
        "Examples:",
        "  datashare project revoke my-project alice",
        "  datashare project revoke my-project alice --yes --json",
        "  datashare project revoke my-project alice --if-exists"
})
public class ProjectRevokeCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", arity = "0..1", description = "Project name (positional)")
    String projectPositional;

    @Parameters(index = "1", arity = "0..1", description = "User login (positional)")
    String userPositional;

    @Option(names = "--project", description = "Project name (alternative to positional)")
    String projectFlag;

    @Option(names = "--user", description = "User login (alternative to positional)")
    String userFlag;

    @Option(names = {"--yes", "-y"}, description = "Skip the y/N confirmation")
    boolean yes;

    @Option(names = "--if-exists", description = "Idempotent: exit 0 if user has no roles on project")
    boolean ifExists;

    @Option(names = "--no-input", description = "Disable interactive prompts (implies --yes)")
    boolean noInput;

    @Option(names = "--json", description = "Emit JSON result on stdout")
    boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    Prompter prompterOverride;

    private String validatedProject;
    private String validatedUser;

    @Override
    public void run() {
        try {
            String project = projectPositional != null ? projectPositional : projectFlag;
            String user    = userPositional    != null ? userPositional    : userFlag;

            if (project != null) Validators.projectName(project);
            if (user    != null) Validators.login(user);

            Prompter prompter = resolvePrompter();
            if (project == null) project = require(prompter, "Project name", Validators::projectName, "--project");
            if (user    == null) user    = require(prompter, "User login",   Validators::login,        "--user");

            this.validatedProject = project;
            this.validatedUser    = user;
        } catch (InvalidValueException | Prompter.ValidationFailedException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            throw new CliExitException(5);
        }
    }

    private Prompter resolvePrompter() {
        if (noInput) return null;
        if (prompterOverride != null) return prompterOverride;
        Prompter prompter = new Prompter();
        return prompter.isInteractive() ? prompter : null;
    }

    private String require(Prompter prompter, String label,
                           java.util.function.Consumer<String> validator, String flagName) {
        if (prompter == null) {
            spec.commandLine().getErr().println(
                    "error: " + flagName + " is required when --no-input is set or no TTY is available");
            throw new CliExitException(2);
        }
        return prompter.promptString(label, validator);
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        if (validatedProject == null) {
            return props;
        }
        DatashareOptions.put(props, PROJECT_REVOKE_OPT, validatedProject);
        DatashareOptions.put(props, PROJECT_REVOKE_USER_OPT, validatedUser);
        DatashareOptions.putIfTrue(props, PROJECT_REVOKE_YES_OPT, yes);
        DatashareOptions.putIfTrue(props, PROJECT_REVOKE_NO_INPUT_OPT, noInput);
        DatashareOptions.putIfTrue(props, PROJECT_REVOKE_IF_EXISTS_OPT, ifExists);
        DatashareOptions.putIfTrue(props, PROJECT_REVOKE_JSON_OPT, json);
        return props;
    }
}
