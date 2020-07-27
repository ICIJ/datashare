package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.Plugin;
import org.icij.datashare.PluginService;

import java.util.Set;

import static java.util.Optional.ofNullable;

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
     * If a request parameter "filter" is provided, the regular expression will be applied to the list.
     * see https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html
     * for pattern syntax.
     *
     * Example:
     * $(curl localhost:8080/api/plugins?filter=.*paginator)
     */
    @Get()
    public Set<Plugin> getPluginList(Context context) {
        return pluginService.list(ofNullable(context.request().query().get("filter")).orElse(".*"));
    }
}
