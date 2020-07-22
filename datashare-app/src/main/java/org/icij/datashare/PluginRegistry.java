package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

public class PluginRegistry {
    private final Map<String, Plugin> pluginMap;

    public PluginRegistry(@JsonProperty("pluginList") List<Plugin> pluginList) {
        this.pluginMap = Collections.unmodifiableMap(pluginList.stream().collect(Collectors.toMap(Plugin::getId, p -> p)));
    }

    public Set<Plugin> get() {
        return new HashSet<>(pluginMap.values());
    }

    public Plugin get(String pluginId) {
        return ofNullable(pluginMap.get(pluginId)).orElseThrow(() -> new UnknownPluginException(pluginId));
    }

    static class UnknownPluginException extends NullPointerException {
        public UnknownPluginException(String pluginId) {
            super("cannot find plugin with id " + pluginId + " in the registry");
        }
    }
}
