package org.icij.datashare.cli.command;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
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

    @Test
    public void test_a_settings_file_does_not_invert_an_arity_zero_flag() throws IOException {
        // picocli sets an arity-0 boolean to !defaultValue when the flag is present, and it takes that
        // default from the value provider. A file saying resume=true must not turn -r into "do not resume".
        Path settings = settingsFile("resume=true\n");

        Properties props = parse("-s", settings.toString(), "stage", "run", "--stages", "ARTIFACT", "-r");

        assertThat(props).includes(entry("resume", "true"));
    }

    @Test
    public void test_an_arity_zero_flag_is_left_out_of_the_settings_defaults() throws IOException {
        // The flag keeps its own meaning instead: an untyped -r stays false here, and CommonMode's
        // overrideWith fold-in is what applies the file's resume=true, as it did before this provider.
        Path settings = settingsFile("resume=true\n");

        Properties props = parse("-s", settings.toString(), "stage", "run", "--stages", "ARTIFACT");

        assertThat(props.containsKey("resume")).isFalse();
    }

    @Test
    public void test_a_classpath_properties_file_is_not_consulted_without_a_settings_option() throws Exception {
        // PropertiesProvider(null) resolves datashare.properties off the context classloader and always
        // folds in DS_DOCKER_* env vars. Neither is the operator's settings file, so neither may outrank
        // a declared option default.
        Files.writeString(tmp.getRoot().toPath().resolve("datashare.properties"), "ocr=false\n");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(
                new URLClassLoader(new URL[] {tmp.getRoot().toURI().toURL()}, previous));
        try {
            Properties props = parse("stage", "run", "--stages", "INDEX");

            assertThat(props).includes(entry("ocr", "true"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private Path settingsFile(String content) throws IOException {
        Path file = tmp.newFile("datashare-test.properties").toPath();
        Files.writeString(file, content);
        return file;
    }
}
