package org.icij.datashare.batch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.icij.datashare.text.PathSerializer;
import org.icij.datashare.text.ProjectProxy;

import java.nio.file.Path;
import java.util.Date;
import java.util.Objects;

public class SearchResult {
    public final String query;
    public final ProjectProxy project;
    public final String documentId;
    public final String rootId;
    @JsonSerialize(using = PathSerializer.class)
    public final Path documentPath;
    public final String contentType;
    public final long contentLength;
    public final Date creationDate;
    public final int documentNumber;

    public SearchResult(String query, String documentId, String rootId, Path documentPath, Date creationDate, String contentType, long contentLength, int documentNumber) {
        this(query, null, documentId, rootId, documentPath, creationDate, contentType, contentLength, documentNumber);
    }

    @JsonCreator
    public SearchResult(@JsonProperty("query") String query, @JsonProperty("project") final ProjectProxy project, @JsonProperty("documentId") String documentId, @JsonProperty("rootId") String rootId,
                        @JsonProperty("documentPath") Path documentPath, @JsonProperty("creationDate") Date creationDate,
                        @JsonProperty("contentType") String contentType, @JsonProperty("contentLength") long contentLength,
                        @JsonProperty("documentNumber") int documentNumber) {
        this.query = query;
        this.project = project;
        this.documentId = documentId;
        this.rootId = rootId;
        this.documentPath = documentPath;
        this.creationDate = creationDate;
        this.contentType = contentType;
        this.contentLength = contentLength;
        this.documentNumber = documentNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchResult that = (SearchResult) o;
        return query.equals(that.query) &&
                documentId.equals(that.documentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, documentId);
    }

    @Override
    public String toString() {
        return "SearchResult{" +
                "query='" + query + '\'' +
                ", documentId='" + documentId + '\'' +
                '}';
    }
}
