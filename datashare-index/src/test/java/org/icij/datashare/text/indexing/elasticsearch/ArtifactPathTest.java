package org.icij.datashare.text.indexing.elasticsearch;

import org.junit.Test;
import org.icij.datashare.text.artifact.ArtifactType;
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
    public void test_payload_dir_is_named_per_type() {
        Path node = Path.of("/artifact/prj/6a/bb/" + DIGEST);
        assertThat(ArtifactPath.payloadDir(node, ArtifactType.PAGE).toString()).isEqualTo(node + "/pages");
        assertThat(ArtifactPath.payloadDir(node, ArtifactType.STRUCTURE).toString()).isEqualTo(node + "/structure");
    }

    @Test
    public void test_payload_page_is_one_based_and_unpadded() {
        Path node = Path.of("/artifact/prj/6a/bb/" + DIGEST);
        assertThat(ArtifactPath.payloadPage(node, ArtifactType.PAGE, 1, "txt").toString()).isEqualTo(node + "/pages/page-1.txt");
        assertThat(ArtifactPath.payloadPage(node, ArtifactType.STRUCTURE, 12, "xhtml").toString()).isEqualTo(node + "/structure/page-12.xhtml");
        assertThat(ArtifactPath.payloadPage(node, ArtifactType.STRUCTURE, 12345, "md").toString()).isEqualTo(node + "/structure/page-12345.md");
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

    @Test
    public void test_payload_content_is_the_byte_ranges_file() {
        Path node = Path.of("/artifact/prj/6a/bb/" + DIGEST);
        assertThat(ArtifactPath.payloadContent(node, ArtifactType.PAGE, "txt").toString()).isEqualTo(node + "/pages/content.txt");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_raw_has_no_payload_dir() {
        ArtifactPath.payloadDir(Path.of("/artifact/prj/6a/bb/" + DIGEST), ArtifactType.RAW);
    }
}
