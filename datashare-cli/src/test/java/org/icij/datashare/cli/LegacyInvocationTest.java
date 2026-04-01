package org.icij.datashare.cli;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

/**
 * Regression tests for the legacy (jopt-simple) CLI invocation path.
 *
 * These tests exercise DatashareCli directly and must pass both
 * before and after any CLI refactoring. They document the contract of the
 * legacy path: given a set of --option arguments, the resulting
 * Properties must contain specific key/value pairs consumed by the
 * application (CommonMode, WebApp, CliApp, TaskWorkerApp, etc.).
 */
public class LegacyInvocationTest {

    private Properties parse(String... args) {
        DatashareCli cli = new DatashareCli();
        cli.parseArguments(args);
        return cli.properties;
    }

    @Before
    public void setUp() {
        System.setProperty("user.home", "/home/datashare");
    }

    @After
    public void tearDown() {
        System.clearProperty("user.home");
    }

    @Test
    public void test_default_mode_is_local() {
        assertThat(parse("")).includes(entry("mode", "LOCAL"));
    }

    @Test
    public void test_no_args_defaults_to_local() {
        assertThat(parse()).includes(entry("mode", "LOCAL"));
    }

    @Test
    public void test_mode_local_explicit() {
        assertThat(parse("--mode=LOCAL")).includes(entry("mode", "LOCAL"));
    }

    @Test
    public void test_mode_server() {
        assertThat(parse("--mode=SERVER")).includes(entry("mode", "SERVER"));
    }

    @Test
    public void test_mode_embedded() {
        assertThat(parse("--mode=EMBEDDED")).includes(entry("mode", "EMBEDDED"));
    }

    @Test
    public void test_mode_ner() {
        assertThat(parse("--mode=NER")).includes(entry("mode", "NER"));
    }

    @Test
    public void test_mode_task_worker() {
        assertThat(parse("--mode=TASK_WORKER")).includes(entry("mode", "TASK_WORKER"));
    }

    @Test
    public void test_mode_cli() {
        assertThat(parse("--mode=CLI")).includes(entry("mode", "CLI"));
    }

    @Test
    public void test_last_mode_wins() {
        assertThat(parse("--mode=SERVER", "--mode=LOCAL")).includes(entry("mode", "LOCAL"));
    }

    @Test
    public void test_is_web_server_for_local_mode() {
        DatashareCli cli = new DatashareCli();
        cli.parseArguments(new String[]{"--mode=LOCAL"});
        assertThat(cli.isWebServer()).isTrue();
    }

    @Test
    public void test_is_web_server_for_server_mode() {
        DatashareCli cli = new DatashareCli();
        cli.parseArguments(new String[]{"--mode=SERVER"});
        assertThat(cli.isWebServer()).isTrue();
    }

    @Test
    public void test_is_web_server_for_embedded_mode() {
        DatashareCli cli = new DatashareCli();
        cli.parseArguments(new String[]{"--mode=EMBEDDED"});
        assertThat(cli.isWebServer()).isTrue();
    }

    @Test
    public void test_is_web_server_for_ner_mode() {
        DatashareCli cli = new DatashareCli();
        cli.parseArguments(new String[]{"--mode=NER"});
        assertThat(cli.isWebServer()).isTrue();
    }

    @Test
    public void test_is_not_web_server_for_task_worker_mode() {
        DatashareCli cli = new DatashareCli();
        cli.parseArguments(new String[]{"--mode=TASK_WORKER"});
        assertThat(cli.isWebServer()).isFalse();
    }

    @Test
    public void test_is_not_web_server_for_cli_mode() {
        DatashareCli cli = new DatashareCli();
        cli.parseArguments(new String[]{"--mode=CLI"});
        assertThat(cli.isWebServer()).isFalse();
    }

    @Test
    public void test_stages_scan_index() {
        assertThat(parse("--mode=CLI", "--stages=SCAN,INDEX"))
                .includes(entry("mode", "CLI"))
                .includes(entry("stages", "SCAN,INDEX"));
    }

    @Test
    public void test_stages_all_main() {
        assertThat(parse("--stages=SCAN,INDEX,NLP")).includes(entry("stages", "SCAN,INDEX,NLP"));
    }

    @Test
    public void test_stages_full_pipeline() {
        assertThat(parse("--stages=SCAN,INDEX,CATEGORIZE,NLP"))
                .includes(entry("stages", "SCAN,INDEX,CATEGORIZE,NLP"));
    }

    @Test
    public void test_stages_single_scan() {
        assertThat(parse("--stages=SCAN")).includes(entry("stages", "SCAN"));
    }

    @Test
    public void test_plugin_list_no_filter() {
        assertThat(parse("--pluginList")).includes(entry("pluginList", "true"));
    }

    @Test
    public void test_plugin_list_with_filter() {
        assertThat(parse("--pluginList=.*")).includes(entry("pluginList", ".*"));
    }

    @Test
    public void test_plugin_install() {
        assertThat(parse("--pluginInstall", "my-plugin")).includes(entry("pluginInstall", "my-plugin"));
    }

    @Test
    public void test_plugin_install_from_url() {
        assertThat(parse("--pluginInstall", "https://example.com/plugin.jar"))
                .includes(entry("pluginInstall", "https://example.com/plugin.jar"));
    }

    @Test
    public void test_plugin_delete() {
        assertThat(parse("--pluginDelete", "my-plugin")).includes(entry("pluginDelete", "my-plugin"));
    }

    @Test
    public void test_plugin_not_set_by_default() {
        Properties props = parse("");
        assertThat(props.getProperty("pluginList")).isNull();
        assertThat(props.getProperty("pluginInstall")).isNull();
        assertThat(props.getProperty("pluginDelete")).isNull();
    }

    @Test
    public void test_extension_list_no_filter() {
        assertThat(parse("--extensionList")).includes(entry("extensionList", "true"));
    }

    @Test
    public void test_extension_list_with_filter() {
        assertThat(parse("--extensionList=.*nlp.*")).includes(entry("extensionList", ".*nlp.*"));
    }

    @Test
    public void test_extension_install() {
        assertThat(parse("--extensionInstall", "my-ext")).includes(entry("extensionInstall", "my-ext"));
    }

    @Test
    public void test_extension_delete() {
        assertThat(parse("--extensionDelete", "my-ext")).includes(entry("extensionDelete", "my-ext"));
    }

    @Test
    public void test_extension_not_set_by_default() {
        Properties props = parse("");
        assertThat(props.getProperty("extensionList")).isNull();
        assertThat(props.getProperty("extensionInstall")).isNull();
        assertThat(props.getProperty("extensionDelete")).isNull();
    }

    @Test
    public void test_create_api_key() {
        assertThat(parse("--createApiKey", "alice")).includes(entry("createApiKey", "alice"));
    }

    @Test
    public void test_create_api_key_short_opt() {
        assertThat(parse("-k", "alice")).includes(entry("createApiKey", "alice"));
    }

    @Test
    public void test_get_api_key() {
        assertThat(parse("--apiKey", "alice")).includes(entry("apiKey", "alice"));
    }

    @Test
    public void test_delete_api_key() {
        assertThat(parse("--deleteApiKey", "alice")).includes(entry("deleteApiKey", "alice"));
    }

    @Test
    public void test_api_key_not_set_by_default() {
        Properties props = parse("");
        assertThat(props.getProperty("createApiKey")).isNull();
        assertThat(props.getProperty("apiKey")).isNull();
        assertThat(props.getProperty("deleteApiKey")).isNull();
    }

    @Test
    public void test_default_port() {
        assertThat(parse("")).includes(entry("tcpListenPort", "8080"));
    }

    @Test
    public void test_port_alias() {
        Properties props = parse("--port=7777");
        assertThat(props).includes(entry("tcpListenPort", "7777"));
        assertThat(props.getProperty("port")).isNull();
    }

    @Test
    public void test_tcp_listen_port() {
        assertThat(parse("--tcpListenPort=9090")).includes(entry("tcpListenPort", "9090"));
    }

    @Test
    public void test_bind_host() {
        assertThat(parse("--bind", "127.0.0.1")).includes(entry("bind", "127.0.0.1"));
    }

    @Test
    public void test_bind_short_opt() {
        assertThat(parse("-b", "0.0.0.0")).includes(entry("bind", "0.0.0.0"));
    }

    @Test
    public void test_bind_not_set_by_default() {
        assertThat(parse("").getProperty("bind")).isNull();
    }

    @Test
    public void test_default_cors() {
        assertThat(parse("")).includes(entry("cors", "no-cors"));
    }

    @Test
    public void test_custom_cors() {
        assertThat(parse("--cors", "http://localhost:3000")).includes(entry("cors", "http://localhost:3000"));
    }

    @Test
    public void test_default_elasticsearch_address() {
        Properties props = parse("");
        assertThat(props.getProperty("elasticsearchAddress")).isNotNull();
    }

    @Test
    public void test_custom_elasticsearch_address() {
        assertThat(parse("--elasticsearchAddress", "http://es:9200"))
                .includes(entry("elasticsearchAddress", "http://es:9200"));
    }

    @Test
    public void test_default_elasticsearch_data_path() {
        assertThat(parse("")).includes(entry("elasticsearchDataPath",
                "/home/datashare/.local/share/datashare/es"));
    }

    @Test
    public void test_default_data_dir() {
        assertThat(parse("")).includes(entry("dataDir", "/home/datashare/Datashare"));
    }

    @Test
    public void test_custom_data_dir() {
        assertThat(parse("--dataDir", "/data/docs")).includes(entry("dataDir", "/data/docs"));
    }

    @Test
    public void test_data_dir_short_opt() {
        assertThat(parse("-d", "/data/docs")).includes(entry("dataDir", "/data/docs"));
    }

    @Test
    public void test_default_plugins_dir() {
        assertThat(parse("")).includes(entry("pluginsDir",
                "/home/datashare/.local/share/datashare/plugins"));
    }

    @Test
    public void test_custom_plugins_dir() {
        assertThat(parse("--pluginsDir", "/opt/plugins")).includes(entry("pluginsDir", "/opt/plugins"));
    }

    @Test
    public void test_default_extensions_dir() {
        assertThat(parse("")).includes(entry("extensionsDir",
                "/home/datashare/.local/share/datashare/extensions"));
    }

    @Test
    public void test_custom_extensions_dir() {
        assertThat(parse("--extensionsDir", "/opt/ext")).includes(entry("extensionsDir", "/opt/ext"));
    }

    @Test
    public void test_batch_download_max_size() {
        assertThat(parse("--batchDownloadMaxSize", "500M")).includes(entry("batchDownloadMaxSize", "500M"));
    }

    @Test
    public void test_batch_download_max_size_numeric() {
        assertThat(parse("--batchDownloadMaxSize", "123")).includes(entry("batchDownloadMaxSize", "123"));
    }

    @Test
    public void test_embedded_document_download_max_size() {
        assertThat(parse("--embeddedDocumentDownloadMaxSize", "2G"))
                .includes(entry("embeddedDocumentDownloadMaxSize", "2G"));
    }

    @Test
    public void test_relative_batch_download_dir() {
        Properties props = parse("--batchDownloadDir", "downloads");
        Path userDir = Path.of(System.getProperty("user.dir"));
        assertThat(props).includes(entry("batchDownloadDir", userDir.resolve("downloads").toString()));
    }

    @Test
    public void test_absolute_batch_download_dir() {
        assertThat(parse("--batchDownloadDir", "/tmp/downloads"))
                .includes(entry("batchDownloadDir", "/tmp/downloads"));
    }

    @Test
    public void test_default_project() {
        assertThat(parse("")).includes(entry("defaultProject", "local-datashare"));
    }

    @Test
    public void test_custom_project() {
        assertThat(parse("--defaultProject", "my-project")).includes(entry("defaultProject", "my-project"));
    }

    @Test
    public void test_project_short_opt() {
        assertThat(parse("-p", "my-project")).includes(entry("defaultProject", "my-project"));
    }

    @Test
    public void test_digest_project_name_defaults_to_default_project() {
        Properties props = parse("");
        assertThat(props).includes(entry("defaultProject", "local-datashare"));
        assertThat(props).includes(entry("digestProjectName", "local-datashare"));
    }

    @Test
    public void test_digest_project_name_follows_default_project() {
        Properties props = parse("--defaultProject=myproject");
        assertThat(props).includes(entry("digestProjectName", "myproject"));
    }

    @Test
    public void test_no_digest_project_clears_digest_project_name() {
        Properties props = parse("--noDigestProject", "true");
        assertThat(props).includes(entry("noDigestProject", "true"));
        assertThat(props.getProperty("digestProjectName")).isNull();
    }

    @Test
    public void test_explicit_digest_project_name_is_preserved() {
        Properties props = parse("--digestProjectName", "custom-digest");
        assertThat(props).includes(entry("digestProjectName", "custom-digest"));
    }

    @Test
    public void test_default_nlp_pipeline() {
        assertThat(parse("")).includes(entry("nlpPipeline", "CORENLP"));
    }

    @Test
    public void test_custom_nlp_pipeline() {
        assertThat(parse("--nlpPipeline", "SPACY")).includes(entry("nlpPipeline", "SPACY"));
    }

    @Test
    public void test_nlp_pipeline_short_opt() {
        assertThat(parse("-nlpp", "OPENNLP")).includes(entry("nlpPipeline", "OPENNLP"));
    }

    @Test
    public void test_default_ocr_enabled() {
        assertThat(parse("")).includes(entry("ocr", "true"));
    }

    @Test
    public void test_ocr_disabled() {
        assertThat(parse("--ocr", "false")).includes(entry("ocr", "false"));
    }

    @Test
    public void test_no_default_language() {
        assertThat(parse("").getProperty("language")).isNull();
    }

    @Test
    public void test_custom_language() {
        assertThat(parse("--language", "ENGLISH")).includes(entry("language", "ENGLISH"));
    }

    @Test
    public void test_default_queue_capacity() {
        assertThat(parse("")).includes(entry("queueCapacity", "1000000"));
    }

    @Test
    public void test_custom_queue_capacity() {
        assertThat(parse("--queueCapacity", "500")).includes(entry("queueCapacity", "500"));
    }

    @Test
    public void test_default_redis_address() {
        Properties props = parse("");
        assertThat(props.getProperty("redisAddress")).isNotNull();
    }

    @Test
    public void test_custom_redis_address() {
        assertThat(parse("--redisAddress", "redis://redis:6379"))
                .includes(entry("redisAddress", "redis://redis:6379"));
    }

    @Test
    public void test_oauth_client_id() {
        assertThat(parse("--oauthClientId", "my-client-id"))
                .includes(entry("oauthClientId", "my-client-id"));
    }

    @Test
    public void test_oauth_client_id_not_set_by_default() {
        assertThat(parse("").getProperty("oauthClientId")).isNull();
    }

    @Test
    public void test_auth_filter() {
        assertThat(parse("--authFilter", "org.icij.datashare.BasicAuthFilter"))
                .includes(entry("authFilter", "org.icij.datashare.BasicAuthFilter"));
    }

    @Test
    public void test_default_user_name() {
        assertThat(parse("")).includes(entry("defaultUserName", "local"));
    }

    @Test
    public void test_custom_user_name() {
        assertThat(parse("--defaultUserName", "admin")).includes(entry("defaultUserName", "admin"));
    }

    @Test
    public void test_user_name_short_opt() {
        assertThat(parse("-u", "admin")).includes(entry("defaultUserName", "admin"));
    }

    @Test
    public void test_oauth_user_projects_attribute_sets_system_property() {
        parse("--oauthUserProjectsAttribute", "org.groups");
        assertThat(System.getProperty("datashare.user.projects")).isEqualTo("org.groups");
        System.clearProperty("datashare.user.projects");
    }

    @Test
    public void test_settings_short_opt() {
        assertThat(parse("-s", "/conf/settings.properties"))
                .includes(entry("settings", "/conf/settings.properties"));
    }

    @Test
    public void test_settings_long_opt() {
        assertThat(parse("--settings", "/conf/settings.properties"))
                .includes(entry("settings", "/conf/settings.properties"));
    }

    @Test
    public void test_default_log_level() {
        assertThat(parse("")).includes(entry("logLevel", "INFO"));
    }

    @Test
    public void test_custom_log_level() {
        assertThat(parse("--logLevel", "DEBUG")).includes(entry("logLevel", "DEBUG"));
    }

    @Test
    public void test_default_index_timeout() {
        assertThat(parse("")).includes(entry("indexTimeout", "30"));
    }

    @Test
    public void test_custom_index_timeout() {
        assertThat(parse("--indexTimeout", "60")).includes(entry("indexTimeout", "60"));
    }

    @Test
    public void test_server_mode_with_custom_port_and_project() {
        Properties props = parse("--mode=SERVER", "--tcpListenPort=9090", "--defaultProject=prod");
        assertThat(props).includes(entry("mode", "SERVER"));
        assertThat(props).includes(entry("tcpListenPort", "9090"));
        assertThat(props).includes(entry("defaultProject", "prod"));
        assertThat(props).includes(entry("digestProjectName", "prod"));
    }

    @Test
    public void test_cli_mode_with_stages_and_elasticsearch() {
        Properties props = parse(
                "--mode=CLI",
                "--stages=SCAN,INDEX",
                "--elasticsearchAddress", "http://es:9200",
                "--defaultProject", "corpus");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("stages", "SCAN,INDEX"));
        assertThat(props).includes(entry("elasticsearchAddress", "http://es:9200"));
        assertThat(props).includes(entry("defaultProject", "corpus"));
    }

    @Test
    public void test_task_worker_mode_with_redis() {
        Properties props = parse(
                "--mode=TASK_WORKER",
                "--redisAddress", "redis://cache:6379",
                "--taskWorkers", "4");
        assertThat(props).includes(entry("mode", "TASK_WORKER"));
        assertThat(props).includes(entry("redisAddress", "redis://cache:6379"));
        assertThat(props).includes(entry("taskWorkers", "4"));
    }
}
