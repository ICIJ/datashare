package org.icij.datashare.mode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

public class CliMode extends CommonMode {
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    public CliMode(Properties properties) { super(properties);}

    CliMode(Map<String, String> properties) { super(properties);}

    @Override
    protected void configure() {
        super.configure();
        configurePersistence();
    }
}
