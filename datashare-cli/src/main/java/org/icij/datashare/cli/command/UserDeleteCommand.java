package org.icij.datashare.cli.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.cli.CliExitException;
import org.icij.datashare.cli.Mode;
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

    private String validatedPayload;

    @Override
    public void run() {
        String login = loginPositional != null ? loginPositional : loginFlag;
        try {
            if (login != null) Validators.login(login);

            if (login == null) {
                if (noInput) {
                    spec.commandLine().getErr().println(
                            "error: --login is required when --no-input is set");
                    throw new CliExitException(2);
                }
                spec.commandLine().getErr().println(
                        "error: missing --login (interactive prompt wired in next task)");
                throw new CliExitException(2);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("login", login);
            payload.put("yes", yes || noInput);
            payload.put("ifExists", ifExists);
            payload.put("json", json);

            validatedPayload = MAPPER.writeValueAsString(payload);
        } catch (InvalidValueException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            throw new CliExitException(5);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        if (validatedPayload != null) {
            DatashareOptions.put(props, USER_DELETE_OPT, validatedPayload);
        }
        return props;
    }
}
