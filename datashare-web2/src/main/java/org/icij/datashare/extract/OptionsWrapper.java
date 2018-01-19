package org.icij.datashare.extract;

import org.icij.task.Options;

import java.util.HashMap;
import java.util.Map;

public class OptionsWrapper {
    private final Map<String, String> options;

    public OptionsWrapper() {
        options = new HashMap<>();
    }

    public OptionsWrapper(final Map<String, String> options) {
        this.options = options;
    }

    public Map<String, String> getOptions() { return options;}

    public Options asOptions() {
        return Options.from(options);
    }
}
