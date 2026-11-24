package org.icij.datashare;

/**
 * What a stage throws to stop the run instead of failing one item: a configuration that would fail every item the
 * same way. Unchecked on purpose, so that it flows through the per-item handling of the stages. Any other exception
 * of a stage is the failure of the item it was working on.
 */
public class FatalStageException extends RuntimeException {
    public FatalStageException(String message) {
        super(message);
    }

    public FatalStageException(Throwable cause) {
        super(cause);
    }
}
