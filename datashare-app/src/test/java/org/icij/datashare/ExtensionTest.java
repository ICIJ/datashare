package org.icij.datashare;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

import static org.fest.assertions.Assertions.assertThat;

public class ExtensionTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    @Test(expected = NullPointerException.class)
    public void test_cannot_create_extension_with_null_url() {
        new Extension(null);
    }

    @Test
    public void test_null_url_constructor() {
        try {
            new Extension(null);
        } catch (NullPointerException npe) {
            assertThat(npe).hasMessage("an extension cannot be created with a null URL");
        }
    }

    @Test
    public void test_id_from_url() throws MalformedURLException {
        assertThat(new Extension(new URL("http://foo.com/bar.jar")).id).isEqualTo("bar");
        assertThat(new Extension(new URL("http://foo.com/baz")).id).isEqualTo("baz");
        assertThat(new Extension(new URL("file:///tmp/foo")).id).isEqualTo("foo");
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
