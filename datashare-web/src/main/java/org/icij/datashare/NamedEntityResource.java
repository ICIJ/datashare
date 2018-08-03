package org.icij.datashare;

import com.google.inject.Inject;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;

import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static org.icij.datashare.user.User.local;

@Prefix("/api/")
public class NamedEntityResource {
    private final Indexer indexer;

    @Inject
    public NamedEntityResource(final Indexer indexer) {
        this.indexer = indexer;
    }

    @Get("/namedEntity/:id?routing=:documentId")
    public NamedEntity getById(final String id, final String documentId) {
        return notFoundIfNull(indexer.get(local().indexName(), id, documentId));
    }
}
