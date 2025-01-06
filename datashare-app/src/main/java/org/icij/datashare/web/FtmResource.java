package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.Entity;
import org.icij.datashare.ftm.FtmDocument;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;

import java.util.Objects;

import static java.util.Optional.ofNullable;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;

@Singleton
@Prefix("/api/ftm")
public class FtmResource {
    private final Indexer indexer;

    @Inject
    public FtmResource(Indexer indexer) {
        this.indexer = indexer;
    }

    @Operation(description = "Get the [FtM](https://followthemoney.tech/) document from its project and id (content hash)")
    @ApiResponse(responseCode = "200", description = "returns the JSON document", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "if no document is found with provided id")
    @Get("/:project/:docId")
    public FtmDocument getDocument(@Parameter(name = "project", description = "project identifier", in = ParameterIn.PATH) String project,
                                   @Parameter(name = "docId", description = "document identifier", in = ParameterIn.PATH) String docId) throws Exception {
        return notFoundIfNull(ofNullable(indexer.get(project, docId)).map(d -> new FtmDocument((Document) d)).orElse(null));
    }
}
