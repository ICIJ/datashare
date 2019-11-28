package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;

public class BasicAuthAdaptorFilter extends BasicAuthFilter {
    @Inject
    public BasicAuthAdaptorFilter(PropertiesProvider propertiesProvider, Users users) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), "datashare", users);
    }
}
