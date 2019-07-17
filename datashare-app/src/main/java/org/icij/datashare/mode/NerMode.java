package org.icij.datashare.mode;

import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import org.icij.datashare.web.NlpResource;
import org.icij.datashare.session.LocalUserFilter;

import java.util.Properties;

public class NerMode extends CommonMode {
    public NerMode(Properties properties) { super(properties);}

    @Override
    protected void configure() {
        super.configure();
        bind(Filter.class).to(LocalUserFilter.class).asEagerSingleton();
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.add(NlpResource.class);
    }
}
