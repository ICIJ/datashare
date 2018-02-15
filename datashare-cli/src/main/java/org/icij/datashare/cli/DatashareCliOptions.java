package org.icij.datashare.cli;

import joptsimple.*;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.File;

import static java.util.Arrays.asList;


final class DatashareCliOptions {
    static final char ARG_VALS_SEP = ',';
    private static final Integer DEFAULT_PARSER_PARALLELISM = 1;
    private static final Integer DEFAULT_NLP_PARALLELISM = 1;
    static final String STAGES_OPT = "stages";
    static final String SCANNING_INPUT_DIR_OPT = "inputDir";
    static final String NLP_PIPELINES_OPT = "nlpPipelines";
    static final String MESSAGE_BUS_OPT = "messageBusAddress";

    static OptionSpec<DatashareCli.Stage> stages(OptionParser parser) {
        return parser.acceptsAll(
                asList(STAGES_OPT, "s"),
                "Stages to be run.")
                .withRequiredArg()
                .ofType( DatashareCli.Stage.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DatashareCli.Stage.values());
    }

    static ArgumentAcceptingOptionSpec<Boolean> web(OptionParser parser) {
        return parser.acceptsAll(asList("web", "w"), "Run as a web server").
                withRequiredArg().ofType(Boolean.class).defaultsTo(false);
    }

    static OptionSpec<File> inputDir(OptionParser parser) {
        return parser.acceptsAll(
                asList(SCANNING_INPUT_DIR_OPT, "i"),
                "Source files directory." )
                .withRequiredArg()
                .ofType( File.class )
                .defaultsTo(new File("/home/datashare/data"));
    }

    static OptionSpec<String> messageBusAddress(OptionParser parser) {
        return parser.acceptsAll(
                asList(MESSAGE_BUS_OPT, "m"),
                "Message bus address")
                .withRequiredArg()
                .defaultsTo("redis");
    }

    static OptionSpec<String> redisAddress(OptionParser parser) {
            return parser.acceptsAll(
                    asList("redisAddress"),
                    "Redis queue address")
                    .withRequiredArg()
                    .defaultsTo("redis://redis:6379");
        }

    static OptionSpec<Integer> fileParserParallelism(OptionParser parser) {
        return parser.acceptsAll(
                asList("parallelism", "p"),
                "Number of file parser threads.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_PARSER_PARALLELISM);
    }

    static OptionSpecBuilder enableOcr(OptionParser parser) {
        return parser.acceptsAll(
                asList("parsingOcr", "ocr"),
                "Run optical character recognition at file parsing time. " +
                        "(Tesseract must be installed beforehand).");
    }

    static OptionSpec<Pipeline.Type> nlpPipelines(OptionParser parser) {
        return parser.acceptsAll(
                asList(NLP_PIPELINES_OPT, "nlpp"),
                "NLP pipelines to be run.")
                .withRequiredArg()
                .ofType( Pipeline.Type.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(Pipeline.Type.values());
    }

    static OptionSpec<Integer> nlpPipelinesParallelism(OptionParser parser) {
        return parser.acceptsAll(
                asList("nlpParallelism", "l"),
                "Number of threads per specified pipeline.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_NLP_PARALLELISM);
    }

    static OptionSpec<String> indexerHost(OptionParser parser) {
        return parser.acceptsAll(
                asList("indexAddress"), "Indexing address")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("elasticsearch:9300");
    }

    static OptionSpec<String> indexName(OptionParser parser) {
        return parser.acceptsAll(
                asList("indexName", "n"), "Index name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("datashare-local");
    }

    static OptionSpec<String> clusterName(OptionParser parser) {
        return parser.acceptsAll(
                asList("clusterName", "c"), "Cluster name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("datashare");
    }

    static AbstractOptionSpec<Void> help(OptionParser parser) {
        return parser.acceptsAll(asList("help", "h", "?")).forHelp();
    }
}
