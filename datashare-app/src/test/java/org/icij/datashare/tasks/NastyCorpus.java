package org.icij.datashare.tasks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Deterministic corpus of embedding shapes known to stress extraction:
 *  deep nesting, wide fan-out, and a corrupt member among valid siblings.
 *  Confidential real corpora must NEVER be added here (see spec). */
public class NastyCorpus {
    public static List<Path> buildInto(Path dir) throws IOException {
        Path deep = dir.resolve("deep.eml");
        Files.write(deep, emlWithAttachment("level1.zip",
                zip("level2.zip", zip("deep-secret.txt", "nested level 3 secret".getBytes(StandardCharsets.UTF_8)))));

        Path wide = dir.resolve("wide.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(wide))) {
            for (int i = 0; i < 200; i++) {
                zos.putNextEntry(pinTime(new ZipEntry("wide-" + i + ".txt")));
                zos.write(("wide entry number " + i).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }

        Path corrupt = dir.resolve("corrupt.zip");
        byte[] garbage = new byte[1024];
        new Random(42).nextBytes(garbage);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(corrupt))) {
            zos.putNextEntry(pinTime(new ZipEntry("sibling-ok.txt")));
            zos.write("the valid sibling must still be extracted".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(pinTime(new ZipEntry("broken.docx")));
            zos.write(garbage);
            zos.closeEntry();
        }

        return List.of(deep, wide, corrupt);
    }

    private static byte[] zip(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(pinTime(new ZipEntry(entryName)));
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    // ZipEntry defaults last-modified/creation/access time to wall-clock "now" when unset, which
    // makes every zip fixture hash differently on each build. Pin all three to a fixed epoch so
    // the corpus stays byte-identical across runs (setTime(0) alone can still emit an extended
    // timestamp extra field on some JDKs, so all three FileTime setters are pinned).
    private static ZipEntry pinTime(ZipEntry entry) {
        FileTime epoch = FileTime.fromMillis(0L);
        entry.setLastModifiedTime(epoch);
        entry.setCreationTime(epoch);
        entry.setLastAccessTime(epoch);
        return entry;
    }

    private static byte[] emlWithAttachment(String filename, byte[] attachment) {
        String boundary = "----=_NastyCorpusBoundary";
        String eml = "From: audit@example.org\r\n"
                + "To: audit@example.org\r\n"
                + "Subject: deep nesting fixture\r\n"
                + "Date: Wed, 15 Jul 2026 10:00:00 +0000\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\r\n\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n\r\n"
                + "body with a nested zip attachment\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: application/zip; name=\"" + filename + "\"\r\n"
                + "Content-Disposition: attachment; filename=\"" + filename + "\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getMimeEncoder().encodeToString(attachment) + "\r\n"
                + "--" + boundary + "--\r\n";
        return eml.getBytes(StandardCharsets.UTF_8);
    }
}
