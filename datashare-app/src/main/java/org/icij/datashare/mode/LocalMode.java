package org.icij.datashare.mode;

import net.codestory.http.routes.Routes;
import org.icij.datashare.DocumentUserRecommendation;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.web.*;

import java.util.Map;
import java.util.Properties;

public class LocalMode extends CommonMode {
    LocalMode(Properties properties) { super(properties);}
    LocalMode(Map<String, String> properties) { super(properties);}

    protected LocalUserFilter getLocalUserFilter() {
        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        JooqRepository jooqRepository = (JooqRepository) repositoryFactory.createRepository();
        return new LocalUserFilter(propertiesProvider, jooqRepository);
    }

    @Override
    protected void configure() {
        super.configure();
        bind(IndexWaiterFilter.class).asEagerSingleton();
        bind(StatusResource.class).asEagerSingleton();
        bind(LocalUserFilter.class).toInstance(getLocalUserFilter());
        configurePersistence();
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.
                add(TaskResource.class).
                add(TreeResource.class).
                add(IndexResource.class).
                add(UserResource.class).
                add(NamedEntityResource.class).
                add(DocumentResource.class).
                add(DocumentUserRecommendationResource.class).
                add(BatchSearchResource.class).
                add(PluginResource.class).
                add(ExtensionResource.class).
                add(ProjectResource.class).
                add(NoteResource.class).
                add(NerResource.class).
                filter(IndexWaiterFilter.class).
                filter(LocalUserFilter.class);
    }
}
