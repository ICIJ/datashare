package org.icij.datashare.cli;

import java.util.Optional;

/**
 * Server users provider, selected via the {@code --authUsersProvider} option.
 *
 * <p>This enum is intentionally label-only: it carries no reference to the actual
 * {@code UsersWritable} classes, which live in the downstream {@code datashare-app}
 * module. The mapping from {@code AuthUsersProvider} to a concrete provider class is
 * owned by {@code CommonMode}.</p>
 *
 * <p>The {@code --authUsersProvider} flag is dual-mode: it accepts these labels and
 * also a fully-qualified class name (back-compat). {@link #tryFromString} returns
 * empty for anything that is not a known label, which is how the resolver decides to
 * fall through to class-name interpretation.</p>
 */
public enum AuthUsersProvider {
    DATABASE("database"),
    REDIS("redis");

    public final String cliName;

    AuthUsersProvider(String cliName) {
        this.cliName = cliName;
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[_\\-]", "");
    }

    public static Optional<AuthUsersProvider> tryFromString(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String normalized = normalize(s);
        for (AuthUsersProvider v : values()) {
            if (normalize(v.cliName).equals(normalized)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /**
     * Strict label lookup that throws on unknown/null input. Retained for symmetry with
     * {@code AuthMode.fromString}; the dual-mode resolver uses {@link #tryFromString} instead
     * (it must tolerate non-label values to fall through to class-name resolution).
     */
    public static AuthUsersProvider fromString(String s) {
        if (s == null) {
            throw new IllegalArgumentException("Auth users provider must not be null");
        }
        return tryFromString(s).orElseThrow(() -> new IllegalArgumentException(
                "Unknown auth users provider: " + s + " (valid: database, redis)"));
    }

    @Override
    public String toString() {
        return cliName;
    }
}
