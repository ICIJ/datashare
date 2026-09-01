package org.icij.datashare.model;

import org.icij.datashare.text.ExtractedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Rebuilds a project's entities index from the statement store, which is the system of record: the
 *  index is a disposable projection and this is the only thing that writes it. */
public class EntitiesIndexRebuilder {
    private static final int CHUNK_SIZE = 1_000;
    private final Indexer indexer;
    private final StatementRepository statements;

    public EntitiesIndexRebuilder(Indexer indexer, StatementRepository statements) {
        this.indexer = indexer;
        this.statements = statements;
    }

    /** Drops the index and refills it, so an entity that left the store leaves the index too, and
     *  returns how many entities were indexed. Dropping rather than emptying repairs an index created
     *  before the entity mappings existed, which holds the document ones and rejects every entity, and
     *  re-creating it is what gives a project older than the entities index one at all. */
    public int rebuild(String projectId) throws IOException {
        if (projectId == null || !Project.NAME_PATTERN.matcher(projectId).matches()) {
            // an unvalidated id reaches a _delete_by_query URL path, where "*" is a whole-cluster wipe
            throw new IllegalArgumentException("Bad format for project id : '" + projectId + "'");
        }
        String indexName = Project.entitiesIndex(projectId);
        indexer.deleteIndex(indexName);
        indexer.createEntitiesIndex(projectId);
        try {
            return statements.entities(projectId, entities -> index(indexName, entities.iterator()));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private int index(String indexName, Iterator<ModelEntity> entities) {
        int written = 0;
        while (entities.hasNext()) {
            List<ExtractedEntity> chunk = new ArrayList<>(CHUNK_SIZE);
            while (chunk.size() < CHUNK_SIZE && entities.hasNext()) {
                chunk.add(ExtractedEntity.from(entities.next()));
            }
            try {
                if (!indexer.bulkAdd(indexName, chunk)) {
                    throw new UncheckedIOException(
                            new IOException("bulk add rejected in " + indexName + " for a chunk of " + chunk.size() + " entities"));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            written += chunk.size();
        }
        return written;
    }
}
