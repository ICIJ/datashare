package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.test.LogbackCapturingRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.opensearch.common.settings.Settings;
import org.slf4j.event.Level;

import java.io.File;
import java.nio.file.Files;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;

public class EsEmbeddedServerTest {
    @Rule public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void launch_with_bad_codec() {
        try  {
            new EsEmbeddedServer("name", "home/path", "data/path", "9876") {
                @Override
                PluginConfigurableNode createNode(org.opensearch.common.settings.Settings settings) {
                    throw new IllegalArgumentException("Could not load codec");
                }
            };
            fail("should send IllegalArgument");
        } catch (IllegalArgumentException iae) {
            assertThat(logbackCapturingRule.logs(Level.ERROR)).contains("Your index version on disk (data/path) doesn't seem to have the same version as the embedded Elasticsearch engine (3.3.2). Please migrate it with snapshots, or remove it then restart datashare.");
        }
    }

    @Test
    public void launch_with_other_illegal_argument() {
        try {
            new EsEmbeddedServer("name", "home/path", "data/path", "9876") {
                @Override
                PluginConfigurableNode createNode(Settings settings) {
                    throw new IllegalArgumentException();
                }
            };
            fail("should send IllegalArgument");
        } catch (IllegalArgumentException iae) {
            assertThat(logbackCapturingRule.logs()).isEmpty();
        }
    }

    @Test
    public void launch_with_settings_file_loads_settings() throws Exception {
        File settingsFile = temporaryFolder.newFile("elasticsearch.yml");
        Files.writeString(settingsFile.toPath(), "node.name: test-node\n");

        final Settings[] capturedSettings = new Settings[1];
        try {
            new EsEmbeddedServer("name", "home/path", "data/path", "9876", "9300", settingsFile.getAbsolutePath()) {
                @Override
                EsEmbeddedServer.PluginConfigurableNode createNode(Settings settings) {
                    capturedSettings[0] = settings;
                    throw new IllegalArgumentException("stop here"); // stop before actually starting
                }
            };
            fail("should send IllegalArgument");
        } catch (IllegalArgumentException iae) {
            assertThat(logbackCapturingRule.logs(Level.INFO)).contains("Loading elasticsearch settings from " + settingsFile.getAbsolutePath());
            assertThat(capturedSettings[0].get("node.name")).isEqualTo("test-node");
        }
    }

    @Test
    public void launch_with_nonexistent_settings_file_logs_debug() {
        String nonExistentPath = "/nonexistent/path/elasticsearch.yml";
        try {
            new EsEmbeddedServer("name", "home/path", "data/path", "9876", "9300", nonExistentPath) {
                @Override
                EsEmbeddedServer.PluginConfigurableNode createNode(Settings settings) {
                    throw new IllegalArgumentException("stop here");
                }
            };
            fail("should send IllegalArgument");
        } catch (IllegalArgumentException iae) {
            assertThat(logbackCapturingRule.logs(Level.DEBUG)).contains("Elasticsearch settings file not found at " + nonExistentPath + ", using defaults");
        }
    }

    @Test
    public void launch_with_null_settings_path_does_not_load_settings() {
        try {
            new EsEmbeddedServer("name", "home/path", "data/path", "9876", "9300", null) {
                @Override
                EsEmbeddedServer.PluginConfigurableNode createNode(Settings settings) {
                    throw new IllegalArgumentException("stop here");
                }
            };
            fail("should send IllegalArgument");
        } catch (IllegalArgumentException iae) {
            assertThat(logbackCapturingRule.logs(Level.INFO)).excludes("Loading elasticsearch settings");
        }
    }

}
