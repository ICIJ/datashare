package org.icij.datashare.cli;

import joptsimple.AbstractOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSpec;
import joptsimple.OptionSpecBuilder;
import org.icij.datashare.Mode;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.extract.ScannerVisitor;

import java.io.File;
import java.util.Arrays;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.icij.datashare.text.nlp.NlpApp.NLP_PARALLELISM_OPT;


final class DatashareCliOptions {
    static final char ARG_VALS_SEP = ',';
    private static final Integer DEFAULT_PARSER_PARALLELISM = 1;
    private static final Integer DEFAULT_NLP_PARALLELISM = 1;
    private static final Integer DEFAULT_PARALLELISM =
            Runtime.getRuntime().availableProcessors() == 1 ? 2 : Runtime.getRuntime().availableProcessors();
    static final String STAGES_OPT = "stages";
    static final String DATA_DIR_OPT = "dataDir";
    static final String NLP_PIPELINES_OPT = "nlpPipelines";
    static final String MESSAGE_BUS_OPT = "messageBusAddress";
    static final String NO_WEB_SERVER_OPT = "noweb";
    static final String RESUME_OPT = "resume";
    public static final String PARALLELISM = "parallelism";

    static OptionSpec<DatashareCli.Stage> stages(OptionParser parser) {
        return parser.acceptsAll(
                asList(STAGES_OPT, "s"),
                "Stages to be run.")
                .withRequiredArg()
                .ofType( DatashareCli.Stage.class )
                .withValuesSeparatedBy(ARG_VALS_SEP)
                .defaultsTo(DatashareCli.Stage.values());
    }

    static OptionSpec<Mode> mode(OptionParser parser) {
        return parser.acceptsAll(
                asList("mode", "m"),
                "Datashare run mode " + Arrays.toString(Mode.values()))
                .withRequiredArg()
                .ofType( Mode.class )
                .defaultsTo(Mode.LOCAL);
    }

    static OptionSpecBuilder noweb(OptionParser parser) {
        return parser.acceptsAll(singletonList(NO_WEB_SERVER_OPT), "Run as a cli app (default is web server)");
    }

    static OptionSpecBuilder followSymlinks(OptionParser parser) {
        return parser.acceptsAll(singletonList(ScannerVisitor.FOLLOW_SYMLINKS), "Follow symlinks (default false)");
    }

    static OptionSpec<String> cors(OptionParser parser) {
        return parser.acceptsAll(
                singletonList("cors"), "CORS headers (needs the web option)")
                        .withRequiredArg()
                        .ofType(String.class)
                        .defaultsTo("no-cors");
    }

    static OptionSpecBuilder resume(OptionParser parser) {
        return parser.acceptsAll(asList(RESUME_OPT, "r"), "Resume pending operations");
    }

    static OptionSpec<File> dataDir(OptionParser parser) {
        return parser.acceptsAll(
                asList(DATA_DIR_OPT, "d"),
                "Source files directory. WARN this directory must end with \"data\"" )
                .withRequiredArg()
                .ofType( File.class )
                .defaultsTo(new File("/home/datashare/data"));
    }

    static OptionSpec<String> messageBusAddress(OptionParser parser) {
        return parser.acceptsAll(
                singletonList(MESSAGE_BUS_OPT),
                "Message bus address")
                .withRequiredArg()
                .defaultsTo("redis");
    }

    static OptionSpec<String> redisAddress(OptionParser parser) {
            return parser.acceptsAll(
                    singletonList("redisAddress"),
                    "Redis queue address")
                    .withRequiredArg()
                    .defaultsTo("redis://redis:6379");
        }

    static OptionSpec<Integer> fileParserParallelism(OptionParser parser) {
        return parser.acceptsAll(
                asList("pp", "parserParallelism"),
                "Number of file parser threads.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_PARSER_PARALLELISM);
    }

    static OptionSpec<Integer> nlpParallelism(OptionParser parser) {
        return parser.acceptsAll(
                asList("np", NLP_PARALLELISM_OPT),
                "Number of NLP extraction threads per pipeline.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_NLP_PARALLELISM);
    }

    static OptionSpecBuilder enableOcr(OptionParser parser) {
        return parser.acceptsAll(
                asList("enableOcr", "o"),
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

    static OptionSpec<Integer> parallelism(OptionParser parser) {
        return parser.acceptsAll(
                asList("p", PARALLELISM),
                "Number of threads allocated for task management.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_PARALLELISM);
    }

    static OptionSpec<String> esHost(OptionParser parser) {
        return parser.acceptsAll(
                asList("elasticsearchAddress"), "Elasticsearch host address")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("http://elasticsearch:9200");
    }

    static OptionSpec<String> indexName(OptionParser parser) {
        return parser.acceptsAll(
                asList("indexName", "n"), "Index name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("local-datashare");
    }

    static OptionSpec<String> clusterName(OptionParser parser) {
        return parser.acceptsAll(
                asList("clusterName", "c"), "Cluster name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("datashare");
    }

    static OptionSpec<String> queueName(OptionParser parser) {
        return parser.acceptsAll(
                singletonList("queueName"), "Redis queue name (default extract:queue)")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("extract:queue");
    }

    static AbstractOptionSpec<Void> help(OptionParser parser) {
        return parser.acceptsAll(asList("help", "h", "?")).forHelp();
    }

    static OptionSpec<String> authFilter(OptionParser parser) {
        return parser.acceptsAll(
                asList("authFilter"), "Server mode auth filter class")
                .withRequiredArg()
                .ofType(String.class);
    }

    static OptionSpec<String> oauthSecret(OptionParser parser) {
        return parser.acceptsAll(
                asList("oauthClientSecret"), "OAuth2 client secret key")
                .withRequiredArg()
                .ofType(String.class);
    }
    static OptionSpec<String> oauthClient(OptionParser parser) {
        return parser.acceptsAll(
                asList("oauthClientId"), "OAuth2 client id")
                .withRequiredArg()
                .ofType(String.class);
    }
    static OptionSpec<String> oauthAuthorizeUrl(OptionParser parser) {
        return parser.acceptsAll(
                asList("oauthAuthorizeUrl"), "OAuth2 authorize url")
                .withRequiredArg()
                .ofType(String.class);
    }
    static OptionSpec<String> oauthTokenUrl(OptionParser parser) {
        return parser.acceptsAll(
                asList("oauthTokenUrl"), "OAuth2 token url")
                .withRequiredArg()
                .ofType(String.class);
    }
    static OptionSpec<String> oauthApiUrl(OptionParser parser) {
        return parser.acceptsAll(
                asList("oauthApiUrl"), "OAuth2 api url")
                .withRequiredArg()
                .ofType(String.class);
    }
}
