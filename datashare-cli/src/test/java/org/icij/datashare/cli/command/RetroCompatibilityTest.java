package org.icij.datashare.cli.command;

import org.icij.datashare.cli.DatashareCli;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import picocli.CommandLine;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;

/**
 * These tests verify that the new picocli subcommand path produces
 * the same key properties as the legacy jopt-simple path for equivalent
 * invocations.
 */
public class RetroCompatibilityTest {

    @Before
    public void setUp() {
        System.setProperty("user.home", "/home/datashare");
    }

    @After
    public void tearDown() {
        System.clearProperty("user.home");
    }

    private Properties parseLegacy(String... args) {
        DatashareCli cli = new DatashareCli();
        cli.parseArguments(args);
        return cli.properties;
    }

    private Properties parseNew(String... args) {
        DatashareCommand cmd = new DatashareCommand();
        CommandLine commandLine = new CommandLine(cmd);
        commandLine.setOverwrittenOptionsAllowed(true);
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setExecutionStrategy(parseResult -> {
            CommandLine.ParseResult sub = parseResult;
            while (sub.hasSubcommand()) {
                sub = sub.subcommand();
            }
            Object userObject = sub.commandSpec().userObject();
            if (userObject instanceof DatashareSubcommand) {
                cmd.setExecutedSubcommand((DatashareSubcommand) userObject);
            }
            return new CommandLine.RunLast().execute(parseResult);
        });
        commandLine.execute(args);
        return cmd.collectProperties();
    }

    /**
     * Verifies that two property sets agree on a specific key.
     */
    private void assertPropertyEqual(Properties legacy, Properties picocli, String key) {
        assertThat(picocli.getProperty(key))
                .as("Property '" + key + "' should be the same in both paths")
                .isEqualTo(legacy.getProperty(key));
    }

    @Test
    public void test_parity_mode_local() {
        Properties legacy = parseLegacy("--mode=LOCAL");
        Properties picocli = parseNew("app", "start", "--mode", "local");
        assertPropertyEqual(legacy, picocli, "mode");
    }

    @Test
    public void test_parity_mode_server() {
        Properties legacy = parseLegacy("--mode=SERVER");
        Properties picocli = parseNew("app", "start", "--mode", "server");
        assertPropertyEqual(legacy, picocli, "mode");
    }

    @Test
    public void test_parity_mode_embedded() {
        Properties legacy = parseLegacy("--mode=EMBEDDED");
        Properties picocli = parseNew("app", "start", "--mode", "embedded");
        assertPropertyEqual(legacy, picocli, "mode");
    }

    @Test
    public void test_parity_mode_ner() {
        Properties legacy = parseLegacy("--mode=NER");
        Properties picocli = parseNew("app", "start", "--mode", "ner");
        assertPropertyEqual(legacy, picocli, "mode");
    }

    @Test
    public void test_parity_mode_task_worker() {
        Properties legacy = parseLegacy("--mode=TASK_WORKER");
        Properties picocli = parseNew("worker", "run");
        assertPropertyEqual(legacy, picocli, "mode");
    }

    @Test
    public void test_parity_mode_cli_with_stages() {
        Properties legacy = parseLegacy("--mode=CLI", "--stages=SCAN,INDEX");
        Properties picocli = parseNew("stage", "run", "--stages", "SCAN,INDEX");
        assertPropertyEqual(legacy, picocli, "mode");
        assertPropertyEqual(legacy, picocli, "stages");
    }

    @Test
    public void test_parity_defaults() {
        Properties legacy = parseLegacy("");
        Properties picocli = parseNew("app", "start");

        // Critical defaults that must match
        assertPropertyEqual(legacy, picocli, "mode");
        assertPropertyEqual(legacy, picocli, "defaultProject");
        assertPropertyEqual(legacy, picocli, "tcpListenPort");
        assertPropertyEqual(legacy, picocli, "logLevel");
        assertPropertyEqual(legacy, picocli, "cors");
        assertPropertyEqual(legacy, picocli, "dataDir");
        assertPropertyEqual(legacy, picocli, "defaultUserName");
        assertPropertyEqual(legacy, picocli, "digestProjectName");
        assertPropertyEqual(legacy, picocli, "noDigestProject");
        assertPropertyEqual(legacy, picocli, "queueCapacity");
        assertPropertyEqual(legacy, picocli, "indexTimeout");
        assertPropertyEqual(legacy, picocli, "nlpPipeline");
        assertPropertyEqual(legacy, picocli, "ocr");
        assertPropertyEqual(legacy, picocli, "followSymlinks");
        assertPropertyEqual(legacy, picocli, "browserOpenLink");
        assertPropertyEqual(legacy, picocli, "sessionTtlSeconds");
        assertPropertyEqual(legacy, picocli, "redisPoolSize");
        assertPropertyEqual(legacy, picocli, "scrollSize");
        assertPropertyEqual(legacy, picocli, "scrollSlices");
        assertPropertyEqual(legacy, picocli, "taskRoutingStrategy");
        assertPropertyEqual(legacy, picocli, "taskRepositoryType");
        assertPropertyEqual(legacy, picocli, "taskWorkers");
    }

    @Test
    public void test_parity_data_dir_default() {
        Properties legacy = parseLegacy("");
        Properties picocli = parseNew("app", "start");
        assertPropertyEqual(legacy, picocli, "dataDir");
    }

    @Test
    public void test_parity_elasticsearch_data_path_default() {
        Properties legacy = parseLegacy("");
        Properties picocli = parseNew("app", "start");
        assertPropertyEqual(legacy, picocli, "elasticsearchDataPath");
    }

    @Test
    public void test_parity_extensions_dir_default() {
        Properties legacy = parseLegacy("");
        Properties picocli = parseNew("app", "start");
        assertPropertyEqual(legacy, picocli, "extensionsDir");
    }

    @Test
    public void test_parity_plugins_dir_default() {
        Properties legacy = parseLegacy("");
        Properties picocli = parseNew("app", "start");
        assertPropertyEqual(legacy, picocli, "pluginsDir");
    }

    @Test
    public void test_parity_port_alias() {
        Properties legacy = parseLegacy("--port=7777");
        Properties picocli = parseNew("app", "start", "--port=7777");
        assertPropertyEqual(legacy, picocli, "tcpListenPort");
        // Both should NOT have "port" as a key
        assertThat(legacy.getProperty("port")).isNull();
        assertThat(picocli.getProperty("port")).isNull();
    }

    @Test
    public void test_parity_digest_project_name_default() {
        Properties legacy = parseLegacy("");
        Properties picocli = parseNew("app", "start");
        assertPropertyEqual(legacy, picocli, "digestProjectName");
    }

    @Test
    public void test_parity_digest_project_name_custom() {
        Properties legacy = parseLegacy("--defaultProject=myproject");
        Properties picocli = parseNew("--defaultProject=myproject", "app", "start");
        assertPropertyEqual(legacy, picocli, "digestProjectName");
    }

    @Test
    public void test_parity_no_digest_project() {
        Properties legacy = parseLegacy("--noDigestProject", "true");
        Properties picocli = parseNew("--noDigestProject=true", "app", "start");
        assertPropertyEqual(legacy, picocli, "noDigestProject");
        assertThat(legacy.getProperty("digestProjectName")).isNull();
        assertThat(picocli.getProperty("digestProjectName")).isNull();
    }

    @Test
    public void test_parity_digest_project_name_explicit() {
        Properties legacy = parseLegacy("--digestProjectName", "foo");
        Properties picocli = parseNew("--digestProjectName", "foo", "app", "start");
        assertPropertyEqual(legacy, picocli, "digestProjectName");
    }

    @Test
    public void test_parity_custom_bind() {
        Properties legacy = parseLegacy("--bind", "0.0.0.0");
        Properties picocli = parseNew("app", "start", "--bind", "0.0.0.0");
        assertPropertyEqual(legacy, picocli, "bind");
    }

    @Test
    public void test_parity_custom_bind_short() {
        Properties legacy = parseLegacy("-b", "0.0.0.0");
        Properties picocli = parseNew("app", "start", "-b", "0.0.0.0");
        assertPropertyEqual(legacy, picocli, "bind");
    }

    @Test
    public void test_parity_custom_elasticsearch_address() {
        Properties legacy = parseLegacy("--elasticsearchAddress", "http://localhost:9200");
        Properties picocli = parseNew("--elasticsearchAddress", "http://localhost:9200", "app", "start");
        assertPropertyEqual(legacy, picocli, "elasticsearchAddress");
    }

    @Test
    public void test_parity_custom_data_dir() {
        Properties legacy = parseLegacy("-d", "/data/docs");
        Properties picocli = parseNew("-d", "/data/docs", "app", "start");
        assertPropertyEqual(legacy, picocli, "dataDir");
    }

    @Test
    public void test_parity_batch_download_dir_relative() {
        Properties legacy = parseLegacy("--batchDownloadDir", "foo");
        Properties picocli = parseNew("app", "start", "--batchDownloadDir", "foo");
        assertPropertyEqual(legacy, picocli, "batchDownloadDir");
    }

    @Test
    public void test_parity_batch_download_dir_absolute() {
        Properties legacy = parseLegacy("--batchDownloadDir", "/home/foo");
        Properties picocli = parseNew("app", "start", "--batchDownloadDir", "/home/foo");
        assertPropertyEqual(legacy, picocli, "batchDownloadDir");
    }

    @Test
    public void test_parity_language() {
        Properties legacy = parseLegacy("--language", "ENGLISH");
        Properties picocli = parseNew("--language", "ENGLISH", "app", "start");
        assertPropertyEqual(legacy, picocli, "language");
    }

    @Test
    public void test_parity_no_language() {
        Properties legacy = parseLegacy("");
        Properties picocli = parseNew("app", "start");
        assertThat(legacy.getProperty("language")).isNull();
        assertThat(picocli.getProperty("language")).isNull();
    }

    @Test
    public void test_parity_plugin_list() {
        Properties legacy = parseLegacy("--pluginList");
        Properties picocli = parseNew("plugin", "list");
        assertPropertyEqual(legacy, picocli, "pluginList");
    }

    @Test
    public void test_parity_plugin_list_with_filter() {
        Properties legacy = parseLegacy("--pluginList=.*");
        Properties picocli = parseNew("plugin", "list", ".*");
        assertPropertyEqual(legacy, picocli, "pluginList");
    }

    @Test
    public void test_parity_plugin_install() {
        Properties legacy = parseLegacy("--pluginInstall", "my-plugin");
        Properties picocli = parseNew("plugin", "install", "my-plugin");
        assertPropertyEqual(legacy, picocli, "pluginInstall");
    }

    @Test
    public void test_parity_plugin_delete() {
        Properties legacy = parseLegacy("--pluginDelete", "my-plugin");
        Properties picocli = parseNew("plugin", "delete", "my-plugin");
        assertPropertyEqual(legacy, picocli, "pluginDelete");
    }

    @Test
    public void test_parity_extension_list() {
        Properties legacy = parseLegacy("--extensionList");
        Properties picocli = parseNew("extension", "list");
        assertPropertyEqual(legacy, picocli, "extensionList");
    }

    @Test
    public void test_parity_extension_install() {
        Properties legacy = parseLegacy("--extensionInstall", "my-ext");
        Properties picocli = parseNew("extension", "install", "my-ext");
        assertPropertyEqual(legacy, picocli, "extensionInstall");
    }

    @Test
    public void test_parity_extension_delete() {
        Properties legacy = parseLegacy("--extensionDelete", "my-ext");
        Properties picocli = parseNew("extension", "delete", "my-ext");
        assertPropertyEqual(legacy, picocli, "extensionDelete");
    }

    @Test
    public void test_parity_create_api_key() {
        Properties legacy = parseLegacy("--createApiKey", "alice");
        Properties picocli = parseNew("api-key", "create", "alice");
        assertPropertyEqual(legacy, picocli, "createApiKey");
    }

    @Test
    public void test_parity_create_api_key_short() {
        Properties legacy = parseLegacy("-k", "alice");
        Properties picocli = parseNew("api-key", "create", "alice");
        assertPropertyEqual(legacy, picocli, "createApiKey");
    }

    @Test
    public void test_parity_get_api_key() {
        Properties legacy = parseLegacy("--apiKey", "alice");
        Properties picocli = parseNew("api-key", "get", "alice");
        assertPropertyEqual(legacy, picocli, "apiKey");
    }

    @Test
    public void test_parity_delete_api_key() {
        Properties legacy = parseLegacy("--deleteApiKey", "alice");
        Properties picocli = parseNew("api-key", "delete", "alice");
        assertPropertyEqual(legacy, picocli, "deleteApiKey");
    }

    @Test
    public void test_parity_batch_download_max_size() {
        Properties legacy = parseLegacy("--batchDownloadMaxSize", "500M");
        Properties picocli = parseNew("app", "start", "--batchDownloadMaxSize", "500M");
        assertPropertyEqual(legacy, picocli, "batchDownloadMaxSize");
    }

    @Test
    public void test_parity_batch_download_max_nb_files() {
        Properties legacy = parseLegacy("--batchDownloadMaxNbFiles", "50");
        Properties picocli = parseNew("app", "start", "--batchDownloadMaxNbFiles", "50");
        assertPropertyEqual(legacy, picocli, "batchDownloadMaxNbFiles");
    }

    @Test
    public void test_parity_batch_search_max_time_seconds() {
        Properties legacy = parseLegacy("--batchSearchMaxTimeSeconds", "120");
        Properties picocli = parseNew("app", "start", "--batchSearchMaxTimeSeconds", "120");
        assertPropertyEqual(legacy, picocli, "batchSearchMaxTimeSeconds");
    }

    @Test
    public void test_parity_ocr_language() {
        Properties legacy = parseLegacy("--ocrLanguage", "fra");
        Properties picocli = parseNew("app", "start", "--ocrLanguage", "fra");
        assertPropertyEqual(legacy, picocli, "ocrLanguage");
    }

    @Test
    public void test_parity_nlp_parallelism() {
        Properties legacy = parseLegacy("--nlpParallelism", "4", "--stages", "SCAN,INDEX,NLP");
        Properties picocli = parseNew("stage", "run", "--stages", "SCAN,INDEX,NLP", "--nlpParallelism", "4");
        assertPropertyEqual(legacy, picocli, "nlpParallelism");
    }

    @Test
    public void test_parity_task_routing_key() {
        Properties legacy = parseLegacy("--taskRoutingKey", "mykey");
        Properties picocli = parseNew("app", "start", "--taskRoutingKey", "mykey");
        assertPropertyEqual(legacy, picocli, "taskRoutingKey");
    }

    @Test
    public void test_legacy_mode_default() {
        Properties props = parseLegacy("");
        assertThat(props.getProperty("mode")).isEqualTo("LOCAL");
    }

    @Test
    public void test_legacy_mode_server() {
        Properties props = parseLegacy("--mode=SERVER");
        assertThat(props.getProperty("mode")).isEqualTo("SERVER");
    }

    @Test
    public void test_legacy_stages() {
        Properties props = parseLegacy("--stages=SCAN,INDEX,CATEGORIZE,NLP");
        assertThat(props.getProperty("stages")).isEqualTo("SCAN,INDEX,CATEGORIZE,NLP");
    }

    @Test
    public void test_legacy_override_last_option_wins() {
        Properties props = parseLegacy("--mode=SERVER", "--mode=LOCAL");
        assertThat(props.getProperty("mode")).isEqualTo("LOCAL");
    }

    @Test
    public void test_legacy_port_alias() {
        Properties props = parseLegacy("--port=7777");
        assertThat(props.getProperty("tcpListenPort")).isEqualTo("7777");
        assertThat(props.getProperty("port")).isNull();
    }

    @Test
    public void test_parity_bus_type() {
        Properties legacy = parseLegacy("--busType", "REDIS");
        Properties picocli = parseNew("app", "start", "--busType", "REDIS");
        assertPropertyEqual(legacy, picocli, "busType");
    }

    @Test
    public void test_parity_queue_type() {
        Properties legacy = parseLegacy("--queueType", "REDIS");
        Properties picocli = parseNew("app", "start", "--queueType", "REDIS");
        assertPropertyEqual(legacy, picocli, "queueType");
    }

    @Test
    public void test_parity_redis_address() {
        Properties legacy = parseLegacy("--redisAddress", "redis://my-redis:6379");
        Properties picocli = parseNew("app", "start", "--redisAddress", "redis://my-redis:6379");
        assertPropertyEqual(legacy, picocli, "redisAddress");
    }

    @Test
    public void test_parity_data_source_url() {
        Properties legacy = parseLegacy("--dataSourceUrl", "jdbc:postgresql://db/ds");
        Properties picocli = parseNew("app", "start", "--dataSourceUrl", "jdbc:postgresql://db/ds");
        assertPropertyEqual(legacy, picocli, "dataSourceUrl");
    }

    @Test
    public void test_parity_auth_filter() {
        Properties legacy = parseLegacy("--authFilter", "org.icij.datashare.session.YesCookieAuthFilter", "--mode", "SERVER");
        Properties picocli = parseNew("app", "start", "--authFilter", "org.icij.datashare.session.YesCookieAuthFilter");
        assertPropertyEqual(legacy, picocli, "authFilter");
    }

    @Test
    public void test_parity_parallelism() {
        Properties legacy = parseLegacy("--parallelism", "8", "--stages", "SCAN,INDEX");
        Properties picocli = parseNew("stage", "run", "--stages", "SCAN,INDEX", "--parallelism", "8");
        assertPropertyEqual(legacy, picocli, "parallelism");
    }
}
