package org.icij.datashare.mode;

import org.icij.datashare.PropertiesProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;


public class CommonModeTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void test_constructor_properties_should_override_configuration_file() throws IOException {
        File configFile = folder.newFile("file.conf");
        Files.write(configFile.toPath(), asList("foo=doe", "bar=baz"));

        CommonMode mode = new CommonMode(PropertiesProvider.fromMap(new HashMap<String, String>(){{
            put(PropertiesProvider.CONFIG_FILE_PARAMETER_KEY,configFile.toString());
            put("bar","toto");
        }}));

        assertThat(mode.properties()).includes(entry("foo", "doe"), entry("bar", "toto")); }
}