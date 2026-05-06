package org.icij.datashare.user.admin;

/**
 * Service-side validation exception thrown by {@code UserAdminService}.
 *
 * <p>The {@code datashare-cli} module has its own counterpart at
 * {@code org.icij.datashare.cli.Validators.InvalidValueException} (note the
 * different name) since the CLI does not depend on {@code datashare-app}.
 * Do not "deduplicate" the two — that would create a circular module
 * dependency.
 */
public class ValidationException extends Exception {
    private final String field;

    public ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() { return field; }
}
