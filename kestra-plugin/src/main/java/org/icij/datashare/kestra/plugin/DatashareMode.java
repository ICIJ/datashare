package org.icij.datashare.kestra.plugin;

import java.util.Map;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.mode.CommonMode;


public class DatashareMode {
    private static CommonMode instance;

    // TODO: we probably want to implement a lighter version of the common mode which has the strict minimum

    // TODO: ideally we should read these from env or env form
    private static final Map<String, Object> ENV_VARS = Map.of(
        "dataSourceUrl", "jdbc:postgresql://postgres:5432/?user=admin&password=admin",
        "mode", Mode.CLI.name()
    );

    public synchronized static CommonMode modeSingleton() {
        if (instance == null) {
            instance = CommonMode.create(ENV_VARS);
        }
        return instance;
    }

}
