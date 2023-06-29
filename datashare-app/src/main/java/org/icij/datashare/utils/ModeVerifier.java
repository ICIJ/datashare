package org.icij.datashare.utils;

import net.codestory.http.errors.ForbiddenException;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.Mode;

import java.util.Arrays;

public class ModeVerifier {
    final PropertiesProvider propertiesProvider;

    public ModeVerifier(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    public void checkAllowedMode(Mode... modes) throws ForbiddenException {
        String modeName = propertiesProvider.get("mode").orElse(null);
        if (modeName != null) {
            Mode mode = Mode.valueOf(modeName);
            if (!Arrays.asList(modes).contains(mode)) {
                throw new ForbiddenException();
            }
        }
    }
}
