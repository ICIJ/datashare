package org.icij.datashare.extract;

import org.icij.task.Options;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class OptionsWrapper<V> {
    private final Map<String, V> options;

    public OptionsWrapper() {
        options = new HashMap<>();
    }

    public OptionsWrapper(final Map<String, V> options) {
        this.options = options;
    }

    public Map<String, V> getOptions() { return options;}

    public Options<String> asOptions() {
        return Options.from(asProperties());
    }

    public Properties asProperties() {
        Properties properties = new Properties();
        properties.putAll(options);
        return properties;
    }
}
