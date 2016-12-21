package org.icij.datashare.cli;

import java.io.File;
import static java.util.Arrays.asList;

import joptsimple.AbstractOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSpec;
import joptsimple.OptionSpecBuilder;

import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.NlpPipeline;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.NamedEntity;
import static org.icij.datashare.DataShare.*;
import static org.icij.datashare.text.indexing.Indexer.NodeType.LOCAL;


/**
 * {@link DataShareCli} options specification
 *
 * Created by julien on 10/10/16.
 */
public class DataShareCliOptions {

    private static final char ARG_VALS_SEP = ',';

    private static final File TEMP_DIR     = new File(System.getProperty("java.io.tmpdir"));


    static OptionSpec<File> inputDir(OptionParser parser) {
        return parser.acceptsAll(
                asList( "input-dir", "i"),
                "Source documents directory." )
                .withRequiredArg()
                .ofType( File.class )
                .required();
    }

    static OptionSpec<File> outputDir(OptionParser parser) {
        return parser.acceptsAll(
                asList("output-dir", "o"),
                "Result files directory.")
                .withRequiredArg()
                .ofType( File.class )
                .defaultsTo(TEMP_DIR);
    }

    static OptionSpecBuilder enableOcr(OptionParser parser) {
        return parser.acceptsAll(
                asList("enable-ocr", "ocr"),
                "Run optical character recognition on documents. Tesseract must be installed beforehand." );
    }

    static OptionSpecBuilder disableOcr(OptionParser parser) {
        return parser.acceptsAll(
                asList("disable-ocr", "nocr"),
                "Prevent optical character recognition from running on documents. (Tesseract must be installed otherwise)" );
    }


    static OptionSpec<NlpPipeline.Type> pipelines(OptionParser parser) {
        return parser.acceptsAll(
                asList("pipelines", "p"),
                "NLP pipelines to be run.")
                .withRequiredArg()
                .ofType( NlpPipeline.Type.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_NLP_PIPELINES.toArray(new NlpPipeline.Type[DEFAULT_NLP_PIPELINES.size()])
                );
    }

    static OptionSpec<Integer> pipelinesParallelism(OptionParser parser) {
        return parser.acceptsAll(
                asList("parallelism", "t"),
                "Number of threads per specified pipeline.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_NLP_PARALLELISM);
    }

    static OptionSpec<NlpStage> pipelinesStages(OptionParser parser) {
        return parser.acceptsAll(
                asList("stages", "s"),
                "Targeted NLP stages.")
                .withRequiredArg()
                .ofType( NlpStage.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_NLP_STAGES.toArray(new NlpStage[DEFAULT_NLP_STAGES.size()]));
    }

    static OptionSpec<NamedEntity.Category> pipelinesEntities(OptionParser parser) {
        return parser.acceptsAll(
                asList("entities", "e"),
                "Targeted named entity categories.")
                .withRequiredArg()
                .ofType( NamedEntity.Category.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_NLP_ENTITIES.toArray(new NamedEntity.Category[DEFAULT_NLP_ENTITIES.size()]));
    }

    static OptionSpecBuilder pipelinesEnableCaching(OptionParser parser) {
        return parser.acceptsAll(
                asList("enable-caching", "c"),
                "Keep pipelines' models and annotators in memory. The default.");
    }

    static OptionSpecBuilder pipelinesDisableCaching(OptionParser parser) {
        return parser.acceptsAll(
                asList("disable-caching", "nc"),
                "Don't keep pipelines' models and annotators in memory.");
    }


    static OptionSpec<Indexer.NodeType> indexerNodeType(OptionParser parser) {
        return parser.acceptsAll(
                asList("index-nodetype", "inodetype"),
                "LOCAL or REMOTE connection to indexing node(s)")
                .withRequiredArg()
                .ofType(Indexer.NodeType.class)
                .defaultsTo(LOCAL);
    }

    static OptionSpec<String> indexerHostsNames(OptionParser parser) {
        return parser.acceptsAll(
                asList("index-hostnames", "ihosts"),
                "Indexing nodes hostnames to connect to.")
                .withRequiredArg()
                .ofType( String.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_INDEXER_NODE_HOSTS.toArray(new String[DEFAULT_INDEXER_NODE_HOSTS.size()]));
    }

    static OptionSpec<Integer> indexerHostsPorts(OptionParser parser) {
        return parser.acceptsAll(
                asList("index-hostports", "iports"),
                String.join( "\n",
                        "Indexing nodes ports to connect to (eg 9300).",
                        "One port per host in --index-server-names."))
                .withRequiredArg()
                .ofType( Integer.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_INDEXER_NODE_PORTS.toArray(new Integer[DEFAULT_INDEXER_NODE_PORTS.size()]));
    }

    static AbstractOptionSpec<Void> help(OptionParser parser) {
        return parser.acceptsAll(asList("help", "h", "?")).forHelp();
    }

}
