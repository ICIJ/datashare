package org.icij.datashare.text.artifact;

/** Content no parser can read, as opposed to a produce failure a re-run could get past. Recorded as
 *  processed-with-no-payload rather than retried, so a corpus's truncated and mislabelled files do not
 *  fail a document and re-log at ERROR on every ARTIFACT run. */
public class UnreadableContentException extends ArtifactException {
    final String documentId;

    public UnreadableContentException(String documentId, Throwable cause) {
        super("no parser can read the content of document \"%s\" (%s)".formatted(documentId, cause), cause);
        this.documentId = documentId;
    }
}
