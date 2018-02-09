package org.icij.datashare.cli;

import com.google.common.base.Joiner;
import joptsimple.*;
import org.icij.datashare.DataShare;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;


public final class DataShareCli {
    private static final Logger LOGGER = LoggerFactory.getLogger(DataShareCli.class);
    private static List<DataShare.Stage> stages = new ArrayList<>();

    private static Path inputDir;

    private static int fileParserParallelism;
    private static boolean enableOcr;

    private static List<Pipeline.Type> nlpPipelineTypes;
    private static int nlpPipelineParallelism;

    private static String index = DataShare.DEFAULT_INDEX;
    private static String indexAddress;
    private static Properties properties;

    private static boolean parseArguments(String[] args) {
        OptionParser parser = new OptionParser();

        AbstractOptionSpec<Void> helpOpt = DataShareCliOptions.help(parser);

        OptionSpec<DataShare.Stage> stagesOpt = DataShareCliOptions.stages(parser);
        OptionSpec<File> scanningInputDirOpt = DataShareCliOptions.inputDir(parser);

        OptionSpec<Integer> parsingParallelismOpt = DataShareCliOptions.fileParserParallelism(parser);
        OptionSpecBuilder parsingEnableOcrOpt = DataShareCliOptions.enableOcr(parser);
        OptionSpecBuilder parsingDisableOcrOpt = DataShareCliOptions.disableOcr(parser);
        parser.mutuallyExclusive(parsingEnableOcrOpt, parsingDisableOcrOpt);

        OptionSpec<Pipeline.Type> nlpPipelinesOpt = DataShareCliOptions.nlpPipelines(parser);
        OptionSpec<Integer> nlpParallelismOpt = DataShareCliOptions.nlpPipelinesParallelism(parser);

        OptionSpec<String> indexerAddressOpt = DataShareCliOptions.indexerHost(parser);

        try {
            OptionSet options = parser.parse(args);

            if (options.has(helpOpt)) {
                printHelp(parser);
                return false;
            }

            stages.addAll(options.valuesOf(stagesOpt));
            stages.sort(DataShare.Stage.comparator);

            inputDir = options.valueOf(scanningInputDirOpt).toPath();

            enableOcr = options.has(parsingEnableOcrOpt);
            fileParserParallelism = options.valueOf(parsingParallelismOpt);

            nlpPipelineTypes = options.valuesOf(nlpPipelinesOpt);
            nlpPipelineParallelism = options.valueOf(nlpParallelismOpt);
            indexAddress = options.valueOf(indexerAddressOpt);
            properties = asProperties(options, "");
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to parse arguments.", e);
            printHelp(parser);
            return false;
        }
    }

    // from https://pholser.github.io/jopt-simple/examples.html
    private static Properties asProperties(OptionSet options, String prefix) {
        Properties properties = new Properties();
        for (Map.Entry<OptionSpec<?>, List<?>> entry : options.asMap().entrySet()) {
            OptionSpec<?> spec = entry.getKey();
            properties.setProperty(
                    asPropertyKey(prefix, spec),
                    asPropertyValue(entry.getValue(), options.has(spec)));
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

    private static String asPropertyValue(List<?> values, boolean present) {
        // Simple flags have no values; treat presence/absence as true/false
        return values.isEmpty() ? String.valueOf(present) : Joiner.on(",").join(values);
    }

    private static void printHelp(OptionParser parser) {
        try {
            System.out.println("Usage: ");
            parser.printHelpOn(System.out);
        } catch (IOException e) {
            LOGGER.debug("Failed to print help message", e);
        }
    }

    public static void main(String[] args) throws Exception {
        if (!parseArguments(args)) {
            LOGGER.info("Exiting...");
            System.exit(1);
        }

        LOGGER.info("Stage:             " + stages);
        LOGGER.info("Indexer Address: " + indexAddress);

        Indexer indexer = new ElasticsearchIndexer(new PropertiesProvider(properties));

        DataShare.processDirectory(
                inputDir,
                fileParserParallelism,
                enableOcr,
                nlpPipelineTypes,
                nlpPipelineParallelism,
                indexer,
                index
        );
        indexer.close();
    }
}
