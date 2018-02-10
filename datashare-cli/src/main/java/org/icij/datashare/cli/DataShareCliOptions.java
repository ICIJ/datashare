package org.icij.datashare.cli;

import joptsimple.*;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.File;

import static java.util.Arrays.asList;


final class DataShareCliOptions {
    static final char ARG_VALS_SEP = ',';
    private static final Integer DEFAULT_PARSER_PARALLELISM = 1;
    private static final Integer DEFAULT_NLP_PARALLELISM = 1;
    static final String STAGES_OPT = "stages";
    static final String SCANNING_INPUT_DIR_OPT = "scanning-input-dir";
    static final String NLP_PIPELINES_OPT = "nlp-pipelines";

    static OptionSpec<DataShareCli.Stage> stages(OptionParser parser) {
        return parser.acceptsAll(
                asList(STAGES_OPT, "s"),
                "Stages to be run.")
                .withRequiredArg()
                .ofType( DataShareCli.Stage.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DataShareCli.Stage.values());
    }

    static ArgumentAcceptingOptionSpec<Boolean> web(OptionParser parser) {
        return parser.acceptsAll(asList("web", "w"), "Run as a web server").
                withRequiredArg().ofType(boolean.class).defaultsTo(false);
    }

    static OptionSpec<File> inputDir(OptionParser parser) {
        return parser.acceptsAll(
                asList(SCANNING_INPUT_DIR_OPT, "i"),
                "Source files directory." )
                .withRequiredArg()
                .ofType( File.class )
                .defaultsTo(new File("."));
    }

    static OptionSpec<Integer> fileParserParallelism(OptionParser parser) {
        return parser.acceptsAll(
                asList("parsing-parallelism", "parsingt"),
                "Number of file parser threads.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_PARSER_PARALLELISM);
    }

    static OptionSpecBuilder enableOcr(OptionParser parser) {
        return parser.acceptsAll(
                asList("parsing-ocr", "ocr"),
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
                asList("nlp-parallelism", "nlpt"),
                "Number of threads per specified pipeline.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_NLP_PARALLELISM);
    }

    static OptionSpec<String> indexerHost(OptionParser parser) {
        return parser.acceptsAll(
                asList("index-address", "indexAddress"),
                String.join( "\n",
                        "Indexing address (default elasticsearch:9300)."))
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("elasticsearch:9300");
    }

    static AbstractOptionSpec<Void> help(OptionParser parser) {
        return parser.acceptsAll(asList("help", "h", "?")).forHelp();
    }

}
