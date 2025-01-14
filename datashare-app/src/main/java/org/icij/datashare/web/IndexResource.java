package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.utils.IndexAccessVerifier;
import org.icij.datashare.utils.PayloadFormatter;

import java.io.IOException;

import static net.codestory.http.payload.Payload.created;
import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api/index")
public class IndexResource {
    private final Indexer indexer;

    @Inject
    public IndexResource(Indexer indexer) {
        this.indexer = indexer;
    }
    
    @Operation(description = "Create the index for the current user if it doesn't exist.")
    @ApiResponse(responseCode = "200", description = "returns 200 if the index already exists")
    @ApiResponse(responseCode = "201", description = "returns 201 if the index has been created")
    @Put("/:index")
    public Payload createIndex(@Parameter(name = "index", description = "index to create", in = ParameterIn.PATH) final String index) throws IOException {
        try{
            return indexer.createIndex(IndexAccessVerifier.checkIndices(index)) ? created() : ok();
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(description = "Preflight for index creation.")
    @ApiResponse(responseCode = "200", description = "returns 200 with PUT")
    @ApiResponse(responseCode = "400", description = "returns 400 if there is an error from ElasticSearch")
    @Options("/:index")
    public Payload createIndexPreflight(final String index) {
        try{
            IndexAccessVerifier.checkIndices(index);
            return PayloadFormatter.allowMethods("OPTIONS", "PUT");
        }catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(description = "Head request useful for JavaScript API (for example to test if an index exists)")
    @ApiResponse(responseCode = "200", description = "returns 200")
    @ApiResponse(responseCode = "400", description = "returns 400 if there is an error from ElasticSearch")
    @Head("/search/:path:")
    public Payload esHead(final String path) throws IOException {
        try {
            return new Payload(indexer.executeRaw("HEAD", path, null));
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(description = """
            The search endpoint is just a proxy in front of Elasticsearch, everything sent is forwarded to Elasticsearch.
            
            DELETE method is not allowed.
            
            Path can be of the form :
            - _search/scroll
            - index_name/_search
            - index_name1,index_name2/_search
            - index_name/_count
            - index_name1,index_name2/_count
            - index_name/doc/_search
            - index_name1,index_name2/doc/_search
            """)
    @ApiResponse(responseCode = "200", description = "returns 200")
    @ApiResponse(responseCode = "400", description = "returns 400 if there is an error from ElasticSearch")
    @Post("/search/:path:")
    public Payload esPost(@Parameter(name = "index", description = "elasticsearch path", in = ParameterIn.PATH) final String path, Context context, final net.codestory.http.Request request) throws IOException {
        try {
            return PayloadFormatter.json(indexer.executeRaw("POST", IndexAccessVerifier.checkPath(path, context), new String(request.contentAsBytes())));
        } catch ( IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(description = """
            Search GET request to Elasticsearch. As it is a GET method, all paths are accepted.
            
            if a body is provided, the body will be sent to ES as source=urlencoded(body)&source_content_type=application%2Fjson\
            In that case, request parameters are not taken into account.""")
    @ApiResponse(responseCode = "200", description = "returns 200")
    @ApiResponse(responseCode = "400", description = "returns 400 if there is an error from ElasticSearch")
    @Get("/search/:path:")
    public Payload esGet(@Parameter(name = "path", description = "elasticsearch path", in = ParameterIn.PATH) final String path, Context context) throws IOException {
        try {
            return PayloadFormatter.json(indexer.executeRaw("GET", IndexAccessVerifier.checkPath(path, context), ""));
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(description = "Preflight request with OPTIONS")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS")
    @ApiResponse(responseCode = "400", description = "returns 400 if there is an error from ElasticSearch")
    @Options("/search/:path:")
    public Payload esOptions(final String index, final String path, Context context) throws IOException {
        try {
            IndexAccessVerifier.checkIndices(index);
            return PayloadFormatter.allowMethods(indexer.executeRaw("OPTIONS", path, null));
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }
}
