package org.icij.datashare.extract;

import org.icij.task.Options;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class OptionsWrapper {
    private final Map<String, String> options;

    public OptionsWrapper() {
        options = new HashMap<>();
    }

    public OptionsWrapper(final Map<String, String> options) {
        this.options = options;
    }

    public Map<String, String> getOptions() { return options;}

    public Options<String> asOptions() { return Options.from(options); }

    public Properties asProperties() {
        Properties properties = new Properties();
        properties.putAll(options);
        return properties;
    }
}
