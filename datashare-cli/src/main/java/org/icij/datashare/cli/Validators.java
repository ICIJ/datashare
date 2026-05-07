package org.icij.datashare.cli;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.icij.datashare.user.User;

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
    private static final Pattern PROJECT = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");
    private static final Set<String> PROVIDERS = Set.of(User.LOCAL, User.OAUTH, User.EXTERNAL);

    private Validators() {}

    public static String login(String value) {
        if (value == null || !LOGIN.matcher(value).matches()) {
            throw new InvalidValueException("login",
                    "login must match ^[a-z0-9][a-z0-9._-]{1,63}$");
        }
        return value;
    }

    public static String email(String value) {
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
        return value;
    }

    public static String provider(String value) {
        if (!PROVIDERS.contains(value)) {
            throw new InvalidValueException("provider",
                    "provider must be one of " + PROVIDERS);
        }
        return value;
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
}
