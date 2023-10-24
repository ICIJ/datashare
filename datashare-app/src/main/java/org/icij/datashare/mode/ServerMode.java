package org.icij.datashare.mode;

import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.routes.Routes;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.session.*;
import org.icij.datashare.web.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

public class ServerMode extends CommonMode {
    ServerMode(Properties properties) { super(properties);}
    ServerMode(Map<String, String> properties) { super(properties);}

    @Override
    protected void configure() {
        super.configure();
        String authUsersProviderClassName = propertiesProvider.get("authUsersProvider").orElse("org.icij.datashare.session.UsersInRedis");
        Class<? extends UsersWritable> authUsersProviderClass = UsersInRedis.class;
        try {
            logger.info("setting auth users provider to {}", authUsersProviderClass);
            authUsersProviderClass = (Class<? extends UsersWritable>) Class.forName(authUsersProviderClassName);
        } catch (ClassNotFoundException e) {
            logger.warn("\"{}\" auth users provider class not found. Setting provider to {}", authUsersProviderClassName, authUsersProviderClass);
        }
        bind(UsersWritable.class).to(authUsersProviderClass);
        QueueType sessionStoreType = QueueType.valueOf(propertiesProvider.get("sessionStoreType").orElse(QueueType.MEMORY.name()));
        if (QueueType.MEMORY == sessionStoreType) {
            bind(SessionIdStore.class).toInstance(SessionIdStore.inMemory());
        } else {
            bind(SessionIdStore.class).to(RedisSessionIdStore.class);
        }
        bind(ApiKeyStore.class).to(ApiKeyStoreAdapter.class);
        String authFilterClassName = propertiesProvider.get("authFilter").orElse("");
        Class<? extends Filter> authFilterClass = OAuth2CookieFilter.class;
        if (!authFilterClassName.isEmpty()) {
            try {
                authFilterClass = (Class<? extends Filter>) Class.forName(authFilterClassName);
                logger.info("setting auth filter to {}", authFilterClass);
            } catch (ClassNotFoundException e) {
                logger.warn("\"{}\" auth filter class not found. Setting filter to {}", authFilterClassName, authFilterClass);
            }
        }
        bind(Filter.class).to(authFilterClass);
        if (authFilterClass.equals(BasicAuthAdaptorFilter.class)) {
            bind(ApiKeyFilter.class).toInstance(getApiKeyFilter());
        } else if (authFilterClass.equals(YesCookieAuthFilter.class)) {
            bind(YesCookieAuthFilter.class).toInstance(getYesCookieAuthFilter());
        }
        bind(StatusResource.class).asEagerSingleton();
        configurePersistence();
    }


    protected ApiKeyFilter getApiKeyFilter() {
        return new ApiKeyFilter(null, apiKey -> null) {
            @Override
            public Payload apply(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
                return nextFilter.get();
            }
        };
    }

    protected YesCookieAuthFilter getYesCookieAuthFilter() {
        RepositoryFactoryImpl repositoryFactory = new RepositoryFactoryImpl(propertiesProvider);
        JooqRepository jooqRepository = (JooqRepository) repositoryFactory.createRepository();
        return new YesCookieAuthFilter(propertiesProvider, jooqRepository);
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.
                add(TaskResource.class).
                add(IndexResource.class).
                add(UserResource.class).
                add(NamedEntityResource.class).
                add(DocumentResource.class).
                add(DocumentUserRecommendationResource.class).
                add(BatchSearchResource.class).
                add(NoteResource.class).
                add(PluginResource.class).
                add(ExtensionResource.class).
                add(NerResource.class).
                add(ApiKeyResource.class).
                add(ProjectResource.class).
                filter(ApiKeyFilter.class).
                filter(Filter.class);
    }
}
