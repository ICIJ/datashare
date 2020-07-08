package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;

public class BasicAuthAdaptorFilter extends BasicAuthFilter {
    @Inject
    public BasicAuthAdaptorFilter(PropertiesProvider propertiesProvider, UsersWritable users) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), "datashare", users);
    }
}
