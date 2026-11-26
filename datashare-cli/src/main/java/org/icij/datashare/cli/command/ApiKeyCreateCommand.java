package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.util.Properties;
import static org.icij.datashare.cli.DatashareCliOptions.API_KEY_EXPIRATION_DATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.API_KEY_MAX_LIFETIME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.CRE_API_KEY_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "create", mixinStandardHelpOptions = true,
        description = {"Generate and store a new API key for a user.", "", "Examples:",
                "  datashare api-key create alice", "  datashare api-key create alice --expiration-date 2027-02-01",
                "  datashare -s /etc/datashare/settings.properties api-key create alice"})
public class ApiKeyCreateCommand implements Runnable, DatashareSubcommand {
    @Parameters(index = "0", description = "Username")
    String user;
    @Option(names = {"--expiration-date"},
            description = "Last day the key is valid (YYYY-MM-DD, UTC), it expires at the end of that day. Defaults to 30 days from now.")
    String expirationDate;
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
        return props;
    }
}
