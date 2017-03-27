package org.icij.datashare.cli;

import java.io.File;
import static java.util.Arrays.asList;

import joptsimple.OptionParser;
import joptsimple.OptionSpec;
import joptsimple.OptionSpecBuilder;
import joptsimple.AbstractOptionSpec;

import org.icij.datashare.DataShare;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.NlpPipeline;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.NamedEntity;
import static org.icij.datashare.DataShare.*;


/**
 * {@link DataShareCli} options specification
 *
 * Created by julien on 10/10/16.
 */
final class DataShareCliOptions {

    private static final char ARG_VALS_SEP = ',';

    static OptionSpec<DataShare.Stage> stages(OptionParser parser) {
        return parser.acceptsAll(
                asList("stages", "s"),
                "Stages to be run.")
                .withRequiredArg()
                .ofType( DataShare.Stage.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_STAGES.toArray(new DataShare.Stage[DEFAULT_STAGES.size()]));
    }

    static OptionSpecBuilder asNode(OptionParser parser) {
        return parser.acceptsAll(
                asList("node", "n"),
                "Run as a cluster node.");
    }


    static OptionSpec<File> inputDir(OptionParser parser) {
        return parser.acceptsAll(
                asList( "input-dir", "i"),
                "Source files directory." )
                .withRequiredArg()
                .ofType( File.class )
                .defaultsTo(new File("."));
    }

    static OptionSpec<Integer> fileParserParallelism(OptionParser parser) {
        return parser.acceptsAll(
                asList("parsing-parallelism", "parsing-t"),
                "Number of file parser threads.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_PARSER_PARALLELISM);
    }

    static OptionSpecBuilder enableOcr(OptionParser parser) {
        return parser.acceptsAll(
                asList("enable-ocr", "ocr"),
                "Run optical character recognition at file parsing time. " +
                        "(Tesseract must be installed beforehand).");
    }

    static OptionSpecBuilder disableOcr(OptionParser parser) {
        return parser.acceptsAll(
                asList("disable-ocr", "nocr"),
                "Prevent optical character recognition from being run at file parsing time. " +
                        "(Tesseract must be installed otherwise).");
    }

    static OptionSpec<NlpPipeline.Type> nlpPipelines(OptionParser parser) {
        return parser.acceptsAll(
                asList("nlp-pipelines", "nlpp"),
                "NLP pipelines to be run.")
                .withRequiredArg()
                .ofType( NlpPipeline.Type.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_NLP_PIPELINES.toArray(new NlpPipeline.Type[DEFAULT_NLP_PIPELINES.size()]));
    }

    static OptionSpec<Integer> nlpPipelinesParallelism(OptionParser parser) {
        return parser.acceptsAll(
                asList("nlp-parallelism", "nlpt"),
                "Number of threads per specified pipeline.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_NLP_PARALLELISM);
    }

    static OptionSpec<NlpStage> nlpPipelinesStages(OptionParser parser) {
        return parser.acceptsAll(
                asList("nlp-stages", "nlps"),
                "Targeted natural language processing stages.")
                .withRequiredArg()
                .ofType( NlpStage.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_NLP_STAGES.toArray(new NlpStage[DEFAULT_NLP_STAGES.size()]));
    }

    static OptionSpec<NamedEntity.Category> nlpPipelinesEntities(OptionParser parser) {
        return parser.acceptsAll(
                asList("entities", "e"),
                "Targeted named entity categories.")
                .withRequiredArg()
                .ofType( NamedEntity.Category.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_NLP_ENTITIES.toArray(new NamedEntity.Category[DEFAULT_NLP_ENTITIES.size()]));
    }

    static OptionSpecBuilder nlpPipelinesEnableCaching(OptionParser parser) {
        return parser.acceptsAll(
                asList("caching", "cach"),
                "Keep nlpPipelines' models and annotators in memory. The default.");
    }

    static OptionSpecBuilder nlpPipelinesDisableCaching(OptionParser parser) {
        return parser.acceptsAll(
                asList("no-caching", "nocach"),
                "Don't keep nlpPipelines' models and annotators in memory.");
    }

    static OptionSpec<Indexer.NodeType> indexerNodeType(OptionParser parser) {
        return parser.acceptsAll(
                asList("index-nodetype", "idxtype"),
                "LOCAL or REMOTE connection to indexing node(s)")
                .withRequiredArg()
                .ofType( Indexer.NodeType.class )
                .defaultsTo(DataShare.DEFAULT_INDEXER_NODE_TYPE);
    }

    static OptionSpec<String> indexerHostNames(OptionParser parser, OptionSpec idxNodeType) {
        return parser.acceptsAll(
                asList("index-hostnames", "idxhosts"),
                "Indexing nodes hostnames to connect to.")
                .requiredIf(idxNodeType)
                .withRequiredArg()
                .ofType( String.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_INDEXER_NODE_HOSTS.toArray(new String[DEFAULT_INDEXER_NODE_HOSTS.size()]));
    }

    static OptionSpec<Integer> indexerHostPorts(OptionParser parser, OptionSpec idxNodeType) {
        return parser.acceptsAll(
                asList("index-hostports", "idxports"),
                String.join( "\n",
                        "Indexing nodes ports to connect to (eg 9300).",
                        "One port per host defined in --index-hostnames."))
                .requiredIf(idxNodeType)
                .withRequiredArg()
                .ofType( Integer.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_INDEXER_NODE_PORTS.toArray(new Integer[DEFAULT_INDEXER_NODE_PORTS.size()]));
    }

    static AbstractOptionSpec<Void> help(OptionParser parser) {
        return parser.acceptsAll(asList("help", "h", "?")).forHelp();
    }

}
