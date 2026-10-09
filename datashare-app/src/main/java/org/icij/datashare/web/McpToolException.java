package org.icij.datashare.web;

/**
 * A tool failure whose message is meant for the MCP client. The nested classes are the failures the JSON-RPC layer
 * answers differently: a bad argument is a protocol error, a refused or missing subject is a tool error.
 */
public class McpToolException extends RuntimeException {
    public McpToolException(String message) {
        super(message);
    }

    public McpToolException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class InvalidArgument extends McpToolException {
        final String argument;

        private InvalidArgument(String problem, String argument) {
            super("%s argument: %s".formatted(problem, argument));
            this.argument = argument;
        }

        public static InvalidArgument missing(String argument) {
            return new InvalidArgument("missing", argument);
        }

        public static InvalidArgument invalid(String argument) {
            return new InvalidArgument("invalid", argument);
        }
    }

    public static class Forbidden extends McpToolException {
        final String subject;

        public Forbidden(String subject) {
            super("forbidden: " + subject);
            this.subject = subject;
        }

        public Forbidden(String subject, Throwable cause) {
            super("forbidden: " + subject, cause);
            this.subject = subject;
        }
    }

    public static class NotFound extends McpToolException {
        final String subject;

        public NotFound(String subject) {
            super("not found: " + subject);
            this.subject = subject;
        }

        public NotFound(String subject, Throwable cause) {
            super("not found: " + subject, cause);
            this.subject = subject;
        }
    }
}
