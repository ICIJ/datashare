package org.icij.datashare;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
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
}
