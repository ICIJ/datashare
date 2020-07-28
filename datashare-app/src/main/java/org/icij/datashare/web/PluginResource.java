package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Delete;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
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
import static net.codestory.http.payload.Payload.ok;

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
     *
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

    @Options("/install/:pluginId")
    public Payload installPluginPreflight(String pluginId) {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    /**
     * Download (if necessary) and install plugin specified by its id or url
     *
     * request parameter id or url must be present, else a
     * @return  200 if the plugin is installed
     *          404 if the plugin is not found by the provided id or url
     *          400 if neither id nor url is provided
     *
     * @throws IOException
     * @throws ArchiveException
     *
     * Example:
     * $(curl -i -XPUT localhost:8080/api/plugins/install?id=datashare-plugin-site-alert)
     */
    @Put("/install")
    public Payload installPlugin(Context context) throws IOException, ArchiveException {
        String pluginId = context.request().query().get("id");
        try {
            pluginService.downloadAndInstall(pluginId);
        } catch (PluginRegistry.UnknownPluginException unknownPluginException) {
            return Payload.notFound();
        }
        return Payload.ok();
    }

    @Options("/remove/:pluginId")
    public Payload removePluginPreflight(String pluginId) {
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    /**
     * Remove plugin specified by its id
     *
     * @param pluginId
     * @return 200 if the plugin is removed 404 if the plugin is not found by the provided id
     *
     * @throws IOException if there is a filesystem error
     *
     * Example:
     * $(curl -i -XDELETE localhost:8080/api/plugins/remove?id=datashare-plugin-site-alert)
     */
    @Delete("/remove?id=:pluginId")
    public Payload removePlugin(String pluginId) throws IOException {
        try {
            pluginService.delete(pluginId);
        } catch (PluginRegistry.UnknownPluginException unknownPluginException) {
            return Payload.notFound();
        }
        return Payload.ok();
    }
}
