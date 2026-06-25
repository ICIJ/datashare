package org.icij.datashare.text.indexing.elasticsearch;

import org.junit.Test;
import java.nio.file.Path;
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
}
