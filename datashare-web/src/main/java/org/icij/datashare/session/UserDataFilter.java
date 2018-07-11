package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;

public class UserDataFilter implements Filter {
    public static final String DATA_URI_PREFIX = "/api/data/";

    @Override
    public Payload apply(String uri, Context context, PayloadSupplier payloadSupplier) throws Exception {
        if (context.currentUser() != null &&
                (uri.startsWith(DATA_URI_PREFIX + context.currentUser().login()) ||
                        context.currentUser().isInRole("local"))) {
            return payloadSupplier.get();
        }
        return new Payload(401);
    }

    @Override
    public boolean matches(String uri, Context context) {
        return uri.startsWith(DATA_URI_PREFIX);
    }
}
