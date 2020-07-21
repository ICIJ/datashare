package org.icij.datashare.cli;

import joptsimple.AbstractOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSpec;
import joptsimple.OptionSpecBuilder;

import java.io.File;
import java.util.Arrays;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;


public final class DatashareCliOptions {
    public static final char ARG_VALS_SEP = ',';
    private static final Integer DEFAULT_PARSER_PARALLELISM = 1;
    private static final Integer DEFAULT_NLP_PARALLELISM = 1;
    private static final Integer DEFAULT_PARALLELISM =
            Runtime.getRuntime().availableProcessors() == 1 ? 2 : Runtime.getRuntime().availableProcessors();
    public static final String STAGES_OPT = "stages";
    public static final String DATA_DIR_OPT = "dataDir";
    public static final String NLP_PIPELINES_OPT = "nlpPipelines";
    public static final String BATCH_SEARCH_THROTTLE = "batchSearchThrottleMilliseconds";
    public static final String BATCH_SEARCH_MAX_TIME = "batchSearchMaxTimeSeconds";
    public static final String SCROLL_SIZE = "scrollSize";

    static final String MESSAGE_BUS_OPT = "messageBusAddress";
    static final String ROOT_HOST = "rootHost";
    public static final String RESUME_OPT = "resume";
    public static final String GET_API_KEY_OPT = "apiKey";
    public static final String CRE_API_KEY_OPT = "createApiKey";
    public static final String PLUGIN_LIST_OPT = "pluginList";
    public static final String DEL_API_KEY_OPT = "deleteApiKey";
    public static final String PARALLELISM = "parallelism";
    public static final String OPEN_LINK = "browserOpenLink";
    public static final String NLP_PARALLELISM_OPT = "nlpParallelism";
    public static final String DEFAULT_USER_NAME = "defaultUserName";

    static OptionSpec<String> stages(OptionParser parser) {
        return parser.acceptsAll(
                asList(STAGES_OPT),
                "Stages to be run. WARN that DEDUPLICATE stages are not streamable like the others. They should be run alone.")
                .withRequiredArg()
                .ofType( String.class );
    }

    static OptionSpec<Mode> mode(OptionParser parser) {
        return parser.acceptsAll(
                asList("mode", "m"),
                "Datashare run mode " + Arrays.toString(Mode.values()))
                .withRequiredArg()
                .ofType( Mode.class )
                .defaultsTo(Mode.LOCAL);
    }

    static OptionSpec<String> defaultUser(OptionParser parser) {
        return parser.acceptsAll(
                asList("u", DEFAULT_USER_NAME),
                "Default local user name")
                .withRequiredArg()
                .ofType( String.class )
                .defaultsTo("local");
    }

    static OptionSpecBuilder followSymlinks(OptionParser parser) {
        return parser.acceptsAll(singletonList("followSymlinks"), "Follow symlinks (default false)");
    }

    static OptionSpec<String> cors(OptionParser parser) {
        return parser.acceptsAll(
                singletonList("cors"), "CORS headers (needs the web option)")
                        .withRequiredArg()
                        .ofType(String.class)
                        .defaultsTo("no-cors");
    }

    static OptionSpec<String> settings (OptionParser parser) {
        return parser.acceptsAll(
                asList("s", "settings"), "Property settings file")
                        .withRequiredArg()
                        .ofType(String.class);
    }

    static OptionSpec<String> pluginsDir(OptionParser parser) {
        return parser.acceptsAll(
                asList("pluginsDir"), "Plugins directory")
                        .withRequiredArg()
                        .ofType(String.class);
    }

    static OptionSpec<String> pluginList(OptionParser parser) {
        return parser.acceptsAll(asList(PLUGIN_LIST_OPT), "Plugins list matching provided string")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(".*");
    }

    public static OptionSpec<String> extensionsDir(OptionParser parser) {
        return parser.acceptsAll(
                        asList("extensionsDir"), "Extensions directory (backend)")
                                .withRequiredArg()
                                .ofType(String.class);
    }

    static OptionSpec<Integer> tcpListenPort(OptionParser parser) {
        return parser.acceptsAll(
                singletonList("tcpListenPort"), "Port used by the HTTP server")
                        .withRequiredArg()
                        .ofType(Integer.class)
                        .defaultsTo(8080);
    }

    static OptionSpec<Integer> sessionTtlSeconds(OptionParser parser) {
        return parser.acceptsAll(
                singletonList("sessionTtlSeconds"), "Time to live for a HTTP session in seconds")
                        .withRequiredArg()
                        .ofType(Integer.class)
                        .defaultsTo(43200);
    }

    static OptionSpec<String> protectedUriPrefix(OptionParser parser) {
        return parser.acceptsAll(
                singletonList("protectedUriPrefix"), "Protected URI prefix")
                        .withRequiredArg()
                        .ofType(String.class)
                        .defaultsTo("/api/");
    }

    static OptionSpecBuilder resume(OptionParser parser) {
        return parser.acceptsAll(asList(RESUME_OPT, "r"), "Resume pending operations");
    }

    static OptionSpec<String> genApiKey(OptionParser parser) {
        return parser.acceptsAll(asList(CRE_API_KEY_OPT, "k"), "Generate and store api key for user defaultUser (see opt)")
                .withRequiredArg()
                .ofType(String.class);
    }

    static OptionSpec<String> getApiKey(OptionParser parser) {
        return parser.acceptsAll(asList(GET_API_KEY_OPT), "Return existing api key for user")
                .withRequiredArg()
                .ofType(String.class);
    }

    static OptionSpec<String> delApiKey(OptionParser parser) {
        return parser.acceptsAll(singletonList(DEL_API_KEY_OPT), "Delete api key for user")
                .withRequiredArg()
                .ofType(String.class);
    }

    static OptionSpec<File> dataDir(OptionParser parser) {
        return parser.acceptsAll(
                asList(DATA_DIR_OPT, "d"),
                "Document source files directory" )
                .withRequiredArg()
                .ofType( File.class )
                .defaultsTo(new File("/home/datashare/data"));
    }

    static OptionSpec<String> rootHost(OptionParser parser) {
            return parser.acceptsAll(
                    singletonList(ROOT_HOST),
                    "Datashare host for urls")
                    .withRequiredArg();
        }

    static OptionSpec<String> messageBusAddress(OptionParser parser) {
        return parser.acceptsAll(
                singletonList(MESSAGE_BUS_OPT),
                "Message bus address")
                .withRequiredArg()
                .defaultsTo("redis");
    }

    public static OptionSpec<String> busType(OptionParser parser) {
        return parser.acceptsAll(
                singletonList("busType"),
                "Backend data bus type. Values can be \"memory\" or \"redis\"")
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

    static OptionSpec<String> queueType(OptionParser parser) {
            return parser.acceptsAll(
                    singletonList("queueType"),
                    "Backend queues and sets type. Values can be \"memory\" or \"redis\"")
                    .withRequiredArg()
                    .defaultsTo("redis");
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

    public static OptionSpec<Integer> batchSearchMaxTime(OptionParser parser) {
         return parser.acceptsAll(
                         asList(BATCH_SEARCH_MAX_TIME), "Max time for batch search in seconds")
                         .withRequiredArg()
                         .ofType(Integer.class);
    }

    public static OptionSpec<Integer> batchSearchThrottle(OptionParser parser) {
         return parser.acceptsAll(
                         asList(BATCH_SEARCH_THROTTLE), "Throttle for batch search in milliseconds")
                         .withRequiredArg()
                         .ofType(Integer.class);
    }

    public static OptionSpec<Integer> scrollSize(OptionParser parser) {
        return parser.acceptsAll(
                asList("scrollSize"), "Scroll size used for elasticsearch scrolls (SCANIDX task)")
                .withRequiredArg()
                .ofType(Integer.class).defaultsTo(1000);
    }

     public static OptionSpec<Integer> scrollSlices(OptionParser parser) {
        return parser.acceptsAll(
                asList("scrollSlices"), "Scroll slice max number used for elasticsearch scrolls (SCANIDX task)")
                .withRequiredArg()
                .ofType(Integer.class).defaultsTo(1);
    }

     public static OptionSpec<Integer> redisPoolSize(OptionParser parser) {
        return parser.acceptsAll(
                asList("redisPoolSize"), "Redis pool size used for each redis collection")
                .withRequiredArg()
                .ofType(Integer.class).defaultsTo(1);
    }

    public static OptionSpec<String> elasticsearchDataPath(OptionParser parser) {
        return parser.acceptsAll(
                asList("elasticsearchDataPath"), "Data path used for embedded Elasticsearch")
                .withRequiredArg()
                .ofType(String.class).defaultsTo("/home/datashare/es");
    }

    public static OptionSpec<String> filterSet(OptionParser parser) {
        return parser.acceptsAll(
                asList("filterSet"), "name of the set for queue filtering")
                .withRequiredArg()
                .ofType(String.class).defaultsTo("extract:filter");
    }

    public static OptionSpec<String> reportName(OptionParser parser) {
        return parser.acceptsAll(
                asList("reportName"), "name of the map for the report map (where index results are stored). " +
                        "No report records are saved if not provided")
                .withRequiredArg()
                .ofType(String.class);
    }

    static OptionSpec<Boolean> enableBrowserOpenLink(OptionParser parser) {
        return parser.acceptsAll(
                singletonList(OPEN_LINK),
                "try to open link in the default browser").
                withRequiredArg().ofType(Boolean.class).defaultsTo(false);
    }

    static OptionSpec<Boolean> enableOcr(OptionParser parser) {
        return parser.acceptsAll(
                asList("ocr", "o"),
                "Run optical character recognition at file parsing time. " +
                        "(Tesseract must be installed beforehand).").
                withRequiredArg().ofType(Boolean.class).defaultsTo(true);
    }

    static OptionSpec<String> nlpPipelines(OptionParser parser) {
        return parser.acceptsAll(
                asList(NLP_PIPELINES_OPT, "nlpp"),
                "NLP pipelines to be run.")
                .withRequiredArg()
                .withValuesSeparatedBy(ARG_VALS_SEP);
    }

    static OptionSpec<Integer> parallelism(OptionParser parser) {
        return parser.acceptsAll(
                asList(PARALLELISM),
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

    static OptionSpec<String> dataSourceUrl(OptionParser parser) {
        return parser.acceptsAll(
                asList("dataSourceUrl"), "Datasource URL")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
    }

    static OptionSpec<String> defaultProject(OptionParser parser) {
        return parser.acceptsAll(
                asList("defaultProject", "p"), "Default project name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("local-datashare");
    }

    static OptionSpec<String> clusterName(OptionParser parser) {
        return parser.acceptsAll(
                singletonList("clusterName"), "Cluster name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("datashare");
    }

    static OptionSpec<String> queueName(OptionParser parser) {
        return parser.acceptsAll(
                singletonList("queueName"), "Redis queue name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("extract:queue");
    }

    static AbstractOptionSpec<Void> help(OptionParser parser) {
        return parser.acceptsAll(asList("help", "h", "?")).forHelp();
    }

    static AbstractOptionSpec<Void> version(OptionParser parser) {
        return parser.acceptsAll(asList("version", "v"));
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
    static OptionSpec<String> oauthCallbackPath(OptionParser parser) {
        return parser.acceptsAll(
                asList("oauthCallbackPath"), "OAuth2 callback path (in datashare)")
                .withRequiredArg()
                .ofType(String.class);
    }

    public static OptionSpec<String> batchSearchQueueType(OptionParser parser) {
        return parser.acceptsAll(
                        asList("batchQueueType"), "Queue class for batch search queue")
                        .withRequiredArg()
                        .ofType(String.class);
    }
}
