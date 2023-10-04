package org.icij.datashare.cli;

import org.icij.datashare.cli.spi.CliExtension;

import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

public class CliExtensionService {
    private static CliExtensionService service;
    private final ServiceLoader<CliExtension> loader;

    private CliExtensionService() {
        loader = ServiceLoader.load(CliExtension.class);
    }

    public static synchronized CliExtensionService getInstance() {
        if (service == null) {
            service = new CliExtensionService();
        }
        return service;
    }

    public List<CliExtension> getExtensions() {
        return loader.stream().map(ServiceLoader.Provider::get).collect(Collectors.toList());
    }
}
