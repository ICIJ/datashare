package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.Plugin;
import org.icij.datashare.PluginService;

import java.util.Set;

@Singleton
@Prefix("/api/plugins")
public class PluginResource {
    private final PluginService pluginService;

    @Inject
    public PluginResource(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    /**
     * Gets the plugins set in JSON
     *
     * Example:
     * $(curl localhost:8080/api/plugins)
     */
    @Get()
    public Set<Plugin> getPluginList() {
        return pluginService.list();
    }
}
