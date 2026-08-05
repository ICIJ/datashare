package org.icij.datashare.text.artifact;

/** A producer configuration that will fail every document the same way, not one document going wrong.
 *  Unchecked on purpose: it passes straight through the producer's per-type catch so the run ends where
 *  an Error would, instead of being counted once per remaining document. */
public class ArtifactConfigurationException extends RuntimeException {
    public ArtifactConfigurationException(Throwable cause) {
        super(cause);
    }
}
