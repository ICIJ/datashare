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
import net.codestory.http.Part;
import net.codestory.http.annotations.Delete;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Patch;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.batch.WebQueryBuilder;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.icij.datashare.utils.PayloadFormatter;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static net.codestory.http.payload.Payload.badRequest;
import static net.codestory.http.payload.Payload.notFound;
import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.CollectionUtils.asSet;

@Singleton
@Prefix("/api/batch")
public class BatchSearchResource {
    private final BatchSearchRepository batchSearchRepository;
    private final BlockingQueue<String> batchSearchQueue;
    private final PropertiesProvider propertiesProvider;
    private final int MAX_BATCH_SIZE = 60000;

    @Inject
    public BatchSearchResource(final BatchSearchRepository batchSearchRepository, BlockingQueue<String> batchSearchQueue, PropertiesProvider propertiesProvider) {
        this.batchSearchRepository = batchSearchRepository;
        this.batchSearchQueue = batchSearchQueue;
        this.propertiesProvider = propertiesProvider;
    }


    @Operation(description = "Retrieve the batch search list for the user issuing the request")
    @ApiResponse(description = "200 and the list of batch search")
    @Get("/search")
    public List<BatchSearchRecord> getSearches(Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        return batchSearchRepository.getRecords(user, user.getProjectNames());
    }

    @Operation(description = "Retrieve the batch search list for the user issuing the request filter with the given criteria, and the total of batch searches matching the criteria.<br>" +
            "If from/size are not given their default values are 0, meaning that all the results are returned. BatchDate must be a list of 2 items (the first one for the starting date and the second one for the ending date) If defined publishState is a string equals to \"0\" or \"1\"",
            requestBody = @RequestBody(description = "the json webQuery request body", required = true,  content = @Content(schema = @Schema(implementation = BatchSearchRepository.WebQuery.class)))
    )
    @ApiResponse(responseCode = "200", description = "the list of batch searches with the total batch searches for the query", useReturnTypeSchema = true)
    @Post("/search")
    public WebResponse<BatchSearchRecord> getSearchesFiltered(BatchSearchRepository.WebQuery webQuery, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        return new WebResponse<>(batchSearchRepository.getRecords(user, user.getProjectNames(), webQuery),
                batchSearchRepository.getTotal(user, user.getProjectNames(), webQuery));
    }

    @Operation(description = "Retrieve the batch search with the given id. The query param \"withQueries\" accepts a boolean value." +
            "When \"withQueries\" is set to false, the list of queries is empty and nbQueries contains the number of queries.")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Get("/search/:batchid")
    public BatchSearch getBatch(@Parameter(name = "batchId", in = ParameterIn.PATH) String batchId, Context context) {
        boolean withQueries = Boolean.parseBoolean(context.get("withQueries"));
        return notFoundIfNull(batchSearchRepository.get((User) context.currentUser(), batchId, withQueries));
    }

    @Operation(description = "Retrieve the batch search queries with the given batch id and returns a list of strings UTF-8 encoded",
                parameters = {@Parameter(name = "from", description = "if not provided it starts from 0", in = ParameterIn.QUERY),
                              @Parameter(name = "size", description = "if not provided all queries are returned from the \"from\" parameter", in = ParameterIn.QUERY),
                              @Parameter(name = "format", description = "if set to csv, answer with content-disposition attachment (file downloading)", in = ParameterIn.QUERY),
                              @Parameter(name = "search", description = "if provided it will filter the queries accordingly", in = ParameterIn.QUERY),
                              @Parameter(name = "orderBy", description = "field name to order by asc, \"query_number\" by default (if it does not exist it will return a 500 error)", in = ParameterIn.QUERY),
                              @Parameter(name = "maxResult", description = "number of maximum results for each returned query (-1 means no maxResults)", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "the batch search queries map [(query, nbResults), ...]")
    @Get("/search/:batchid/queries")
    public Payload getBatchQueries(@Parameter(name = "batchId", description = "identifier of the batch search", in = ParameterIn.PATH) String batchId, Context context) {
        User user = (User) context.currentUser();
        int from = Integer.parseInt(ofNullable(context.get("from")).orElse("0"));
        int size = Integer.parseInt(ofNullable(context.get("size")).orElse("0"));
        String search = context.get("search");
        String orderBy = context.get("orderBy");
        int maxResults = Integer.parseInt(ofNullable(context.get("maxResults")).orElse("-1"));

        Map<String, Integer> queries = batchSearchRepository.getQueries(user, batchId, from, size, search, orderBy, maxResults);

        if ("csv".equals(context.get("format"))) {
            String contentType = "text/csv;charset=UTF-8";
            String queriesFilename = batchId + "-queries.csv";
            String body = String.join("\n", queries.keySet());
            return new Payload(contentType, body). withHeader("Content-Disposition", "attachment;filename=\"" + queriesFilename + "\"");
        }
        return new Payload(queries);
    }

    @Operation(description = "Preflight request")
    @ApiResponse(responseCode = "200", description = "returns DELETE")
    @Options("/search")
    public Payload optionsSearches(Context context) {
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    @Operation(description = "Preflight request")
    @ApiResponse(responseCode = "200", description = "returns DELETE")
    @Options("/search/:batchid")
    public Payload optionsDelete(String batchId, Context context) {
        return ok().withAllowMethods("OPTIONS", "DELETE", "PATCH");
    }

    @Operation(description = "Delete batch search with the given id and its results. It won't delete running batch searches, because results are added and would be orphans.")
    @ApiResponse(responseCode = "204", description = "Returns 204 (No Content) : idempotent")
    @Delete("/search/:batchid")
    public Payload deleteBatch(String batchId, Context context) {
        batchSearchRepository.delete((User) context.currentUser(), batchId);
        return new Payload(204);
    }

    @Operation(description = "Update batch search with the given id.",
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

    /**
     * Creates a new batch search. This is a multipart form with 8 fields :
     * name, description, csvFile, published, fileTypes, paths, fuzziness, phrase_matches
     *
     * No matter the order. The name and csv file are mandatory else it will return 400 (bad request)
     * Csv file must have under 60 000 lines else it will return 413 (payload too large)
     * Queries with less than two characters are filtered
     *
     * To do so with bash you can create a text file like :
     * ```
     * --BOUNDARY
     * Content-Disposition: form-data; name="name"
     *
     * my batch search
     * --BOUNDARY
     * Content-Disposition: form-data; name="description"
     *
     * search description
     * --BOUNDARY
     * Content-Disposition: form-data; name="csvFile"; filename="search.csv"
     * Content-Type: text/csv
     *
     * Obama
     * skype
     * test
     * query three
     * --BOUNDARY--
     * Content-Disposition: form-data; name="published"
     *
     * true
     * --BOUNDARY--
     * ```
     * Then replace `\n` with `\r\n` with a sed like this:
     *
     * `sed -i 's/$/^M/g' ~/multipart.txt`
     *
     * Then make a curl request with this file :
     * ```
     * curl -i -XPOST localhost:8080/api/batch/search/prj1,prj2 -H 'Content-Type: multipart/form-data; boundary=BOUNDARY' --data-binary @/home/dev/multipart.txt
     * ```
     * @param comaSeparatedProjects
     * @param context : the request body
     * @return 200 or 400 or 413
     */
    @Post("/search/:coma_separated_projects")
    public Payload search(String comaSeparatedProjects, Context context) throws Exception {
        List<Part> parts = context.parts();
        String name = fieldValue("name", parts);
        String csv = fieldValue("csvFile", parts);

        if (name == null  || csv == null) {
            return badRequest();
        }

        String description = fieldValue("description", parts);
        boolean published = "true".equalsIgnoreCase(fieldValue("published", parts)) ? TRUE: FALSE ;
        List<String> fileTypes = fieldValues("fileTypes", parts);
        List<String> tags = fieldValues("tags", parts);
        List<String> paths = fieldValues("paths", parts);
        Optional<Part> fuzzinessPart = parts.stream().filter(p -> "fuzziness".equals(p.name())).findAny();
        int fuzziness = fuzzinessPart.isPresent() ? parseInt(fuzzinessPart.get().content()):0;
        Optional<Part> phraseMatchesPart = parts.stream().filter(p -> "phrase_matches".equals(p.name())).findAny();
        boolean phraseMatches=phraseMatchesPart.isPresent()?parseBoolean(phraseMatchesPart.get().content()): FALSE;
        LinkedHashSet<String> queries = getQueries(csv)
                .stream().map(query -> (phraseMatches && query.contains("\"")) ? query : sanitizeDoubleQuotesInQuery(query)).collect(Collectors.toCollection(LinkedHashSet::new));
        if(queries.size() >= MAX_BATCH_SIZE)
            return new Payload(413);
        BatchSearch batchSearch = new BatchSearch(stream(comaSeparatedProjects.split(",")).map(Project::project).collect(Collectors.toList()), name, description, queries,
                (User) context.currentUser(), published, fileTypes, tags, paths, fuzziness,phraseMatches);
        boolean isSaved = batchSearchRepository.save(batchSearch);
        if (isSaved) batchSearchQueue.put(batchSearch.uuid);
        return isSaved ? new Payload("application/json", batchSearch.uuid, 200) : badRequest();
    }

    /**
     * preflight request
     *
     * @return 200 POST
     */
    @Options("/search/copy/:sourcebatchid")
    public Payload optionsCopy(String sourceBatchId, Context context) {
        return ok().withAllowMethods("OPTIONS", "POST");
    }

    /**
     * Create a new batch search based on a previous one given its id, and enqueue it for running
     *
     * it returns 404 if the source BatchSearch object is not found in the repository.
     *
     * @param sourceBatchId: the id of BatchSearch to copy
     * @param context : the context of request (containing body)
     * @return 200 or 404
     *
     * Example:
     * $(curl localhost:8080/api/batch/search/copy/b7bee2d8-5ede-4c56-8b69-987629742146 -H 'Content-Type: application/json' -d "{\"name\": \"my new batch\", \"description\":\"desc\"}"
     */
    @Post("/search/copy/:sourcebatchid")
    public String copySearch(String sourceBatchId, Context context) throws Exception {
        BatchSearch sourceBatchSearch = batchSearchRepository.get((User) context.currentUser(), sourceBatchId);
        if (sourceBatchSearch == null) {
            throw new NotFoundException();
        }
        BatchSearch copy = new BatchSearch(sourceBatchSearch, context.extract(HashMap.class));
        boolean isSaved = batchSearchRepository.save(copy);
        if (isSaved) batchSearchQueue.put(copy.uuid);
        return copy.uuid;
    }

    /**
     * Retrieve the results of a batch search as JSON.
     *
     * It needs a Query json body with the parameters :
     *
     * - from : index offset of the first document to return (mandatory)
     * - size : window size of the results (mandatory)
     * - queries: list of queries to be downloaded (default null)
     * - sort: field to sort ("doc_nb", "doc_id", "root_id", "doc_path", "creation_date", "content_type", "content_length", "creation_date") (default "doc_nb")
     * - order: "asc" or "desc" (default "asc")
     *
     * If from/size are not given their default values are 0, meaning that all the results are returned.
     * @param batchId
     * @param webQuery
     * @return 200
     *
     * Example :
     * $(curl -XPOST localhost:8080/api/batch/search/result/b7bee2d8-5ede-4c56-8b69-987629742146 -d "{\"from\":0, \"size\": 2}")
     */
    @Post("/search/result/:batchid")
    public List<SearchResult> getResult(String batchId, BatchSearchRepository.WebQuery webQuery, Context context) {
        return getResultsOrThrowUnauthorized(batchId, (User) context.currentUser(), webQuery);
    }

    //@Get("/search/result/:batchid/query?from=&to=")


    /**
     * Retrieve the results of a batch search as a CSV file.
     *
     * The search request is by default all results of the batch search.
     *
     * @param batchId
     * @return 200 and the CSV file as attached file
     *
     * Example :
     * $(curl -i localhost:8080/api/batch/search/result/csv/f74432db-9ae8-401d-977c-5c44a124f2c8)
     */
    @Get("/search/result/csv/:batchid")
    public Payload getResultAsCsv(String batchId, Context context) {
        StringBuilder builder = new StringBuilder("\"query\", \"documentUrl\", \"documentId\",\"rootId\",\"contentType\",\"contentLength\",\"documentPath\",\"creationDate\",\"documentNumber\"\n");
        BatchSearch batchSearch = batchSearchRepository.get((User) context.currentUser(), batchId);
        String url = propertiesProvider.get("rootHost").orElse(context.header("Host"));

        getResultsOrThrowUnauthorized(batchId, (User) context.currentUser(), WebQueryBuilder.createWebQuery().queryAll().build()).forEach(result -> builder.
                append("\"").append(result.query).append("\"").append(",").
                append("\"").append(docUrl(url, batchSearch.projects, result.documentId, result.rootId)).append("\"").append(",").
                append("\"").append(result.documentId).append("\"").append(",").
                append("\"").append(result.rootId).append("\"").append(",").
                append("\"").append(result.contentType).append("\"").append(",").
                append("\"").append(result.contentLength).append("\"").append(",").
                append("\"").append(result.documentPath).append("\"").append(",").
                append("\"").append(result.creationDate).append("\"").append(",").
                append("\"").append(result.documentNumber).append("\"").append("\n")
        );

        return new Payload("text/csv", builder.toString()).
                withHeader("Content-Disposition", "attachment;filename=\"" + batchId + ".csv\"");
    }

    /**
     * Delete batch searches and results for the current user.
     *
     * Returns 204 (No Content): idempotent 
     *
     * @return 204
     *
     * Example :
     * $(curl -XDELETE localhost:8080/api/batch/search)
     */
    @Delete("/search")
    public Payload deleteSearches(Context context) {
        batchSearchRepository.deleteAll((User) context.currentUser());
        return new Payload(204);
    }

    private String docUrl(String uri, List<Project> projects, String documentId, String rootId) {
        return format("%s/#/d/%s/%s/%s", uri, projects.stream().map(Project::getId).collect(Collectors.joining(",")), documentId, rootId);
    }

    private LinkedHashSet<String> getQueries(String csv) {
        return asSet(stream(csv.split("\r?\n")).filter(q -> q.length() >= 2).toArray(String[]::new));
    }

    private List<SearchResult> getResultsOrThrowUnauthorized(String batchId, User user, BatchSearchRepository.WebQuery webQuery) {
        try {
            return batchSearchRepository.getResults(user, batchId, webQuery);
        } catch (JooqBatchSearchRepository.UnauthorizedUserException unauthorized) {
            throw new UnauthorizedException();
        }
    }

    private String fieldValue(String field, List<Part> parts) {
        List<String> values = fieldValues(field, parts);
        return values.isEmpty() ? null: values.get(0);
    }

    private List<String> fieldValues(String field, List<Part> parts) {
        return parts.stream().filter(p -> field.equals(p.name())).map(part -> {
            try {
                return part.content();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }

    private String sanitizeDoubleQuotesInQuery(String query) {
        if(query.contains("\"\"\"")) {
            return query.substring(1, query.length() - 1).replaceAll("\"\"","\"");
        }
        return query;
    }
}
