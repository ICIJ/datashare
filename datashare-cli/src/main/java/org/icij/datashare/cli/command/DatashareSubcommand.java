package org.icij.datashare.cli.command;

import java.util.Properties;

/**
 * Interface for picocli subcommands that produce properties
 * to be consumed by the rest of the Datashare application.
 */
public interface DatashareSubcommand {
    /** Returns the properties contributed by this subcommand to be merged into the global properties. */
    Properties getSubcommandProperties();
}
