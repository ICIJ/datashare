package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;

public class SessionFilter implements Filter {
    private final SessionManager sessionManager;
    private final String uriPrefix;

    public SessionFilter(final SessionManager sessionManager, final String uriPrefix) {
        this.sessionManager = sessionManager;
        this.uriPrefix = uriPrefix;
    }

    @Override
    public boolean matches(String uri, Context context) { return uri.startsWith(this.uriPrefix);}

    @Override
    public Payload apply(String s, Context context, PayloadSupplier nextFilter) throws Exception {
        //return nextFilter.get();
        return Payload.unauthorized("Datashare");
    }
}
