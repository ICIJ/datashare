package org.icij.datashare;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Set;
import java.util.stream.Collectors;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.Extension.Type.WEB;

public class ExtensionServiceTest {
    @Rule public TemporaryFolder pluginFolder = new TemporaryFolder();

    @Test
    public void test_get_list() {
        Set<Extension> extensions = new ExtensionService(pluginFolder.getRoot().toPath()).list();
        assertThat(extensions).hasSize(3);
        assertThat(extensions.stream().map(Extension::getId).collect(Collectors.toSet()))
                .containsOnly("my-extension-foo", "my-extension-baz", "my-extension-bar");
    }

    @Test
    public void test_get_extension() {
        Set<Extension> extensions = new ExtensionService(pluginFolder.getRoot().toPath()).list("my-extension-baz");
        assertThat(extensions).hasSize(1);
        assertThat(extensions.iterator().next().type).isEqualTo(WEB);
    }
}
