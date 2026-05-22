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
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_GRANT_IF_NOT_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_GRANT_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_GRANT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_GRANT_ROLE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_GRANT_USER_OPT;

@Command(name = "grant", mixinStandardHelpOptions = true, description = {
        "Grant a role to a user on a project (replaces any existing role).",
        "",
        "Examples:",
        "  datashare project grant my-project alice admin",
        "  datashare project grant my-project alice editor --if-not-exists",
        "  datashare project grant my-project alice visitor --json --no-input"
})
public class ProjectGrantCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", arity = "0..1", description = "Project name (positional)")
    String projectPositional;

    @Parameters(index = "1", arity = "0..1", description = "User login (positional)")
    String userPositional;

    @Parameters(index = "2", arity = "0..1", description = "Role: admin|editor|member|visitor (positional)")
    String rolePositional;

    @Option(names = "--project", description = "Project name (alternative to positional)")
    String projectFlag;

    @Option(names = "--user", description = "User login (alternative to positional)")
    String userFlag;

    @Option(names = "--role", description = "Role: admin|editor|member|visitor (alternative to positional)")
    String roleFlag;

    @Option(names = "--if-not-exists", description = "Idempotent: exit 0 if user already has the role")
    boolean ifNotExists;

    @Option(names = "--no-input", description = "Disable interactive prompts")
    boolean noInput;

    @Option(names = "--json", description = "Emit JSON result on stdout")
    boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    // Package-visible for test injection; when non-null the TTY check is skipped.
    Prompter prompterOverride;

    private String validatedProject;
    private String validatedUser;
    private String validatedRoleAlias; // canonical lower-case alias

    @Override
    public void run() {
        try {
            String project = projectPositional != null ? projectPositional : projectFlag;
            String user    = userPositional    != null ? userPositional    : userFlag;
            String roleIn  = rolePositional    != null ? rolePositional    : roleFlag;

            if (project != null) Validators.projectName(project);
            if (user    != null) Validators.login(user);
            if (roleIn  != null) Validators.projectRole(roleIn);

            Prompter prompter = resolvePrompter();
            if (project == null) project = require(prompter, "Project name", Validators::projectName, "--project");
            if (user    == null) user    = require(prompter, "User login",   Validators::login,        "--user");
            // Validators.projectRole returns Role; wrap as Consumer for the require() signature.
            // We only care that it doesn't throw.
            if (roleIn  == null) roleIn  = require(prompter, "Role (admin|editor|member|visitor)",
                                                  s -> Validators.projectRole(s), "--role");

            this.validatedProject   = project;
            this.validatedUser      = user;
            this.validatedRoleAlias = roleIn.trim().toLowerCase(java.util.Locale.ROOT);
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
        DatashareOptions.put(props, PROJECT_GRANT_OPT, validatedProject);
        DatashareOptions.put(props, PROJECT_GRANT_USER_OPT, validatedUser);
        DatashareOptions.put(props, PROJECT_GRANT_ROLE_OPT, validatedRoleAlias);
        DatashareOptions.putIfTrue(props, PROJECT_GRANT_IF_NOT_EXISTS_OPT, ifNotExists);
        DatashareOptions.putIfTrue(props, PROJECT_GRANT_JSON_OPT, json);
        return props;
    }
}
