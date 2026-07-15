package org.icij.datashare.tasks;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Deterministic corpus of embedding shapes known to stress extraction:
 *  deep nesting, wide fan-out, corrupt member among valid siblings, archive, big binary.
 *  Confidential real corpora must NEVER be added here (see spec). */
public class NastyCorpus {
    public static List<Path> buildInto(Path dir) throws IOException {
        Path deep = dir.resolve("deep.eml");
        Files.write(deep, emlWithAttachment("level1.zip",
                zip("level2.zip", zip("deep-secret.txt", "nested level 3 secret".getBytes(StandardCharsets.UTF_8)))));

        Path wide = dir.resolve("wide.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(wide))) {
            for (int i = 0; i < 200; i++) {
                zos.putNextEntry(new ZipEntry("wide-" + i + ".txt"));
                zos.write(("wide entry number " + i).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }

        Path corrupt = dir.resolve("corrupt.zip");
        byte[] garbage = new byte[1024];
        new Random(42).nextBytes(garbage);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(corrupt))) {
            zos.putNextEntry(new ZipEntry("sibling-ok.txt"));
            zos.write("the valid sibling must still be extracted".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("broken.docx"));
            zos.write(garbage);
            zos.closeEntry();
        }

        Path archive = dir.resolve("archive.tar.gz");
        try (OutputStream fos = Files.newOutputStream(archive);
             GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(fos);
             TarArchiveOutputStream taos = new TarArchiveOutputStream(gzos)) {
            for (int i = 0; i < 2; i++) {
                byte[] content = ("tar member " + i).getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry("member-" + i + ".txt");
                entry.setSize(content.length);
                taos.putArchiveEntry(entry);
                taos.write(content);
                taos.closeArchiveEntry();
            }
        }

        Path big = dir.resolve("big.bin");
        byte[] bigBytes = new byte[8 * 1024 * 1024];
        new Random(4242).nextBytes(bigBytes);
        Files.write(big, bigBytes);

        Path ocr = dir.resolve("ocr_image.pdf");
        writeOcrOnlyPdf(ocr);

        return List.of(deep, wide, corrupt, archive, big, ocr);
    }

    // PDFBox 3.0.7 is on the test classpath via tika-parser-pdf-module. The pdmodel API used here
    // (PDDocument, PDPage, PDPageContentStream, PDImageXObject, LosslessFactory) is unchanged from 2.x.
    private static void writeOcrOnlyPdf(Path target) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(400, 120, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, 400, 120);
            g.setColor(java.awt.Color.BLACK);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 32));
            g.drawString("OCR ONLY TEXT", 20, 70);
            g.dispose();
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject pdImage =
                    org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(pdf, img);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                cs.drawImage(pdImage, 100, 500);
            }
            pdf.save(target.toFile());
        }
    }

    private static byte[] zip(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
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
