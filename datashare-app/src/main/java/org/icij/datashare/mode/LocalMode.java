package org.icij.datashare.mode;

import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchServer;
import org.icij.datashare.web.*;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public class LocalMode extends CommonMode {
    LocalMode(Properties properties) { super(properties);}
    public LocalMode(Map<String, String> properties) { super(properties);}

    @Override
    protected void configure() {
        super.configure();
        bind(Filter.class).to(LocalUserFilter.class).asEagerSingleton();
        bind(IndexWaiterFilter.class).asEagerSingleton();
        try {
            new ElasticsearchServer().start();
        } catch (IOException e) {
            logger.error("cannot run elasticsearch", e);
            System.exit(5);
        }
        configurePersistence();
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.
                add(TaskResource.class).
                add(IndexResource.class).
                add(UserResource.class).
                add(NamedEntityResource.class).
                add(DocumentResource.class).
                add(BatchSearchResource.class).
                add(ProjectResource.class).
                add(NoteResource.class).
                add(NlpResource.class).
                filter(IndexWaiterFilter.class);
    }
}
