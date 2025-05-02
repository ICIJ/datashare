package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.*;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.User;
import org.icij.datashare.utils.PayloadFormatter;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static net.codestory.http.payload.Payload.*;
import static org.icij.datashare.function.ThrowingFunctions.parseBoolean;

@Singleton
@Prefix("/api/batch")
public class BatchSearchResource {
    private final BatchSearchRepository batchSearchRepository;
    private final PropertiesProvider propertiesProvider;

    @Inject
    public BatchSearchResource(PropertiesProvider propertiesProvider, final BatchSearchRepository batchSearchRepository) {
        this.batchSearchRepository = batchSearchRepository;
        this.propertiesProvider = propertiesProvider;
    }

    @Operation(description = """
            Retrieves the batch search list for the user issuing the request filter with the given criteria, and the total of batch searches matching the criteria.
            
            If from/size are not given their default values are 0, meaning that all the results are returned. BatchDate must be a list of 2 items (the first one for the starting date and the second one for the ending date) If defined publishState is a string equals to "0" or "1\"""",
            requestBody = @RequestBody(description = "the json webQuery request body", required = true,  content = @Content(schema = @Schema(implementation = BatchSearchRepository.WebQuery.class)))
    )
    @ApiResponse(responseCode = "200", description = "the list of batch searches with the total batch searches for the query", useReturnTypeSchema = true)
    @Post("/search")
    public WebResponse<BatchSearchRecord> getBatchSearchesFiltered(BatchSearchRepository.WebQuery webQuery, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        return new WebResponse<>(batchSearchRepository.getRecords(user, user.getProjectNames(), webQuery), webQuery.from, webQuery.size,
                batchSearchRepository.getTotal(user, user.getProjectNames(), webQuery));
    }
    @Operation(description = "Retrieves the list of batch searches",
            parameters ={
                    @Parameter(name = "query", in = ParameterIn.QUERY, description = "'freetext' search filter. Empty string or '*' to select all. Default is '*'"),
                    @Parameter(name = "field", in = ParameterIn.QUERY, description = "specifies field on query filter ('all','author'...). Default is 'all' "),
                    @Parameter(name = "queries", in = ParameterIn.QUERY, description = "list of selected queries in the batch search (to invert selection put 'queriesExcluded' parameter to true)"),
                    @Parameter(name = "queriesExcluded", in = ParameterIn.QUERY, description = "Associated with 'queries', if true it excludes the listed queries from the results"),
                    @Parameter(name = "contentTypes", in = ParameterIn.QUERY, description = "filters by contentTypes"),
                    @Parameter(name = "project", in = ParameterIn.QUERY, description = "filters by projects. Empty array corresponds to no projects"),
                    @Parameter(name = "batchDate", in = ParameterIn.QUERY, description = "filters by date range timestamps with [dateStart, dateEnd]"),
                    @Parameter(name = "state", in = ParameterIn.QUERY, description = "filters by task status (RUNNING, QUEUED, DONE, FAILED)"),
                    @Parameter(name = "publishState", in = ParameterIn.QUERY, description = "filters by published state (0: private to the user, 1: public on the platform)"),
                    @Parameter(name = "withQueries", in = ParameterIn.QUERY, description = "boolean, if true it includes list of queries"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, description = "if not provided default is 100"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, description = "if not provided it starts from 0"),
            })
    @Get("/search")
    public WebResponse<BatchSearchRecord> getSearchesFiltered(Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        int from = Integer.parseInt(ofNullable(context.get("from")).orElse("0"));
        int size = Integer.parseInt(ofNullable(context.get("size")).orElse("100"));
        List<String> queries = ofNullable(context.get("queries")).map(q-> List.of(q.split(","))).orElse(null);
        List<String> project = ofNullable(context.get("project")).map(q-> List.of(q.split(","))).orElse(null);
        List<String> batchDate = ofNullable(context.get("batchDate")).map(q-> List.of(q.split(","))).orElse(null);
        List<String> state =ofNullable(context.get("state")).map(q->  List.of(q.split(","))).orElse(null);
        List<String> contentTypes = ofNullable(context.get("contentTypes")).map(q-> List.of(q.split(","))).orElse(null);
        String query = ofNullable(context.get("query")).orElse("*");
        String field = ofNullable(context.get("field")).orElse("all");
        String sort = ofNullable(context.get("sort")).orElse("doc_nb");
        String order = ofNullable(context.get("order")).orElse("asc");
        String publishState = ofNullable(context.get("publishState")).filter(q -> q.equals("0") || q.equals("1")).orElse(null);
        boolean withQueries = ofNullable(context.get("withQueries")).map(parseBoolean).orElse(false);
        boolean queriesExcluded = ofNullable(context.get("queriesExcluded")).map(parseBoolean).orElse(false);

        BatchSearchRepository.WebQuery webQuery = new BatchSearchRepository.WebQuery(
                size,
                from,
                sort,
                order,
                query,
                field,
                queries,
                project,
                batchDate,
                state,
                publishState,
                withQueries,
                queriesExcluded,
                contentTypes
        );

        return new WebResponse<>(
                batchSearchRepository.getRecords(user, user.getProjectNames(), webQuery), webQuery.from, webQuery.size,
                batchSearchRepository.getTotal(user, user.getProjectNames(), webQuery));
    }


    @Operation(description = "Retrieves the batch search with the given id. The query param \"withQueries\" accepts a boolean value." +
            "When \"withQueries\" is set to false, the list of queries is empty and nbQueries contains the number of queries.")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BatchSearch.class)))
    @ApiResponse(responseCode = "404", description = "if batchsearch is not found")
    @Get("/search/:batchid")
    public Payload getBatch(@Parameter(name = "batchId", in = ParameterIn.PATH) String batchId, Context context) {
        boolean withQueries = Boolean.parseBoolean(context.get("withQueries"));
        BatchSearch batchSearch = batchSearchRepository.get((User) context.currentUser(), batchId, withQueries);
        return batchSearch == null ? PayloadFormatter.error("Batch search not found.", HttpStatus.NOT_FOUND) : new Payload(batchSearch);
    }

    @Operation(description = "Retrieves the batch search queries with the given batch id and returns a list of strings UTF-8 encoded",
                parameters = {@Parameter(name = "from", description = "if not provided it starts from 0", in = ParameterIn.QUERY),
                              @Parameter(name = "size", description = "if not provided all queries are returned from the \"from\" parameter", in = ParameterIn.QUERY),
                              @Parameter(name = "format", description = "if set to csv, it answers with content-disposition attachment (file downloading)", in = ParameterIn.QUERY),
                              @Parameter(name = "search", description = "if provided it will filter the queries accordingly", in = ParameterIn.QUERY),
                              @Parameter(name = "sort", description = "field name to sort by, \"query_number\" by default (if it does not exist it will return a 500 error)", in = ParameterIn.QUERY),
                              @Parameter(name = "order", description = "order to sort by, \"asc\" by default (if it does not exist it will return a 500 error)", in = ParameterIn.QUERY),
                              @Parameter(name = "maxResult", description = "number of maximum results for each returned query (-1 means no maxResults)", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "the batch search queries map [(query, nbResults), ...]")
    @Get("/search/:batchid/queries")
    public Payload getBatchQueries(@Parameter(name = "batchId", description = "identifier of the batch search", in = ParameterIn.PATH) String batchId, Context context) {
        User user = (User) context.currentUser();
        int from = Integer.parseInt(ofNullable(context.get("from")).orElse("0"));
        int size = Integer.parseInt(ofNullable(context.get("size")).orElse("0"));
        String search = context.get("search");
        String sort = context.get("sort");
        String order = context.get("order");
        int maxResults = Integer.parseInt(ofNullable(context.get("maxResults")).orElse("-1"));

        Map<String, Integer> queries = batchSearchRepository.getQueries(user, batchId, from, size, search, sort, order, maxResults);

        if ("csv".equals(context.get("format"))) {
            String contentType = "text/csv;charset=UTF-8";
            String queriesFilename = batchId + "-queries.csv";
            String body = String.join("\n", queries.keySet());
            return new Payload(contentType, body). withHeader("Content-Disposition", "attachment;filename=\"" + queriesFilename + "\"");
        }
        return new Payload(queries);
    }

    @Operation(description = "Preflight request")
    @ApiResponse(responseCode = "200", description = "returns 200 with DELETE")
    @Options("/search")
    public Payload optionsSearches(Context context) {
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    @Operation(description = "Preflight request")
    @ApiResponse(responseCode = "200", description = "returns 200 with DELETE")
    @Options("/search/:batchid")
    public Payload optionsDelete(String batchId, Context context) {
        return ok().withAllowMethods("OPTIONS", "DELETE", "PATCH");
    }

    @Operation(description = "Deletes a batch search and its results with the given id. It won't delete running batch searches, because results would be orphans.")
    @ApiResponse(responseCode = "204", description = "Returns 204 (No Content) : idempotent")
    @Delete("/search/:batchid")
    public Payload deleteBatch(String batchId, Context context) {
        batchSearchRepository.delete((User) context.currentUser(), batchId);
        return new Payload(204);
    }

    @Operation(description = "Updates a batch search with the given id.",
            requestBody = @RequestBody(content = @Content(schema = @Schema(implementation = JsonData.class))))
    @ApiResponse(responseCode = "404", description = "If the user issuing the request is not the same as the batch owner in database, it will do nothing (thus returning 404)")
    @ApiResponse(responseCode = "200", description = "If the batch has been updated")
    @Patch("/search/:batchid")
    public Payload updateBatch(String batchId, Context context, JsonData data) {
        if (batchSearchRepository.publish((User) context.currentUser(), batchId, data.asBoolean("published"))) {
            return ok();
        }
        return notFound();
    }

    @Operation( description = """
            Retrieves the results of a batch search as JSON with a list of items and a pagination metadata.
            
            If from/size are not given their default values are 0, meaning that all the results are returned.""",
                requestBody = @RequestBody(
                        required = true,
                        description = "filter ",
                        content = @Content(schema = @Schema(implementation = BatchSearchRepository.WebQuery.class))
                ),
                parameters = { @Parameter(name = "batchId", description = "id of the batchsearch") }
    )
    @Post("/search/result/:batchid")
    public WebResponse<SearchResult> getResult(String batchId, BatchSearchRepository.WebQuery webQuery, Context context) {
        return getResultsOrThrowUnauthorized(batchId, (User) context.currentUser(), webQuery);
    }

    @Operation( description = "Retrieves the results of a batch search as an attached CSV file.",
                parameters = {@Parameter(name = "batchid")}
    )
    @ApiResponse(responseCode = "200", description = "returns the results of the batch search as CSV attached file.")
    @Get("/search/result/csv/:batchid")
    public Payload getResultAsCsv(String batchId, Context context) {
        // Define the CSV header
        final String CSV_HEADER = "query,documentUrl,documentId,rootId,contentType,contentLength,documentPath,documentDirname,creationDate,documentNumber";

        // Create a StringBuilder to build the CSV content
        StringBuilder builder = new StringBuilder();
        builder.append(CSV_HEADER).append("\n");

        // Get the current user and batch search information
        User currentUser = (User) context.currentUser();
        BatchSearch batchSearch = batchSearchRepository.get(currentUser, batchId);
        String url = propertiesProvider.get("rootHost").orElse(context.header("Host"));
        BatchSearchRepository.WebQuery webQuery = WebQueryBuilder.createWebQuery().queryAll().build();
        WebResponse<SearchResult> results = getResultsOrThrowUnauthorized(batchId, (User) context.currentUser(), webQuery);

        // Iterate through the results and construct CSV lines
        results.items.forEach(result -> {
            String docUrl = docUrl(url, batchSearch.projects, result.documentId, result.rootId);
            String dirname = dirname(result.documentPath);

            builder.append("\"").append(result.query).append("\",");
            builder.append("\"").append(docUrl).append("\",");
            builder.append("\"").append(result.documentId).append("\",");
            builder.append("\"").append(result.rootId).append("\",");
            builder.append("\"").append(result.contentType).append("\",");
            builder.append("\"").append(result.contentLength).append("\",");
            builder.append("\"").append(result.documentPath).append("\",");
            builder.append("\"").append(dirname).append("\",");
            builder.append("\"").append(result.creationDate).append("\",");
            builder.append("\"").append(result.documentNumber).append("\"\n");
        });

        return new Payload("text/csv", builder.toString())
                .withHeader("Content-Disposition", "attachment;filename=\"" + batchId + ".csv\"");
    }


    @Operation(description = "Deletes batch searches and results for the current user.")
    @ApiResponse(responseCode = "204", description = "no content: idempotent")
    @Delete("/search")
    public Payload deleteSearches(Context context) {
        batchSearchRepository.deleteAll((User) context.currentUser());
        return new Payload(204);
    }

    private String docUrl(String uri, List<ProjectProxy> projects, String documentId, String rootId) {
        return format("%s/#/d/%s/%s/%s", uri, projects.stream().map(ProjectProxy::getId).collect(Collectors.joining(",")), documentId, rootId);
    }

    private String dirname(Path path) {
        return path.getParent().toString();
    }

    private WebResponse<SearchResult> getResultsOrThrowUnauthorized(String batchId, User user, BatchSearchRepository.WebQuery webQuery) {
        try {
            return new WebResponse<>(batchSearchRepository.getResults(user, batchId, webQuery), webQuery.from,webQuery.size,
                    batchSearchRepository.getResultsTotal(user,batchId,webQuery));
        } catch (JooqBatchSearchRepository.UnauthorizedUserException unauthorized) {
            throw new UnauthorizedException();
        }
    }
}
