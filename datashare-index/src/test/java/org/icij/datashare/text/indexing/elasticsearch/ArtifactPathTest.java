package org.icij.datashare.text.indexing.elasticsearch;

import org.junit.Test;
import java.nio.file.Path;
import java.util.Locale;
import static org.fest.assertions.Assertions.assertThat;

public class ArtifactPathTest {
    private static final String DIGEST = "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e";

    @Test
    public void test_dir_is_sharded_by_first_two_hex_pairs() {
        Path root = Path.of("/artifact/prj");
        assertThat(ArtifactPath.dir(root, DIGEST).toString())
                .isEqualTo("/artifact/prj/6a/bb/" + DIGEST);
    }

    @Test
    public void test_manifest_path_is_in_node_dir() {
        Path root = Path.of("/artifact/prj");
        assertThat(ArtifactPath.manifest(root, DIGEST).toString())
                .isEqualTo("/artifact/prj/6a/bb/" + DIGEST + "/manifest.json");
    }

    @Test
    public void test_structure_dir_is_under_the_node_dir() {
        Path docDir = ArtifactPath.dir(Path.of("/artifact/prj"), DIGEST);
        assertThat(ArtifactPath.structureDir(docDir).toString())
                .isEqualTo("/artifact/prj/6a/bb/" + DIGEST + "/structure");
    }

    @Test
    public void test_structure_page_is_one_based_and_unpadded() {
        Path docDir = ArtifactPath.dir(Path.of("/artifact/prj"), DIGEST);
        assertThat(ArtifactPath.structurePage(docDir, 1, "md").getFileName().toString())
                .isEqualTo("page-1.md");
        assertThat(ArtifactPath.structurePage(docDir, 12, "xhtml").getFileName().toString())
                .isEqualTo("page-12.xhtml");
        assertThat(ArtifactPath.structurePage(docDir, 12345, "md").getFileName().toString())
                .isEqualTo("page-12345.md");
    }

    @Test
    public void test_page_filename_digits_do_not_follow_the_default_locale() {
        Locale previous = Locale.getDefault();
        try {
            // this locale formats %d with Arabic-Indic digits, so an unpinned format writes page-١٢.md
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertThat(ArtifactPath.pageFilename(12, "md")).isEqualTo("page-12.md");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
