package org.icij.datashare.kestra.plugin;

import java.util.Map;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.mode.CommonMode;
import org.junit.jupiter.api.Test;

public class ModeTest {
    @Test
    public void debugCommonModeInjectionTestMode() {
        // This test will in more obvious way than other if something goes wrong with injection, allowing to debug
        CommonMode.create(Map.of("mode", Mode.CLI.name()));
    }
}
