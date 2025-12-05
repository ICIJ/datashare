package org.icij.datashare;

/**
 * Generic exception indicating that a requested entity/record was not found.
 * <p>
 * Can be used for any entity type (e.g., user, project, batch, document...).
 * Note: Java forbids making {@code Throwable} subclasses generic. Instead, the
 * entity type is provided via a {@link Class} parameter in convenience constructors.
 */
public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException() {
        super("entity not found");
    }

    public EntityNotFoundException(String message) {
        super(message);
    }

    public EntityNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Convenience constructor for messages like: "no {EntitySimpleName} with id={id} found".
     */
    public EntityNotFoundException(Class<?> entityClass, String id) {
        super("no " + entityClass.getSimpleName().toLowerCase() + " with id=" + id + " found");
    }

    /**
     * Convenience factory for messages like: "{EntitySimpleName} not found".
     */
    public static EntityNotFoundException ofType(Class<?> entityClass) {
        return new EntityNotFoundException(entityClass.getSimpleName().toLowerCase() + " not found");
    }
}
