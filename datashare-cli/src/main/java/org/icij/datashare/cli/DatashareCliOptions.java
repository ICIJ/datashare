package org.icij.datashare.cli;

import joptsimple.OptionParser;
import joptsimple.OptionSpec;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.Stage;
import org.slf4j.event.Level;

import java.io.File;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static joptsimple.util.RegexMatcher.regex;


public final class DatashareCliOptions {
    private static final Integer DEFAULT_NLP_PARALLELISM = 1;
    private static final Integer DEFAULT_PARALLELISM = Runtime.getRuntime().availableProcessors() == 1 ? 2 : Runtime.getRuntime().availableProcessors();
    private static final Integer DEFAULT_PARSER_PARALLELISM = 1;
    public static final String DEFAULT_BATCH_DOWNLOAD_DIR = Paths.get(System.getProperty("user.dir")).resolve("app/tmp").toString();
    public static final String DEFAULT_BATCH_DOWNLOAD_MAX_SIZE = "100M";
    public static final String DEFAULT_EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE = "1G";
    public static final int DEFAULT_BATCH_DOWNLOAD_MAX_NB_FILES = 10000;
    public static final int DEFAULT_BATCH_DOWNLOAD_ZIP_TTL = 24;

    public static final String AUTH_FILTER_OPT = "authFilter";
    public static final String AUTH_USERS_PROVIDER_OPT = "authUsersProvider";
    public static final String BATCH_DOWNLOAD_DIR_OPT = "batchDownloadDir";
    public static final String BATCH_DOWNLOAD_ENCRYPT_OPT = "batchDownloadEncrypt";
    public static final String BATCH_DOWNLOAD_MAX_NB_FILES_OPT = "batchDownloadMaxNbFiles";
    public static final String BATCH_DOWNLOAD_MAX_SIZE_OPT = "batchDownloadMaxSize";
    public static final String BATCH_DOWNLOAD_ZIP_TTL_OPT = "batchDownloadTimeToLive";
    public static final String BATCH_QUEUE_TYPE_OPT = "batchQueueType";
    public static final String BATCH_SEARCH_MAX_TIME_OPT = "batchSearchMaxTimeSeconds";
    public static final String BATCH_THROTTLE_OPT = "batchThrottleMilliseconds";
    public static final String BROWSER_OPEN_LINK_OPT = "browserOpenLink";
    public static final String BUS_TYPE_OPT = "busType";
    public static final String CHARSET_OPT = "charset";
    public static final String CLUSTER_NAME_OPT = "clusterName";
    public static final String CORS_OPT = "cors";
    public static final String CREATE_INDEX_OPT = "createIndex";
    public static final String CRE_API_KEY_ABBR_OPT = "k";
    public static final String CRE_API_KEY_OPT = "createApiKey";
    public static final String DATA_DIR_ABBR_OPT = "d";
    public static final String DATA_DIR_OPT = "dataDir";
    public static final String DATA_SOURCE_URL_OPT = "dataSourceUrl";
    public static final String DEFAULT_PROJECT_ABBR_OPT = "p";
    public static final String DEFAULT_PROJECT_OPT = "defaultProject";
    public static final String DEFAULT_USER_NAME_ABBR_OPT = "u";
    public static final String DEFAULT_USER_NAME_OPT = "defaultUserName";
    public static final String DEL_API_KEY_OPT = "deleteApiKey";
    public static final String DIGEST_ALGORITHM_OPT = "digestAlgorithm";
    public static final String DIGEST_PROJECT_NAME_OPT = "digestProjectName";
    public static final String ELASTICSEARCH_ADDRESS_OPT = "elasticsearchAddress";
    public static final String ELASTICSEARCH_DATA_PATH_OPT = "elasticsearchDataPath";
    public static final String EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT = "embeddedDocumentDownloadMaxSize";
    public static final String EXTENSIONS_DIR_OPT = "extensionsDir";
    public static final String EXTENSION_DELETE_OPT = "extensionDelete";
    public static final String EXTENSION_INSTALL_OPT = "extensionInstall";
    public static final String EXTENSION_LIST_OPT = "extensionList";
    public static final String EXT_OPT = "ext";
    public static final String FOLLOW_SYMLINKS_OPT = "followSymlinks";
    public static final String GET_API_KEY_OPT = "apiKey";
    public static final String HELP_ABBR_OPT = "h";
    public static final String HELP_OPT = "help";
    public static final String LANGUAGE_ABBR_OPT = "l";
    public static final String LANGUAGE_OPT = "language";
    public static final String LOG_LEVEL_OPT = "logLevel";
    public static final String MAX_CONTENT_LENGTH_OPT = "maxContentLength";
    public static final String MESSAGE_BUS_OPT = "messageBusAddress";
    public static final String MODE_ABBR_OPT = "m";
    public static final String MODE_OPT = "mode";
    public static final String NLP_PARALLELISM_ABBR_OPT = "np";
    public static final String NLP_PARALLELISM_OPT = "nlpParallelism";
    public static final String NLP_PIPELINE_ABBR_OPT = "nlpp";
    public static final String NLP_PIPELINE_OPT = "nlpPipeline";
    public static final String NO_DIGEST_PROJECT_OPT = "noDigestProject";
    public static final String OAUTH_API_URL_OPT = "oauthApiUrl";
    public static final String OAUTH_AUTHORIZE_URL_OPT = "oauthAuthorizeUrl";
    public static final String OAUTH_CALLBACK_PATH_OPT = "oauthCallbackPath";
    public static final String OAUTH_CLIENT_ID_OPT = "oauthClientId";
    public static final String OAUTH_CLIENT_SECRET_OPT = "oauthClientSecret";
    public static final String OAUTH_DEFAULT_PROJECT_OPT = "oauthDefaultProject";
    public static final String OAUTH_SCOPE_OPT = "oauthScope";
    public static final String OAUTH_TOKEN_URL_OPT = "oauthTokenUrl";
    public static final String OCR_ABBR_OPT = "o";
    public static final String OCR_LANGUAGE_OPT = "ocrLanguage";
    public static final String OCR_OPT = "ocr";
    public static final String PARALLELISM_OPT = "parallelism";
    public static final String PARSER_PARALLELISM_ABBR_OPT = "pp";
    public static final String PARSER_PARALLELISM_OPT = "parserParallelism";
    public static final String PLUGINS_DIR_OPT = "pluginsDir";
    public static final String PLUGIN_DELETE_OPT = "pluginDelete";
    public static final String PLUGIN_INSTALL_OPT = "pluginInstall";
    public static final String PLUGIN_LIST_OPT = "pluginList";
    public static final String PORT_OPT = "port";
    public static final String PROTECTED_URI_PREFIX_OPT = "protectedUriPrefix";
    public static final String QUEUE_NAME_OPT = "queueName";
    public static final String QUEUE_TYPE_OPT = "queueType";
    public static final String REDIS_ADDRESS_OPT = "redisAddress";
    public static final String REDIS_POOL_SIZE_OPT = "redisPoolSize";
    public static final String REPORT_NAME_OPT = "reportName";
    public static final String RESUME_ABBR_OPT = "r";
    public static final String RESUME_OPT = "resume";
    public static final String ROOT_HOST_OPT = "rootHost";
    public static final String SCROLL_SIZE_OPT = "scrollSize";
    public static final String SCROLL_SLICES_OPT = "scrollSlices";
    public static final String SESSION_STORE_TYPE_OPT = "sessionStoreType";
    public static final String SESSION_TTL_SECONDS_OPT = "sessionTtlSeconds";
    public static final String SETTINGS_OPT = "settings";
    public static final String SETTING_ABBR_OPT = "s";
    public static final String SMTP_URL_OPT = "smtpUrl";
    public static final String TCP_LISTEN_PORT_OPT = "tcpListenPort";
    public static final String VERSION_ABBR_OPT = "v";
    public static final String VERSION_OPT = "version";

    static void stages(OptionParser parser) {
        parser.acceptsAll(
                singletonList(PipelineHelper.STAGES_OPT),
                format("Stages to be run separated by %s %s", PipelineHelper.STAGES_SEPARATOR, Arrays.toString(Stage.values())))
                .withRequiredArg()
                .ofType( String.class );
    }

    static void mode(OptionParser parser) {
        parser.acceptsAll(
                asList(MODE_ABBR_OPT, MODE_OPT),
                "Datashare run mode " + Arrays.toString(Mode.values()))
                .withRequiredArg()
                .ofType( Mode.class )
                .defaultsTo(Mode.LOCAL);
    }

    static void charset(OptionParser parser) {
        parser.acceptsAll(
                asList(CHARSET_OPT),
                "Datashare default charset. Example: " +
                        Arrays.toString(new Charset[]{StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1}))
                .withRequiredArg()
                .defaultsTo(Charset.defaultCharset().toString());
    }

    static void defaultUser(OptionParser parser) {
        parser.acceptsAll(
                asList(DEFAULT_USER_NAME_ABBR_OPT, DEFAULT_USER_NAME_OPT),
                "Default local user name")
                .withRequiredArg()
                .ofType( String.class )
                .defaultsTo("local");
    }

    static void followSymlinks(OptionParser parser) {
        parser.acceptsAll(singletonList(FOLLOW_SYMLINKS_OPT), "Follow symlinks (default false)");
    }

    static void cors(OptionParser parser) {
        parser.acceptsAll(
                singletonList(CORS_OPT), "CORS headers (needs the web option)")
                        .withRequiredArg()
                        .ofType(String.class)
                        .defaultsTo("no-cors");
    }

    static void settings (OptionParser parser) {
        parser.acceptsAll(
                asList(SETTING_ABBR_OPT, SETTINGS_OPT), "Property settings file")
                        .withRequiredArg()
                        .ofType(String.class);
    }

    static void pluginsDir(OptionParser parser) {
        parser.acceptsAll(
                singletonList(PLUGINS_DIR_OPT), "Plugins directory")
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
                singletonList(EXTENSIONS_DIR_OPT), "Extensions directory (backend)")
                                .withRequiredArg()
                                .ofType(String.class);
    }

    static void port(OptionParser parser) {
        parser.acceptsAll(
                List.of(PORT_OPT, TCP_LISTEN_PORT_OPT), "Port used by the HTTP server")
                        .withRequiredArg()
                        .ofType(Integer.class)
                        .defaultsTo(8080);
    }

    static void sessionTtlSeconds(OptionParser parser) {
        parser.acceptsAll(
                singletonList(SESSION_TTL_SECONDS_OPT), "Time to live for a HTTP session in seconds")
                        .withRequiredArg()
                        .ofType(Integer.class)
                        .defaultsTo(43200);
    }

    static void protectedUriPrefix(OptionParser parser) {
        parser.acceptsAll(
                singletonList(PROTECTED_URI_PREFIX_OPT), "Protected URI prefix")
                        .withRequiredArg()
                        .ofType(String.class)
                        .defaultsTo("/api/");
    }

    static void resume(OptionParser parser) {
        parser.acceptsAll(asList(RESUME_ABBR_OPT, RESUME_OPT), "Resume pending operations");
    }

    static void createIndex(OptionParser parser) {
        parser.acceptsAll(singletonList(CREATE_INDEX_OPT), "creates an index with the given name")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void genApiKey(OptionParser parser) {
        parser.acceptsAll(asList(CRE_API_KEY_ABBR_OPT, CRE_API_KEY_OPT), "Generate and store api key for user defaultUser (see opt)")
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
                asList(DATA_DIR_ABBR_OPT, DATA_DIR_OPT),
                "Document source files directory" )
                .withRequiredArg()
                .ofType( File.class )
                .defaultsTo(new File("/home/datashare/data"));
    }

    static void rootHost(OptionParser parser) {
            parser.acceptsAll(
                    singletonList(ROOT_HOST_OPT),
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
                singletonList(BUS_TYPE_OPT),
                "Backend data bus type.")
                .withRequiredArg().ofType( QueueType.class )
                .defaultsTo(QueueType.MEMORY);
    }

    static void redisAddress(OptionParser parser) {
            parser.acceptsAll(
                    singletonList(REDIS_ADDRESS_OPT),
                    "Redis queue address")
                    .withRequiredArg()
                    .defaultsTo("redis://redis:6379");
        }

    static void queueType(OptionParser parser) {
            parser.acceptsAll(
                    singletonList(QUEUE_TYPE_OPT),
                    "Backend queues and sets type.")
                    .withRequiredArg().ofType(QueueType.class)
                    .defaultsTo(QueueType.MEMORY);
        }

    static void fileParserParallelism(OptionParser parser) {
        parser.acceptsAll(
                asList(PARSER_PARALLELISM_ABBR_OPT, PARSER_PARALLELISM_OPT),
                "Number of file parser threads.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_PARSER_PARALLELISM);
    }

    static void nlpParallelism(OptionParser parser) {
        parser.acceptsAll(
                asList(NLP_PARALLELISM_ABBR_OPT, NLP_PARALLELISM_OPT),
                "Number of NLP extraction threads per pipeline.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_NLP_PARALLELISM);
    }

    public static void batchSearchMaxTime(OptionParser parser) {
         parser.acceptsAll(
                 singletonList(BATCH_SEARCH_MAX_TIME_OPT), "Max time for batch search in seconds")
                         .withRequiredArg()
                         .ofType(Integer.class);
    }

    public static void batchThrottle(OptionParser parser) {
         parser.acceptsAll(
                 singletonList(BATCH_THROTTLE_OPT), "Throttle for batch in milliseconds")
                         .withRequiredArg()
                         .ofType(Integer.class);
    }

    public static void scrollSize(OptionParser parser) {
        parser.acceptsAll(
                singletonList(SCROLL_SIZE_OPT), "Scroll size used for elasticsearch scrolls (SCANIDX task)")
                .withRequiredArg()
                .ofType(Integer.class).defaultsTo(1000);
    }

     public static void scrollSlices(OptionParser parser) {
        parser.acceptsAll(
                singletonList(SCROLL_SLICES_OPT), "Scroll slice max number used for elasticsearch scrolls (SCANIDX task)")
                .withRequiredArg()
                .ofType(Integer.class).defaultsTo(1);
    }

     public static void redisPoolSize(OptionParser parser) {
        parser.acceptsAll(
                singletonList(REDIS_POOL_SIZE_OPT), "Pool size for main Redis client")
                .withRequiredArg()
                .ofType(Integer.class).defaultsTo(5);
    }

    public static void elasticsearchDataPath(OptionParser parser) {
        parser.acceptsAll(
                singletonList(ELASTICSEARCH_DATA_PATH_OPT), "Data path used for embedded Elasticsearch")
                .withRequiredArg()
                .ofType(String.class).defaultsTo("/home/datashare/es");
    }

    public static void reportName(OptionParser parser) {
        parser.acceptsAll(
                singletonList(REPORT_NAME_OPT), "name of the map for the report map (where index results are stored). " +
                        "No report records are saved if not provided")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void enableBrowserOpenLink(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BROWSER_OPEN_LINK_OPT),
                "try to open link in the default browser").
                withRequiredArg().ofType(Boolean.class).defaultsTo(false);
    }

    static void enableOcr(OptionParser parser) {
        parser.acceptsAll(
                asList(OCR_ABBR_OPT, OCR_OPT),
                "Run optical character recognition at file parsing time. " +
                        "(Tesseract must be installed beforehand).").
                withRequiredArg().ofType(Boolean.class).defaultsTo(true);
    }

    static void language(OptionParser parser) {
        parser.acceptsAll(
                asList(LANGUAGE_ABBR_OPT, LANGUAGE_OPT),
                "Explicitly specify language of indexed documents (instead of detecting automatically)")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void ocrLanguage(OptionParser parser) {
        parser.acceptsAll(
                        List.of(OCR_LANGUAGE_OPT),
                        "Explicitly specify OCR languages for tesseract. 3-character ISO 639-2 language codes and + sign for multiple languages")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void nlpPipeline(OptionParser parser) {
        parser.acceptsAll(
                asList(NLP_PIPELINE_ABBR_OPT, NLP_PIPELINE_OPT),
                "NLP pipeline to be run.")
                .withRequiredArg().defaultsTo("CORENLP");
    }

    static void parallelism(OptionParser parser) {
        parser.acceptsAll(
                singletonList(PARALLELISM_OPT),
                "Number of threads allocated for task management.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_PARALLELISM);
    }

    static void esHost(OptionParser parser) {
        parser.acceptsAll(
                singletonList(ELASTICSEARCH_ADDRESS_OPT), "Elasticsearch host address")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("http://elasticsearch:9200");
    }

    static void dataSourceUrl(OptionParser parser) {
        parser.acceptsAll(
                singletonList(DATA_SOURCE_URL_OPT), "Datasource URL")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared");
    }

    static void defaultProject(OptionParser parser) {
        parser.acceptsAll(
                asList(DEFAULT_PROJECT_ABBR_OPT, DEFAULT_PROJECT_OPT), "Default project name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("local-datashare");
    }

    static void clusterName(OptionParser parser) {
        parser.acceptsAll(
                singletonList(CLUSTER_NAME_OPT), "Cluster name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("datashare");
    }

    static void queueName(OptionParser parser) {
        parser.acceptsAll(
                singletonList(QUEUE_NAME_OPT), "Extract queue name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("extract:queue");
    }

    static OptionSpec<Void> help(OptionParser parser) {
        return parser.acceptsAll(asList(HELP_OPT, HELP_ABBR_OPT, "?")).forHelp();
    }

    static OptionSpec<Void> version(OptionParser parser) {
        return parser.acceptsAll(asList(VERSION_ABBR_OPT, VERSION_OPT));
    }

    static void authUsersProvider(OptionParser parser) {
        parser.acceptsAll(
                singletonList(AUTH_USERS_PROVIDER_OPT), "Server mode auth users provider class")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void authFilter(OptionParser parser) {
        parser.acceptsAll(
                singletonList(AUTH_FILTER_OPT), "Server mode auth filter class")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void oauthSecret(OptionParser parser) {
        parser.acceptsAll(
                singletonList(OAUTH_CLIENT_SECRET_OPT), "OAuth2 client secret key")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthClient(OptionParser parser) {
        parser.acceptsAll(
                singletonList(OAUTH_CLIENT_ID_OPT), "OAuth2 client id")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthAuthorizeUrl(OptionParser parser) {
        parser.acceptsAll(
                singletonList(OAUTH_AUTHORIZE_URL_OPT), "OAuth2 authorize url")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthTokenUrl(OptionParser parser) {
        parser.acceptsAll(
                singletonList(OAUTH_TOKEN_URL_OPT), "OAuth2 token url")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthApiUrl(OptionParser parser) {
        parser.acceptsAll(
                singletonList(OAUTH_API_URL_OPT), "OAuth2 api url")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthCallbackPath(OptionParser parser) {
        parser.acceptsAll(
                singletonList(OAUTH_CALLBACK_PATH_OPT), "OAuth2 callback path (in datashare)")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthDefaultProject(OptionParser parser) {
        parser.acceptsAll(
                singletonList(OAUTH_DEFAULT_PROJECT_OPT), "Default project to use for Oauth2 users")
                .withRequiredArg()
                .ofType(String.class);
    }
    static void oauthScope(OptionParser parser) {
        parser.acceptsAll(
                singletonList(OAUTH_SCOPE_OPT), "Set scope in oauth2 callback url, needed for OIDC providers")
                .withRequiredArg()
                .ofType(String.class);
    }

    public static void batchQueueType(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_QUEUE_TYPE_OPT), "")
                .withRequiredArg().ofType( QueueType.class )
                .defaultsTo(QueueType.MEMORY);
    }

    public static void batchDownloadTimeToLive(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_DOWNLOAD_ZIP_TTL_OPT), "Time to live in hour for batch download zip files (Default 24)")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(DEFAULT_BATCH_DOWNLOAD_ZIP_TTL);
    }

    public static void batchDownloadMaxNbFiles(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_DOWNLOAD_MAX_NB_FILES_OPT), "Maximum file number that can be archived in a zip (Default 10,000)")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(DEFAULT_BATCH_DOWNLOAD_MAX_NB_FILES);
    }

    public static void batchDownloadEncrypt(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_DOWNLOAD_ENCRYPT_OPT), "Whether Batch download zip files are encrypted or not. SmtpUrl should be set to send the password. (default false)")
                .withRequiredArg()
                .ofType(Boolean.class);
    }

    public static void smtpUrl(OptionParser parser) {
        parser.acceptsAll(
                singletonList(SMTP_URL_OPT), "Smtp url to allow datashare to send emails (ex: smtp://localhost:25)")
                .withRequiredArg()
                .ofType(URI.class);
    }

    public static void embeddedDocumentDownloadMaxSize(OptionParser parser) {
        parser.acceptsAll(
                singletonList(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT), "Maximum download size of embedded documents. Human readable suffix K/M/G for KB/MB/GB (Default 1G)")
                .withRequiredArg()
                .withValuesConvertedBy(regex("[0-9]+[KMG]?"))
                .defaultsTo(DEFAULT_EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE);

    }

    public static void batchDownloadMaxSize(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_DOWNLOAD_MAX_SIZE_OPT), "Maximum total files size that can be zipped. Human readable suffix K/M/G for KB/MB/GB (Default 100M)")
                .withRequiredArg()
                .withValuesConvertedBy(regex("[0-9]+[KMG]?"))
                .defaultsTo(DEFAULT_BATCH_DOWNLOAD_MAX_SIZE);

    }

    public static void batchDownloadDir(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_DOWNLOAD_DIR_OPT), "Directory where Batch Download archives are downloaded. (Default <currentUserDir>/app/tmp")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_BATCH_DOWNLOAD_DIR);
    }

    public static void maxContentLength(OptionParser parser) {
        parser.acceptsAll(
                singletonList(MAX_CONTENT_LENGTH_OPT), "Maximum length (in bytes) of extracted text that could be indexed " +
                        "(-1 means no limit and value should be less or equal than 2G). Human readable suffix K/M/G for KB/MB/GB (Default -1)")
                .withRequiredArg()
                .withValuesConvertedBy(regex("[0-9]+[KMG]?"));
    }

    public static void sessionStoreType(OptionParser parser) {
        parser.acceptsAll(
                singletonList(SESSION_STORE_TYPE_OPT), "Type of session store")
                .withRequiredArg()
                .ofType(QueueType.class)
                .defaultsTo(QueueType.MEMORY);
    }

    public static void digestMethod(OptionParser parser) {
        parser.acceptsAll(
                singletonList(DIGEST_ALGORITHM_OPT))
                .withRequiredArg()
                .ofType(DigestAlgorithm.class)
                .defaultsTo(DigestAlgorithm.SHA_384)
                .withValuesConvertedBy(new DigestAlgorithm.DigestAlgorithmConverter());
    }

    public static void digestProjectName(OptionParser parser) {
        parser.acceptsAll(
                singletonList(DIGEST_PROJECT_NAME_OPT), "Includes the project name in the hash of documents when indexing. " +
                        "It is set by default to the defaultProject value. See noDigestProject option to disable it.")
                .withRequiredArg()
                .ofType(String.class);
    }

    public static void noDigestProject(OptionParser parser) {
        parser.acceptsAll(
                singletonList(NO_DIGEST_PROJECT_OPT), "Disable the project name in document hash processing (only using binary contents).")
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(false);
    }

    public static OptionSpec<String> extOption(OptionParser parser) {
        return parser.acceptsAll(
                singletonList(EXT_OPT), "Run CLI extension")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void logLevel(OptionParser parser) {
        parser.acceptsAll(
                        singletonList(LOG_LEVEL_OPT),
                        format("Sets the log level of Datashare (%s)", Arrays.toString(Level.values())))
                .withRequiredArg()
                .ofType( String.class )
                .defaultsTo(Level.INFO.toString());
    }
}
