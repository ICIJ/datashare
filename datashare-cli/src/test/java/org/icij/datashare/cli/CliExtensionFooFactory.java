package org.icij.datashare.cli;


import org.icij.datashare.cli.spi.CliExtension;
import org.icij.datashare.cli.spi.CliExtensionFactory;

public class CliExtensionFooFactory extends CliExtensionFoo implements CliExtensionFactory {
    public Class<? extends CliExtension> type() {
        return CliExtensionFoo.class;
    }
}
