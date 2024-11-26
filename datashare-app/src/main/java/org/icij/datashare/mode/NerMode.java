package org.icij.datashare.mode;

import net.codestory.http.routes.Routes;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.web.NerResource;

import java.util.Properties;

public class NerMode extends CommonMode {
    NerMode(Properties properties) { super(properties);}

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.add(NerResource.class).filter(LocalUserFilter.class);
    }
}
