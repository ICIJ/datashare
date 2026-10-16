package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

final class McpArguments {
    private McpArguments() {}

    static String requireText(JsonNode arguments, String name) {
        String value = arguments.path(name).asText("");
        if (value.isBlank()) {
            throw McpToolException.InvalidArgument.missing(name);
        }
        return value;
    }

    static String optionalText(JsonNode arguments, String name) {
        return arguments.path(name).asText(null);
    }

    /**
     * A non-empty list of non-blank strings, capped at {@code max} items and {@code maxLength} characters each: the
     * rail on the batch write tools. The length is not cosmetic. A value longer than its database column makes
     * Postgres reject the insert, and the failure the client is handed carries the whole INSERT statement.
     */
    static List<String> requireTexts(JsonNode arguments, String name, int max, int maxLength) {
        JsonNode node = arguments.path(name);
        if (node.isMissingNode() || node.isNull()) {
            throw McpToolException.InvalidArgument.missing(name);
        }
        if (!node.isArray() || node.isEmpty() || node.size() > max) {
            throw McpToolException.InvalidArgument.invalid(name);
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(requireItemText(name, item, maxLength)));
        return values;
    }

    private static String requireItemText(String name, JsonNode item, int maxLength) {
        if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > maxLength) {
            throw McpToolException.InvalidArgument.invalid(name);
        }
        return item.asText();
    }

    record IntArgument(String name, int defaultValue, int min, int max) {
        int readFrom(JsonNode arguments) {
            JsonNode node = arguments.path(name);
            if (node.isMissingNode() || node.isNull()) {
                return defaultValue;
            }
            return requireInRange(node);
        }

        private int requireInRange(JsonNode node) {
            if (!node.canConvertToInt() || node.asInt() < min || node.asInt() > max) {
                throw McpToolException.InvalidArgument.invalid(name);
            }
            return node.asInt();
        }
    }
}
