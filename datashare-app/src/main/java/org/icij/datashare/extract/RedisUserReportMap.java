package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedisReportMap;
import org.icij.task.Options;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.Charset;
import java.util.HashMap;

import static org.icij.datashare.PropertiesProvider.MAP_NAME_OPTION;

public class RedisUserReportMap extends RedisReportMap {

    @Inject
    public RedisUserReportMap(PropertiesProvider provider, @Assisted String mapName) {
        super(Options.from(provider.createOverriddenWith(new HashMap<String, String>() {{put("reportName", mapName);put("charset", String.valueOf(Charset.defaultCharset()));}})));
    }

    public RedisUserReportMap(@NotNull final User user, PropertiesProvider propertiesProvider) {
         this(propertiesProvider, getMapName(user, propertiesProvider.get(MAP_NAME_OPTION).orElse("extract:report")));
     }

    private static String getMapName(User user, String baseName) {
        return user.isNull() ? baseName : baseName + "_" + user.id;
    }
}
