package org.icij.datashare.cli;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Server authentication method, selected via the {@code --auth} option.
 *
 * <p>This enum is intentionally label-only: it carries no reference to the actual
 * authentication {@code Filter} classes, which live in the downstream {@code datashare-app}
 * module. The mapping from {@code AuthMode} to a concrete filter class is owned by
 * {@code ServerMode}.</p>
 */
public enum AuthMode {
    OAUTH("oauth"),
    FORM("form"),
    BASIC("basic"),
    YES_COOKIE("yesCookie"),
    YES_BASIC("yesBasic");

    public final String cliName;

    AuthMode(String cliName) {
        this.cliName = cliName;
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[_\\-]", "");
    }

    public static AuthMode fromString(String s) {
        String normalized = normalize(s);
        for (AuthMode v : values()) {
            if (normalize(v.cliName).equals(normalized)) {
                return v;
            }
        }
        throw new IllegalArgumentException(
                "Unknown auth mode: " + s + " (valid: oauth, form, basic, yesCookie, yesBasic)");
    }

    @Override
    public String toString() {
        return cliName;
    }

    public static class PicocliConverter implements ITypeConverter<AuthMode> {
        @Override
        public AuthMode convert(String s) {
            try {
                return fromString(s);
            } catch (IllegalArgumentException e) {
                throw new TypeConversionException(e.getMessage());
            }
        }
    }
}
