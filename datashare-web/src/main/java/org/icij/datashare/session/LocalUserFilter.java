package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;

import static org.icij.datashare.session.OAuth2User.local;

public class LocalUserFilter implements Filter {
    private final String protectedUriPrefix;

    @Inject
    public LocalUserFilter(final PropertiesProvider propertiesProvider) {
        protectedUriPrefix = propertiesProvider.get("protectedUrPrefix").orElse("/");
    }

    @Override
    public Payload apply(String s, Context context, PayloadSupplier payloadSupplier) throws Exception {
        context.setCurrentUser(local());
        return payloadSupplier.get();
    }

    @Override
    public boolean matches(String uri, Context context) {
        return uri.startsWith(protectedUriPrefix);
    }
}
