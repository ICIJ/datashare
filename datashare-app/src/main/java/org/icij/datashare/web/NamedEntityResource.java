package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Entity;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;

import java.io.IOException;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static net.codestory.http.payload.Payload.ok;

@Prefix("/api/")
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
     * Example : $($ curl "localhost:8080/api/namedEntity/ab994e364f51ce59f93cf464c66e01c84c6c013a0703d53a4a01bfa1bbaa3d99bc7936aa49914dd5fc6dfd2e4c8011db?routing=5c98cae06574d8110c6ece3c934fd910ee057a2d07875858e438f92f3bc99529")
     */
    @Get("/namedEntity/:id?routing=:documentId")
    public NamedEntity getById(final String id, final String documentId, Context context) {
        return notFoundIfNull(indexer.get(((User)context.currentUser()).projectName(), id, documentId));
    }

    /**
     * preflight request for hide
     * @param mentionNorm
     * @return 200 PUT
     */
    @Options("/namedEntity/hide/:mentionNorm")
    public Payload hide(final String mentionNorm) {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    /**
     * hide all named entities with the given normalized mention
     *
     * @param mentionNorm
     * @return 200
     *
     * Example : $(curl -i -XPUT localhost:8080/api/namedEntity/hide/xlsx)
     */
    @Put("/namedEntity/hide/:mentionNorm")
    public Payload hide(final String mentionNorm, Context context) throws IOException {
        List<? extends Entity> nes = indexer.search(((User) context.currentUser()).projectName(), NamedEntity.class).
                thatMatchesFieldValue("mentionNorm", mentionNorm).execute().map(ne -> ((NamedEntity)ne).hide()).collect(toList());
        indexer.bulkUpdate(((User)context.currentUser()).projectName(), nes);
        return ok();
    }
}
