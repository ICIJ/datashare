package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedisReportMap;
import org.redisson.api.RedissonClient;

import java.nio.charset.Charset;

public class RedisUserReportMap extends RedisReportMap {

    @Inject
    public RedisUserReportMap(PropertiesProvider propertiesProvider, RedissonClient redissonClient, @Assisted String mapName) {
        super(redissonClient, mapName, Charset.forName(propertiesProvider.get("charset").orElse(Charset.defaultCharset().name())));
    }

    private static String getMapName(User user, String baseName) {
        return user.isNull() ? baseName : baseName + "_" + user.id;
    }
}
