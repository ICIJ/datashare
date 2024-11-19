package org.icij.datashare;

import java.io.IOException;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URL;
import java.util.AbstractMap.SimpleEntry;

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
    public void test_compare_to_equals_without_versions() throws Exception {
        Extension e1 = new Extension(new URL("http://foo.com/bar.jar"));
        Extension e2 = new Extension(new URL("http://foo.com/bar.jar"));
        assertThat(e1).isEqualTo(e2);
        assertThat(e1.compareTo(e2)).isEqualTo(0);
    }

    @Test
    public void test_compare_to_equals_with_version() throws Exception {
        Extension e1 = new Extension(new URL("http://foo.com/bar-1.0.0.jar"));
        Extension e2 = new Extension(new URL("http://foo.com/bar-1.0.0.jar"));
        assertThat(e1).isEqualTo(e2);
        assertThat(e1.compareTo(e2)).isEqualTo(0);
    }

    @Test
    public void test_compare_to_equals_with_different_version() throws Exception {
        Extension e1 = new Extension(new URL("http://foo.com/bar-1.0.0.jar"));
        Extension e2 = new Extension(new URL("http://foo.com/bar-1.0.1.jar"));
        assertThat(e1).isNotEqualTo(e2);
        assertThat(e1.compareTo(e2)).isNegative();
    }

    @Test
    public void test_compare_to_equals_with_different_id() throws Exception {
        Extension e1 = new Extension(new URL("http://foo.com/bar.jar"));
        Extension e2 = new Extension(new URL("http://foo.com/foo.jar"));
        assertThat(e1).isNotEqualTo(e2);
        assertThat(e1.compareTo(e2)).isNegative();
    }

    @Test
    public void test_compare_to_equals_with_null_one_version() throws Exception {
        Extension e1 = new Extension(new URL("http://foo.com/bar.jar"));
        Extension e2 = new Extension(new URL("http://foo.com/bar-1.0.0.jar"));
        assertThat(e1).isNotEqualTo(e2);
        assertThat(e1.compareTo(e2)).isNegative();
    }

    @Test
    public void test_extract_id_version() throws Exception {
        assertThat(Extension.extractIdVersion(new URL("file:///my-extension"))).isEqualTo(new SimpleEntry<>("my-extension",null));
        assertThat(Extension.extractIdVersion(new URL("file:///my-extension-1.2.3"))).isEqualTo(new SimpleEntry<>("my-extension","1.2.3"));
        assertThat(Extension.extractIdVersion(new URL("file:///my.extension.with.dot-1.2.3"))).isEqualTo(new SimpleEntry<>("my.extension.with.dot","1.2.3"));
        assertThat(Extension.extractIdVersion(new URL("file:///my-extension.jar"))).isEqualTo(new SimpleEntry<>("my-extension",null));
        assertThat(Extension.extractIdVersion(new URL("file:///my-extension-1.2.3.jar"))).isEqualTo(new SimpleEntry<>("my-extension","1.2.3"));
        assertThat(Extension.extractIdVersion(new URL("file:///my-extension-1.2.3-jar-with-dependencies.jar"))).isEqualTo(new SimpleEntry<>("my-extension","1.2.3"));
    }

    @Test
    public void test_has_previous_version() throws Exception {
        File[] files = new File[] {extensionsDir.newFile("ext3nsion-1.0.0.jar"), extensionsDir.newFile("ext3nsion-1.0.0")};
        assertThat(Extension.getPreviousVersionInstalled(files, "ext3nsion-0.1.0")).hasSize(2);
        assertThat(Extension.getPreviousVersionInstalled(files, "ext3nsion-1.1.0")).hasSize(2);
        assertThat(Extension.getPreviousVersionInstalled(files, "ext3nsion-1.1")).hasSize(2);
        assertThat(Extension.getPreviousVersionInstalled(files, "ext3nsion")).hasSize(2);

        assertThat(Extension.getPreviousVersionInstalled(files, "ext_3nsion-1.1")).isEmpty();
        assertThat(Extension.getPreviousVersionInstalled(files, "other-extension")).isEmpty();
        assertThat(Extension.getPreviousVersionInstalled(files, "extension-other-1.2.3")).isEmpty();
    }

    @Test
    public void test_host_specific_url_jar() throws IOException {
        // Given
        URL url = new URL("https://some-repository/path/to/my-favorite-executabe-extension.jar");
        Extension extension = new Extension(url);
        // When
        URL hostSpecificUrl = extension.hostSpecificUrl();
        // Then
        assertThat(hostSpecificUrl).isEqualTo(url);
    }

    @Test
    public void test_host_specific_url() throws IOException {
        // Given
        URL url = new URL("https://some-repository/path/to/my-favorite-executable-extension-6.0");
        Extension extension = new Extension(url);
        // When
        URL hostSpecificUrl = extension.hostSpecificUrl();
        // Then
        List<String> expectedEnds = List.of(
            "my-favorite-executable-extension-linux-aarch64-6.0",
            "my-favorite-executable-extension-linux-x86_64-6.0",
            "my-favorite-executable-extension-macos-aarch64-6.0",
            "my-favorite-executable-extension-macos-x86_64-6.0",
            "my-favorite-executable-extension-windows-aarch64-6.0",
            "my-favorite-executable-extension-windows-x86_64-6.0"
        );
        boolean isPlatformSpecific = !expectedEnds.stream().filter(end -> hostSpecificUrl.toString().endsWith(end))
            .toList().isEmpty();
        assertThat(isPlatformSpecific).isTrue();
    }
}
