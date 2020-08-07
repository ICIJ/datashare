package org.icij.datashare;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.fest.assertions.Assertions.assertThat;

public class ExtensionTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    @Test(expected = NullPointerException.class)
    public void test_cannot_create_extension_with_null_url() {
        new Extension(null);
    }

    @Test
    public void test_remove_version() {
        assertThat(Extension.removeVersion("my-extension")).isEqualTo("my-extension");
        assertThat(Extension.removeVersion("extension")).isEqualTo("extension");
        assertThat(Extension.removeVersion("extension.with.dot")).isEqualTo("extension.with.dot");
        assertThat(Extension.removeVersion("my-extension-1.2.3")).isEqualTo("my-extension");
        assertThat(Extension.removeVersion("extension.with.dot-1.0.0")).isEqualTo("extension.with.dot");
    }

    @Test
    public void test_has_previous_version() throws Exception {
        File[] files = new File[] {dir.newFile("extension-1.0.0.jar")};
        assertThat(Extension.getPreviousVersionInstalled(files, "extension-0.1.0")).hasSize(1);
        assertThat(Extension.getPreviousVersionInstalled(files, "extension-1.1.0")).hasSize(1);
        assertThat(Extension.getPreviousVersionInstalled(files, "extension-1.1")).hasSize(1);
        assertThat(Extension.getPreviousVersionInstalled(files, "extension")).hasSize(1);

        assertThat(Extension.getPreviousVersionInstalled(files, "other-extension")).isEmpty();
        assertThat(Extension.getPreviousVersionInstalled(files, "extension-other-1.2.3")).isEmpty();
    }
}
