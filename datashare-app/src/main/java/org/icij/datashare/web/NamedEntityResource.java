package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Entity;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static java.util.Collections.*;
import static java.util.stream.Collectors.toList;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api")
public class NamedEntityResource {
    private final Indexer indexer;

    @Inject
    public NamedEntityResource(final Indexer indexer) {
        this.indexer = indexer;
    }

    @Operation(description = "Returns the named entity given an id and a document id.")
    @ApiResponse(responseCode = "200", description = "returns the pipeline set", useReturnTypeSchema = true)
    @Get("/:project/namedEntities/:id?routing=:documentId")
    public NamedEntity getById(@Parameter(name = "project", description = "current project", in = ParameterIn.PATH) final String project,
                               @Parameter(name = "id", description = "named entity id", in = ParameterIn.PATH) final String id,
                               @Parameter(name = "documentId", description = "documentId the root document", in = ParameterIn.PATH) final String documentId) {
        return notFoundIfNull(indexer.get(project, id, documentId));
    }

    @Operation(description = "Preflight request for hide endpoint")
    @ApiResponse(responseCode = "200", description = "returns PUT")
    @Options("/:project/namedEntities/hide/:mentionNorm")
    public Payload hidePreflight(final String project, final String mentionNorm) {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    @Operation(description = "Hide all named entities with the given normalized mention")
    @ApiResponse(responseCode = "200", description = "returns 200 OK")
    @Put("/:project/namedEntities/hide/:mentionNorm")
    public Payload hide(@Parameter(name = "project", description = "current project", in = ParameterIn.PATH) final String project,
                        @Parameter(name = "mentionNorm", description = "normalized mention", in = ParameterIn.PATH) final String mentionNorm) throws IOException {
        List<? extends Entity> nes = indexer.search(singletonList(project), NamedEntity.class).
                thatMatchesFieldValue("mentionNorm", mentionNorm).execute().map(ne -> ((NamedEntity)ne).hide()).collect(toList());
        indexer.bulkUpdate(project, nes);
        return ok();
    }
}
