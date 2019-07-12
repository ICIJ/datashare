package org.icij.datashare.batch;

import com.google.common.base.Joiner;
import com.google.inject.Guice;
import com.google.inject.Injector;
import joptsimple.AbstractOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.icij.datashare.text.indexing.Indexer;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.util.Arrays.asList;

public class App {
    public static void main(String[] args) throws Exception {
        Properties properties = parseArguments(args);
        Injector injector = Guice.createInjector(new AppInjector(properties));

        injector.getInstance(BatchSearchRunner.class).call();
        injector.getInstance(Indexer.class).close();
    }

    private static Properties parseArguments(String[] args) throws IOException {
        OptionParser parser = new OptionParser();

        AbstractOptionSpec<Void> helpOpt = parser.acceptsAll(asList("help", "h", "?")).forHelp();
        parser.acceptsAll(asList("dataSourceUrl"), "Datasource URL").withRequiredArg().ofType(String.class)
                        .defaultsTo("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
        parser.acceptsAll(asList("elasticsearchAddress"), "Elasticsearch host address").withRequiredArg().ofType(String.class)
                        .defaultsTo("http://elasticsearch:9200");
        OptionSet options = parser.parse(args);

        if (options.has(helpOpt)) {
            parser.printHelpOn(System.out);
            System.exit(0);
        }
        return asProperties(options, null);
    }

    private static Properties asProperties(OptionSet options, String prefix) {
        Properties properties = new Properties();
        for (Map.Entry<OptionSpec<?>, List<?>> entry : options.asMap().entrySet()) {
            OptionSpec<?> spec = entry.getKey();
            if (options.has(spec) || !entry.getValue().isEmpty()) {
                properties.setProperty(
                        asPropertyKey(prefix, spec),
                        asPropertyValue(entry.getValue()));
            }
        }
        return properties;
    }

    private static String asPropertyKey(String prefix, OptionSpec<?> spec) {
        List<String> flags = spec.options();
        for (String flag : flags)
            if (1 < flag.length())
                return null == prefix ? flag : (prefix + '.' + flag);
        throw new IllegalArgumentException("No usable non-short flag: " + flags);
    }

    private static String asPropertyValue(List<?> values) {
        String stringValue = Joiner.on(",").join(values);
        return stringValue.isEmpty() ? "true" : stringValue;
    }
}
