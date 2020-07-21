package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

public class PluginRegistry {
    public final List<Plugin> pluginList;

    public PluginRegistry(@JsonProperty("pluginList") List<Plugin> pluginList) {
        this.pluginList = Collections.unmodifiableList(pluginList);
    }
}
