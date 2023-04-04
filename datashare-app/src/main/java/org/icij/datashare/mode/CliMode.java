package org.icij.datashare.mode;

import net.codestory.http.routes.Routes;

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

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return null;
    }
}
