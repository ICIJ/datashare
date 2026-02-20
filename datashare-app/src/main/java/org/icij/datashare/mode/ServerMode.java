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
import org.icij.datashare.web.ApiKeyResource;
import org.icij.datashare.web.BatchSearchResource;
import org.icij.datashare.web.DocumentResource;
import org.icij.datashare.web.DocumentUserRecommendationResource;
import org.icij.datashare.web.FtmResource;
import org.icij.datashare.web.IndexResource;
import org.icij.datashare.web.NamedEntityResource;
import org.icij.datashare.web.NerResource;
import org.icij.datashare.web.NoteResource;
import org.icij.datashare.web.ProjectResource;
import org.icij.datashare.web.StatusResource;
import org.icij.datashare.web.TaskResource;
import org.icij.datashare.web.UserPolicyResource;
import org.icij.datashare.web.UserResource;

import java.util.Map;
import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.SESSION_STORE_TYPE_OPT;

public class ServerMode extends CommonMode {
    ServerMode(Properties properties) { super(properties);}
    ServerMode(Map<String, Object> properties) { super(properties);}

    @Override
    protected void configure() {
        super.configure();
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

    protected void addPermissionConfiguration(final Routes routes) {
        // Use registerAroundAnnotation (not registerAfterAnnotation) so @Policy checks
        // run BEFORE the endpoint handler and can block unauthorized requests with 403.
        routes.registerAroundAnnotation(ProjectPolicy.class, get(UserProjectPolicyAnnotation.class));
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        addPermissionConfiguration(routes);
        return routes.
                add(TaskResource.class).
                add(IndexResource.class).
                add(UserResource.class).
                add(PolicyResource.class).
                add(NamedEntityResource.class).
                add(DocumentResource.class).
                add(DocumentUserRecommendationResource.class).
                add(BatchSearchResource.class).
                add(NoteResource.class).
                add(FtmResource.class).
                add(NerResource.class).
                add(ApiKeyResource.class).
                add(ProjectResource.class).
                filter(CsrfFilter.class).
                filter(ApiKeyFilter.class).
                filter(Filter.class);
    }
}
