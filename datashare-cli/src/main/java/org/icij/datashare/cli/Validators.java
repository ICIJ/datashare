package org.icij.datashare.cli;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
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

    private static final Pattern LOGIN   = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,63}$");
    private static final Pattern PROJECT = Pattern.compile(Project.NAME_REGEX);
    private static final Pattern ALLOW_FROM_MASK = Pattern.compile(Project.ALLOW_FROM_MASK_REGEX);
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
        if (!PROVIDERS.contains(value)) {
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
            if (!PROJECT.matcher(projectName).matches()) {
                throw new InvalidValueException("groups",
                        "project name '" + projectName + "' must match ^[a-z0-9][a-z0-9-]{1,63}$");
            }
            validatedGroups.add(projectName);
        }
        return validatedGroups;
    }

    public static void projectName(String value) {
        if (value == null || !PROJECT.matcher(value).matches()) {
            throw new InvalidValueException("projectName",
                    "project name must match " + Project.NAME_REGEX);
        }
    }

    public static void allowFromMask(String value) {
        if (value == null || !ALLOW_FROM_MASK.matcher(value).matches()) {
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
