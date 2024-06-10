package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.IndexParent;
import org.icij.datashare.text.indexing.IndexRoot;
import org.icij.datashare.text.indexing.IndexType;

import java.nio.file.Path;

@IndexType("Duplicate")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Duplicate implements Entity {
    private final String id;
    @JsonDeserialize(using = PathDeserializer.class)
    public final Path path;
    @IndexParent
    @IndexRoot
    public final String documentId;

    public Duplicate(final Path path, final String docId) {
        this(path, docId, DEFAULT_DIGESTER);
    }

    public Duplicate(final Path path, final String docId, Hasher hasher) {
        this(hasher.hash(path.toString()), path, docId);
    }

    @JsonCreator
    private Duplicate(@JsonProperty("id") final String id,
                     @JsonProperty("path") final Path path,
                     @JsonProperty("documentId") final String docId) {
        this.id = id;
        this.path = path;
        this.documentId = docId;
    }

    @Override
    public String getId() { return id;}
}
