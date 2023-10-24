package org.icij.datashare.cli.spi;

import com.google.inject.Injector;
import com.google.inject.Module;
import java.util.Properties;
import java.util.function.Function;
import joptsimple.OptionParser;

public interface CliExtension {
    void init(Function<Module[], Injector> injectorCreator);

    void addOptions(OptionParser parser);

    String identifier();

    void run(Properties properties) throws Exception;
}
