package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedisDocumentSet;
import org.jetbrains.annotations.NotNull;

import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPTION;

public class RedisUserDocumentSet extends RedisDocumentSet {
    public RedisUserDocumentSet(String setName, String redisAddress) {
        super(setName, redisAddress);
    }

    @Inject
    public RedisUserDocumentSet(PropertiesProvider provider, @Assisted String setName) {
        super(setName, provider.get("redisAddress").orElse("redis://redis:6379"));
    }

    public RedisUserDocumentSet(@NotNull final User user, PropertiesProvider propertiesProvider) {
         this(propertiesProvider, getSetName(user, propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue")));
     }

    private static String getSetName(User user, String baseName) {
        return user.isNull() ? baseName : baseName + "_" + user.id;
    }
}
