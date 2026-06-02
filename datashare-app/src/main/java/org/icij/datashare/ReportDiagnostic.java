package org.icij.datashare;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.extract.document.DigestIdentifier;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.icij.spewer.PrintStreamSpewer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReportDiagnostic {

    private static final Map<String, String> STATUS_NAMES = Map.of(
            "0", "SUCCESS",
            "1", "FAILURE_NOT_FOUND",
            "2", "FAILURE_UNREADABLE",
            "3", "FAILURE_NOT_DECRYPTED",
            "4", "FAILURE_NOT_PARSED",
            "8", "FAILURE_UNKNOWN",
            "9", "FAILURE_NOT_SAVED");

    /** Collapse volatile tokens (decimal offsets, hex values) so error variants aggregate. */
    static String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message
                .replaceAll("0x[0-9A-Fa-f]+", "0xN")
                .replaceAll("(?<!0x)\\b\\d+\\b", "N");
    }

    static String statusName(String code) {
        String name = STATUS_NAMES.get(code);
        return name != null ? name : "UNKNOWN_CODE(" + code + ")";
    }

    /** Reads the exported report map and returns path -> status code for non-success entries only. */
    static Map<String, String> loadFailures(Path jsonFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
        Map<String, String> all;
        try (Reader reader = new InputStreamReader(Files.newInputStream(jsonFile), StandardCharsets.ISO_8859_1)) {
            all = mapper.readValue(reader, new TypeReference<LinkedHashMap<String, String>>() {});
        }
        Map<String, String> failures = new LinkedHashMap<>();
        all.forEach((path, value) -> {
            String code = value.split("\\|", 2)[0];
            if (!"0".equals(code)) {
                failures.put(path, code);
            }
        });
        return failures;
    }

    public record Result(String path, long size, String originalStatus, String newStatus,
                          String throwableClass, String rootCauseClass, String message,
                          String topFrame, String stackTrace, long durationMs) {}

    /** Re-runs extraction for one file (parse-only, discarding output) and captures the real outcome. */
    static Result diagnose(Path file, String originalStatus) {
        long start = System.currentTimeMillis();
        long size = sizeOrMinusOne(file);
        try {
            Extractor extractor = new Extractor(new DocumentFactory()
                    .withIdentifier(new DigestIdentifier("SHA-384", StandardCharsets.UTF_8)));
            TikaDocument document = extractor.extract(file);
            try (PrintStreamSpewer spewer = new PrintStreamSpewer(
                    new PrintStream(OutputStream.nullOutputStream()), new FieldNames())) {
                spewer.write(document); // consumes the reader -> triggers the lazy parse
            }
            return new Result(file.toString(), size, originalStatus, "SUCCESS",
                    "", "", "", "", "", System.currentTimeMillis() - start);
        } catch (Throwable t) {
            Throwable root = rootCause(t);
            return new Result(file.toString(), size, originalStatus, "FAILURE",
                    t.getClass().getName(), root.getClass().getName(),
                    normalizeMessage(root.getMessage()), topFrame(root),
                    stackTraceToString(t), System.currentTimeMillis() - start);
        }
    }

    private static long sizeOrMinusOne(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String topFrame(Throwable t) {
        StackTraceElement[] trace = t.getStackTrace();
        return trace.length > 0 ? trace[0].toString() : "";
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
