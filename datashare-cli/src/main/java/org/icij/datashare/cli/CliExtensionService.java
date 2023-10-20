package org.icij.datashare.cli;

import com.google.inject.Injector;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.icij.datashare.cli.spi.CliExtension;
import org.icij.datashare.cli.spi.CliExtensionFactory;

public class CliExtensionService {
    private static CliExtensionService service;
    private final ServiceLoader<CliExtensionFactory> factoriesLoader;
    private final ServiceLoader<CliExtension> extensionLoader;

    private CliExtensionService() {
        extensionLoader = ServiceLoader.load(CliExtension.class);
        factoriesLoader = ServiceLoader.load(CliExtensionFactory.class);
    }

    public static synchronized CliExtensionService getInstance() {
        if (service == null) {
            service = new CliExtensionService();
        }
        return service;
    }

    public List<Cli.CliExtender> getExtensions() {
        return Stream.concat(
            extensionLoader.stream().map(ServiceLoader.Provider::get),
            factoriesLoader.stream().map(ServiceLoader.Provider::get)
        ).collect(Collectors.toList());
    }

    public List<Cli.CliRunner> getExtensionRunners(Injector injector) {
        return Stream.concat(
            extensionLoader.stream().map(ServiceLoader.Provider::get),
            factoriesLoader.stream().map(p -> p.get().apply(injector))
        ).collect(Collectors.toList());
    }
}
