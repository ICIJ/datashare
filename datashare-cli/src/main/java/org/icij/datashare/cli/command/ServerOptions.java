package org.icij.datashare.cli.command;

import org.icij.datashare.cli.QueueType;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Paths;
import java.util.Properties;

import static org.icij.datashare.PropertiesProvider.REPORT_NAME_OPT;
import static org.icij.datashare.PropertiesProvider.TCP_LISTEN_PORT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.*;

/**
 * Options specific to the app start subcommand.
 */
public class ServerOptions {

    private static String userHome() {
        return System.getProperty("user.home", "");
    }

    // HTTP server binding
    @Option(names = {"-b", "--bind"}, description = "Host/IP to bind the HTTP server to")
    String bind;

    @Option(names = {"--port", "--tcpListenPort"}, description = "HTTP server port", defaultValue = "8080")
    int tcpListenPort;

    @Option(names = {"--cors"}, description = "CORS headers", defaultValue = "no-cors")
    String cors;

    @Option(names = {"--rootHost"}, description = "Datashare host for urls")
    String rootHost;

    @Option(names = {"--maxContentLength"}, description = "Maximum content length", defaultValue = "20000000")
    String maxContentLength;

    @Option(names = {"--browserOpenLink"}, description = "Open link in default browser", defaultValue = "false")
    boolean browserOpenLink;

    @Option(names = {"--protectedUriPrefix"}, description = "Protected URI prefix", defaultValue = "/api/")
    String protectedUriPrefix;

    @Option(names = {"--statusAllowedNets"}, description = "CIDR list for unauthenticated /api/status", defaultValue = "127.0.0.0/8,::1/128")
    String statusAllowedNets;

    // OAuth / Authentication
    @Option(names = {"--oauthClientId"}, description = "OAuth2 client id")
    String oauthClientId;

    @Option(names = {"--oauthClientSecret"}, description = "OAuth2 client secret key. Prefer passing via a settings file to avoid leaking into shell history.")
    String oauthClientSecret;

    @Option(names = {"--oauthAuthorizeUrl"}, description = "OAuth2 authorize url")
    String oauthAuthorizeUrl;

    @Option(names = {"--oauthTokenUrl"}, description = "OAuth2 token url")
    String oauthTokenUrl;

    @Option(names = {"--oauthApiUrl"}, description = "OAuth2 api url")
    String oauthApiUrl;

    @Option(names = {"--oauthCallbackPath"}, description = "OAuth2 callback path")
    String oauthCallbackPath;

    @Option(names = {"--oauthDefaultProject"}, description = "Default project for OAuth2 users")
    String oauthDefaultProject;

    @Option(names = {"--oauthScope"}, description = "OAuth2 scope")
    String oauthScope;

    @Option(names = {"--oauthClaimIdAttribute"}, description = "OAuth claim id attribute")
    String oauthClaimIdAttribute;

    @Option(names = {"--authUsersProvider"}, description = "Auth users provider class")
    String authUsersProvider;

    @Option(names = {"--authFilter"}, description = "Auth filter class")
    String authFilter;

    @Option(names = {"--sessionSigningKey"}, description = "HMAC key for session signing. Prefer passing via a settings file to avoid leaking into shell history.")
    String sessionSigningKey;

    @Option(names = {"--sessionTtlSeconds"}, description = "Session TTL in seconds", defaultValue = "43200")
    int sessionTtlSeconds;

    @Option(names = {"--sessionStoreType"}, description = "Session store type", defaultValue = "MEMORY")
    QueueType sessionStoreType;

    // Batch / Download
    @Option(names = {"--batchQueueType"}, description = "Batch queue type", defaultValue = "MEMORY")
    QueueType batchQueueType;

    @Option(names = {"--batchSearchMaxTimeSeconds"}, description = "Max batch search time in seconds")
    Integer batchSearchMaxTimeSeconds;

    @Option(names = {"--batchThrottleMilliseconds"}, description = "Batch throttle in milliseconds")
    Integer batchThrottleMilliseconds;

    @Option(names = {"--batchDownloadDir"}, description = "Batch download directory")
    String batchDownloadDir = Paths.get(userHome(), ".local/share/datashare/tmp").toString();

    @Option(names = {"--batchDownloadMaxSize"}, description = "Max batch download size", defaultValue = "100M")
    String batchDownloadMaxSize;

    @Option(names = {"--batchDownloadMaxNbFiles"}, description = "Max batch download files", defaultValue = "10000")
    int batchDownloadMaxNbFiles;

    @Option(names = {"--batchDownloadEncrypt"}, description = "Encrypt batch download zips")
    Boolean batchDownloadEncrypt;

    @Option(names = {"--batchDownloadTimeToLive"}, description = "Batch download TTL in hours", defaultValue = "24")
    int batchDownloadTimeToLive;

    @Option(names = {"--embeddedDocumentDownloadMaxSize"}, description = "Max embedded document download size", defaultValue = "1G")
    String embeddedDocumentDownloadMaxSize;

    // Scroll
    @Option(names = {"--scroll"}, description = "Scroll duration for ES", defaultValue = "60000ms")
    String scroll;

    @Option(names = {"--scrollSize"}, description = "Scroll size for ES", defaultValue = "1000")
    int scrollSize;

    @Option(names = {"--scrollSlices"}, description = "Scroll slices for ES", defaultValue = "1")
    int scrollSlices;

    @Option(names = {"--batchSearchScroll"}, description = "Batch search scroll duration", defaultValue = "60000ms")
    String batchSearchScroll;

    @Option(names = {"--batchSearchScrollSize"}, description = "Batch search scroll size", defaultValue = "1000")
    int batchSearchScrollSize;

    @Option(names = {"--batchDownloadScroll"}, description = "Batch download scroll duration", defaultValue = "60000ms")
    String batchDownloadScroll;

    @Option(names = {"--batchDownloadScrollSize"}, description = "Batch download scroll size", defaultValue = "1000")
    int batchDownloadScrollSize;

    // Misc server
    @Option(names = {"--reportName"}, description = "Report map name")
    String reportName;

    @Option(names = {"--smtpUrl"}, description = "SMTP URL for sending emails")
    String smtpUrl;

    @Option(names = {"--temporalNamespace"}, description = "Temporal namespace", defaultValue = "datashare-default")
    String temporalNamespace;

    // Task management and document processing options — shared with WorkerRunCommand and StageRunCommand
    @Mixin
    WorkerOptions workerOptions = new WorkerOptions();

    @Mixin
    PipelineOptions pipelineOptions = new PipelineOptions();

    /** Converts the parsed server option fields into a Properties map for the rest of the application. */
    public Properties toProperties() {
        Properties props = new Properties();

        DatashareOptions.putIfNotNull(props, BIND_HOST_OPT, bind);
        DatashareOptions.put(props, TCP_LISTEN_PORT_OPT, tcpListenPort);
        DatashareOptions.putIfNotNull(props, CORS_OPT, cors);
        DatashareOptions.putIfNotNull(props, ROOT_HOST_OPT, rootHost);
        DatashareOptions.putIfNotNull(props, MAX_CONTENT_LENGTH_OPT, maxContentLength);
        DatashareOptions.put(props, BROWSER_OPEN_LINK_OPT, browserOpenLink);
        DatashareOptions.putIfNotNull(props, PROTECTED_URI_PREFIX_OPT, protectedUriPrefix);
        DatashareOptions.putIfNotNull(props, STATUS_ALLOWED_NETS_OPT, statusAllowedNets);

        DatashareOptions.putIfNotNull(props, OAUTH_CLIENT_ID_OPT, oauthClientId);
        DatashareOptions.putIfNotNull(props, OAUTH_CLIENT_SECRET_OPT, oauthClientSecret);
        DatashareOptions.putIfNotNull(props, OAUTH_AUTHORIZE_URL_OPT, oauthAuthorizeUrl);
        DatashareOptions.putIfNotNull(props, OAUTH_TOKEN_URL_OPT, oauthTokenUrl);
        DatashareOptions.putIfNotNull(props, OAUTH_API_URL_OPT, oauthApiUrl);
        DatashareOptions.putIfNotNull(props, OAUTH_CALLBACK_PATH_OPT, oauthCallbackPath);
        DatashareOptions.putIfNotNull(props, OAUTH_DEFAULT_PROJECT_OPT, oauthDefaultProject);
        DatashareOptions.putIfNotNull(props, OAUTH_SCOPE_OPT, oauthScope);
        DatashareOptions.putIfNotNull(props, OAUTH_CLAIM_ID_ATTRIBUTE_OPT, oauthClaimIdAttribute);
        DatashareOptions.putIfNotNull(props, AUTH_USERS_PROVIDER_OPT, authUsersProvider);
        DatashareOptions.putIfNotNull(props, AUTH_FILTER_OPT, authFilter);
        DatashareOptions.putIfNotNull(props, SESSION_SIGNING_KEY_OPT, sessionSigningKey);
        DatashareOptions.put(props, SESSION_TTL_SECONDS_OPT, sessionTtlSeconds);
        DatashareOptions.putIfNotNull(props, SESSION_STORE_TYPE_OPT, sessionStoreType);

        DatashareOptions.putIfNotNull(props, BATCH_QUEUE_TYPE_OPT, batchQueueType);
        DatashareOptions.putIfNotNull(props, BATCH_SEARCH_MAX_TIME_OPT, batchSearchMaxTimeSeconds);
        DatashareOptions.putIfNotNull(props, BATCH_THROTTLE_OPT, batchThrottleMilliseconds);
        DatashareOptions.putIfNotNull(props, BATCH_DOWNLOAD_DIR_OPT, resolveAbsolutePath(batchDownloadDir));
        DatashareOptions.putIfNotNull(props, BATCH_DOWNLOAD_MAX_SIZE_OPT, batchDownloadMaxSize);
        DatashareOptions.put(props, BATCH_DOWNLOAD_MAX_NB_FILES_OPT, batchDownloadMaxNbFiles);
        DatashareOptions.putIfNotNull(props, BATCH_DOWNLOAD_ENCRYPT_OPT, batchDownloadEncrypt);
        DatashareOptions.put(props, BATCH_DOWNLOAD_ZIP_TTL_OPT, batchDownloadTimeToLive);
        DatashareOptions.putIfNotNull(props, EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT, embeddedDocumentDownloadMaxSize);

        DatashareOptions.putIfNotNull(props, SCROLL_DURATION_OPT, scroll);
        DatashareOptions.put(props, SCROLL_SIZE_OPT, scrollSize);
        DatashareOptions.put(props, SCROLL_SLICES_OPT, scrollSlices);
        DatashareOptions.putIfNotNull(props, BATCH_SEARCH_SCROLL_DURATION_OPT, batchSearchScroll);
        DatashareOptions.put(props, BATCH_SEARCH_SCROLL_SIZE_OPT, batchSearchScrollSize);
        DatashareOptions.putIfNotNull(props, BATCH_DOWNLOAD_SCROLL_DURATION_OPT, batchDownloadScroll);
        DatashareOptions.put(props, BATCH_DOWNLOAD_SCROLL_SIZE_OPT, batchDownloadScrollSize);

        DatashareOptions.putIfNotNull(props, REPORT_NAME_OPT, reportName);
        DatashareOptions.putIfNotNull(props, SMTP_URL_OPT, smtpUrl);
        DatashareOptions.putIfNotNull(props, TEMPORAL_NAMESPACE_OPT, temporalNamespace);

        DatashareOptions.putAll(props, workerOptions.toProperties());
        DatashareOptions.putAll(props, pipelineOptions.toProperties());

        return props;
    }

    private static String resolveAbsolutePath(String value) {
        if (value == null) return null;
        return Paths.get(value).toAbsolutePath().normalize().toString();
    }
}
