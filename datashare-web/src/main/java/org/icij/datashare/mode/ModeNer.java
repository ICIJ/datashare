package org.icij.datashare.mode;

import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import org.icij.datashare.NlpResource;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;

import java.util.Properties;

public class ModeNer extends AbstractMode {
    public ModeNer(Properties properties) { super(properties);}

    @Override
    protected void configure() {
        PropertiesProvider propertiesProvider = properties == null ? new PropertiesProvider() : new PropertiesProvider().mergeWith(properties);
        bind(PropertiesProvider.class).toInstance(propertiesProvider);
        bind(Filter.class).to(LocalUserFilter.class).asEagerSingleton();
        bind(LanguageGuesser.class).to(OptimaizeLanguageGuesser.class);
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.add(NlpResource.class);
    }
}
