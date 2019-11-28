package org.icij.datashare.mode;

import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.language.OptimaizeLanguageGuesser;
import org.icij.datashare.web.NlpResource;
import org.icij.datashare.session.LocalUserFilter;

import java.util.Properties;

public class NerMode extends CommonMode {
    public NerMode(Properties properties) { super(properties);}

    @Override
    protected void configure() {
        bind(Filter.class).to(LocalUserFilter.class).asEagerSingleton();
        bind(LanguageGuesser.class).to(OptimaizeLanguageGuesser.class);
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.add(NlpResource.class);
    }
}
