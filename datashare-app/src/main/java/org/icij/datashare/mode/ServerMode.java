package org.icij.datashare.mode;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.routes.Routes;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.cli.AuthMode;
import org.icij.datashare.cli.QueueType;
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

    @Override
    protected void configure() {
        super.configure();
        bind(ApiKeyStore.class).to(ApiKeyStoreAdapter.class);
        Class<? extends Filter> authFilterClass = resolveAuthFilterClass();
        // Materialize the effective auth mode back into the properties so the public /settings
        // endpoint reports it (e.g. "form" by default). Unrecognized custom filters write nothing.
        modeForFilterClass(authFilterClass)
                .ifPresent(mode -> propertiesProvider.setProperty(AUTH_MODE_OPT, mode.cliName));
        bindAuthFilter(authFilterClass);
        bind(StatusResource.class).asEagerSingleton();
        configurePersistence();
    }

    @Provides @Singleton
    SessionIdStore provideSessionIdStore() {
        QueueType sessionStoreType = getQueueType(propertiesProvider, SESSION_STORE_TYPE_OPT, QueueType.MEMORY);
        if (QueueType.MEMORY == sessionStoreType) {
            return SessionIdStore.inMemory();
        }
        RedisSessionIdStore sessionIdStore = new RedisSessionIdStore(propertiesProvider);
        addCloseable(sessionIdStore);
        return sessionIdStore;
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

    // Inverse of filterClassFor. Relies on filterClassFor being total over AuthMode: a new enum
    // value without a matching case there makes this throw, so keep the switch above exhaustive.
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
