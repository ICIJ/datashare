package org.icij.datashare.cli;


import static java.util.Collections.singletonList;

import com.google.inject.Inject;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import joptsimple.OptionParser;
import org.icij.datashare.cli.spi.CliExtension;

public class CliExtensionFoo implements CliExtension {
    protected final Properties fooProperties;

    public CliExtensionFoo() {
        this.fooProperties = null;
    }

    @Inject
    public CliExtensionFoo(Properties fooProperties) {
        this.fooProperties = fooProperties;
    }

    @Override
    public void addOptions(OptionParser parser) {
        parser.acceptsAll(singletonList("foo"), "Test foo")
            .withRequiredArg()
            .ofType(String.class);
        parser.acceptsAll(singletonList("fooCommand"));
    }

    @Override
    public String identifier() {
        return "foo";
    }

    @Override
    public void run(Properties properties) throws Exception {
        Stream<Map.Entry<Object, Object>> props = properties.entrySet().stream();
        if (this.fooProperties != null) {
            props = props.filter(e -> !this.fooProperties.containsKey(e.getKey()));
        }
        props.forEach(
            e -> System.out.println("extra key: " + e.getKey() + " extra value: " + e.getValue()));
    }

}
