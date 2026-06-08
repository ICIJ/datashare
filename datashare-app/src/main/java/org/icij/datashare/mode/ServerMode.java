package org.icij.datashare.mode;

import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.routes.Routes;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.cli.AuthMode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.db.RepositoryFactoryImpl;
import org.icij.datashare.policies.Policy;
import org.icij.datashare.policies.PolicyAnnotation;
import org.icij.datashare.policies.TaskPolicy;
import org.icij.datashare.policies.TaskPolicyAnnotation;
import org.icij.datashare.session.*;
import org.icij.datashare.web.*;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.AUTH_FILTER_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.AUTH_MODE_OPT;
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
        Class<? extends Filter> authFilterClass = resolveAuthFilterClass();
        modeForFilterClass(authFilterClass)
                .ifPresent(mode -> propertiesProvider.setProperty(AUTH_MODE_OPT, mode.cliName));
        bindAuthFilter(authFilterClass);
        bind(StatusResource.class).asEagerSingleton();
        configurePersistence();
    }

    static Class<? extends Filter> filterClassFor(AuthMode mode) {
        switch (mode) {
            case OAUTH:      return OAuth2CookieFilter.class;
            case FORM:       return FormAuthFilter.class;
            case BASIC:      return BasicAuthAdaptorFilter.class;
            case YES_COOKIE: return YesCookieAuthFilter.class;
            case YES_BASIC:  return YesBasicAuthFilter.class;
            default:         throw new IllegalStateException("Unhandled auth mode: " + mode);
        }
    }

    static Optional<AuthMode> modeForFilterClass(Class<? extends Filter> clazz) {
        return Arrays.stream(AuthMode.values())
                .filter(mode -> filterClassFor(mode).equals(clazz))
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    Class<? extends Filter> resolveAuthFilterClass() {
        String authMode = propertiesProvider.get(AUTH_MODE_OPT).orElse("");
        String authFilterClassName = propertiesProvider.get(AUTH_FILTER_OPT).orElse("");
        if (!authMode.isEmpty()) {
            if (!authFilterClassName.isEmpty()) {
                logger.warn("--authFilter is deprecated and ignored because --auth is set");
            }
            return filterClassFor(AuthMode.fromString(authMode));
        }
        if (!authFilterClassName.isEmpty()) {
            logger.warn("--authFilter is deprecated; prefer --auth (oauth, form, basic, yesCookie, yesBasic)");
            try {
                return (Class<? extends Filter>) Class.forName(authFilterClassName, true, ClassLoader.getSystemClassLoader());
            } catch (ClassNotFoundException e) {
                logger.warn("\"{}\" auth filter class not found. Using default {}", authFilterClassName, FormAuthFilter.class);
                return FormAuthFilter.class;
            }
        }
        return filterClassFor(AuthMode.FORM);
    }

    void bindAuthFilter(Class<? extends Filter> authFilterClass) {
        logger.info("setting auth filter to {}", authFilterClass);
        bind(Filter.class).to(authFilterClass);
        if (BasicAuthFilter.class.isAssignableFrom(authFilterClass)) {
            bind(ApiKeyFilter.class).toInstance(getDummyApiKeyFilter());
        } else if (authFilterClass.equals(YesCookieAuthFilter.class)) {
            bind(YesCookieAuthFilter.class).toInstance(getYesCookieAuthFilter());
        }
    }

    protected ApiKeyFilter getDummyApiKeyFilter() {
        return new ApiKeyFilter(null, apiKey -> null, null) {
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
        // Use registerAroundAnnotation (not registerAfterAnnotation) so @ProjectPolicy checks
        // run BEFORE the endpoint handler and can block unauthorized requests with 403.
        routes.registerAroundAnnotation(Policy.class, get(PolicyAnnotation.class));
        routes.registerAroundAnnotation(TaskPolicy.class, get(TaskPolicyAnnotation.class));
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
                add(PathBannerResource.class).
                add(FtmResource.class).
                add(NerResource.class).
                add(ApiKeyResource.class).
                add(ProjectResource.class).
                add(ContentTypeResource.class).
                filter(CsrfFilter.class).
                filter(ApiKeyFilter.class).
                filter(Filter.class);
    }
}
