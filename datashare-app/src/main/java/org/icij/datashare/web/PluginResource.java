package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Delete;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
import org.apache.commons.compress.archivers.ArchiveException;
import org.icij.datashare.Plugin;
import org.icij.datashare.PluginRegistry;
import org.icij.datashare.PluginService;

import java.io.IOException;
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

    /**
     * Download (if necessary) and install plugin specified by its id
     *
     * @param pluginId
     * @return 200 if the plugin is installed 404 if the plugin is not found by the provided id
     *
     * @throws IOException
     * @throws ArchiveException
     */
    @Put("/install/:pluginId")
    public Payload installPlugin(String pluginId) throws IOException, ArchiveException {
        try {
            pluginService.downloadAndInstall(pluginId);
        } catch (PluginRegistry.UnknownPluginException unknownPluginException) {
            return Payload.notFound();
        }
        return Payload.ok();
    }

    /**
     * Remove plugin specified by its id
     *
     * @param pluginId
     * @return 200 if the plugin is removed 404 if the plugin is not found by the provided id
     *
     * @throws IOException if there is a filesystem error
     */
    @Delete("/remove/:pluginId")
    public Payload removePlugin(String pluginId) throws IOException {
        try {
            pluginService.delete(pluginId);
        } catch (PluginRegistry.UnknownPluginException unknownPluginException) {
            return Payload.notFound();
        }
        return Payload.ok();
    }
}
