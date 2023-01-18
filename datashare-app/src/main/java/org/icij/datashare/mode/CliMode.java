package org.icij.datashare.mode;

import java.util.Properties;

public class CliMode extends CommonMode {
    CliMode(Properties properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        super.configure();

        configurePersistence();
    }
}
