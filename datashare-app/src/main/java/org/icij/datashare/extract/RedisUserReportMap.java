package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedisReportMap;
import org.jetbrains.annotations.NotNull;

import static org.icij.datashare.PropertiesProvider.MAP_NAME_OPTION;

public class RedisUserReportMap extends RedisReportMap {
    private final String mapName;

    @Inject
    public RedisUserReportMap(PropertiesProvider provider, @Assisted String mapName) {
        super(mapName, provider.get("redisAddress").orElse("redis://redis:6379"));
        this.mapName = mapName;
    }

    public RedisUserReportMap(@NotNull final User user, PropertiesProvider propertiesProvider) {
         this(propertiesProvider, getMapName(user, propertiesProvider.get(MAP_NAME_OPTION).orElse("extract:report")));
     }

    private static String getMapName(User user, String baseName) {
        return user.isNull() ? baseName : baseName + "_" + user.id;
    }
}
