package org.icij.datashare.cli.spi;

import com.google.inject.Injector;
import java.util.function.Function;
import org.icij.datashare.cli.Cli;

public interface CliExtensionFactory extends Function<Injector, Cli.CliRunner>, Cli.CliExtender {
    Class<? extends Cli.CliRunner> type();

    @Override
    default Cli.CliRunner apply(Injector injector) {
        return injector.getInstance(this.type());
    }
}
