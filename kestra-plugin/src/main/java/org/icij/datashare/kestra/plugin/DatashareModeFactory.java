package org.icij.datashare.kestra.plugin;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.util.Map;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.mode.CommonMode;


@Factory // Let's have micronaut call this factory at startup
public class DatashareModeFactory {
    private static final Map<String, Object> ENV_VARS = Map.of(
        "dataSourceUrl", "jdbc:postgresql://postgres:5432/?user=admin&password=admin",
        "mode", Mode.CLI.name()
    );
    @Singleton
    public CommonMode provideMode() {
        return CommonMode.create(ENV_VARS);
    }
}
