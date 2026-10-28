package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;

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

    record IntArgument(String name, int defaultValue, int min, int max) {
        int readFrom(JsonNode arguments) {
            JsonNode node = arguments.path(name);
            if (node.isMissingNode() || node.isNull()) {
                return defaultValue;
            }
            return requireInRange(node);
        }

        private int requireInRange(JsonNode node) {
            // canConvertToInt() only bounds the value: 1.5 converts, and a fraction is not the integer the schema asks for
            if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < min || node.asInt() > max) {
                throw McpToolException.InvalidArgument.invalid(name);
            }
            return node.asInt();
        }
    }
}
