package org.icij.datashare.cli.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.cli.CliExitException;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.Prompter;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.cli.Validators.InvalidValueException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_DELETE_IF_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_DELETE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_DELETE_OPT;

@Command(name = "delete", mixinStandardHelpOptions = true, description = {
        "Delete a Datashare user and all their owned data.",
        "",
        "Examples:",
        "  datashare user delete alice --yes",
        "  datashare user delete alice --if-exists --no-input"
})
public class UserDeleteCommand implements Runnable, DatashareSubcommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", description = "Login (positional)")
    String loginPositional;

    @Option(names = "--login", description = "Login (alternative to positional)")
    String loginFlag;

    @Option(names = {"--yes", "-y"}, description = "Skip confirmation prompt")
    boolean yes;

    @Option(names = "--if-exists", description = "Idempotent: exit 0 if user missing")
    boolean ifExists;

    @Option(names = "--no-input", description = "Disable interactive prompts (forces --yes)")
    boolean noInput;

    @Option(names = "--json", description = "Emit JSON result on stdout")
    boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    // Package-visible for test injection; when non-null the TTY check is skipped.
    Prompter prompterOverride;

    private String resolvedLogin;
    private boolean ready;

    @Override
    public void run() {
        String login = loginPositional != null ? loginPositional : loginFlag;
        try {
            if (login != null) Validators.login(login);

            Prompter prompter = null;
            if (login == null) {
                if (noInput) {
                    spec.commandLine().getErr().println(
                            "error: --login is required when --no-input is set");
                    throw new CliExitException(2);
                }
                prompter = prompterOverride != null ? prompterOverride : new Prompter();
                if (prompterOverride == null && !prompter.isInteractive()) {
                    spec.commandLine().getErr().println(
                            "error: --login is required and no TTY available");
                    throw new CliExitException(2);
                }
                try {
                    login = prompter.promptString("Login", Validators::login);
                } catch (Prompter.ValidationFailedException e) {
                    spec.commandLine().getErr().println("error: " + e.getMessage());
                    throw new CliExitException(5);
                }
            }

            // Confirmation: prompt before scheduling the destructive action so
            // CliApp stays IO-free and tests can drive the decline path via
            // prompterOverride. --yes and --no-input both skip the prompt.
            boolean confirmed = yes || noInput;
            if (!confirmed) {
                Prompter confirmPrompter = prompter != null
                        ? prompter
                        : (prompterOverride != null ? prompterOverride : new Prompter());
                confirmed = confirmPrompter.confirm("Really delete user '" + login + "'?");
            }
            if (!confirmed) {
                emitAborted(login);
                throw new CliExitException(0);
            }

            this.resolvedLogin = login;
            this.ready = true;
        } catch (InvalidValueException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            throw new CliExitException(5);
        }
    }

    private void emitAborted(String login) {
        if (json) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("deleted", false);
            payload.put("noop", true);
            payload.put("aborted", true);
            payload.put("login", login);
            try {
                spec.commandLine().getOut().println(MAPPER.writeValueAsString(payload));
            } catch (JsonProcessingException e) {
                spec.commandLine().getErr().println("aborted");
            }
        } else {
            spec.commandLine().getErr().println("aborted");
        }
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        if (!ready) {
            return props;
        }
        DatashareOptions.put(props, USER_DELETE_OPT, resolvedLogin);
        DatashareOptions.putIfTrue(props, USER_DELETE_IF_EXISTS_OPT, ifExists);
        DatashareOptions.putIfTrue(props, USER_DELETE_JSON_OPT, json);
        return props;
    }
}
