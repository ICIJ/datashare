package org.icij.datashare.tasks;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class NastyCorpusTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void builds_top_level_files_deterministically() throws Exception {
        Path dir = tmp.getRoot().toPath();
        List<Path> files = NastyCorpus.buildInto(dir);

        assertThat(files.stream().map(p -> p.getFileName().toString()).toList())
                .contains("deep.eml", "wide.zip", "corrupt.zip");
        // deterministic: rebuilding elsewhere yields byte-identical files (stable digests).
        // This is the load-bearing property (it caught the wall-clock ZipEntry timestamp bug);
        // the coverage integration test covers structure (wide fan-out, corrupt sibling).
        Path dir2 = tmp.newFolder("again").toPath();
        NastyCorpus.buildInto(dir2);
        for (String name : List.of("deep.eml", "wide.zip", "corrupt.zip")) {
            assertThat(Files.readAllBytes(dir2.resolve(name)))
                    .isEqualTo(Files.readAllBytes(dir.resolve(name)));
        }
    }
}
