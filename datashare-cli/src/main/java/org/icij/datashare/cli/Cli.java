package org.icij.datashare.cli;

import java.util.Properties;
import joptsimple.OptionParser;

public class Cli {
    public interface CliExtender {
        String identifier();

        void addOptions(OptionParser parser);

    }

    public interface CliRunner {
        void run(Properties properties) throws Exception;
    }
}
