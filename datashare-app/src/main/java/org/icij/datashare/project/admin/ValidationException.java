package org.icij.datashare.project.admin;

/**
 * Service-side validation exception thrown by {@code ProjectAdminService}.
 *
 * <p>This intentionally duplicates {@code org.icij.datashare.user.admin.ValidationException}.
 * The {@code datashare-cli} module also has its own counterpart at
 * {@code org.icij.datashare.cli.Validators.InvalidValueException}. The three
 * exception types deliberately live in different modules to avoid a circular
 * dependency between {@code datashare-cli} and {@code datashare-app}.
 */
public class ValidationException extends Exception {
    private final String field;

    public ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() { return field; }
}
