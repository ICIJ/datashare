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

    /** Empties the index and refills it, so an entity that left the store leaves the index too, and
     *  returns how many entities were indexed. Creates the index first, which is what gives a project
     *  created before the entities index existed one. */
    public int rebuild(String projectId) throws IOException {
        String indexName = Project.entitiesIndex(projectId);
        indexer.createEntitiesIndex(projectId);
        indexer.deleteAll(indexName);
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
                indexer.bulkAdd(indexName, chunk);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            written += chunk.size();
        }
        return written;
    }
}
