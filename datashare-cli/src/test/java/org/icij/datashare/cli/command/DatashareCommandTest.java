package org.icij.datashare.cli.command;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class DatashareCommandTest {

    @Before
    public void setUp() {
        System.setProperty("user.home", "/home/datashare");
    }

    @After
    public void tearDown() {
        System.clearProperty("user.home");
    }

    private Properties parse(String... args) {
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

    private int parseExitCode(String... args) {
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
        return commandLine.execute(args);
    }

    @Test
    public void test_app_serve_defaults_to_local() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("mode", "LOCAL"));
    }

    @Test
    public void test_app_serve_server() {
        Properties props = parse("app", "start", "--mode", "server");
        assertThat(props).includes(entry("mode", "SERVER"));
    }

    @Test
    public void test_app_serve_embedded() {
        Properties props = parse("app", "start", "--mode", "embedded");
        assertThat(props).includes(entry("mode", "EMBEDDED"));
    }

    @Test
    public void test_app_serve_ner() {
        Properties props = parse("app", "start", "--mode", "ner");
        assertThat(props).includes(entry("mode", "NER"));
    }

    @Test
    public void test_app_serve_case_insensitive_uppercase() {
        Properties props = parse("app", "start", "--mode", "SERVER");
        assertThat(props).includes(entry("mode", "SERVER"));
    }

    @Test
    public void test_app_serve_case_insensitive_mixed() {
        Properties props = parse("app", "start", "--mode", "Local");
        assertThat(props).includes(entry("mode", "LOCAL"));
    }

    @Test
    public void test_app_serve_invalid_mode_rejected_by_parser() {
        // picocli rejects unknown enum values at parse time — expect non-zero exit
        int exitCode = parseExitCode("app", "start", "--mode", "INVALID");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_worker_run() {
        Properties props = parse("worker", "run");
        assertThat(props).includes(entry("mode", "TASK_WORKER"));
    }

    @Test
    public void test_worker_run_with_shared_options() {
        Properties props = parse("--redisAddress", "redis://my-redis:6379", "worker", "run");
        assertThat(props).includes(entry("mode", "TASK_WORKER"));
        assertThat(props).includes(entry("redisAddress", "redis://my-redis:6379"));
    }

    @Test
    public void test_worker_run_with_task_workers() {
        Properties props = parse("worker", "run", "--taskWorkers", "4");
        assertThat(props).includes(entry("mode", "TASK_WORKER"));
        assertThat(props).includes(entry("taskWorkers", "4"));
    }

    @Test
    public void test_stage_run_scan_index() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("stages", "SCAN,INDEX"));
    }

    @Test
    public void test_stage_run_single_stage() {
        Properties props = parse("stage", "run", "--stages", "SCAN");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("stages", "SCAN"));
    }

    @Test
    public void test_stage_run_all_main_stages() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX,NLP");
        assertThat(props).includes(entry("stages", "SCAN,INDEX,NLP"));
    }

    @Test
    public void test_stage_run_missing_stages_fails() {
        int exitCode = parseExitCode("stage", "run");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_stage_run_with_data_dir() {
        Properties props = parse("--dataDir", "/data/docs", "stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("stages", "SCAN,INDEX"));
        assertThat(props).includes(entry("dataDir", "/data/docs"));
    }

    @Test
    public void test_plugin_list_no_filter() {
        Properties props = parse("plugin", "list");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("pluginList", "true"));
    }

    @Test
    public void test_plugin_list_with_filter() {
        Properties props = parse("plugin", "list", ".*foo.*");
        assertThat(props).includes(entry("pluginList", ".*foo.*"));
    }

    @Test
    public void test_plugin_install_by_id() {
        Properties props = parse("plugin", "install", "my-plugin");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("pluginInstall", "my-plugin"));
    }

    @Test
    public void test_plugin_install_by_url() {
        Properties props = parse("plugin", "install", "https://example.com/plugin.jar");
        assertThat(props).includes(entry("pluginInstall", "https://example.com/plugin.jar"));
    }

    @Test
    public void test_plugin_install_by_path() {
        Properties props = parse("plugin", "install", "/opt/plugins/my-plugin.jar");
        assertThat(props).includes(entry("pluginInstall", "/opt/plugins/my-plugin.jar"));
    }

    @Test
    public void test_plugin_install_missing_id_fails() {
        int exitCode = parseExitCode("plugin", "install");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_plugin_delete() {
        Properties props = parse("plugin", "delete", "my-plugin");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("pluginDelete", "my-plugin"));
    }

    @Test
    public void test_plugin_delete_missing_id_fails() {
        int exitCode = parseExitCode("plugin", "delete");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_plugin_list_with_custom_plugins_dir() {
        Properties props = parse("--pluginsDir", "/custom/plugins", "plugin", "list");
        assertThat(props).includes(entry("pluginsDir", "/custom/plugins"));
        assertThat(props).includes(entry("pluginList", "true"));
    }

    @Test
    public void test_extension_list_no_filter() {
        Properties props = parse("extension", "list");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("extensionList", "true"));
    }

    @Test
    public void test_extension_list_with_filter() {
        Properties props = parse("extension", "list", ".*nlp.*");
        assertThat(props).includes(entry("extensionList", ".*nlp.*"));
    }

    @Test
    public void test_extension_install_by_id() {
        Properties props = parse("extension", "install", "my-ext");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("extensionInstall", "my-ext"));
    }

    @Test
    public void test_extension_install_by_url() {
        Properties props = parse("extension", "install", "https://example.com/ext.jar");
        assertThat(props).includes(entry("extensionInstall", "https://example.com/ext.jar"));
    }

    @Test
    public void test_extension_install_missing_id_fails() {
        int exitCode = parseExitCode("extension", "install");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_extension_delete() {
        Properties props = parse("extension", "delete", "my-ext");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("extensionDelete", "my-ext"));
    }

    @Test
    public void test_extension_delete_missing_id_fails() {
        int exitCode = parseExitCode("extension", "delete");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_extension_list_with_custom_extensions_dir() {
        Properties props = parse("--extensionsDir", "/custom/ext", "extension", "list");
        assertThat(props).includes(entry("extensionsDir", "/custom/ext"));
        assertThat(props).includes(entry("extensionList", "true"));
    }

    @Test
    public void test_api_key_create() {
        Properties props = parse("api-key", "create", "alice");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("createApiKey", "alice"));
    }

    @Test
    public void test_api_key_get() {
        Properties props = parse("api-key", "get", "alice");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("apiKey", "alice"));
    }

    @Test
    public void test_api_key_delete() {
        Properties props = parse("api-key", "delete", "alice");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("deleteApiKey", "alice"));
    }

    @Test
    public void test_api_key_create_missing_user_fails() {
        int exitCode = parseExitCode("api-key", "create");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_api_key_get_missing_user_fails() {
        int exitCode = parseExitCode("api-key", "get");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_api_key_delete_missing_user_fails() {
        int exitCode = parseExitCode("api-key", "delete");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_default_mode_is_local() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("mode", "LOCAL"));
    }

    @Test
    public void test_default_project() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("defaultProject", "local-datashare"));
    }

    @Test
    public void test_default_port() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("tcpListenPort", "8080"));
    }

    @Test
    public void test_custom_port() {
        Properties props = parse("app", "start", "--tcpListenPort=7777");
        assertThat(props).includes(entry("tcpListenPort", "7777"));
    }

    @Test
    public void test_port_alias() {
        Properties props = parse("app", "start", "--port=7777");
        // port should be aliased to tcpListenPort
        assertThat(props).includes(entry("tcpListenPort", "7777"));
    }

    @Test
    public void test_default_log_level() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("logLevel", "INFO"));
    }

    @Test
    public void test_custom_log_level() {
        Properties props = parse("--logLevel", "DEBUG", "app", "start");
        assertThat(props).includes(entry("logLevel", "DEBUG"));
    }

    @Test
    public void test_default_cors() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("cors", "no-cors"));
    }

    @Test
    public void test_data_dir_default() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("dataDir", "/home/datashare/Datashare"));
    }

    @Test
    public void test_data_dir_custom() {
        Properties props = parse("--dataDir", "/my/docs", "app", "start");
        assertThat(props).includes(entry("dataDir", "/my/docs"));
    }

    @Test
    public void test_data_dir_short_opt() {
        Properties props = parse("-d", "/my/docs", "app", "start");
        assertThat(props).includes(entry("dataDir", "/my/docs"));
    }

    @Test
    public void test_elasticsearch_data_path_default() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("elasticsearchDataPath", "/home/datashare/.local/share/datashare/es"));
    }

    @Test
    public void test_extensions_dir_default() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("extensionsDir", "/home/datashare/.local/share/datashare/extensions"));
    }

    @Test
    public void test_plugins_dir_default() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("pluginsDir", "/home/datashare/.local/share/datashare/plugins"));
    }

    @Test
    public void test_bind_host_opt() {
        Properties props = parse("app", "start", "--bind", "127.0.0.1");
        assertThat(props).includes(entry("bind", "127.0.0.1"));
    }

    @Test
    public void test_bind_short_opt() {
        Properties props = parse("app", "start", "-b", "0.0.0.0");
        assertThat(props).includes(entry("bind", "0.0.0.0"));
    }

    @Test
    public void test_bind_not_set_by_default() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("bind")).isNull();
    }

    @Test
    public void test_queue_capacity_default() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("queueCapacity", "1000000"));
    }

    @Test
    public void test_queue_capacity_custom() {
        Properties props = parse("--queueCapacity", "10", "app", "start");
        assertThat(props).includes(entry("queueCapacity", "10"));
    }

    @Test
    public void test_index_timeout_default() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("indexTimeout", "30"));
    }

    @Test
    public void test_index_timeout_custom() {
        Properties props = parse("app", "start", "--indexTimeout", "10");
        assertThat(props).includes(entry("indexTimeout", "10"));
    }

    @Test
    public void test_no_default_language_value() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("language")).isNull();
    }

    @Test
    public void test_custom_language() {
        // language is a global option — can be passed before the subcommand
        Properties props = parse("--language", "ENGLISH", "app", "start");
        assertThat(props).includes(entry("language", "ENGLISH"));
    }

    @Test
    public void test_default_user_name() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("defaultUserName", "local"));
    }

    @Test
    public void test_custom_user_name_short() {
        Properties props = parse("-u", "admin", "app", "start");
        assertThat(props).includes(entry("defaultUserName", "admin"));
    }

    @Test
    public void test_batch_download_max_size() {
        Properties props = parse("app", "start", "--batchDownloadMaxSize", "123");
        assertThat(props).includes(entry("batchDownloadMaxSize", "123"));
    }

    @Test
    public void test_batch_download_max_size_with_suffix() {
        Properties props = parse("app", "start", "--batchDownloadMaxSize", "123G");
        assertThat(props).includes(entry("batchDownloadMaxSize", "123G"));
    }

    @Test
    public void test_embedded_document_download_max_size() {
        Properties props = parse("app", "start", "--embeddedDocumentDownloadMaxSize", "500M");
        assertThat(props).includes(entry("embeddedDocumentDownloadMaxSize", "500M"));
    }

    @Test
    public void test_relative_batch_download_dir() {
        Properties props = parse("app", "start", "--batchDownloadDir", "foo");
        Path userDir = Path.of(System.getProperty("user.dir"));
        assertThat(props).includes(entry("batchDownloadDir", userDir.resolve("foo").toString()));
    }

    @Test
    public void test_absolute_batch_download_dir() {
        Properties props = parse("app", "start", "--batchDownloadDir", "/home/foo");
        assertThat(props).includes(entry("batchDownloadDir", "/home/foo"));
    }

    @Test
    public void test_default_ocr_enabled() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("ocr", "true"));
    }

    @Test
    public void test_ocr_disabled() {
        Properties props = parse("app", "start", "--ocr=false");
        assertThat(props).includes(entry("ocr", "false"));
    }

    @Test
    public void test_ocr_short_opt() {
        Properties props = parse("app", "start", "-o=false");
        assertThat(props).includes(entry("ocr", "false"));
    }

    @Test
    public void test_session_ttl() {
        Properties props = parse("app", "start", "--sessionTtlSeconds", "3600");
        assertThat(props).includes(entry("sessionTtlSeconds", "3600"));
    }

    @Test
    public void test_session_ttl_default() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("sessionTtlSeconds", "43200"));
    }

    @Test
    public void test_redis_address() {
        Properties props = parse("--redisAddress", "redis://localhost:6379", "app", "start");
        assertThat(props).includes(entry("redisAddress", "redis://localhost:6379"));
    }

    @Test
    public void test_elasticsearch_address() {
        Properties props = parse("--elasticsearchAddress", "http://localhost:9200", "app", "start");
        assertThat(props).includes(entry("elasticsearchAddress", "http://localhost:9200"));
    }

    @Test
    public void test_data_source_url() {
        Properties props = parse("--dataSourceUrl", "jdbc:postgresql://localhost/ds", "app", "start");
        assertThat(props).includes(entry("dataSourceUrl", "jdbc:postgresql://localhost/ds"));
    }

    @Test
    public void test_task_routing_strategy() {
        Properties props = parse("app", "start", "--taskRoutingStrategy", "GROUP");
        assertThat(props).includes(entry("taskRoutingStrategy", "GROUP"));
    }

    @Test
    public void test_task_routing_strategy_default() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("taskRoutingStrategy", "UNIQUE"));
    }

    @Test
    public void test_task_repository_type_default() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("taskRepositoryType", "DATABASE"));
    }

    @Test
    public void test_bus_type_default() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("busType", "MEMORY"));
    }

    @Test
    public void test_follow_symlinks_default() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("followSymlinks", "true"));
    }

    @Test
    public void test_follow_symlinks_disabled() {
        Properties props = parse("app", "start", "--followSymlinks=false");
        assertThat(props).includes(entry("followSymlinks", "false"));
    }

    @Test
    public void test_oauth_options() {
        Properties props = parse("app", "start", "--mode", "server",
                "--oauthClientId", "myId",
                "--oauthClientSecret", "mySecret",
                "--oauthAuthorizeUrl", "https://auth.example.com/authorize",
                "--oauthTokenUrl", "https://auth.example.com/token",
                "--oauthApiUrl", "https://api.example.com");
        assertThat(props).includes(entry("oauthClientId", "myId"));
        assertThat(props).includes(entry("oauthClientSecret", "mySecret"));
        assertThat(props).includes(entry("oauthAuthorizeUrl", "https://auth.example.com/authorize"));
        assertThat(props).includes(entry("oauthTokenUrl", "https://auth.example.com/token"));
        assertThat(props).includes(entry("oauthApiUrl", "https://api.example.com"));
        assertThat(props).includes(entry("mode", "SERVER"));
    }

    @Test
    public void test_auth_filter() {
        Properties props = parse("app", "start", "--mode", "server", "--authFilter", "org.icij.BasicAuthFilter");
        assertThat(props).includes(entry("authFilter", "org.icij.BasicAuthFilter"));
    }

    @Test
    public void test_nlp_pipeline() {
        Properties props = parse("app", "start", "--nlpPipeline", "SPACY");
        assertThat(props).includes(entry("nlpPipeline", "SPACY"));
    }

    @Test
    public void test_nlp_parallelism() {
        Properties props = parse("stage", "run", "--stages", "NLP", "--nlpParallelism", "4");
        assertThat(props).includes(entry("nlpParallelism", "4"));
    }

    @Test
    public void test_settings_opt() {
        Properties props = parse("-s", "/path/to/settings.properties", "app", "start");
        assertThat(props).includes(entry("settings", "/path/to/settings.properties"));
    }

    @Test
    public void test_settings_long_opt() {
        Properties props = parse("--settings", "/path/to/settings.properties", "app", "start");
        assertThat(props).includes(entry("settings", "/path/to/settings.properties"));
    }

    @Test
    public void test_digest_project_name_defaults_to_default_project() {
        Properties props = parse("app", "start");
        assertThat(props).includes(entry("defaultProject", "local-datashare"));
        assertThat(props).includes(entry("digestProjectName", "local-datashare"));
    }

    @Test
    public void test_digest_project_name_with_custom_project() {
        Properties props = parse("--defaultProject=myproject", "app", "start");
        assertThat(props).includes(entry("digestProjectName", "myproject"));
    }

    @Test
    public void test_no_digest_project_flag() {
        Properties props = parse("--noDigestProject=true", "app", "start");
        assertThat(props).includes(entry("defaultProject", "local-datashare"));
        assertThat(props).includes(entry("noDigestProject", "true"));
        assertThat(props.getProperty("digestProjectName")).isNull();
    }

    @Test
    public void test_digest_project_name_left_as_is_when_provided() {
        Properties props = parse("--digestProjectName", "foo", "app", "start");
        assertThat(props).includes(entry("defaultProject", "local-datashare"));
        assertThat(props).includes(entry("digestProjectName", "foo"));
        assertThat(props).includes(entry("noDigestProject", "false"));
    }

    @Test
    public void test_empty_default_project_falls_back_to_local_datashare() {
        // An empty defaultProject should fall back to "local-datashare" for digestProjectName
        Properties props = parse("--defaultProject", "", "app", "start");
        assertThat(props.getProperty("digestProjectName")).isEqualTo("local-datashare");
    }

    @Test
    public void test_short_project_opt() {
        Properties props = parse("-P", "myproject", "app", "start");
        assertThat(props).includes(entry("defaultProject", "myproject"));
        assertThat(props).includes(entry("digestProjectName", "myproject"));
    }

    @Test
    public void test_subcommand_overrides_shared_mode() {
        // The shared option defaults mode to LOCAL,
        // but "--mode SERVER" should override it to SERVER
        Properties props = parse("app", "start", "--mode", "server");
        assertThat(props).includes(entry("mode", "SERVER"));
    }

    @Test
    public void test_worker_run_overrides_shared_mode() {
        Properties props = parse("worker", "run");
        assertThat(props).includes(entry("mode", "TASK_WORKER"));
    }

    @Test
    public void test_stage_run_overrides_shared_mode() {
        Properties props = parse("stage", "run", "--stages", "SCAN");
        assertThat(props).includes(entry("mode", "CLI"));
    }

    @Test
    public void test_plugin_list_overrides_shared_mode() {
        Properties props = parse("plugin", "list");
        assertThat(props).includes(entry("mode", "CLI"));
    }

    @Test
    public void test_api_key_create_overrides_shared_mode() {
        Properties props = parse("api-key", "create", "bob");
        assertThat(props).includes(entry("mode", "CLI"));
    }

    @Test
    public void test_multiple_shared_options_with_app_serve() {
        Properties props = parse(
                "--defaultProject=foo",
                "--elasticsearchAddress", "http://es:9200",
                "--dataDir", "/data/docs",
                "--logLevel", "DEBUG",
                "app", "start", "--mode", "server");
        assertThat(props).includes(entry("defaultProject", "foo"));
        assertThat(props).includes(entry("elasticsearchAddress", "http://es:9200"));
        assertThat(props).includes(entry("dataDir", "/data/docs"));
        assertThat(props).includes(entry("logLevel", "DEBUG"));
        assertThat(props).includes(entry("mode", "SERVER"));
    }

    @Test
    public void test_multiple_shared_options_with_stage_run() {
        Properties props = parse(
                "--elasticsearchAddress", "http://es:9200",
                "--dataDir", "/data/docs",
                "--queueType", "REDIS",
                "--redisAddress", "redis://redis:6379",
                "stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props).includes(entry("elasticsearchAddress", "http://es:9200"));
        assertThat(props).includes(entry("dataDir", "/data/docs"));
        assertThat(props).includes(entry("queueType", "REDIS"));
        assertThat(props).includes(entry("redisAddress", "redis://redis:6379"));
        assertThat(props).includes(entry("stages", "SCAN,INDEX"));
        assertThat(props).includes(entry("mode", "CLI"));
    }

    @Test
    public void test_unknown_subcommand_fails() {
        int exitCode = parseExitCode("unknown");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_app_without_serve_shows_help() {
        int exitCode = parseExitCode("app");
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    public void test_worker_without_run_shows_help() {
        int exitCode = parseExitCode("worker");
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    public void test_stage_without_run_shows_help() {
        int exitCode = parseExitCode("stage");
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    public void test_plugin_without_subcommand_shows_help() {
        int exitCode = parseExitCode("plugin");
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    public void test_extension_without_subcommand_shows_help() {
        int exitCode = parseExitCode("extension");
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    public void test_api_key_without_subcommand_shows_help() {
        int exitCode = parseExitCode("api-key");
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    public void test_oauth_client_id_not_set_by_default() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("oauthClientId")).isNull();
    }

    @Test
    public void test_oauth_client_secret_not_set_by_default() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("oauthClientSecret")).isNull();
    }

    @Test
    public void test_root_host_not_set_by_default() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("rootHost")).isNull();
    }

    @Test
    public void test_session_signing_key_not_set_by_default() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("sessionSigningKey")).isNull();
    }

    @Test
    public void test_create_index_not_set_by_default() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("createIndex")).isNull();
    }

    @Test
    public void test_report_name_not_set_by_default() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("reportName")).isNull();
    }

    @Test
    public void test_artifact_dir_not_set_by_default() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("artifactDir")).isNull();
    }

    @Test
    public void test_search_query_not_set_by_default() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("searchQuery")).isNull();
    }

    @Test
    public void test_ocr_language_not_set_by_default() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("ocrLanguage")).isNull();
    }

    @Test
    public void test_task_routing_key_not_set_by_default() {
        Properties props = parse("app", "start");
        assertThat(props.getProperty("taskRoutingKey")).isNull();
    }

    // Global options accepted after subcommand name

    @Test
    public void test_global_option_after_app_start() {
        Properties props = parse("app", "start", "--elasticsearchAddress", "http://es:9200");
        assertThat(props).includes(entry("elasticsearchAddress", "http://es:9200"));
    }

    @Test
    public void test_global_option_short_after_app_start() {
        Properties props = parse("app", "start", "-d", "/my/docs");
        assertThat(props).includes(entry("dataDir", "/my/docs"));
    }

    @Test
    public void test_global_option_after_worker_run() {
        Properties props = parse("worker", "run", "--redisAddress", "redis://my-redis:6379");
        assertThat(props).includes(entry("redisAddress", "redis://my-redis:6379"));
    }

    @Test
    public void test_global_option_after_stage_run() {
        Properties props = parse("stage", "run", "--stages", "SCAN", "--dataDir", "/my/docs");
        assertThat(props).includes(entry("dataDir", "/my/docs"));
    }

    @Test
    public void test_global_options_mixed_before_and_after_subcommand() {
        Properties props = parse("--defaultProject", "myproject", "app", "start", "--logLevel", "DEBUG");
        assertThat(props).includes(entry("defaultProject", "myproject"));
        assertThat(props).includes(entry("logLevel", "DEBUG"));
    }

    @Test
    public void test_global_and_subcommand_options_both_after_subcommand() {
        Properties props = parse("worker", "run", "--taskWorkers", "4", "--redisAddress", "redis://x:6379");
        assertThat(props).includes(entry("taskWorkers", "4"));
        assertThat(props).includes(entry("redisAddress", "redis://x:6379"));
    }

    // Guard: no option name conflicts between GlobalOptions and subcommand-specific option classes

    @Test
    public void test_no_option_name_conflicts_between_global_and_subcommand_options() {
        Set<String> globalNames = optionNamesOf(GlobalOptions.class);
        assertNoOverlap(globalNames, ServerOptions.class);
        assertNoOverlap(globalNames, WorkerOptions.class);
        assertNoOverlap(globalNames, PipelineOptions.class);
    }

    @Test
    public void test_root_help_flag_exits_zero() {
        assertThat(parseExitCode("--help")).isEqualTo(0);
    }

    @Test
    public void test_root_help_short_flag_exits_zero() {
        assertThat(parseExitCode("-h")).isEqualTo(0);
    }

    @Test
    public void test_app_start_help_flag_exits_zero() {
        assertThat(parseExitCode("app", "start", "--help")).isEqualTo(0);
    }

    @Test
    public void test_worker_run_help_flag_exits_zero() {
        assertThat(parseExitCode("worker", "run", "--help")).isEqualTo(0);
    }

    @Test
    public void test_stage_run_help_flag_exits_zero() {
        assertThat(parseExitCode("stage", "run", "--help")).isEqualTo(0);
    }

    @Test
    public void test_version_flag_exits_zero() {
        assertThat(parseExitCode("--version")).isEqualTo(0);
    }

    @Test
    public void test_version_short_flag_exits_zero() {
        assertThat(parseExitCode("-V")).isEqualTo(0);
    }

    @Test
    public void test_help_subcommand_exits_zero() {
        assertThat(parseExitCode("help")).isEqualTo(0);
    }

    @Test
    public void test_help_subcommand_with_app_exits_zero() {
        assertThat(parseExitCode("help", "app")).isEqualTo(0);
    }

    @Test
    public void test_help_subcommand_with_app_start_exits_zero() {
        assertThat(parseExitCode("help", "app", "start")).isEqualTo(0);
    }

    @Test
    public void test_no_color_flag_is_accepted() {
        assertThat(parseExitCode("--no-color", "app", "start")).isEqualTo(0);
    }

    @Test
    public void test_no_color_flag_after_subcommand_is_accepted() {
        assertThat(parseExitCode("app", "start", "--no-color")).isEqualTo(0);
    }

    @Test
    public void test_unknown_flag_fails() {
        assertThat(parseExitCode("--foo")).isNotEqualTo(0);
    }

    @Test
    public void test_unknown_leaf_subcommand_fails() {
        assertThat(parseExitCode("app", "foo")).isNotEqualTo(0);
    }

    private static Set<String> optionNamesOf(Class<?> clazz) {
        Set<String> names = new HashSet<>();
        for (Field field : clazz.getDeclaredFields()) {
            CommandLine.Option opt = field.getAnnotation(CommandLine.Option.class);
            if (opt != null) names.addAll(Arrays.asList(opt.names()));
        }
        return names;
    }

    private static void assertNoOverlap(Set<String> globalNames, Class<?> optionClass) {
        Set<String> subNames = optionNamesOf(optionClass);
        Set<String> conflicts = new HashSet<>(globalNames);
        conflicts.retainAll(subNames);
        assertThat(conflicts)
            .as("Option name conflicts between GlobalOptions and " + optionClass.getSimpleName())
            .isEmpty();
    }
}
