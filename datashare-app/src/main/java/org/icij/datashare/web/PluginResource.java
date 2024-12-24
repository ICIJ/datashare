package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.payload.Payload;
import org.apache.commons.compress.archivers.ArchiveException;
import org.icij.datashare.DeliverablePackage;
import org.icij.datashare.DeliverableRegistry;
import org.icij.datashare.PluginService;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.NoSuchElementException;
import java.util.Set;

import static java.util.Optional.ofNullable;
import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api/plugins")
public class PluginResource {
    private final PluginService pluginService;

    @Inject
    public PluginResource(PluginService pluginService) { this.pluginService = pluginService; }

    @Operation(description = """
            Gets the plugins set in JSON.
            
            If a request parameter "filter" is provided, the regular expression will be applied to the list.
            
            See [Pattern](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html) for pattern syntax.""",
            parameters = {@Parameter(name = "filter", description = "regular expression to apply", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns the plugins set", useReturnTypeSchema = true)
    @Get()
    public Set<DeliverablePackage> getPluginList(Context context) {
        return pluginService.list(ofNullable(context.request().query().get("filter")).orElse(".*"));
    }

    @Operation(description = "Preflight request")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS and PUT")
    @Options("/install")
    public Payload installPluginPreflight() {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    @Operation(description = "Download (if necessary) and install plugin specified by its id or url." +
            "Request parameter `id` or `url` must be present.",
            parameters = {@Parameter(name = "id", description = "id of the plugin", in = ParameterIn.QUERY),
                    @Parameter(name = "url", description = "url of the plugin", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns 200 if the plugin is installed")
    @ApiResponse(responseCode = "400", description = "returns 400 if neither id nor url is provided")
    @ApiResponse(responseCode = "404", description = "returns 404 if the plugin is not found by the provided id or url")
    @Put("/install")
    public Payload installPlugin(Context context) throws IOException, ArchiveException {
        String pluginUrlString = context.request().query().get("url");
        try {
            pluginService.downloadAndInstall(new URL(pluginUrlString));
            return Payload.ok();
        } catch (MalformedURLException not_url) {
            String pluginId = context.request().query().get("id");
            if (pluginId == null) {
                return Payload.badRequest();
            }
            try {
                pluginService.downloadAndInstall(pluginId);
                return Payload.ok();
            } catch (DeliverableRegistry.UnknownDeliverableException unknownDeliverableException) {
                return Payload.notFound();
            }
        }
    }

    @Operation(description = "Preflight request")
    @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS and DELETE")
    @Options("/uninstall")
    public Payload uninstallPluginPreflight() { return ok().withAllowMethods("OPTIONS", "DELETE");}

    @Operation(description = "Uninstall plugin specified by its id.")
    @ApiResponse(responseCode = "204", description = "returns 204 if the plugin is uninstalled (idempotent)")
    @Delete("/uninstall?id=:pluginId")
    public Payload uninstallPlugin(String pluginId) throws IOException {
        try {
            pluginService.delete(pluginId);
        } catch (DeliverableRegistry.UnknownDeliverableException|NoSuchElementException unknownDeliverableException) {
            return new Payload(204);
        }
        return new Payload(204);
    }
}
