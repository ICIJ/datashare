package org.icij.datashare;

/**
 * Generic exception indicating that a requested record was not found.
 * <p>
 * Can be used for any entity type (e.g., user, project, batch, document...).
 * Note: Java forbids making {@code Throwable} subclasses generic. Instead, the
 * entity type is provided via a {@link Class} parameter in convenience constructors.
 */
public class RecordNotFoundException extends RuntimeException {

    public RecordNotFoundException() {
        super("entity not found");
    }

    public RecordNotFoundException(String message) {
        super(message);
    }

    public RecordNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Convenience constructor for messages like: "no {EntitySimpleName} with id={id} found".
     */
    public RecordNotFoundException(Class<?> recordClass, String id) {
        super("no " + recordClass.getSimpleName().toLowerCase() + " with id=" + id + " found");
    }
}
