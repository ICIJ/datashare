package org.icij.datashare.batch;

import java.nio.file.Path;
import java.util.Date;

public class SearchResult {
    public final String documentId;
    public final String rootId;
    public final Path documentPath;
    public final Date creationDate;
    public final int documentNumber;

    public SearchResult(String documentId, String rootId, Path documentPath, Date creationDate, int documentNumber) {
        this.documentId = documentId;
        this.rootId = rootId;
        this.documentPath = documentPath;
        this.creationDate = creationDate;
        this.documentNumber = documentNumber;
    }
}
