package org.icij.datashare.cli.spi;

import joptsimple.OptionParser;

public interface CliExtension {
    void addOptions(OptionParser parser);
}
