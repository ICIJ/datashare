package org.icij.datashare;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
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

    @Get("/namedEntity/:id?routing=:documentId")
    public NamedEntity getById(final String id, final String documentId, Context context) {
        return notFoundIfNull(indexer.get(((User)context.currentUser()).projectName(), id, documentId));
    }

    @Options("/namedEntity/hide/:mentionNorm")
    public Payload hide(final String mentionNorm) {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    @Put("/namedEntity/hide/:mentionNorm")
    public Payload hide(final String mentionNorm, Context context) throws IOException {
        List<? extends Entity> nes = indexer.search(((User) context.currentUser()).projectName(), NamedEntity.class).
                withFieldValue("mentionNorm", mentionNorm).execute().map(ne -> ((NamedEntity)ne).hide()).collect(toList());
        indexer.bulkUpdate(((User)context.currentUser()).projectName(), nes);
        return ok();
    }
}
