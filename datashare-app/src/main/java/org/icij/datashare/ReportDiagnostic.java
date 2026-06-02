package org.icij.datashare;

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
}
