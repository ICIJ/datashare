package org.icij.datashare.web;

import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import org.icij.datashare.DeliverableRegistry;
import org.icij.datashare.DeliverableService;
import org.icij.datashare.Extension;
import org.icij.datashare.utils.PayloadFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

/** Install of a plugin or an extension from the `url` or `id` request parameters, shared by both resources. */
final class DeliverableInstall {
    private static final Logger logger = LoggerFactory.getLogger(DeliverableInstall.class);

    private DeliverableInstall() {}

    static Payload install(DeliverableService<?> service, String urlParameter, String id) {
        boolean hasUrl = urlParameter != null && !urlParameter.isBlank();
        if (!hasUrl && id == null) {
            return PayloadFormatter.error("id or url parameter is required", HttpStatus.BAD_REQUEST);
        }
        try {
            if (hasUrl) {
                service.downloadAndInstallFromRemote(new URL(urlParameter));
            } else {
                service.downloadAndInstall(id);
            }
            return Payload.ok();
        } catch (MalformedURLException malformedUrl) {
            return PayloadFormatter.error("malformed url", HttpStatus.BAD_REQUEST);
        } catch (DeliverableService.UrlNotAllowedException urlNotAllowed) {
            logger.warn("refusing install: {}", urlNotAllowed.getMessage());
            return PayloadFormatter.error(urlNotAllowed, HttpStatus.BAD_REQUEST);
        } catch (DeliverableRegistry.UnknownDeliverableException unknownDeliverable) {
            return Payload.notFound();
        } catch (Extension.InvalidDeliverableException invalidDeliverable) {
            logger.warn("refusing install", invalidDeliverable);
            return PayloadFormatter.error(invalidDeliverable.getMessage(), HttpStatus.BAD_GATEWAY);
        } catch (IOException installFailed) {
            // a local failure: its message can hold server paths, so it stays in the log
            logger.error("install failed", installFailed);
            return PayloadFormatter.error("install failed", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
