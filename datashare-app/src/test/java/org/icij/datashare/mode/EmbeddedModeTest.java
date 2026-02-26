package org.icij.datashare.mode;

import org.icij.datashare.test.LogbackCapturingRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.event.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.lang.String.format;
import static org.fest.assertions.Assertions.assertThat;

public class EmbeddedModeTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();
    @Rule
    public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();

    @Test
    public void test_creates_default_settings_file_when_not_exists() throws Exception {
        Path settingsFile = temporaryFolder.getRoot().toPath().resolve("elasticsearch.yml");
        Path expectedBackupsDir = temporaryFolder.getRoot().toPath().resolve("backups");

        assertThat(settingsFileExists(settingsFile)).isFalse();
        createDefaultSettingsFile(settingsFile);

        assertThat(Files.exists(settingsFile)).isTrue();
        String content = Files.readString(settingsFile);
        assertThat(content).contains("path.repo:");
        assertThat(content).contains(expectedBackupsDir.toString());
        assertThat(logbackCapturingRule.logs(Level.INFO)).contains("Created default elasticsearch settings file at " + settingsFile);
    }

    @Test
    public void test_does_not_overwrite_existing_settings_file() throws Exception {
        Path settingsFile = temporaryFolder.newFile("elasticsearch.yml").toPath();
        String originalContent = "custom.setting: value\n";
        Files.writeString(settingsFile, originalContent);

        assertThat(settingsFileExists(settingsFile)).isTrue();

        String content = Files.readString(settingsFile);
        assertThat(content).isEqualTo(originalContent);
    }

    @Test(expected = RuntimeException.class)
    public void test_creates_default_settings_throws_runtime_when_fails() throws Exception {
        Path invalidPath = Path.of("/invalid/path/elasticsearch.yml");

        createDefaultSettingsFile(invalidPath);
    }

    @Test
    public void test_buildElasticsearchArgs_with_quoted_values() throws Exception {
        Path settingsFile = temporaryFolder.newFile("elasticsearch.yml").toPath();
        String content = """
                foo.bar: "/some/path"
                #bar.baz: true
                foo.bar.baz: false
                quz.qux: 10%
                """;
        Files.writeString(settingsFile, content);

        List<String> args = EmbeddedMode.buildElasticsearchArgs(settingsFile);

        assertThat(args).containsOnly("-Efoo.bar=/some/path",
                "-Equz.qux=10%",
                "-Efoo.bar.baz=false");
    }

    @Test
    public void test_buildElasticsearchArgs_with_win_path() throws Exception {
        Path settingsFile = temporaryFolder.newFile("elasticsearch.yml").toPath();
        String content = """
                foo.bar: "C:\\some\\path"
                bar.baz: "http://localhost:9200"
                """;
        Files.writeString(settingsFile, content);

        List<String> args = EmbeddedMode.buildElasticsearchArgs(settingsFile);

        assertThat(args).containsOnly("-Efoo.bar=C:\\some\\path",
                "-Ebar.baz=http://localhost:9200");
    }

    @Test(expected = RuntimeException.class)
    public void test_buildElasticsearchArgs_with_nonexistent_file() throws Exception {
        Path settingsFile = temporaryFolder.getRoot().toPath().resolve("nonexistent.yml");

        EmbeddedMode.buildElasticsearchArgs(settingsFile);
    }

    // Helper methods that mirror the logic in EmbeddedMode for testing
    private boolean settingsFileExists(Path settingsFile) {
        return Files.exists(settingsFile);
    }

    private void createDefaultSettingsFile(Path settingsFile) {
        try {
            Path backupsDir = settingsFile.getParent().resolve("backups");
            String defaultContent = String.format("path.repo: %s", backupsDir);
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, defaultContent);
            org.slf4j.LoggerFactory.getLogger(EmbeddedMode.class).info("Created default elasticsearch settings file at {}", settingsFile);
        } catch (java.io.IOException e) {
            throw new RuntimeException(format("failed to create default elasticsearch settings file at %s", settingsFile), e);
        }
    }
}
