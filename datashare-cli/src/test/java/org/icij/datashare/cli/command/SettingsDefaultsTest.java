package org.icij.datashare.cli.command;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class SettingsDefaultsTest extends AbstractDatashareCommandTest {
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void test_settings_file_value_beats_an_annotation_default() throws IOException {
        // --ocr is declared with defaultValue = "true" in PipelineOptions
        Path settings = settingsFile("ocr=false\n");

        Properties props = parse("-s", settings.toString(), "stage", "run", "--stages", "INDEX");

        assertThat(props).includes(entry("ocr", "false"));
    }

    @Test
    public void test_an_explicit_option_beats_the_settings_file() throws IOException {
        Path settings = settingsFile("ocr=false\n");

        Properties props = parse("-s", settings.toString(), "stage", "run", "--stages", "INDEX",
                "--ocr", "true");

        assertThat(props).includes(entry("ocr", "true"));
    }

    @Test
    public void test_annotation_default_applies_for_a_key_the_settings_file_omits() throws IOException {
        Path settings = settingsFile("ocr=false\n");

        Properties props = parse("-s", settings.toString(), "stage", "run", "--stages", "INDEX");

        // scrollSize is declared with defaultValue = "1000" and the file says nothing about it
        assertThat(props).includes(entry("scrollSize", "1000"));
    }

    @Test
    public void test_annotation_defaults_are_untouched_without_a_settings_file() {
        Properties props = parse("stage", "run", "--stages", "INDEX");

        assertThat(props).includes(entry("ocr", "true"));
        assertThat(props).includes(entry("scrollSize", "1000"));
    }

    private Path settingsFile(String content) throws IOException {
        Path file = tmp.newFile("datashare-test.properties").toPath();
        Files.writeString(file, content);
        return file;
    }
}
