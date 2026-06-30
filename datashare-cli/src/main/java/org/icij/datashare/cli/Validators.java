package org.icij.datashare.cli;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.icij.datashare.policies.Role;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class Validators {

    public static class InvalidValueException extends RuntimeException {
        private final String field;
        public InvalidValueException(String field, String message) {
            super(message);
            this.field = field;
        }
        public String field() { return field; }
    }

    private static final Pattern LOGIN = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,63}$");
    private static final Set<String> PROVIDERS = Set.of(User.LOCAL, User.OAUTH, User.EXTERNAL);

    private Validators() {}

    public static void login(String value) {
        if (value == null || !LOGIN.matcher(value).matches()) {
            throw new InvalidValueException("login",
                    "login must match ^[a-z0-9][a-z0-9._-]{1,63}$");
        }
    }

    public static void email(String value) {
        if (value == null || value.isEmpty()) {
            throw new InvalidValueException("email", "email is required");
        }
        try {
            InternetAddress addr = new InternetAddress(value, true);
            addr.validate();
        } catch (AddressException e) {
            throw new InvalidValueException("email",
                    "email is not a valid RFC 5322 address: " + e.getMessage());
        }
    }

    public static void provider(String value) {
        if (value == null || !PROVIDERS.contains(value)) {
            throw new InvalidValueException("provider",
                    "provider must be one of " + PROVIDERS);
        }
    }

    public static List<String> groups(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> validatedGroups = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String projectName = raw.trim();
            if (projectName.isEmpty()) continue;
            if (!Project.NAME_PATTERN.matcher(projectName).matches()) {
                throw new InvalidValueException("groups",
                        "project name '" + projectName + "' must match ^[a-z0-9][a-z0-9-]{1,63}$");
            }
            validatedGroups.add(projectName);
        }
        return validatedGroups;
    }
    public static boolean password(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException("password", "password is required");
        }
        return true;
    }

    public static void projectName(String value) {
        if (value == null || !Project.NAME_PATTERN.matcher(value).matches()) {
            throw new InvalidValueException("projectName",
                    "project name must match " + Project.NAME_REGEX);
        }
    }

    public static Role projectRole(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new InvalidValueException("role",
                    "role must be one of admin|editor|member|visitor");
        }
        return switch (alias.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "admin"   -> Role.PROJECT_ADMIN;
            case "editor"  -> Role.PROJECT_EDITOR;
            case "member"  -> Role.PROJECT_MEMBER;
            case "visitor" -> Role.PROJECT_VISITOR;
            default -> throw new InvalidValueException("role",
                    "role must be one of admin|editor|member|visitor");
        };
    }

    public static void allowFromMask(String value) {
        if (value == null || !Project.ALLOW_FROM_MASK_PATTERN.matcher(value).matches()) {
            throw new InvalidValueException("allowFromMask",
                    "allow-from-mask must match " + Project.ALLOW_FROM_MASK_REGEX + " (e.g. *.*.*.*)");
        }
    }

    public static void uri(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException("uri", "uri is required");
        }
        try {
            URI parsed = URI.create(value);
            if (parsed.getScheme() == null) {
                throw new InvalidValueException("uri", "uri must include a scheme (e.g. https://)");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidValueException("uri", "uri is not a valid RFC 3986 URI: " + e.getMessage());
        }
    }

    public static void iso8601(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException("iso8601", "date is required");
        }
        try {
            java.time.Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new InvalidValueException("iso8601",
                    "date must be ISO-8601 (e.g. 2026-05-15T10:00:00Z): " + e.getMessage());
        }
    }
}
