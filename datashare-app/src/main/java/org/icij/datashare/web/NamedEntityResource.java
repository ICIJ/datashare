package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Entity;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;

import java.io.IOException;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static net.codestory.http.payload.Payload.ok;

@Prefix("/api")
public class NamedEntityResource {
    private final Indexer indexer;

    @Inject
    public NamedEntityResource(final Indexer indexer) {
        this.indexer = indexer;
    }

    /**
     * Returns the named entity with given id and document id.
     *
     * @param id
     * @param documentId the root document
     * @return 200
     *
     * Example :
     * $($ curl "localhost:8080/api/apigen-datashare/namedEntities/4c262715b69f33e9ba69c794cc37ce6a90081fa124ca2ef67ab4f0654c72cb250e08f1f8455fbf8e4331f8955300c83a?routing=bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f")
     */
    @Get("/:project/namedEntities/:id?routing=:documentId")
    public NamedEntity getById(final String project, final String id, final String documentId) {
        return notFoundIfNull(indexer.get(project, id, documentId));
    }

    /**
     * preflight request for hide
     * @param mentionNorm
     * @return 200 PUT
     */
    @Options("/:project/namedEntities/hide/:mentionNorm")
    public Payload hidePreflight(final String project, final String mentionNorm) {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    /**
     * hide all named entities with the given normalized mention
     *
     * @param mentionNorm
     * @param project
     * @return 200
     *
     * Example :
     * $(curl -i -XPUT localhost:8080/api/namedEntities/hide/xlsx)
     */
    @Put("/:project/namedEntities/hide/:mentionNorm")
    public Payload hide(final String project, final String mentionNorm) throws IOException {
        List<? extends Entity> nes = indexer.search(project, NamedEntity.class).
                thatMatchesFieldValue("mentionNorm", mentionNorm).execute().map(ne -> ((NamedEntity)ne).hide()).collect(toList());
        indexer.bulkUpdate(project, nes);
        return ok();
    }
}
