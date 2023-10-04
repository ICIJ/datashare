package org.icij.datashare.cli;

import joptsimple.OptionParser;
import org.icij.datashare.cli.spi.CliExtension;

import java.util.Properties;

import static java.util.Collections.singletonList;

public class CliExtensionFoo implements CliExtension {
    @Override
    public void addOptions(OptionParser parser) {
        parser.acceptsAll(
                        singletonList("foo"), "Test foo")
                .withRequiredArg()
                .ofType(String.class);
        parser.acceptsAll(singletonList("fooCommand"));
    }

    @Override
    public String identifier() {
        return "foo";
    }

    @Override
    public void run(Properties properties) {}
}
