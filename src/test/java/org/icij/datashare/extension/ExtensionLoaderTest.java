package org.icij.datashare.extension;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;

public class ExtensionLoaderTest {
    @Test
    public void test_load_jars() throws Exception {
        Path extensionsDir = Paths.get(getClass().getResource("/extensions").toURI());
        assertThat(new ExtensionLoader(extensionsDir).getJars()).hasSize(1);
        assertThat(new ExtensionLoader(extensionsDir).getJars()).containsOnly(extensionsDir.resolve("executable.jar").toFile());
    }
}
