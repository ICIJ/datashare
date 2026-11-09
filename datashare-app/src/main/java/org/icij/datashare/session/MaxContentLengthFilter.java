package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_MAX_HTTP_BODY_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.MAX_HTTP_BODY_SIZE_OPT;

/**
 * Rejects a request whose declared Content-Length exceeds the configured maximum, before any
 * handler reads the body. Without it, simple-http spools an unbounded body to the temp directory
 * and the form-login handler reads the full body before authenticating, so one large unauthenticated
 * POST can exhaust disk or memory. Registered ahead of the authentication filter so the check runs
 * first.
 */
public class MaxContentLengthFilter implements Filter {
    private static final Logger logger = LoggerFactory.getLogger(MaxContentLengthFilter.class);
    private final long maxBytes;

    @Inject
    public MaxContentLengthFilter(PropertiesProvider propertiesProvider) {
        this.maxBytes =
                propertiesProvider.get(MAX_HTTP_BODY_SIZE_OPT).map(Long::parseLong).orElse(DEFAULT_MAX_HTTP_BODY_SIZE);
    }

    @Override
    public Payload apply(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        if (isWithinLimit(context.header("Content-Length"))) {
            return nextFilter.get();
        }
        logger.warn("rejecting request to {}: Content-Length above the {}-byte limit", uri, maxBytes);
        return new Payload(413);
    }

    // A disabled check (limit <= 0), an absent header (GET and chunked requests) or an unparseable
    // value all pass through: the framing of chunked or malformed requests is simple-http's concern.
    private boolean isWithinLimit(String contentLengthHeader) {
        if (maxBytes <= 0 || contentLengthHeader == null) {
            return true;
        }
        try {
            return Long.parseLong(contentLengthHeader.trim()) <= maxBytes;
        } catch (NumberFormatException invalidHeader) {
            return true;
        }
    }
}
