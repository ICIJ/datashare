package org.icij.datashare.cli;

import joptsimple.OptionParser;
import joptsimple.OptionSpec;

import java.io.File;
import java.net.URI;
import java.util.Arrays;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static joptsimple.util.RegexMatcher.regex;


public final class DatashareCliOptions {
    public static final char ARG_VALS_SEP = ',';
    private static final Integer DEFAULT_PARSER_PARALLELISM = 1;
    private static final Integer DEFAULT_NLP_PARALLELISM = 1;
    private static final Integer DEFAULT_PARALLELISM =
            Runtime.getRuntime().availableProcessors() == 1 ? 2 : Runtime.getRuntime().availableProcessors();
    public static final String STAGES_OPT = "stages";
    public static final String DATA_DIR_OPT = "dataDir";
    public static final String NLP_PIPELINES_OPT = "nlpPipelines";
    public static final String BATCH_THROTTLE = "batchThrottleMilliseconds";
    public static final String BATCH_SEARCH_MAX_TIME = "batchSearchMaxTimeSeconds";
    public static final String BATCH_DOWNLOAD_ZIP_TTL = "batchDownloadTimeToLive";
    public static final String SCROLL_SIZE = "scrollSize";
    public static final String BATCH_DOWNLOAD_MAX_NB_FILES = "batchDownloadMaxNbFiles";
    public static final String BATCH_DOWNLOAD_MAX_SIZE = "batchDownloadMaxSize";

    static final String MESSAGE_BUS_OPT = "messageBusAddress";
    static final String ROOT_HOST = "rootHost";
    public static final String RESUME_OPT = "resume";
    public static final String GET_API_KEY_OPT = "apiKey";
    public static final String CREATE_INDEX_OPT = "createIndex";
    public static final String CRE_API_KEY_OPT = "createApiKey";
    public static final String PLUGIN_LIST_OPT = "pluginList";
    public static final String PLUGIN_INSTALL_OPT = "pluginInstall";
    public static final String PLUGIN_DELETE_OPT = "pluginDelete";
    public static final String EXTENSION_LIST_OPT = "extensionList";
    public static final String EXTENSION_INSTALL_OPT = "extensionInstall";
    public static final String EXTENSION_DELETE_OPT = "extensionDelete";
    public static final String DEL_API_KEY_OPT = "deleteApiKey";
    public static final String PARALLELISM = "parallelism";
    public static final String OPEN_LINK = "browserOpenLink";
    public static final String NLP_PARALLELISM_OPT = "nlpParallelism";
    public static final String DEFAULT_USER_NAME = "defaultUserName";

    static void stages(OptionParser parser) {
        parser.acceptsAll(
                singletonList(STAGES_OPT),
                "Stages to be run. WARN that DEDUPLICATE stages are not streamable like the others. They should be run alone.")
                .withRequiredArg()
                .ofType( String.class );
    }

    static void mode(OptionParser parser) {
        parser.acceptsAll(
                asList("mode", "m"),
                "Datashare run mode " + Arrays.toString(Mode.values()))
                .withRequiredArg()
                .ofType( Mode.class )
                .defaultsTo(Mode.LOCAL);
    }

    static void defaultUser(OptionParser parser) {
        parser.acceptsAll(
                asList("u", DEFAULT_USER_NAME),
                "Default local user name")
                .withRequiredArg()
                .ofType( String.class )
                .defaultsTo("local");
    }

    static void followSymlinks(OptionParser parser) {
        parser.acceptsAll(singletonList("followSymlinks"), "Follow symlinks (default false)");
    }

    static void cors(OptionParser parser) {
        parser.acceptsAll(
                singletonList("cors"), "CORS headers (needs the web option)")
                        .withRequiredArg()
                        .ofType(String.class)
                        .defaultsTo("no-cors");
    }

    static void settings (OptionParser parser) {
        parser.acceptsAll(
                asList("s", "settings"), "Property settings file")
                        .withRequiredArg()
                        .ofType(String.class);
    }

    static void pluginsDir(OptionParser parser) {
        parser.acceptsAll(
                singletonList("pluginsDir"), "Plugins directory")
                        .withRequiredArg()
                        .ofType(String.class);
    }

    static void pluginList(OptionParser parser) {
        parser.acceptsAll(singletonList(PLUGIN_LIST_OPT), "Plugins list matching provided string")
                .withOptionalArg()
                .ofType(String.class);
    }

    static void pluginInstall(OptionParser parser) {
        parser.acceptsAll(singletonList(PLUGIN_INSTALL_OPT), "Install plugin with either id or URL or file path (needs pluginsDir option)")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void pluginDelete(OptionParser parser) {
        parser.acceptsAll(singletonList(PLUGIN_DELETE_OPT), "Delete plugin with its id or base directory (needs pluginsDir option)")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void extensionList(OptionParser parser) {
        parser.acceptsAll(singletonList(EXTENSION_LIST_OPT), "Extensions list matching provided string")
                .withOptionalArg()
                .ofType(String.class);
    }

    static void extensionInstall(OptionParser parser) {
        parser.acceptsAll(singletonList(EXTENSION_INSTALL_OPT), "Install extension with either id or URL or file path (needs extensionsDir option)")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void extensionDelete(OptionParser parser) {
        parser.acceptsAll(singletonList(EXTENSION_DELETE_OPT), "Delete extension with its id or base directory (needs extensionsDir option)")
                .withRequiredArg()
                .ofType(String.class);
    }

    public static void extensionsDir(OptionParser parser) {
        parser.acceptsAll(
                singletonList("extensionsDir"), "Extensions directory (backend)")
                                .withRequiredArg()
                                .ofType(String.class);
    }

    static void tcpListenPort(OptionParser parser) {
        parser.acceptsAll(
                singletonList("tcpListenPort"), "Port used by the HTTP server")
                        .withRequiredArg()
                        .ofType(Integer.class)
                        .defaultsTo(8080);
    }

    static void sessionTtlSeconds(OptionParser parser) {
        parser.acceptsAll(
                singletonList("sessionTtlSeconds"), "Time to live for a HTTP session in seconds")
                        .withRequiredArg()
                        .ofType(Integer.class)
                        .defaultsTo(43200);
    }

    static void protectedUriPrefix(OptionParser parser) {
        parser.acceptsAll(
                singletonList("protectedUriPrefix"), "Protected URI prefix")
                        .withRequiredArg()
                        .ofType(String.class)
                        .defaultsTo("/api/");
    }

    static void resume(OptionParser parser) {
        parser.acceptsAll(asList(RESUME_OPT, "r"), "Resume pending operations");
    }

    static void createIndex(OptionParser parser) {
        parser.acceptsAll(singletonList(CREATE_INDEX_OPT), "creates an index with the given name")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void genApiKey(OptionParser parser) {
        parser.acceptsAll(asList(CRE_API_KEY_OPT, "k"), "Generate and store api key for user defaultUser (see opt)")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void getApiKey(OptionParser parser) {
        parser.acceptsAll(singletonList(GET_API_KEY_OPT), "existing api key for user")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void delApiKey(OptionParser parser) {
        parser.acceptsAll(singletonList(DEL_API_KEY_OPT), "Delete api key for user")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void dataDir(OptionParser parser) {
        parser.acceptsAll(
                asList(DATA_DIR_OPT, "d"),
                "Document source files directory" )
                .withRequiredArg()
                .ofType( File.class )
                .defaultsTo(new File("/home/datashare/data"));
    }

    static void rootHost(OptionParser parser) {
            parser.acceptsAll(
                    singletonList(ROOT_HOST),
                    "Datashare host for urls")
                    .withRequiredArg();
        }

    static void messageBusAddress(OptionParser parser) {
        parser.acceptsAll(
                singletonList(MESSAGE_BUS_OPT),
                "Message bus address")
                .withRequiredArg()
                .defaultsTo("redis://redis:6379");
    }

    public static void busType(OptionParser parser) {
        parser.acceptsAll(
                singletonList("busType"),
                "Backend data bus type. Values can be \"memory\" or \"redis\"")
                .withRequiredArg().ofType( QueueType.class )
                .defaultsTo(QueueType.MEMORY);
    }

    static void redisAddress(OptionParser parser) {
            parser.acceptsAll(
                    singletonList("redisAddress"),
                    "Redis queue address")
                    .withRequiredArg()
                    .defaultsTo("redis://redis:6379");
        }

    static void queueType(OptionParser parser) {
            parser.acceptsAll(
                    singletonList("queueType"),
                    "Backend queues and sets type. Values can be \"memory\" or \"redis\"")
                    .withRequiredArg().ofType(QueueType.class)
                    .defaultsTo(QueueType.MEMORY);
        }

    static void fileParserParallelism(OptionParser parser) {
        parser.acceptsAll(
                asList("pp", "parserParallelism"),
                "Number of file parser threads.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_PARSER_PARALLELISM);
    }

    static void nlpParallelism(OptionParser parser) {
        parser.acceptsAll(
                asList("np", NLP_PARALLELISM_OPT),
                "Number of NLP extraction threads per pipeline.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_NLP_PARALLELISM);
    }

    public static void batchSearchMaxTime(OptionParser parser) {
         parser.acceptsAll(
                 singletonList(BATCH_SEARCH_MAX_TIME), "Max time for batch search in seconds")
                         .withRequiredArg()
                         .ofType(Integer.class);
    }

    public static void batchThrottle(OptionParser parser) {
         parser.acceptsAll(
                 singletonList(BATCH_THROTTLE), "Throttle for batch in milliseconds")
                         .withRequiredArg()
                         .ofType(Integer.class);
    }

    public static void scrollSize(OptionParser parser) {
        parser.acceptsAll(
                singletonList("scrollSize"), "Scroll size used for elasticsearch scrolls (SCANIDX task)")
                .withRequiredArg()
                .ofType(Integer.class).defaultsTo(1000);
    }

     public static void scrollSlices(OptionParser parser) {
        parser.acceptsAll(
                singletonList("scrollSlices"), "Scroll slice max number used for elasticsearch scrolls (SCANIDX task)")
                .withRequiredArg()
                .ofType(Integer.class).defaultsTo(1);
    }

     public static void redisPoolSize(OptionParser parser) {
        parser.acceptsAll(
                singletonList("redisPoolSize"), "Redis pool size used for each redis collection")
                .withRequiredArg()
                .ofType(Integer.class).defaultsTo(1);
    }

    public static void elasticsearchDataPath(OptionParser parser) {
        parser.acceptsAll(
                singletonList("elasticsearchDataPath"), "Data path used for embedded Elasticsearch")
                .withRequiredArg()
                .ofType(String.class).defaultsTo("/home/datashare/es");
    }

    public static void reportName(OptionParser parser) {
        parser.acceptsAll(
                singletonList("reportName"), "name of the map for the report map (where index results are stored). " +
                        "No report records are saved if not provided")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void enableBrowserOpenLink(OptionParser parser) {
        parser.acceptsAll(
                singletonList(OPEN_LINK),
                "try to open link in the default browser").
                withRequiredArg().ofType(Boolean.class).defaultsTo(false);
    }

    static void enableOcr(OptionParser parser) {
        parser.acceptsAll(
                asList("ocr", "o"),
                "Run optical character recognition at file parsing time. " +
                        "(Tesseract must be installed beforehand).").
                withRequiredArg().ofType(Boolean.class).defaultsTo(true);
    }

    static void language(OptionParser parser) {
        parser.acceptsAll(
                asList("language", "l"),
                "Explicitly specify language of indexed documents (instead of detecting automatically)")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void nlpPipelines(OptionParser parser) {
        parser.acceptsAll(
                asList(NLP_PIPELINES_OPT, "nlpp"),
                "NLP pipelines to be run.")
                .withRequiredArg()
                .withValuesSeparatedBy(ARG_VALS_SEP);
    }

    static void parallelism(OptionParser parser) {
        parser.acceptsAll(
                singletonList(PARALLELISM),
                "Number of threads allocated for task management.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_PARALLELISM);
    }

    static void esHost(OptionParser parser) {
        parser.acceptsAll(
                singletonList("elasticsearchAddress"), "Elasticsearch host address")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("http://elasticsearch:9200");
    }

    static void dataSourceUrl(OptionParser parser) {
        parser.acceptsAll(
                singletonList("dataSourceUrl"), "Datasource URL")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
    }

    static void defaultProject(OptionParser parser) {
        parser.acceptsAll(
                asList("defaultProject", "p"), "Default project name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("local-datashare");
    }

    static void clusterName(OptionParser parser) {
        parser.acceptsAll(
                singletonList("clusterName"), "Cluster name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("datashare");
    }

    static void queueName(OptionParser parser) {
        parser.acceptsAll(
                singletonList("queueName"), "Redis queue name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("extract:queue");
    }

    static OptionSpec<Void> help(OptionParser parser) {
        return parser.acceptsAll(asList("help", "h", "?")).forHelp();
    }

    static OptionSpec<Void> version(OptionParser parser) {
        return parser.acceptsAll(asList("version", "v"));
    }

    static void authUsersProvider(OptionParser parser) {
        parser.acceptsAll(
                singletonList("authUsersProvider"), "Server mode auth users provider class")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void authFilter(OptionParser parser) {
        parser.acceptsAll(
                singletonList("authFilter"), "Server mode auth filter class")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void oauthSecret(OptionParser parser) {
        parser.acceptsAll(
                singletonList("oauthClientSecret"), "OAuth2 client secret key")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthClient(OptionParser parser) {
        parser.acceptsAll(
                singletonList("oauthClientId"), "OAuth2 client id")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthAuthorizeUrl(OptionParser parser) {
        parser.acceptsAll(
                singletonList("oauthAuthorizeUrl"), "OAuth2 authorize url")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthTokenUrl(OptionParser parser) {
        parser.acceptsAll(
                singletonList("oauthTokenUrl"), "OAuth2 token url")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthApiUrl(OptionParser parser) {
        parser.acceptsAll(
                singletonList("oauthApiUrl"), "OAuth2 api url")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthCallbackPath(OptionParser parser) {
        parser.acceptsAll(
                singletonList("oauthCallbackPath"), "OAuth2 callback path (in datashare)")
                .withRequiredArg()
                .ofType(String.class);
    }

    public static void batchQueueType(OptionParser parser) {
        parser.acceptsAll(
                singletonList("batchQueueType"), "")
                .withRequiredArg().ofType( QueueType.class )
                .defaultsTo(QueueType.MEMORY);
    }

    public static void batchDownloadTimeToLive(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_DOWNLOAD_ZIP_TTL), "Time to live in hour for batch download zip files (Default 24)")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(24);
    }

    public static void batchDownloadMaxNbFiles(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_DOWNLOAD_MAX_NB_FILES), "Maximum file number that can be archived in a zip (Default 10,000)")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(10000);
    }

    public static void batchDownloadEncrypt(OptionParser parser) {
        parser.acceptsAll(
                singletonList("batchDownloadEncrypt"), "Whether Batch download zip files are encrypted or not. SmtpUrl should be set to send the password. (default false)")
                .withRequiredArg()
                .ofType(Boolean.class);
    }

    public static void smtpUrl(OptionParser parser) {
        parser.acceptsAll(
                singletonList("smtpUrl"), "Smtp url to allow datashare to send emails (ex: smtp://localhost:25)")
                .withRequiredArg()
                .ofType(URI.class);
    }

    public static void batchDownloadMaxSize(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_DOWNLOAD_MAX_SIZE), "Maximum total files size that can be zipped. Human readable suffix K/M/G for KB/MB/GB (Default 100M)")
                .withRequiredArg()
                .withValuesConvertedBy(regex("[0-9]+[KMG]?"))
                .defaultsTo("100M");

    }

    public static void maxContentLength(OptionParser parser) {
        parser.acceptsAll(
                singletonList("maxContentLength"), "Maximum length (in bytes) of extracted text that could be indexed " +
                        "(-1 means no limit and value should be less or equal than 2G). Human readable suffix K/M/G for KB/MB/GB (Default -1)")
                .withRequiredArg()
                .withValuesConvertedBy(regex("[0-9]+[KMG]?"));
    }

    public static void sessionStoreType(OptionParser parser) {
        parser.acceptsAll(
                singletonList("sessionStoreType"), "Type of session store")
                .withRequiredArg()
                .ofType(QueueType.class);
    }
}
