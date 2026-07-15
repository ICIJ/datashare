package org.icij.datashare.tasks;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.fest.assertions.Assertions.assertThat;

public class NastyCorpusTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void builds_all_top_level_files_deterministically() throws Exception {
        Path dir = tmp.getRoot().toPath();
        List<Path> files = NastyCorpus.buildInto(dir);

        assertThat(files.stream().map(p -> p.getFileName().toString()).toList())
                .contains("deep.eml", "wide.zip", "corrupt.zip", "archive.tar.gz", "big.bin", "ocr_image.pdf");
        // deterministic: rebuilding elsewhere yields byte-identical files (stable digests)
        Path dir2 = tmp.newFolder("again").toPath();
        NastyCorpus.buildInto(dir2);
        assertThat(Files.readAllBytes(dir2.resolve("big.bin")))
                .isEqualTo(Files.readAllBytes(dir.resolve("big.bin")));
        assertThat(Files.readAllBytes(dir2.resolve("deep.eml")))
                .isEqualTo(Files.readAllBytes(dir.resolve("deep.eml")));
        assertThat(Files.readAllBytes(dir2.resolve("wide.zip")))
                .isEqualTo(Files.readAllBytes(dir.resolve("wide.zip")));
        assertThat(Files.readAllBytes(dir2.resolve("corrupt.zip")))
                .isEqualTo(Files.readAllBytes(dir.resolve("corrupt.zip")));
        assertThat(Files.readAllBytes(dir2.resolve("archive.tar.gz")))
                .isEqualTo(Files.readAllBytes(dir.resolve("archive.tar.gz")));
        // ocr_image.pdf is intentionally excluded: PDFBox embeds a document-ID that varies per build.
    }

    @Test
    public void wide_zip_has_200_entries_and_corrupt_zip_keeps_valid_sibling() throws Exception {
        Path dir = tmp.getRoot().toPath();
        NastyCorpus.buildInto(dir);

        int wideEntries = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(dir.resolve("wide.zip")))) {
            while (zis.getNextEntry() != null) wideEntries++;
        }
        assertThat(wideEntries).isEqualTo(200);

        boolean hasValidSibling = false, hasGarbage = false;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(dir.resolve("corrupt.zip")))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals("sibling-ok.txt")) hasValidSibling = true;
                if (e.getName().equals("broken.docx")) hasGarbage = true;
            }
        }
        assertThat(hasValidSibling).isTrue();
        assertThat(hasGarbage).isTrue();
    }
}
