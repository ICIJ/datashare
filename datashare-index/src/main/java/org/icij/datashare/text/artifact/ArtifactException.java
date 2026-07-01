package org.icij.datashare.text.artifact;

/** A type-level produce failure. Caught per-type by the producer so siblings still run. */
public class ArtifactException extends Exception {
    public ArtifactException(String message, Throwable cause) {
        super(message, cause);
    }
}
