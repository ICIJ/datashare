package org.icij.datashare.cli.command.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.cli.CliExitException;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.cli.Validators.InvalidValueException;
import org.icij.datashare.cli.command.DatashareOptions;
import org.icij.datashare.cli.command.DatashareSubcommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.USER_CREATE_OPT;

@Command(name = "create", mixinStandardHelpOptions = true, description = {
        "Create a Datashare user.",
        "",
        "Examples:",
        "  datashare user create alice --email alice@example.org",
        "  datashare user create alice --email alice@example.org --password $PW --groups p1,p2",
        "  datashare user create alice --email alice@example.org --provider oauth --no-input"
})
public class UserCreateCommand implements Runnable, DatashareSubcommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    // Validated payload, populated during run(); consumed by getSubcommandProperties()
    private String validatedPayload;

    @Override
    public void run() {
        String login = loginPositional != null ? loginPositional : loginFlag;
        try {
            // Validate non-null fields immediately; validation runs before the
            // missing-field check so an invalid value exits 5, not 2.
            if (login != null) Validators.login(login);
            if (email != null) Validators.email(email);
            Validators.provider(provider);
            List<String> groups = Validators.groups(groupsCsv);

            if (login == null || email == null || (("local".equals(provider)) && password == null)) {
                if (noInput) {
                    spec.commandLine().getErr().println(
                            "error: missing required field; --no-input prevents prompting");
                    throw new CliExitException(2);
                }
                // Prompts are wired in Task 8. For now, emit exit 2 in non-prompt
                // mode and rely on Task 8 to fill in missing fields.
                spec.commandLine().getErr().println(
                        "error: missing required field (interactive prompts wired in next task)");
                throw new CliExitException(2);
            }

            if (password != null) {
                spec.commandLine().getErr().println(
                        "warning: passing --password on the command line exposes it in process listings; consider using the interactive prompt instead");
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("login", login);
            payload.put("email", email);
            payload.put("name", name == null ? login : name);
            payload.put("password", password);
            payload.put("provider", provider);
            payload.put("groups", groups);
            payload.put("ifNotExists", ifNotExists);
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
            DatashareOptions.put(props, USER_CREATE_OPT, validatedPayload);
        }
        return props;
    }
}
