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
import org.icij.datashare.ftm.FtmDocument;
import org.icij.datashare.text.indexing.Indexer;

@Singleton
@Prefix("/api/ftm")
public class FtmResource {
    private final Indexer indexer;

    @Inject
    public FtmResource(Indexer indexer) {
        this.indexer = indexer;
    }

    @Operation(description = "Get the document from its project and id (content hash)")
    @ApiResponse(responseCode = "200", description = "returns the hashed key JSON",
            content = { @Content(examples = { @ExampleObject(value="")} )})
    @Get("/:project/:docId")
    public FtmDocument getDocument(@Parameter(name = "project", description = "project identifier", in = ParameterIn.PATH) String project,
                                   @Parameter(name = "docId", description = "document identifier", in = ParameterIn.PATH) String docId) throws Exception {
        return new FtmDocument(indexer.get(project, docId));
    }
}
