package org.icij.datashare.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.util.HashMap;
import java.util.Map;

/**
 * Single exit path for command-level failures. Mirrors the shape CliApp.error emits for
 * service-level failures, so a caller parsing --json output sees one schema whether the command
 * failed before dispatch (here) or after it.
 */
public final class CliErrors {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CliErrors() {
    }

    /**
     * Reports the failure on stderr and throws. Never returns; declared as returning
     * CliExitException so callers can write {@code throw CliErrors.fail(...)} where the compiler
     * needs a terminal statement.
     */
    public static CliExitException fail(CommandLine.Model.CommandSpec spec, boolean json, String code,
                                        String message, int exitCode) {
        if (json) {
            // HashMap, not Map.of: a null message is a programming slip we would rather report as
            // {"message":null} than swallow behind a NullPointerException from Map.of
            Map<String, String> payload = new HashMap<>();
            payload.put("error", code);
            payload.put("message", message);
            try {
                spec.commandLine().getErr().println(MAPPER.writeValueAsString(payload));
            } catch (Exception e) {
                spec.commandLine().getErr().println("error: " + message);
            }
        } else {
            spec.commandLine().getErr().println("error: " + message);
        }
        throw new CliExitException(exitCode);
    }
}
