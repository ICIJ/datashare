package org.icij.datashare.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import joptsimple.*;

import org.icij.datashare.DataShare;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.extraction.FileParser;
import org.icij.datashare.text.nlp.NlpPipeline;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.indexing.Indexer;
import static org.icij.datashare.text.indexing.Indexer.NodeType.REMOTE;


/**
 * Command Line Interface to Datashare
 *
 * Created by julien on 3/9/16.
 */
public class DataShareCli {

    private static final Logger LOGGER = LogManager.getLogger(DataShareCli.class);

    private static Path                       inputDir;
    private static Path                       outputDir;

    private static FileParser.Type            fileParserType = DataShare.DEFAULT_FILEPARSER_TYPE;
    private static boolean                    enableOcr;

    private static List<NlpPipeline.Type>     nlpPipelineTypes;
    private static List<NlpStage>             nlpStages;
    private static List<NamedEntity.Category> nlpTargetEntities;
    private static boolean                    nlpPipelineCaching;
    private static int                        nlpPipelineParallelism;

    private static Indexer.Type               indexerType     = DataShare.DEFAULT_INDEXER_TYPE;
    private static Indexer.NodeType           indexerNodeType = DataShare.DEFAULT_INDEXER_NODE_TYPE;
    private static String                     index           = DataShare.DEFAULT_INDEX;
    private static List<String>  indexerHostNames;
    private static List<Integer> indexerHostPorts;


    /**
     * Parse command line arguments
     *
     * @param args the array of arguments
     */
    private static boolean parseArguments(String[] args) {
        OptionParser parser = new OptionParser();
        AbstractOptionSpec<Void>         helpOpt               = DataShareCliOptions.help(parser);
        OptionSpec<File>                 inputDirOpt           = DataShareCliOptions.inputDir(parser);
        OptionSpec<File>                 outputDirOpt          = DataShareCliOptions.outputDir(parser);
        OptionSpecBuilder                enableOcrOpt          = DataShareCliOptions.enableOcr(parser);
        OptionSpecBuilder                disableOcrOpt         = DataShareCliOptions.disableOcr(parser);
        OptionSpec<NlpPipeline.Type>     pipelinesOpt          = DataShareCliOptions.pipelines(parser);
        OptionSpec<NlpStage>             stagesOpt             = DataShareCliOptions.pipelinesStages(parser);
        OptionSpec<NamedEntity.Category> entitiesOpt           = DataShareCliOptions.pipelinesEntities(parser);
        OptionSpec<Integer>              parallelismOpt        = DataShareCliOptions.pipelinesParallelism(parser);
        OptionSpecBuilder                enableCachingOpt      = DataShareCliOptions.pipelinesEnableCaching(parser);
        OptionSpecBuilder                disableCachingOpt     = DataShareCliOptions.pipelinesDisableCaching(parser);
        OptionSpec<Indexer.NodeType>     indexNodeTypeOpt      = DataShareCliOptions.indexerNodeType(parser);
        OptionSpec<String>               indexNodeHostnamesOpt = DataShareCliOptions.indexerHostsNames(parser);
        OptionSpec<Integer>              indexNodeHostportsOpt = DataShareCliOptions.indexerHostsPorts(parser);
        parser.mutuallyExclusive(enableCachingOpt, disableCachingOpt);
        parser.mutuallyExclusive(enableOcrOpt, disableOcrOpt);

        try {
            // Parse arguments w.r.t. options specifications
            OptionSet options = parser.parse( args );
            if (options.has(helpOpt)) {
                printHelp(parser);
                return false;
            }
            inputDir               = options.valueOf(inputDirOpt).toPath();
            outputDir              = options.valueOf(outputDirOpt).toPath();
            enableOcr              = options.has(enableOcrOpt);
            nlpPipelineTypes       = options.valuesOf(pipelinesOpt);
            nlpPipelineParallelism = options.valueOf(parallelismOpt);
            nlpStages              = options.valuesOf(stagesOpt);
            nlpPipelineCaching     = !options.has(disableCachingOpt);
            nlpTargetEntities      = options.valuesOf(entitiesOpt);
            indexerNodeType        = options.valueOf(indexNodeTypeOpt);
            indexerHostNames       = options.valuesOf(indexNodeHostnamesOpt);
            indexerHostPorts       = options.valuesOf(indexNodeHostportsOpt);
            if (indexerHostNames.size() != indexerHostPorts.size()) {
                LOGGER.error("Number of index hosts names and hosts ports mismatch.");
                printHelp(parser);
                return false;
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to parse arguments.", e);
            printHelp(parser);
            return false;
        }
    }

    private static void printHelp(OptionParser parser) {
        try {
            System.out.println("Usage: ");
            parser.printHelpOn( System.out );
        } catch (IOException e) {
            LOGGER.debug("Failed to print help message", e);
        }
    }


    /**
     * Datashare CLI Main
     *
     * @param args the arguments from command line call
     */
    public static void main(String[] args) {
        if( ! parseArguments(args)) {
            System.exit(1);
        }

        LOGGER.info(indexerType);
        LOGGER.info(indexerNodeType);
        LOGGER.info(indexerHostNames);
        LOGGER.info(indexerHostPorts);

        Properties indexerProperties = Indexer.Property.build
                .apply(indexerNodeType)
                .apply(indexerHostNames)
                .apply(indexerHostPorts);

        Indexer.create(indexerType, indexerProperties).ifPresent( indexer -> {

            DataShare.processDirectory(
                    inputDir,
                    fileParserType,
                    enableOcr,
                    nlpStages,
                    nlpTargetEntities,
                    nlpPipelineTypes,
                    nlpPipelineParallelism,
                    nlpPipelineCaching,
                    indexer,
                    index
            );
            indexer.close();

        });


    }


}
