package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.Prompter;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.cli.Validators.InvalidValueException;
import org.icij.datashare.user.User;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_CREATE_EMAIL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_CREATE_GROUPS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_CREATE_IF_NOT_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_CREATE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_CREATE_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_CREATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_CREATE_PASSWORD_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_CREATE_PROVIDER_OPT;

@Command(name = "create", mixinStandardHelpOptions = true, description = {
        "Create a Datashare user.",
        "",
        "Examples:",
        "  datashare user create alice --email alice@example.org",
        "  datashare user create alice --email alice@example.org --password $PW --groups p1,p2",
        "  datashare user create alice --email alice@example.org --provider oauth --no-input"
})
public class UserCreateCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", arity = "0..1", description = "Login (positional)")
    String loginPositional;

    @Option(names = "--login", description = "Login (alternative to positional)")
    String loginFlag;

    @Option(names = "--email", description = "RFC 5322 email")
    String email;

    @Option(names = "--name", description = "Display name (default: login)")
    String name;

    @Option(names = "--password", description = "Password (local provider)")
    String password;

    @Option(names = "--provider", defaultValue = "local",
            description = "local | oauth | external (default: local)")
    String provider;

    @Option(names = "--groups", description = "Comma-separated project names")
    String groupsCsv;

    @Option(names = "--if-not-exists", description = "Idempotent: exit 0 if user exists")
    boolean ifNotExists;

    @Option(names = "--no-input", description = "Disable interactive prompts")
    boolean noInput;

    @Option(names = "--json", description = "Emit JSON result on stdout")
    boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    // Package-visible for test injection; when non-null the TTY check is skipped.
    Prompter prompterOverride;

    private String resolvedLogin;
    private String resolvedEmail;
    private String resolvedPassword;
    private String canonicalGroupsCsv;
    private boolean ready;

    @Override
    public void run() {
        String login = loginPositional != null ? loginPositional : loginFlag;
        try {
            // Validate supplied values before checking for missing fields so an
            // invalid value exits 5, not 2.
            if (login != null) Validators.login(login);
            if (email != null) Validators.email(email);
            Validators.provider(provider);
            List<String> groups = Validators.groups(groupsCsv);

            boolean passwordFromFlag = password != null;

            if (login == null || email == null || (User.LOCAL.equals(provider) && password == null)) {
                if (noInput) {
                    spec.commandLine().getErr().println(
                            "error: missing required field; --no-input prevents prompting");
                    throw new CliExitException(2);
                }
                Prompter prompter = prompterOverride != null ? prompterOverride : new Prompter();
                if (prompterOverride == null && !prompter.isInteractive()) {
                    spec.commandLine().getErr().println(
                            "error: missing required field and no TTY available");
                    throw new CliExitException(2);
                }
                try {
                    if (login == null) login = prompter.promptString("Login", Validators::login);
                    if (email == null) email = prompter.promptString("Email", Validators::email);
                    if (User.LOCAL.equals(provider) && password == null) {
                        password = prompter.promptPassword();
                    }
                } catch (Prompter.ValidationFailedException e) {
                    spec.commandLine().getErr().println("error: " + e.getMessage());
                    throw new CliExitException(5);
                }
            }

            if (passwordFromFlag) {
                spec.commandLine().getErr().println(
                        "warning: passing --password on the command line exposes it in process listings; consider using the interactive prompt instead");
            }

            this.resolvedLogin = login;
            this.resolvedEmail = email;
            this.resolvedPassword = password;
            this.canonicalGroupsCsv = String.join(",", groups);
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
        // The login is the dispatch marker (CliApp tests for USER_CREATE_OPT
        // presence); siblings carry the remaining fields as typed strings.
        DatashareOptions.put(props, USER_CREATE_OPT, resolvedLogin);
        DatashareOptions.put(props, USER_CREATE_EMAIL_OPT, resolvedEmail);
        DatashareOptions.putIfNotNull(props, USER_CREATE_NAME_OPT, name);
        DatashareOptions.putIfNotNull(props, USER_CREATE_PASSWORD_OPT, resolvedPassword);
        DatashareOptions.put(props, USER_CREATE_PROVIDER_OPT, provider);
        DatashareOptions.put(props, USER_CREATE_GROUPS_OPT, canonicalGroupsCsv);
        DatashareOptions.putIfTrue(props, USER_CREATE_IF_NOT_EXISTS_OPT, ifNotExists);
        DatashareOptions.putIfTrue(props, USER_CREATE_JSON_OPT, json);
        return props;
    }
}
