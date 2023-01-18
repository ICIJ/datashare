package org.icij.datashare.mode;

import java.util.Properties;

public class BatchMode extends CommonMode {
    BatchMode(Properties properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        super.configure();

        configurePersistence();
    }
}
