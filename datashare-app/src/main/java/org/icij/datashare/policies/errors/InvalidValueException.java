package org.icij.datashare.policies.errors;

public class InvalidValueException extends AuthorizerException {
    public InvalidValueException(String message) {
        super(message);
    }

    public static String invalidValueIfBlank(String value) {
        if (value != null && !value.isBlank()) {
            return value;
        } else {
            throw new InvalidValueException("The parameter cannot be blank");
        }
    }

    public static String invalidValueIfNull(String value) {
        if (value != null) {
            return value;
        } else {
            throw new InvalidValueException("The parameter cannot be null");
        }
    }

    public static String invalidValueIfWildcard(String value) {
        if (value != null && !value.equals("*")) {
            return value;
        } else {
            throw new InvalidValueException("The parameter cannot be a wildcard");
        }
    }
}
