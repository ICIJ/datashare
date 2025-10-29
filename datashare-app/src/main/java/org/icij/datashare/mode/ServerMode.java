package org.icij.datashare.mode;

import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.routes.Routes;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.session.*;
import org.icij.datashare.web.*;

import java.util.Map;
import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.SESSION_STORE_TYPE_OPT;

public class ServerMode extends CommonMode {
    ServerMode(Properties properties) { super(properties);}
    ServerMode(Map<String, Object> properties) { super(properties);}

    @Override
    protected void configure() {
        super.configure();
        String authUsersProviderClassName = propertiesProvider.get("authUsersProvider").orElse("org.icij.datashare.session.UsersInRedis");
        Class<? extends UsersWritable> authUsersProviderClass = UsersInRedis.class;
        try {
            authUsersProviderClass = (Class<? extends UsersWritable>) Class.forName(authUsersProviderClassName, true, ClassLoader.getSystemClassLoader());
            logger.info("setting auth users provider to {}", authUsersProviderClass);
        } catch (ClassNotFoundException e) {
            logger.warn("\"{}\" auth users provider class not found. Setting provider to {}", authUsersProviderClassName, authUsersProviderClass);
        }
        bind(UsersWritable.class).to(authUsersProviderClass);
        QueueType sessionStoreType = getQueueType(propertiesProvider, SESSION_STORE_TYPE_OPT, QueueType.MEMORY);
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
                authFilterClass = (Class<? extends Filter>) Class.forName(authFilterClassName, true, ClassLoader.getSystemClassLoader());
                logger.info("setting auth filter to {}", authFilterClass);
            } catch (ClassNotFoundException e) {
                logger.warn("\"{}\" auth filter class not found. Setting filter to {}", authFilterClassName, authFilterClass);
            }
        }
        bind(Filter.class).to(authFilterClass);
        if (BasicAuthFilter.class.isAssignableFrom(authFilterClass)) {
            bind(ApiKeyFilter.class).toInstance(getDummyApiKeyFilter());
        } else if (authFilterClass.equals(YesCookieAuthFilter.class)) {
            bind(YesCookieAuthFilter.class).toInstance(getYesCookieAuthFilter());
        }
        bind(StatusResource.class).asEagerSingleton();
        configurePersistence();
    }

    protected ApiKeyFilter getDummyApiKeyFilter() {
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
                add(FtmResource.class).
                add(PluginResource.class).
                add(ExtensionResource.class).
                add(NerResource.class).
                add(ApiKeyResource.class).
                add(ProjectResource.class).
                filter(ApiKeyFilter.class).
                filter(Filter.class);
    }
}
