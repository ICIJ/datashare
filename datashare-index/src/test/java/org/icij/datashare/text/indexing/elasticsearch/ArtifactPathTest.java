package org.icij.datashare.text.indexing.elasticsearch;

import org.junit.Test;
import java.nio.file.Path;
import static org.fest.assertions.Assertions.assertThat;

public class ArtifactPathTest {
    @Test
    public void test_dir_is_sharded_by_first_two_hex_pairs() {
        Path root = Path.of("/artifact/prj");
        assertThat(ArtifactPath.dir(root, "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e").toString())
                .isEqualTo("/artifact/prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e");
    }

    @Test
    public void test_structure_markdown_file_path() {
        Path root = Path.of("/artifact/prj");
        assertThat(ArtifactPath.structureMarkdown(root, "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e").toString())
                .isEqualTo("/artifact/prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/structure.md");
    }

    @Test
    public void test_structure_dir_path() {
        Path root = Path.of("/artifact/prj");
        assertThat(ArtifactPath.structureDir(root, "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e").toString())
                .isEqualTo("/artifact/prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/structure");
    }

    @Test
    public void test_structure_page_path_is_zero_padded() {
        Path root = Path.of("/artifact/prj");
        String digest = "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e";
        assertThat(ArtifactPath.structurePage(root, digest, 1).toString())
                .isEqualTo("/artifact/prj/6a/bb/" + digest + "/structure/page-0001.md");
        assertThat(ArtifactPath.structurePage(root, digest, 12).toString())
                .isEqualTo("/artifact/prj/6a/bb/" + digest + "/structure/page-0012.md");
    }
}
