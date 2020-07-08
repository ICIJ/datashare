package org.icij.datashare.mode;

import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.session.*;
import org.icij.datashare.web.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

public class ServerMode extends CommonMode {
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    public ServerMode(Properties properties) { super(properties);}
    public ServerMode(Map<String, String> properties) { super(properties);}

    @Override
    protected void configure() {
        super.configure();
        bind(UsersWritable.class).to(UsersInDb.class);
        bind(SessionIdStore.class).to(RedisSessionIdStore.class);
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
        bind(StatusResource.class).asEagerSingleton();
        configurePersistence();
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.
                add(TaskResource.class).
                add(IndexResource.class).
                add(UserResource.class).
                add(NamedEntityResource.class).
                add(BatchSearchResource.class).
                add(NoteResource.class).
                add(NerResource.class).
                add(ProjectResource.class).
                add(DocumentResource.class).
                filter(ApiKeyFilter.class).
                filter(Filter.class);
    }
}
