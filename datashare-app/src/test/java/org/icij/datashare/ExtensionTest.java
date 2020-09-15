package org.icij.datashare;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URL;

import static org.fest.assertions.Assertions.assertThat;

public class ExtensionTest {
    @Rule public TemporaryFolder extensionsDir = new TemporaryFolder();

    @Test(expected = NullPointerException.class)
    public void test_cannot_create_extension_with_null_url() {
        new Extension(null);
    }

    @Test
    public void test_null_url_constructor() {
        try {
            new Extension(null);
        } catch (NullPointerException npe) {
            assertThat(npe).hasMessage("an extension/plugin cannot be created with a null URL");
        }
    }

    @Test
    public void test_id_from_simple_url() throws Exception {
        assertThat(new Extension(new URL("http://foo.com/bar.jar")).id).isEqualTo("bar");
        assertThat(new Extension(new URL("http://foo.com/bar.jar")).version).isNull();
        assertThat(new Extension(new URL("http://foo.com/baz")).id).isEqualTo("baz");
        assertThat(new Extension(new URL("file:///tmp/foo")).id).isEqualTo("foo");
    }

    @Test
    public void test_id_from_url_with_version_number() throws Exception {
        assertThat(new Extension(new URL("http://foo.com/bar-1.1.1.jar")).id).isEqualTo("bar");
        assertThat(new Extension(new URL("http://foo.com/bar-1.1.1.jar")).version).isEqualTo("1.1.1");
    }

    @Test
    public void test_id_from_url_with_version_number_and_dots() throws Exception {
        assertThat(new Extension(new URL("http://foo.com/bar.qux-1.1.1.jar")).id).isEqualTo("bar.qux");
        assertThat(new Extension(new URL("http://foo.com/bar.qux-1.1.1.jar")).version).isEqualTo("1.1.1");
    }

    @Test
    public void test_remove_pattern() {
        assertThat(Extension.removePattern(Extension.endsWithVersion,"my-extension")).isEqualTo("my-extension");
        assertThat(Extension.removePattern(Extension.endsWithVersion,"my-extension-1.2.3")).isEqualTo("my-extension");
        assertThat(Extension.removePattern(Extension.endsWithVersion,"extension.with.dot-1.0.0")).isEqualTo("extension.with.dot");
        assertThat(Extension.removePattern(Extension.endsWithExtension,"my-extension")).isEqualTo("my-extension");
        assertThat(Extension.removePattern(Extension.endsWithExtension,"my-extension.jar")).isEqualTo("my-extension");
        assertThat(Extension.removePattern(Extension.endsWithExtension,"my-extension-1.2.3")).isEqualTo("my-extension-1.2.3");
    }

    @Test
    public void test_has_previous_version() throws Exception {
        File[] files = new File[] {extensionsDir.newFile("extension-1.0.0.jar")};
        assertThat(Extension.getPreviousVersionInstalled(files, "extension-0.1.0")).hasSize(1);
        assertThat(Extension.getPreviousVersionInstalled(files, "extension-1.1.0")).hasSize(1);
        assertThat(Extension.getPreviousVersionInstalled(files, "extension-1.1")).hasSize(1);
        assertThat(Extension.getPreviousVersionInstalled(files, "extension")).hasSize(1);

        assertThat(Extension.getPreviousVersionInstalled(files, "other-extension")).isEmpty();
        assertThat(Extension.getPreviousVersionInstalled(files, "extension-other-1.2.3")).isEmpty();
    }
}
