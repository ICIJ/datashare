package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.util.List;
import java.util.Properties;
import static org.icij.datashare.cli.DatashareCliOptions.API_KEY_EXPIRATION_DATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.API_KEY_MAX_LIFETIME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.API_KEY_PROJECTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.CRE_API_KEY_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "create", mixinStandardHelpOptions = true,
        description = {"Generate and store a new API key for a user.", "", "Examples:",
                "  datashare api-key create alice", "  datashare api-key create alice --expiration-date 2027-02-01",
                "  datashare api-key create alice --project a --project b",
                "  datashare -s /etc/datashare/settings.properties api-key create alice"})
public class ApiKeyCreateCommand implements Runnable, DatashareSubcommand {
    @Parameters(index = "0", description = "Username")
    String user;
    @Option(names = {"--expiration-date"},
            description = "Expiration date of the key (YYYY-MM-DD, UTC). Defaults to 30 days from now.")
    String expirationDate;
    @Option(names = {"--project"},
            description = "Limit the key to this project. Repeat to allow several. Without it the key is unscoped and replaces the user's current key.")
    List<String> projects;
    @Option(names = {"--apiKeyMaxLifetime"},
            description = "Maximum lifetime of API keys as an ISO-8601 duration in days or smaller units (e.g. P90D, PT12H). Unset means any requested date is allowed; keys created without a date still expire after 30 days.")
    String apiKeyMaxLifetime;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        DatashareOptions.put(props, CRE_API_KEY_OPT, user);
        DatashareOptions.putIfNotNull(props, API_KEY_EXPIRATION_DATE_OPT, expirationDate);
        DatashareOptions.putIfNotNull(props, API_KEY_MAX_LIFETIME_OPT, apiKeyMaxLifetime);
        if (projects != null) {
            String joinedProjects = String.join(",", projects);
            DatashareOptions.put(props, API_KEY_PROJECTS_OPT, joinedProjects);
        }
        return props;
    }
}
