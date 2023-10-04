package org.icij.datashare.cli.spi;

import joptsimple.OptionParser;

import java.util.Properties;

public interface CliExtension {
    void addOptions(OptionParser parser);
    String identifier();

    void run(Properties properties) throws Exception;
}
