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
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.utils.IndexAccessVerifier;
import org.icij.datashare.utils.ModeVerifier;
import org.icij.datashare.utils.PayloadFormatter;

import java.io.IOException;

import static net.codestory.http.payload.Payload.created;
import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api/index")
public class IndexResource {
    private final Indexer indexer;
    private final ModeVerifier modeVerifier;

    @Inject
    public IndexResource(Indexer indexer, PropertiesProvider propertiesProvider) {
        this.indexer = indexer;
        this.modeVerifier = new ModeVerifier(propertiesProvider);
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

    @Operation(description = "Close an index. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "index closed successfully")
    @ApiResponse(responseCode = "400", description = "invalid index name")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Post("/:index/_close")
    public Payload closeIndex(
            @Parameter(name = "index", description = "index name to close", in = ParameterIn.PATH)
            final String index) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        try {
            return PayloadFormatter.json(indexer.executeRaw("POST", IndexAccessVerifier.checkIndices(index) + "/_close", null));
        } catch (IllegalArgumentException e) {
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(description = "Open a closed index. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "index opened successfully")
    @ApiResponse(responseCode = "400", description = "invalid index name")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Post("/:index/_open")
    public Payload openIndex(
            @Parameter(name = "index", description = "index name to open", in = ParameterIn.PATH)
            final String index) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        try {
            return PayloadFormatter.json(indexer.executeRaw("POST", IndexAccessVerifier.checkIndices(index) + "/_open", null));
        } catch (IllegalArgumentException e) {
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(description = "List all snapshot repositories. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "list of repositories")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Get("/_snapshot")
    public Payload getSnapshotRepositories() throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        return PayloadFormatter.json(indexer.executeRaw("GET", "_snapshot", null));
    }

    @Operation(description = "Get a snapshot repository configuration. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "repository configuration")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Get("/_snapshot/:repository")
    public Payload getSnapshotRepository(
            @Parameter(name = "repository", description = "snapshot repository name", in = ParameterIn.PATH)
            final String repository) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        return PayloadFormatter.json(indexer.executeRaw("GET", "_snapshot/" + repository, null));
    }

    @Operation(description = "Create or update a snapshot repository. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "repository created or updated")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Put("/_snapshot/:repository")
    public Payload createSnapshotRepository(
            @Parameter(name = "repository", description = "snapshot repository name", in = ParameterIn.PATH)
            final String repository,
            net.codestory.http.Request request) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        return PayloadFormatter.json(indexer.executeRaw("PUT", "_snapshot/" + repository, new String(request.contentAsBytes())));
    }

    @Operation(description = "Delete a snapshot repository. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "repository deleted")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Delete("/_snapshot/:repository")
    public Payload deleteSnapshotRepository(
            @Parameter(name = "repository", description = "snapshot repository name", in = ParameterIn.PATH)
            final String repository) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        return PayloadFormatter.json(indexer.executeRaw("DELETE", "_snapshot/" + repository, null));
    }

    @Operation(description = "List snapshots in a repository. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "list of snapshots")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Get("/_snapshot/:repository/_all")
    public Payload getSnapshots(
            @Parameter(name = "repository", description = "snapshot repository name", in = ParameterIn.PATH)
            final String repository) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        return PayloadFormatter.json(indexer.executeRaw("GET", "_snapshot/" + repository + "/_all", null));
    }

    @Operation(description = "Get a specific snapshot. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "snapshot details")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Get("/_snapshot/:repository/:snapshot")
    public Payload getSnapshot(
            @Parameter(name = "repository", description = "snapshot repository name", in = ParameterIn.PATH)
            final String repository,
            @Parameter(name = "snapshot", description = "snapshot name", in = ParameterIn.PATH)
            final String snapshot) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        return PayloadFormatter.json(indexer.executeRaw("GET", "_snapshot/" + repository + "/" + snapshot, null));
    }

    @Operation(description = "Create a snapshot. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "snapshot creation started")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Put("/_snapshot/:repository/:snapshot")
    public Payload createSnapshot(
            @Parameter(name = "repository", description = "snapshot repository name", in = ParameterIn.PATH)
            final String repository,
            @Parameter(name = "snapshot", description = "snapshot name", in = ParameterIn.PATH)
            final String snapshot,
            Context context,
            net.codestory.http.Request request) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        String waitForCompletion = context.query().get("wait_for_completion");
        String path = "_snapshot/" + repository + "/" + snapshot;
        if (waitForCompletion != null) {
            path += "?wait_for_completion=" + waitForCompletion;
        }
        String body = request.contentAsBytes().length > 0 ? new String(request.contentAsBytes()) : null;
        return PayloadFormatter.json(indexer.executeRaw("PUT", path, body));
    }

    @Operation(description = "Restore a snapshot. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "restore started")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Post("/_snapshot/:repository/:snapshot/_restore")
    public Payload restoreSnapshot(
            @Parameter(name = "repository", description = "snapshot repository name", in = ParameterIn.PATH)
            final String repository,
            @Parameter(name = "snapshot", description = "snapshot name", in = ParameterIn.PATH)
            final String snapshot,
            Context context,
            net.codestory.http.Request request) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        String waitForCompletion = context.query().get("wait_for_completion");
        String path = "_snapshot/" + repository + "/" + snapshot + "/_restore";
        if (waitForCompletion != null) {
            path += "?wait_for_completion=" + waitForCompletion;
        }
        String body = request.contentAsBytes().length > 0 ? new String(request.contentAsBytes()) : null;
        return PayloadFormatter.json(indexer.executeRaw("POST", path, body));
    }

    @Operation(description = "Delete a snapshot. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "snapshot deleted")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Delete("/_snapshot/:repository/:snapshot")
    public Payload deleteSnapshot(
            @Parameter(name = "repository", description = "snapshot repository name", in = ParameterIn.PATH)
            final String repository,
            @Parameter(name = "snapshot", description = "snapshot name", in = ParameterIn.PATH)
            final String snapshot) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        return PayloadFormatter.json(indexer.executeRaw("DELETE", "_snapshot/" + repository + "/" + snapshot, null));
    }

    @Operation(description = "Get cluster nodes settings. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "nodes settings")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Get("/_nodes/settings")
    public Payload getNodesSettings() throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        return PayloadFormatter.json(indexer.executeRaw("GET", "_nodes/settings", null));
    }

    @Operation(description = "Get cluster nodes info. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "nodes info")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Get("/_nodes")
    public Payload getNodes() throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        return PayloadFormatter.json(indexer.executeRaw("GET", "_nodes", null));
    }

    @Operation(description = "Get cluster settings. Only available in LOCAL and EMBEDDED modes.")
    @ApiResponse(responseCode = "200", description = "cluster settings")
    @ApiResponse(responseCode = "403", description = "operation not allowed in current mode")
    @Get("/_cluster/settings")
    public Payload getClusterSettings() throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        return PayloadFormatter.json(indexer.executeRaw("GET", "_cluster/settings", null));
    }
}
