package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCliOptions;
import org.jetbrains.annotations.NotNull;

public class Utils {
    @NotNull
    static RoutingStrategy getRoutingStrategy(PropertiesProvider propertiesProvider) {
        return RoutingStrategy.valueOf(propertiesProvider.get(DatashareCliOptions.TASK_ROUTING_STRATEGY_OPT).orElse(DatashareCliOptions.DEFAULT_TASK_ROUTING_STRATEGY.name()));
    }

    static String getRoutingKey(PropertiesProvider propertiesProvider) {
        return propertiesProvider.get(DatashareCliOptions.TASK_ROUTING_KEY_OPT).orElse(null);
    }
}
