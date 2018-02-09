package org.icij.datashare.cli;

import joptsimple.AbstractOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSpec;
import joptsimple.OptionSpecBuilder;
import org.icij.datashare.DataShare;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.File;

import static java.util.Arrays.asList;
import static org.icij.datashare.DataShare.*;


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

    static OptionSpec<Boolean> web(OptionParser parser) {
        return parser.acceptsAll(
                asList("web", "w"),
                "Run as a web server")
                .withRequiredArg()
                .ofType( Boolean.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(false);
    }

    static OptionSpec<File> inputDir(OptionParser parser) {
        return parser.acceptsAll(
                asList( "scanning-input-dir", "i"),
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

    static OptionSpecBuilder disableOcr(OptionParser parser) {
        return parser.acceptsAll(
                asList("parsing-no-ocr", "nocr"),
                "Prevent optical character recognition from being run at file parsing time. " +
                        "(Tesseract must be installed otherwise).");
    }

    static OptionSpec<Pipeline.Type> nlpPipelines(OptionParser parser) {
        return parser.acceptsAll(
                asList("nlp-pipelines", "nlpp"),
                "NLP pipelines to be run.")
                .withRequiredArg()
                .ofType( Pipeline.Type.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DEFAULT_NLP_PIPELINES.toArray(new Pipeline.Type[DEFAULT_NLP_PIPELINES.size()]));
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
