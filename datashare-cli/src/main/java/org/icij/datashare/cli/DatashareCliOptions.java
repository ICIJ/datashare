package org.icij.datashare.cli;

import joptsimple.OptionParser;
import joptsimple.OptionSpec;
import joptsimple.ValueConverter;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.Stage;
import org.icij.datashare.user.User;
import org.slf4j.event.Level;
import org.icij.datashare.tasks.RoutingStrategy;

import java.io.File;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static joptsimple.util.RegexMatcher.regex;
import static org.icij.datashare.PropertiesProvider.*;


public final class DatashareCliOptions {
    public static final String AUTH_FILTER_OPT = "authFilter";
    public static final String AUTH_MODE_OPT = "auth";
    public static final String AUTH_USERS_PROVIDER_OPT = "authUsersProvider";
    public static final String BIND_HOST_ABBR_OPT = "b";
    public static final String BIND_HOST_OPT = "bind";
    public static final String BATCH_DOWNLOAD_DIR_OPT = "batchDownloadDir";
    public static final String BATCH_DOWNLOAD_ENCRYPT_OPT = "batchDownloadEncrypt";
    public static final String BATCH_DOWNLOAD_MAX_NB_FILES_OPT = "batchDownloadMaxNbFiles";
    public static final String BATCH_DOWNLOAD_MAX_SIZE_OPT = "batchDownloadMaxSize";
    public static final String BATCH_DOWNLOAD_SCROLL_DURATION_OPT = "batchDownloadScroll";
    public static final String BATCH_DOWNLOAD_SCROLL_SIZE_OPT = "batchDownloadScrollSize";
    public static final String BATCH_DOWNLOAD_ZIP_TTL_OPT = "batchDownloadTimeToLive";
    public static final String BATCH_QUEUE_TYPE_OPT = "batchQueueType";
    public static final String BATCH_SEARCH_MAX_TIME_OPT = "batchSearchMaxTimeSeconds";
    public static final String BATCH_SEARCH_SCROLL_DURATION_OPT = "batchSearchScroll";
    public static final String BATCH_SEARCH_SCROLL_SIZE_OPT = "batchSearchScrollSize";
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
    public static final String DATA_SOURCE_URL_OPT = "dataSourceUrl";
    public static final String DEFAULT_OCR_TYPE = "TESSERACT";
    public static final String DEFAULT_PROJECT_ABBR_OPT = "p";
    public static final String DEFAULT_USER_NAME_ABBR_OPT = "u";
    public static final String DEFAULT_USER_NAME_OPT = "defaultUserName";
    public static final String DEL_API_KEY_OPT = "deleteApiKey";
    public static final String DIGEST_ALGORITHM_OPT = "digestAlgorithm";
    public static final String ELASTICSEARCH_ADDRESS_OPT = "elasticsearchAddress";
    public static final String ELASTICSEARCH_SETTINGS_OPT = "elasticsearchSettings";
    public static final String ELASTICSEARCH_PATH_OPT = "elasticsearchPath";
    public static final String ELASTICSEARCH_DATA_PATH_OPT = "elasticsearchDataPath";
    // Time in ms after which an idling connection should be considered invalid
    public static final String ELASTICSEARCH_MAX_IDLE_CONNECTION_TIME_OPT = "elasticsearchMaxIdleConnectionTime";
    public static final String EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT = "embeddedDocumentDownloadMaxSize";
    public static final String EXTENSION_DELETE_OPT = "extensionDelete";
    public static final String EXTENSION_INSTALL_OPT = "extensionInstall";
    public static final String EXTENSION_LIST_OPT = "extensionList";
    public static final String EXT_OPT = "ext";
    public static final String FOLLOW_SYMLINKS_OPT = "followSymlinks";
    public static final String GET_API_KEY_OPT = "apiKey";
    public static final String GRANT_ADMIN_OPT = "grantAdmin";
    public static final String HELP_ABBR_OPT = "h";
    public static final String HELP_OPT = "help";
    public static final String INDEX_TIMEOUT_OPT = "indexTimeout";
    public static final String LANGUAGE_ABBR_OPT = "l";
    public static final String LANGUAGE_OPT = "language";
    public static final String LOG_LEVEL_OPT = "logLevel";
    public static final String MAX_CONTENT_LENGTH_OPT = "maxContentLength";
    public static final String MESSAGE_BUS_OPT = "messageBusAddress";
    public static final String MODE_ABBR_OPT = "m";
    public static final String MODE_OPT = "mode";
    public static final String NLP_BATCH_SIZE_OPT = "batchSize";
    public static final String NLP_PARALLELISM_ABBR_OPT = "np";
    public static final String NLP_MAX_TEXT_LENGTH_OPT = "maxTextLength";
    public static final String NLP_PARALLELISM_OPT = "nlpParallelism";
    public static final String NLP_PIPELINE_ABBR_OPT = "nlpp";
    public static final String NLP_PIPELINE_OPT = "nlpPipeline";
    public static final String NO_DIGEST_PROJECT_OPT = "noDigestProject";
    public static final String OAUTH_API_URL_OPT = "oauthApiUrl";
    public static final String OAUTH_AUTHORIZE_URL_OPT = "oauthAuthorizeUrl";
    public static final String OAUTH_CALLBACK_PATH_OPT = "oauthCallbackPath";
    public static final String OAUTH_CLAIM_ID_ATTRIBUTE_OPT = "oauthClaimIdAttribute";
    public static final String OAUTH_CLIENT_ID_OPT = "oauthClientId";
    public static final String OAUTH_CLIENT_SECRET_OPT = "oauthClientSecret";
    public static final String OAUTH_DEFAULT_PROJECT_OPT = "oauthDefaultProject";
    public static final String OAUTH_SCOPE_OPT = "oauthScope";
    public static final String OAUTH_TOKEN_URL_OPT = "oauthTokenUrl";
    public static final String OCR_ABBR_OPT = "o";
    public static final String OCR_LANGUAGE_OPT = "ocrLanguage";
    public static final String OCR_OPT = "ocr";
    public static final String OCR_TYPE_OPT = "ocrType";
    public static final String OCR_TIMEOUT = "ocrTimeout";
    public static final String OCR_STRATEGY_OPT = "ocrStrategy";
    public static final String MAX_EMBED_DEPTH_OPT = "maxEmbedDepth";
    public static final String MAX_EMBED_DEPTH_DESC =
            "Maximum nesting depth of embedded documents to extract before deeper embeds " +
                    "are skipped, guarding against decompression-bomb / deeply-nested-archive " +
                    "inputs. 0 disables the guard. Default: 20.";
    public static final String PARALLELISM_OPT = "parallelism";
    public static final String PARSER_PARALLELISM_ABBR_OPT = "pp";
    public static final String PARSER_PARALLELISM_OPT = "parserParallelism";
    public static final String PARSE_TIMEOUT_OPT = "parseTimeout";
    public static final String PLUGIN_DELETE_OPT = "pluginDelete";
    public static final String PLUGIN_INSTALL_OPT = "pluginInstall";
    public static final String PLUGIN_LIST_OPT = "pluginList";
    public static final String PORT_OPT = "port";
    public static final String PROTECTED_URI_PREFIX_OPT = "protectedUriPrefix";
    public static final String QUEUE_TYPE_OPT = "queueType";
    public static final String QUEUE_CAPACITY_OPT = "queueCapacity";
    public static final String REDIS_ADDRESS_OPT = "redisAddress";
    public static final String REDIS_POOL_SIZE_OPT = "redisPoolSize";
    public static final String RESUME_ABBR_OPT = "r";
    public static final String ROOT_HOST_OPT = "rootHost";
    public static final String SCROLL_DURATION_OPT = "scroll";
    public static final String SCROLL_SIZE_OPT = "scrollSize";
    public static final String SCROLL_SLICES_OPT = "scrollSlices";
    public static final String SESSION_SIGNING_KEY_OPT = "sessionSigningKey";
    public static final String SESSION_STORE_TYPE_OPT = "sessionStoreType";
    public static final String SESSION_TTL_SECONDS_OPT = "sessionTtlSeconds";
    public static final String STATUS_ALLOWED_NETS_OPT = "statusAllowedNets";
    public static final String SETTING_ABBR_OPT = "s";
    public static final String SMTP_URL_OPT = "smtpUrl";
    public static final String TEMPORAL_NAMESPACE_OPT = "temporalNamespace";
    public static final String TEMPORAL_ADDRESS_OPT = "temporalAddress";
    public static final String VERSION_ABBR_OPT = "v";
    public static final String VERSION_OPT = "version";
    public static final String ARTIFACT_DIR_OPT = "artifactDir";
    public static final String ARTIFACTS_OPT = "artifacts";
    public static final String SEARCH_QUERY_OPT = "searchQuery";
    public static final String TASK_ROUTING_STRATEGY_OPT = "taskRoutingStrategy";
    public static final String TASK_ROUTING_KEY_OPT = "taskRoutingKey";
    public static final String OAUTH_USER_PROJECTS_KEY_OPT = "oauthUserProjectsAttribute";
    public static final String POLLING_INTERVAL_SECONDS_OPT = "pollingInterval";
    public static final String TASK_REPOSITORY_OPT = "taskRepositoryType";
    public static final String TASK_MANAGER_POLLING_INTERVAL_OPT = "taskManagerPollingIntervalMilliseconds";
    public static final String TASK_WORKERS_OPT = "taskWorkers";
    public static final String TASK_PROGRESS_INTERVAL_OPT = "taskProgressUpdateIntervalSeconds";
    public static final String POLICY_RELOAD_INTERVAL_OPT = "policyReloadInterval";
    // Internal property keys for the CLI -> CliApp dispatch of the user
    // commands. USER_CREATE_OPT and USER_DELETE_OPT carry the login; the
    // .* siblings carry the remaining fields. Typed siblings replaced a
    // JSON blob to keep the dispatch ABI compile-time-typed.
    public static final String USER_CREATE_OPT = "userCreate";
    public static final String USER_CREATE_EMAIL_OPT = USER_CREATE_OPT + ".email";
    public static final String USER_CREATE_NAME_OPT = USER_CREATE_OPT + ".name";
    public static final String USER_CREATE_PASSWORD_OPT = USER_CREATE_OPT + ".password";
    public static final String USER_CREATE_PROVIDER_OPT = USER_CREATE_OPT + ".provider";
    public static final String USER_CREATE_GROUPS_OPT = USER_CREATE_OPT + ".groups";
    public static final String USER_CREATE_IF_NOT_EXISTS_OPT = USER_CREATE_OPT + ".ifNotExists";
    public static final String USER_CREATE_JSON_OPT = USER_CREATE_OPT + ".json";
    public static final String USER_DELETE_OPT = "userDelete";
    public static final String USER_DELETE_IF_EXISTS_OPT = USER_DELETE_OPT + ".ifExists";
    public static final String USER_DELETE_JSON_OPT = USER_DELETE_OPT + ".json";
    // Project admin CLI: keys consumed by CliApp.handleProjectCreate /
    // handleProjectDelete. PROJECT_CREATE_OPT and PROJECT_DELETE_OPT carry the
    // project name; the dotted siblings carry one typed field each.
    public static final String PROJECT_CREATE_OPT = "projectCreate";
    public static final String PROJECT_CREATE_LABEL_OPT = PROJECT_CREATE_OPT + ".label";
    public static final String PROJECT_CREATE_DESCRIPTION_OPT = PROJECT_CREATE_OPT + ".description";
    public static final String PROJECT_CREATE_SOURCE_PATH_OPT = PROJECT_CREATE_OPT + ".sourcePath";
    public static final String PROJECT_CREATE_ALLOW_FROM_MASK_OPT = PROJECT_CREATE_OPT + ".allowFromMask";
    public static final String PROJECT_CREATE_SOURCE_URL_OPT = PROJECT_CREATE_OPT + ".sourceUrl";
    public static final String PROJECT_CREATE_MAINTAINER_NAME_OPT = PROJECT_CREATE_OPT + ".maintainerName";
    public static final String PROJECT_CREATE_PUBLISHER_NAME_OPT = PROJECT_CREATE_OPT + ".publisherName";
    public static final String PROJECT_CREATE_LOGO_URL_OPT = PROJECT_CREATE_OPT + ".logoUrl";
    public static final String PROJECT_CREATE_CREATION_DATE_OPT = PROJECT_CREATE_OPT + ".creationDate";
    public static final String PROJECT_CREATE_UPDATE_DATE_OPT = PROJECT_CREATE_OPT + ".updateDate";
    public static final String PROJECT_CREATE_NO_INDEX_OPT = PROJECT_CREATE_OPT + ".noIndex";
    public static final String PROJECT_CREATE_IF_NOT_EXISTS_OPT = PROJECT_CREATE_OPT + ".ifNotExists";
    public static final String PROJECT_CREATE_JSON_OPT = PROJECT_CREATE_OPT + ".json";
    public static final String PROJECT_CREATE_CREATOR_OPT = PROJECT_CREATE_OPT + ".creator";
    public static final String PROJECT_DELETE_OPT = "projectDelete";
    public static final String PROJECT_DELETE_YES_OPT = PROJECT_DELETE_OPT + ".yes";
    public static final String PROJECT_DELETE_KEEP_INDEX_OPT = PROJECT_DELETE_OPT + ".keepIndex";
    public static final String PROJECT_DELETE_IF_EXISTS_OPT = PROJECT_DELETE_OPT + ".ifExists";
    public static final String PROJECT_DELETE_NO_INPUT_OPT = PROJECT_DELETE_OPT + ".noInput";
    public static final String PROJECT_DELETE_JSON_OPT = PROJECT_DELETE_OPT + ".json";

    public static final String PROJECT_GRANT_OPT              = "projectGrant";
    public static final String PROJECT_GRANT_USER_OPT         = PROJECT_GRANT_OPT + ".user";
    public static final String PROJECT_GRANT_ROLE_OPT         = PROJECT_GRANT_OPT + ".role";
    public static final String PROJECT_GRANT_IF_NOT_EXISTS_OPT = PROJECT_GRANT_OPT + ".ifNotExists";
    public static final String PROJECT_GRANT_JSON_OPT          = PROJECT_GRANT_OPT + ".json";

    public static final String PROJECT_REVOKE_OPT             = "projectRevoke";
    public static final String PROJECT_REVOKE_USER_OPT        = PROJECT_REVOKE_OPT + ".user";
    public static final String PROJECT_REVOKE_YES_OPT         = PROJECT_REVOKE_OPT + ".yes";
    public static final String PROJECT_REVOKE_NO_INPUT_OPT    = PROJECT_REVOKE_OPT + ".noInput";
    public static final String PROJECT_REVOKE_IF_EXISTS_OPT   = PROJECT_REVOKE_OPT + ".ifExists";
    public static final String PROJECT_REVOKE_JSON_OPT        = PROJECT_REVOKE_OPT + ".json";

    private static final Path DEFAULT_DATASHARE_HOME = Paths.get(System.getProperty("user.home"), ".local/share/datashare");
    private static final Integer DEFAULT_NLP_PARALLELISM = 1;
    public static final Integer DEFAULT_PARALLELISM = Runtime.getRuntime().availableProcessors() == 1 ? 2 : Runtime.getRuntime().availableProcessors();
    // Connections the shared Redis client needs on top of the stage worker pool: one for the task
    // supplier's own blocking poll plus headroom for concurrent short-lived commands. The main pool
    // is sized to parallelism + this overhead so blocking BLPOP workers never starve the rest of the app.
    public static final int REDIS_POOL_SIZE_OVERHEAD = 4;
    private static final Integer DEFAULT_PARSER_PARALLELISM = 1;
    public static final DigestAlgorithm DEFAULT_DIGEST_METHOD = DigestAlgorithm.SHA_384;
    public static final String DEFAULT_STATUS_ALLOWED_NETS = "127.0.0.0/8,::1/128";
    public static final String DEFAULT_DATA_DIR = Paths.get(System.getProperty("user.home")).resolve("Datashare").toString();
    public static final Mode DEFAULT_MODE = Mode.LOCAL;
    public static final QueueType DEFAULT_BATCH_QUEUE_TYPE = QueueType.MEMORY;
    public static final QueueType DEFAULT_BUS_TYPE = QueueType.MEMORY;
    public static final QueueType DEFAULT_QUEUE_TYPE = QueueType.MEMORY;
    public static final QueueType DEFAULT_SESSION_STORE_TYPE = QueueType.MEMORY;
    public static final String DEFAULT_BATCH_THROTTLE = "0";
    public static final String DEFAULT_BATCH_DOWNLOAD_DIR = DEFAULT_DATASHARE_HOME.resolve("tmp").toString();
    public static final String DEFAULT_BATCH_DOWNLOAD_MAX_SIZE = "100M";
    public static final String DEFAULT_BATCH_SEARCH_MAX_TIME = "100000";
    public static final String DEFAULT_CHARSET = Charset.defaultCharset().toString();
    public static final String DEFAULT_CLUSTER_NAME = "datashare";
    public static final String DEFAULT_CORS = "no-cors";
    public static final String DEFAULT_DATA_SOURCE_URL = "jdbc:sqlite:file:" + DEFAULT_DATASHARE_HOME.resolve("dist/datashare.db");
    public static final String DEFAULT_DEFAULT_PROJECT = "local-datashare";
    public static final String DEFAULT_ELASTICSEARCH_ADDRESS = EnvUtils.resolveUri("elasticsearch", "http://elasticsearch:9200");
    public static final String DEFAULT_ELASTICSEARCH_PATH = DEFAULT_DATASHARE_HOME.resolve("elasticsearch").toString();
    public static final String DEFAULT_ELASTICSEARCH_DATA_PATH = DEFAULT_DATASHARE_HOME.resolve("es").toString();
    public static final String DEFAULT_ELASTICSEARCH_SETTINGS = DEFAULT_DATASHARE_HOME.resolve("elasticsearch.yml").toString();
    public static final String DEFAULT_EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE = "1G";
    public static final String DEFAULT_EXTENSIONS_DIR = DEFAULT_DATASHARE_HOME.resolve("extensions").toString();
    public static final int DEFAULT_INDEX_TIMEOUT = 30;
    public static final boolean DEFAULT_FOLLOW_SYMLINKS = true;
    public static final String DEFAULT_LOG_LEVEL = Level.INFO.toString();
    public static final String DEFAULT_MESSAGE_BUS_ADDRESS = EnvUtils.resolveUri("redis", "redis://redis:6379");
    public static final String DEFAULT_NLP_PIPELINE = "CORENLP";
    public static final int DEFAULT_NLP_BATCH_SIZE = 1024;
    public static final int DEFAULT_NLP_MAX_TEXT_LENGTH = 1024;
    public static final String DEFAULT_PROTECTED_URI_PREFIX = "/api/";
    public static final String DEFAULT_QUEUE_NAME = "extract:queue";
    public static final int DEFAULT_QUEUE_CAPACITY = (int) 1e6;
    public static final String DEFAULT_REDIS_ADDRESS = EnvUtils.resolveUri("redis", "redis://redis:6379");
    public static final String DEFAULT_USER = "local";
    public static final boolean DEFAULT_BROWSER_OPEN_LINK = false;
    public static final boolean DEFAULT_NO_DIGEST_PROJECT = false;
    public static final boolean DEFAULT_OCR = true;
    public static final String DEFAULT_OCR_TIMEOUT = "12h";
    public static final String DEFAULT_PARSE_TIMEOUT = "24h";
    public static final int DEFAULT_MAX_EMBED_DEPTH = 20;
    public static final int DEFAULT_BATCH_DOWNLOAD_MAX_NB_FILES = 10000;
    public static final int DEFAULT_BATCH_DOWNLOAD_ZIP_TTL = 24;
    public static final String DEFAULT_PLUGIN_DIR = DEFAULT_DATASHARE_HOME.resolve("plugins").toString();
    public static final String DEFAULT_SCROLL_DURATION = "60000ms";
    public static final int DEFAULT_SCROLL_SIZE = 1000;
    public static final int DEFAULT_SCROLL_SLICES = 1;
    public static final int DEFAULT_TCP_LISTEN_PORT = 8080;
    public static final int DEFAULT_SESSION_TTL_SECONDS = 43200;
    public static final String DEFAULT_MAX_CONTENT_LENGTH = "20000000";
    public static final RoutingStrategy DEFAULT_TASK_ROUTING_STRATEGY = RoutingStrategy.UNIQUE;
    public static final String DEFAULT_POLLING_INTERVAL_SEC = "60";
    public static final int DEFAULT_TASK_MANAGER_POLLING_INTERVAL = 5000;
    public static final String DEFAULT_TASK_WORKERS = "1";
    public static final double DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS = 10;
    public static final String DEFAULT_TEMPORAL_NAMESPACE = "datashare-default";
    public static final String DEFAULT_TEMPORAL_ADDRESS = EnvUtils.resolveUri("temporal", "temporal:7233");
    public static final int DEFAULT_POLICY_RELOAD_INTERVAL = 30_000;
    public static final int DEFAULT_ELASTICSEARCH_MAX_IDLE_CONNECTION_TIME = 349000;

    // A list of aliases for retro-compatibility when an option changed
    public static final Map<String, String> OPT_ALIASES = Map.ofEntries(
            Map.entry(PORT_OPT, TCP_LISTEN_PORT_OPT)
    );

    static void bindHost(OptionParser parser) {
        parser.acceptsAll(
                asList(BIND_HOST_ABBR_OPT, BIND_HOST_OPT),
                "Host/IP address to bind the HTTP server to (default: localhost for LOCAL/EMBEDDED, 0.0.0.0 for SERVER)")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void stages(OptionParser parser) {
        parser.acceptsAll(
                singletonList(PipelineHelper.STAGES_OPT),
                format("Stages to be run separated by %s %s", PipelineHelper.STAGES_SEPARATOR, Arrays.toString(Stage.values())))
                .withRequiredArg()
                .ofType(String.class);
    }

    static void mode(OptionParser parser) {
        parser.acceptsAll(
                asList(MODE_ABBR_OPT, MODE_OPT),
                "Datashare run mode " + Arrays.toString(Mode.values()))
                .withRequiredArg()
                .ofType(Mode.class)
                .defaultsTo(DEFAULT_MODE);
    }

    static void charset(OptionParser parser) {
        parser.acceptsAll(
                singletonList(CHARSET_OPT),
                "Datashare default charset. Example: " +
                        Arrays.toString(new Charset[]{StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1}))
                .withRequiredArg()
                .defaultsTo(DEFAULT_CHARSET);
    }

    static void defaultUser(OptionParser parser) {
        parser.acceptsAll(
                asList(DEFAULT_USER_NAME_ABBR_OPT, DEFAULT_USER_NAME_OPT),
                "Default local user name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_USER);
    }

    static void defaultUserProjectKey(OptionParser parser) {
        parser.acceptsAll(
                        List.of(OAUTH_USER_PROJECTS_KEY_OPT),
                "Json field name sent by the Identity Provider that contains user projects.")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(User.DEFAULT_PROJECTS_KEY);
    }

    static void followSymlinks(OptionParser parser) {
        parser.acceptsAll(
                singletonList(FOLLOW_SYMLINKS_OPT), "Follow symlinks while scanning documents")
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(DEFAULT_FOLLOW_SYMLINKS);
    }

    static void cors(OptionParser parser) {
        parser.acceptsAll(
                singletonList(CORS_OPT), "CORS headers (needs the web option)")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_CORS);
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
                        .ofType(String.class)
                        .defaultsTo(DEFAULT_PLUGIN_DIR);
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
        parser.acceptsAll(singletonList(EXTENSIONS_DIR_OPT), "Extensions directory (backend)")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_EXTENSIONS_DIR);
    }

    static void tcpListenPort(OptionParser parser) {
        parser.acceptsAll(
                List.of(PORT_OPT, TCP_LISTEN_PORT_OPT), "Port used by the HTTP server")
                        .withRequiredArg()
                        .ofType(Integer.class)
                        .defaultsTo(DEFAULT_TCP_LISTEN_PORT);
    }

    static void sessionSigningKey(OptionParser parser) {
        parser.acceptsAll(
                singletonList(SESSION_SIGNING_KEY_OPT), "HMAC key used to sign session IDs (defaults to oauthClientSecret)")
                        .withRequiredArg()
                        .ofType(String.class);
    }

    static void sessionTtlSeconds(OptionParser parser) {
        parser.acceptsAll(
                singletonList(SESSION_TTL_SECONDS_OPT), "Time to live for a HTTP session in seconds")
                        .withRequiredArg()
                        .ofType(Integer.class)
                        .defaultsTo(DEFAULT_SESSION_TTL_SECONDS);
    }

    static void protectedUriPrefix(OptionParser parser) {
        parser.acceptsAll(
                singletonList(PROTECTED_URI_PREFIX_OPT), "Protected URI prefix")
                        .withRequiredArg()
                        .ofType(String.class)
                        .defaultsTo(DEFAULT_PROTECTED_URI_PREFIX);
    }

    static void resume(OptionParser parser) {
        parser.acceptsAll(asList(RESUME_ABBR_OPT, RESUME_OPT), "Resume pending operations");
    }

    static void createIndex(OptionParser parser) {
        parser.acceptsAll(singletonList(CREATE_INDEX_OPT), "creates an index with the given name")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void indexTimeout(OptionParser parser) {
        parser.acceptsAll(singletonList(INDEX_TIMEOUT_OPT), "Time to wait in minutes before consumer termination during document indexing (Default 30m)")
                .withRequiredArg()
                .withValuesConvertedBy(new PositiveIntegerConverter())
                .defaultsTo(DEFAULT_INDEX_TIMEOUT);
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

    static void grantAdminPolicy(OptionParser parser) {
        parser.acceptsAll(singletonList(GRANT_ADMIN_OPT), "Grant admin policy to user if there is none")
                .withRequiredArg()
                .ofType(String.class);
    }

    static void dataDir(OptionParser parser) {
        parser.acceptsAll(
                asList(DATA_DIR_ABBR_OPT, DATA_DIR_OPT),
                "Document source files directory" )
                .withRequiredArg()
                .ofType(File.class)
                .defaultsTo(new File(DEFAULT_DATA_DIR));
    }

    static void artifactDir(OptionParser parser) {
        parser.acceptsAll(
                List.of(ARTIFACT_DIR_OPT),
                "Artifact directory for embedded caching. If not provided datashare will use memory." )
                .withRequiredArg();
    }

    static void artifacts(OptionParser parser) {
        parser.acceptsAll(
                List.of(ARTIFACTS_OPT),
                "Artifact types to produce (comma-separated, e.g. raw,structure). Bare flag = all types." )
                .withOptionalArg();
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
                .defaultsTo(DEFAULT_MESSAGE_BUS_ADDRESS);
    }

    public static void busType(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BUS_TYPE_OPT),
                "Backend data bus type.")
                .withRequiredArg().ofType( QueueType.class )
                .defaultsTo(DEFAULT_BUS_TYPE);
    }

    public static void taskRoutingStrategy(OptionParser parser) {
        parser.acceptsAll(
                singletonList(TASK_ROUTING_STRATEGY_OPT),
                format("Task Manager routing strategy (%s).", Arrays.toString(RoutingStrategy.values())))
                .withRequiredArg().ofType( RoutingStrategy.class )
                .defaultsTo(DEFAULT_TASK_ROUTING_STRATEGY);
    }

    static void taskRoutingKey(OptionParser parser) {
        parser.acceptsAll(
                        singletonList(TASK_ROUTING_KEY_OPT),
                        "Routing key (and queue suffix) for task worker. If 'Key' is provided " +
                                "task worker will connect to the queue TASK.Key with 'Key' binding (AMQP).")
                .withRequiredArg();
    }

    static void redisAddress(OptionParser parser) {
            parser.acceptsAll(
                    singletonList(REDIS_ADDRESS_OPT),
                    "Redis queue address")
                    .withRequiredArg()
                    .defaultsTo(DEFAULT_REDIS_ADDRESS);
        }

    static void queueName(OptionParser parser) {
        parser.acceptsAll(
                        singletonList(QUEUE_NAME_OPT), "Extract queue name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_QUEUE_NAME);
    }

    static void queueType(OptionParser parser) {
            parser.acceptsAll(
                    singletonList(QUEUE_TYPE_OPT),
                    "Backend queues and sets type.")
                    .withRequiredArg().ofType(QueueType.class)
                    .defaultsTo(DEFAULT_QUEUE_TYPE);
        }
    static void queueCapacity(OptionParser parser) {
            parser.acceptsAll(
                    singletonList(QUEUE_CAPACITY_OPT),
                    "Queue capacity is the size of the internal file path buffer used by the queue.")
                    .withRequiredArg().ofType(Integer.class)
                    .withValuesConvertedBy(new PositiveIntegerConverter())
                    .defaultsTo(DEFAULT_QUEUE_CAPACITY);
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

    static void nlpBatchSize(OptionParser parser) {
        parser.acceptsAll(
                List.of(NLP_BATCH_SIZE_OPT),
                "Batch size of NLP extraction task in number of documents.")
                .withRequiredArg()
                .ofType( Integer.class )
                .defaultsTo(DEFAULT_NLP_BATCH_SIZE);
    }

    static void nlpMaxTextLength(OptionParser parser) {
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

    public static void scroll(OptionParser parser) {
        parser.acceptsAll(
                singletonList(SCROLL_DURATION_OPT), "Scroll duration used for elasticsearch scrolls (SCANIDX task)")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_SCROLL_DURATION);
    }
    public static void scrollSize(OptionParser parser) {
        parser.acceptsAll(
                singletonList(SCROLL_SIZE_OPT), "Scroll size used for elasticsearch scrolls (SCANIDX task)")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(DEFAULT_SCROLL_SIZE);
    }

     public static void scrollSlices(OptionParser parser) {
        parser.acceptsAll(
                singletonList(SCROLL_SLICES_OPT), "Scroll slice max number used for elasticsearch scrolls (SCANIDX task)")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(DEFAULT_SCROLL_SLICES);
    }

    public static void batchSearchScroll(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_SEARCH_SCROLL_DURATION_OPT), "Scroll duration used for elasticsearch scrolls (Batch Search)")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_SCROLL_DURATION);
    }

    public static void batchSearchScrollSize(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_SEARCH_SCROLL_SIZE_OPT), "Scroll size used for elasticsearch scrolls (Batch Search)")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(DEFAULT_SCROLL_SIZE);
    }

    public static void batchDownloadScroll(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_DOWNLOAD_SCROLL_DURATION_OPT), "Scroll duration used for elasticsearch scrolls (Batch Download)")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_SCROLL_DURATION);
    }

    public static void batchDownloadScrollSize(OptionParser parser) {
        parser.acceptsAll(
                singletonList(BATCH_DOWNLOAD_SCROLL_SIZE_OPT), "Scroll size used for elasticsearch scrolls (Batch Download)")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(DEFAULT_SCROLL_SIZE);
    }

     public static void redisPoolSize(OptionParser parser) {
        parser.acceptsAll(
                singletonList(REDIS_POOL_SIZE_OPT), "Pool size for the main Redis client. When unset it defaults to parallelism + " + REDIS_POOL_SIZE_OVERHEAD + " so blocking workers never starve the pool; an explicit value below that floor is raised to it.")
                .withRequiredArg()
                .ofType(Integer.class);
    }

    public static void elasticsearchDataPath(OptionParser parser) {
        parser.acceptsAll(
                singletonList(ELASTICSEARCH_DATA_PATH_OPT), "Data path used for embedded Elasticsearch")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_ELASTICSEARCH_DATA_PATH);
    }

    public static void elasticsearchPath(OptionParser parser) {
        parser.acceptsAll(
                singletonList(ELASTICSEARCH_PATH_OPT), "Path used for launching Elasticsearch (should be installed with official tar/zip).")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_ELASTICSEARCH_PATH);
    }

    public static void elasticsearchSettings(OptionParser parser) {
        parser.acceptsAll(
                singletonList(ELASTICSEARCH_SETTINGS_OPT), "Path to elasticsearch.yml settings file for embedded Elasticsearch (optional, loaded if exists)")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_ELASTICSEARCH_SETTINGS);
    }

    public static void elasticsearchMaxIdleConnectionTime(OptionParser parser) {
        parser.acceptsAll(
                singletonList(ELASTICSEARCH_MAX_IDLE_CONNECTION_TIME_OPT), "Max idling time for a TCP connection to ES to be reused")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(DEFAULT_ELASTICSEARCH_MAX_IDLE_CONNECTION_TIME);
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
                withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(DEFAULT_BROWSER_OPEN_LINK);
    }

    static void enableOcr(OptionParser parser) {
        parser.acceptsAll(
                asList(OCR_ABBR_OPT, OCR_OPT),
                "Run optical character recognition at file parsing time. " +
                        "(Tesseract must be installed beforehand).").
                withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(DEFAULT_OCR);
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

    static void ocrType(OptionParser parser) {
        parser.acceptsAll(List.of(OCR_TYPE_OPT), "OCR implementation: TESSERACT or TESS4J")
            .withRequiredArg()
            .ofType(String.class)
            .defaultsTo(DEFAULT_OCR_TYPE);
    }

    static void ocrTimeout(OptionParser parser) {
        parser.acceptsAll(List.of(OCR_TIMEOUT), "OCR timeout")
            .withRequiredArg()
            .ofType(String.class)
            .defaultsTo(DEFAULT_OCR_TIMEOUT);
    }

    static void parseTimeout(OptionParser parser) {
        parser.acceptsAll(List.of(PARSE_TIMEOUT_OPT),
                "Wall-clock timeout for a single document's parse and output, e.g. \"30m\" or \"24h\". " +
                        "Set to 0 to disable. Defaults to 24h.")
            .withRequiredArg()
            .ofType(String.class)
            .defaultsTo(DEFAULT_PARSE_TIMEOUT);
    }

    static void ocrStrategy(OptionParser parser) {
        parser.acceptsAll(
                        List.of(OCR_STRATEGY_OPT),
                        "PDF OCR strategy: NO_OCR (default), AUTO, OCR_AND_TEXT_EXTRACTION or OCR_ONLY. " +
                                "A rendering strategy (anything other than NO_OCR) OCRs whole pages, which is " +
                                "needed to correctly extract scanned/MRC PDFs. Only takes effect when OCR is enabled.")
                .withRequiredArg()
                // No defaultsTo on purpose: when unset the key must stay out of the properties so
                // extract applies its own NO_OCR default. Adding a default here would emit the key
                // for every run and silently change OCR behavior for all users.
                // Typed as the OcrStrategy enum (not String) so an unknown value fails fast with a
                // usage error, matching the picocli stage subcommand, instead of silently degrading
                // to NO_OCR inside extract. asProperties serializes it back to the enum name.
                .ofType(OcrStrategy.class);
    }

    static void maxEmbedDepth(OptionParser parser) {
        parser.acceptsAll(
                        List.of(MAX_EMBED_DEPTH_OPT),
                        MAX_EMBED_DEPTH_DESC)
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(DEFAULT_MAX_EMBED_DEPTH);
    }

    static void nlpPipeline(OptionParser parser) {
        parser.acceptsAll(
                asList(NLP_PIPELINE_ABBR_OPT, NLP_PIPELINE_OPT),
                "NLP pipeline to be run.")
                .withRequiredArg()
                .defaultsTo(DEFAULT_NLP_PIPELINE);
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
                .defaultsTo(DEFAULT_ELASTICSEARCH_ADDRESS);
    }

    static void dataSourceUrl(OptionParser parser) {
        parser.acceptsAll(
                singletonList(DATA_SOURCE_URL_OPT), "Datasource URL. For using memory you can use 'jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared'")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_DATA_SOURCE_URL);
    }

    static void defaultProject(OptionParser parser) {
        parser.acceptsAll(
                asList(DEFAULT_PROJECT_ABBR_OPT, DEFAULT_PROJECT_OPT), "Default project name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_DEFAULT_PROJECT);
    }

    static void clusterName(OptionParser parser) {
        parser.acceptsAll(
                singletonList(CLUSTER_NAME_OPT), "Cluster name")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_CLUSTER_NAME);
    }


    static OptionSpec<Void> help(OptionParser parser) {
        return parser.acceptsAll(asList(HELP_OPT, HELP_ABBR_OPT, "?")).forHelp();
    }

    static OptionSpec<Void> version(OptionParser parser) {
        return parser.acceptsAll(asList(VERSION_ABBR_OPT, VERSION_OPT));
    }

    static void authUsersProvider(OptionParser parser) {
        parser.acceptsAll(
                singletonList(AUTH_USERS_PROVIDER_OPT), "Server mode users provider: database, redis, or a fully-qualified class name (default: database)")
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
                .withRequiredArg()
                .ofType( QueueType.class )
                .defaultsTo(DEFAULT_BATCH_QUEUE_TYPE);
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
                singletonList(BATCH_DOWNLOAD_DIR_OPT), "Directory where Batch Download archives are downloaded.")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_BATCH_DOWNLOAD_DIR)
                .withValuesConvertedBy(DatashareCliOptions.toAbsolute());
    }

    public static void maxContentLength(OptionParser parser) {
        parser.acceptsAll(
                singletonList(MAX_CONTENT_LENGTH_OPT), "Maximum length (in bytes) of extracted text that could be indexed " +
                        "(-1 means no limit and value should be less or equal than 2G). Human readable suffix K/M/G for KB/MB/GB (Default 20M)")
                .withRequiredArg()
                .defaultsTo(DEFAULT_MAX_CONTENT_LENGTH)
                .withValuesConvertedBy(regex("[0-9]+[KMG]?"));
    }

    public static void sessionStoreType(OptionParser parser) {
        parser.acceptsAll(
                singletonList(SESSION_STORE_TYPE_OPT), "Type of session store")
                .withRequiredArg()
                .ofType(QueueType.class)
                .defaultsTo(DEFAULT_SESSION_STORE_TYPE);
    }

    public static void statusAllowedNets(OptionParser parser) {
        parser.acceptsAll(
                singletonList(STATUS_ALLOWED_NETS_OPT), "Comma-separated CIDR list for unauthenticated access to /api/status")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_STATUS_ALLOWED_NETS);
    }

    public static void digestMethod(OptionParser parser) {
        parser.acceptsAll(
                singletonList(DIGEST_ALGORITHM_OPT))
                .withRequiredArg()
                .ofType(DigestAlgorithm.class)
                .defaultsTo(DEFAULT_DIGEST_METHOD)
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
                .defaultsTo(DEFAULT_NO_DIGEST_PROJECT);
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
                .defaultsTo(DEFAULT_LOG_LEVEL);
    }

    public static void oauthClaimIdAttribute(OptionParser parser) {
        parser.acceptsAll(singletonList("oauthClaimIdAttribute"), "Json field name sent by the Identity Provider that contains user identifier value.")
                .withRequiredArg()
                .ofType(String.class);
    }

    public static void searchQuery(OptionParser parser) {
        parser.acceptsAll(singletonList(SEARCH_QUERY_OPT), "Json query for filtering index matches for EnqueueFromIndex task.")
                .withRequiredArg()
                .ofType(String.class);
    }

    public static void pollingInterval(OptionParser parser) {
        parser.acceptsAll(singletonList(POLLING_INTERVAL_SECONDS_OPT), "Queue polling interval.")
                .withRequiredArg()
                .ofType(String.class).defaultsTo(DEFAULT_POLLING_INTERVAL_SEC);
    }

    public static void taskRepositoryType(OptionParser parser) {
        parser.acceptsAll(
                        singletonList(TASK_REPOSITORY_OPT), format("type of task repository (%s)", Arrays.toString(TaskRepositoryType.values())))
                .withRequiredArg()
                .ofType( TaskRepositoryType.class )
                .defaultsTo(TaskRepositoryType.DATABASE);
    }

    static void taskManagerPollingInterval(OptionParser parser) {
        parser.acceptsAll(
                        singletonList(TASK_MANAGER_POLLING_INTERVAL_OPT), "Time to wait for task manager polling in milliseconds.")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(DEFAULT_TASK_MANAGER_POLLING_INTERVAL);
    }

    static void policyReloadInterval(OptionParser parser) {
        parser.acceptsAll(
                        singletonList(POLICY_RELOAD_INTERVAL_OPT), "Interval in milliseconds to reload Casbin policies from DB. In non-Redis mode defaults to 30s; in Redis mode defaults to 0 (event-driven only). Set to 0 to disable.")
                .withRequiredArg()
                .ofType(Integer.class);
    }

    static void taskProgressUpdateInterval(OptionParser parser) {
        parser.acceptsAll(
                        singletonList(TASK_PROGRESS_INTERVAL_OPT), "Minimum interval between progress updates for the same task in seconds.")
                .withRequiredArg()
                .ofType(Double.class)
                .defaultsTo(DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS);
    }

    static void taskWorkers(OptionParser parser) {
        parser.acceptsAll(
                        singletonList(TASK_WORKERS_OPT),
                        "Number of task workers (threads).")
                .withRequiredArg()
                .ofType( String.class )
                .defaultsTo(DEFAULT_TASK_WORKERS);
    }

    static void temporalNamespaceOpt(OptionParser parser) {
        parser.acceptsAll(
                singletonList(TEMPORAL_NAMESPACE_OPT), "temporal namespace")
                .withRequiredArg()
                .ofType( String.class )
                .defaultsTo(DEFAULT_TEMPORAL_NAMESPACE);
    }

    static void temporalAddressOpt(OptionParser parser) {
        parser.acceptsAll(
                List.of(TEMPORAL_ADDRESS_OPT), "Temporal address")
                .withRequiredArg()
                .ofType( String.class )
                .defaultsTo(DEFAULT_TEMPORAL_ADDRESS);
    }

    public static ValueConverter<String> toAbsolute() {
        return new ValueConverter<>() {
            @Override
            public String convert(String value) {
                Path path = Paths.get(value);
                Path relativeTo = Path.of(System.getProperty("user.dir"));
                return path.isAbsolute() ? value : relativeTo.resolve(path).normalize().toAbsolutePath().toString();
            }

            @Override
            public Class<? extends String> valueType() {
                return String.class;
            }

            @Override
            public String valuePattern() {
                return null;
            }
        };
    }
}
