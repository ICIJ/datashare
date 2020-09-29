package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.payload.Payload;
import org.apache.commons.compress.archivers.ArchiveException;
import org.icij.datashare.DeliverablePackage;
import org.icij.datashare.DeliverableRegistry;
import org.icij.datashare.ExtensionService;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;

import static java.util.Optional.ofNullable;
import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api/extensions")
public class ExtensionResource {
    private final ExtensionService extensionService;

    @Inject
    public ExtensionResource(ExtensionService extensionService) {this.extensionService = extensionService;}

    /**
     * Gets the extension set in JSON
     *
     * If a request parameter "filter" is provided, the regular expression will be applied to the list.
     *
     * see https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html
     * for pattern syntax.
     *
     * Example:
     * $(curl localhost:8080/api/extensions?filter=.*paginator)
     * @return
     */
    @Get()
    public Set<DeliverablePackage> getExtensionList(Context context) {
        return extensionService.list(ofNullable(context.request().query().get("filter")).orElse(".*"));
    }

    /**
     * Preflight request
     *
     * @return OPTIONS,PUT
     */
    @Options("/install")
    public Payload installExtensionPreflight() {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    /**
     * Download (if necessary) and install extension specified by its id or url
     *
     * request parameter `id` or `url` must be present.
     *
     * @return  200 if the extension is installed
     * @return  404 if the extension is not found by the provided id or url
     * @return  400 if neither id nor url is provided
     *
     * @throws IOException
     *
     * Example:
     * $(curl -i -XPUT localhost:8080/api/extensions/install?id=https://github.com/ICIJ/datashare-extension-nlp-ixapipe/releases/download/7.0.0/datashare-nlp-ixapipe-7.0.0-jar-with-dependencies.jar)
     */
    @Put("/install")
    public Payload installExtension(Context context) throws IOException, ArchiveException {
        String extensionUrlString = context.request().query().get("url");
        try {
            extensionService.downloadAndInstall(new URL(extensionUrlString));
            return Payload.ok();
        } catch (MalformedURLException not_url) {
            String extensionId = context.request().query().get("id");
            if (extensionId == null) {
                return Payload.badRequest();
            }
            try {
                extensionService.downloadAndInstall(extensionId);
                return Payload.ok();
            } catch (DeliverableRegistry.UnknownDeliverableException unknownDeliverableException) {
                return Payload.notFound();
            }
        }
    }

    /**
     * Preflight request
     *
     * @return OPTIONS,DELETE
     */
    @Options("/uninstall")
    public Payload uninstallExtensionPreflight() { return ok().withAllowMethods("OPTIONS", "DELETE");}

    /**
     * Uninstall extension specified by its id
     *
     * @param extensionId
     * @return 200 if the extension is uninstalled 404 if the extension is not found by the provided id
     *
     * @throws IOException if there is a filesystem error
     *
     * Example:
     * $(curl -i -XDELETE localhost:8080/api/extensions/uninstall?id=https://github.com/ICIJ/datashare-extension-nlp-ixapipe/releases/download/7.0.0/datashare-nlp-ixapipe-7.0.0-jar-with-dependencies.jar)
     */
    @Delete("/uninstall?id=:extensionId")
    public Payload uninstallExtension(String extensionId) throws IOException {
        try {
            extensionService.delete(extensionId);
        } catch (DeliverableRegistry.UnknownDeliverableException unknownDeliverableException) {
            return Payload.notFound();
        }
        return Payload.ok();
    }
}
