package org.icij.datashare.cli;

import com.google.common.base.Joiner;
import joptsimple.*;
import org.icij.datashare.DataShare;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.extraction.FileParser;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.NlpStage;
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

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.icij.datashare.DataShare.Stage.*;


/**
 * Command Line Interface to Datashare
 * <p>
 * Created by julien on 3/9/16.
 */
public final class DataShareCli {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataShareCli.class);
    private static List<DataShare.Stage> stages = new ArrayList<>();

    private static boolean runAsNode;

    private static Path inputDir;

    private static FileParser.Type fileParserType = DataShare.DEFAULT_PARSER_TYPE;
    private static int fileParserParallelism;
    private static boolean enableOcr;

    private static List<Pipeline.Type> nlpPipelineTypes;
    private static List<NlpStage> nlpStages;
    private static List<NamedEntity.Category> nlpTargetEntities;
    private static boolean nlpPipelineCaching;
    private static int nlpPipelineParallelism;

    private static String index = DataShare.DEFAULT_INDEX;
    private static String indexAddress;
    private static Properties properties;

    /**
     * Parse command line arguments
     *
     * @param args the array of arguments
     */
    private static boolean parseArguments(String[] args) {
        OptionParser parser = new OptionParser();

        AbstractOptionSpec<Void> helpOpt = DataShareCliOptions.help(parser);

        OptionSpec<DataShare.Stage> stagesOpt = DataShareCliOptions.stages(parser);
        OptionSpecBuilder asNodeOpt = DataShareCliOptions.asNode(parser);

        OptionSpec<File> scanningInputDirOpt = DataShareCliOptions.inputDir(parser);

        OptionSpec<Integer> parsingParallelismOpt = DataShareCliOptions.fileParserParallelism(parser);
        OptionSpecBuilder parsingEnableOcrOpt = DataShareCliOptions.enableOcr(parser);
        OptionSpecBuilder parsingDisableOcrOpt = DataShareCliOptions.disableOcr(parser);
        parser.mutuallyExclusive(parsingEnableOcrOpt, parsingDisableOcrOpt);

        OptionSpec<Pipeline.Type> nlpPipelinesOpt = DataShareCliOptions.nlpPipelines(parser);
        OptionSpec<NlpStage> nlpStagesOpt = DataShareCliOptions.nlpPipelinesStages(parser);
        OptionSpec<NamedEntity.Category> nlpEntitiesOpt = DataShareCliOptions.nlpPipelinesEntities(parser);
        OptionSpec<Integer> nlpParallelismOpt = DataShareCliOptions.nlpPipelinesParallelism(parser);
        OptionSpecBuilder nlpEnableCachingOpt = DataShareCliOptions.nlpPipelinesEnableCaching(parser);
        OptionSpecBuilder nlpDisableCachingOpt = DataShareCliOptions.nlpPipelinesDisableCaching(parser);
        parser.mutuallyExclusive(nlpEnableCachingOpt, nlpDisableCachingOpt);

        OptionSpec<String> indexerAddressOpt = DataShareCliOptions.indexerHost(parser);

        try {
            // Parse arguments w.r.t. options specifications
            OptionSet options = parser.parse(args);

            if (options.has(helpOpt)) {
                printHelp(parser);
                return false;
            }

            stages.addAll(options.valuesOf(stagesOpt));
            stages.sort(DataShare.Stage.comparator);

            // Run as a Cluster Node
            runAsNode = options.has(asNodeOpt);

            // File System Scanning Options
            inputDir = options.valueOf(scanningInputDirOpt).toPath();

            // File Parsing Options
            enableOcr = options.has(parsingEnableOcrOpt);
            fileParserParallelism = options.valueOf(parsingParallelismOpt);

            // Natural Language Processing Options
            nlpPipelineTypes = options.valuesOf(nlpPipelinesOpt);
            nlpPipelineParallelism = options.valueOf(nlpParallelismOpt);
            nlpStages = options.valuesOf(nlpStagesOpt);
            nlpPipelineCaching = !options.has(nlpDisableCachingOpt);
            nlpTargetEntities = options.valuesOf(nlpEntitiesOpt);
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

    private static String environment() {
        List<String> envList = new ArrayList<>();
        System.getenv().forEach((key, value) -> envList.add(key + " = " + value));
        return String.join("\n", envList);
    }

    /**
     * Datashare CLI Main
     *
     * @param args the arguments from command line call
     */
    public static void main(String[] args) throws Exception {
        if (!parseArguments(args)) {
            LOGGER.info("Exiting...");
            System.exit(1);
        }

        LOGGER.info("Stage:             " + stages);
        LOGGER.info("Indexer Address: " + indexAddress);

        Indexer indexer = new ElasticsearchIndexer(new PropertiesProvider(properties));

        if (runAsNode) {
            if (stages.equals(asList(SCANNING, PARSING))) {
                DataShare.Node.parseDirectory(
                        inputDir,
                        fileParserType,
                        fileParserParallelism,
                        enableOcr,
                        indexer,
                        index
                );
            } else if (stages.equals(singletonList(NLP))) {
                DataShare.Node.extractNamedEntities(
                        nlpStages,
                        nlpTargetEntities,
                        nlpPipelineTypes,
                        nlpPipelineParallelism,
                        nlpPipelineCaching,
                        indexer,
                        index
                );
            }
        } else {
            DataShare.StandAlone.processDirectory(
                    inputDir,
                    fileParserType,
                    fileParserParallelism,
                    enableOcr,
                    nlpPipelineTypes,
                    nlpPipelineParallelism,
                    nlpPipelineCaching,
                    nlpStages,
                    nlpTargetEntities,
                    indexer,
                    index
            );
        }
        indexer.close();
    }
}
