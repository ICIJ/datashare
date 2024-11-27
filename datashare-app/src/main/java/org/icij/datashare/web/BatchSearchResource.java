package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.Part;
import net.codestory.http.annotations.*;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.*;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.tasks.DatashareTaskManager;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.User;
import org.icij.datashare.utils.PayloadFormatter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static net.codestory.http.payload.Payload.*;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.function.ThrowingFunctions.parseBoolean;

@Singleton
@Prefix("/api/batch")
public class BatchSearchResource {
    private final DatashareTaskManager taskManager;
    private final BatchSearchRepository batchSearchRepository;
    private final PropertiesProvider propertiesProvider;
    private final int MAX_BATCH_SIZE = 60000;

    @Inject
    public BatchSearchResource(PropertiesProvider propertiesProvider,  DatashareTaskManager taskManager, final BatchSearchRepository batchSearchRepository) {
        this.taskManager = taskManager;
        this.batchSearchRepository = batchSearchRepository;
        this.propertiesProvider = propertiesProvider;
    }

    @Operation(description = "Retrieves the batch search list for the user issuing the request filter with the given criteria, and the total of batch searches matching the criteria.<br>" +
            "If from/size are not given their default values are 0, meaning that all the results are returned. BatchDate must be a list of 2 items (the first one for the starting date and the second one for the ending date) If defined publishState is a string equals to \"0\" or \"1\"",
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

    @Operation(description = "Creates a new batch search. This is a multipart form with 9 fields:<br/>" +
            "name, description, csvFile, published, fileTypes, paths, fuzziness, phrase_matches, query_template<br>" +
            "<br/>" +
            "Queries with less than two characters are filtered.<br>" +
            "<br>" +
            "To make a request manually, you can create a file like:<br>" +
            "<pre>"+
            "--BOUNDARY<br/>\"" +
            "Content-Disposition: form-data; name=\"name\"<br/>" +
            "<br/>" +
            "my batch search<br/>" +
            " --BOUNDARY<br/>" +
            "Content-Disposition: form-data; name=\"description\"<br/>" +
            "<br/>" +
            "search description<br/>" +
            " --BOUNDARY<br/>" +
            "Content-Disposition: form-data; name=\"csvFile\"; filename=\"search.csv\"<br/>" +
            "Content-Type: text/csv<br/>" +
            "<br/>" +
            "Obama<br/>" +
            "skype<br/>" +
            "test<br/>" +
            "query three<br/>" +
            "--BOUNDARY--<br/>" +
            "Content-Disposition: form-data; name=\"published\"<br/>" +
            "<br/>" +
            "true<br/>" +
            "--BOUNDARY--<br/>" +
            "</pre><br/>" +
            "<br/>Then curl with" +
            "<pre>curl -i -XPOST localhost:8080/api/batch/search/prj1,prj2 -H 'Content-Type: multipart/form-data; boundary=BOUNDARY' --data-binary @/home/dev/multipart.txt</pre>" +
            "you'll maybe have to replace \\n with \\n\\r with <pre>sed -i 's/$/^M/g' ~/multipart.txt</pre>",
            requestBody = @RequestBody(description = "multipart form", required = true,
                    content = @Content(mediaType = "multipart/form-data",
                            schemaProperties = {
                                    @SchemaProperty(name = "name", schema = @Schema(implementation = String.class)),
                                    @SchemaProperty(name = "description", schema = @Schema(implementation = String.class)),
                                    @SchemaProperty(name = "csvFile", schema = @Schema(implementation = String.class)),
                                    @SchemaProperty(name = "published", schema = @Schema(implementation = Boolean.class)),
                                    @SchemaProperty(name = "fileTypes", schema = @Schema(implementation = List.class)),
                                    @SchemaProperty(name = "tags", schema = @Schema(implementation = List.class)),
                                    @SchemaProperty(name = "paths", schema = @Schema(implementation = List.class)),
                                    @SchemaProperty(name = "fuzziness", schema = @Schema(implementation = Integer.class)),
                                    @SchemaProperty(name = "phrase_matches", schema = @Schema(implementation = Boolean.class))
                            }
                    )
            ),
            parameters = {@Parameter(description = "Coma-separated list of projects",
                    in = ParameterIn.PATH, examples = @ExampleObject(value = "prj1,prj2"))}
    )
    @ApiResponse(responseCode = "413", description = "if the CSV file is more than 60K lines")
    @ApiResponse(responseCode = "400", description = "if either name or CSV file is missing")
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
        String queryTemplate = fieldValue("query_template", parts);
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
                (User) context.currentUser(), published, fileTypes, queryTemplate, paths, fuzziness,phraseMatches);
        boolean isSaved = batchSearchRepository.save(batchSearch);
        if (isSaved) {
            taskManager.startTask(batchSearch.uuid, BatchSearchRunner.class, (User) context.currentUser());
        }
        return isSaved ? new Payload("application/json", batchSearch.uuid, 200) : badRequest();
    }

    @Operation(description = "Preflight request", method = "OPTION")
    @ApiResponse(description = "returns POST")
    @Options("/search/copy/:sourcebatchid")
    public Payload optionsCopy(String sourceBatchId, Context context) {
        return ok().withAllowMethods("OPTIONS", "POST");
    }

    @Operation( description = "Creates a new batch search based on a previous one given its id, and enqueue it for running",
                parameters = {@Parameter(name = "sourcebatchid", in = ParameterIn.PATH, description = "source batch id")},
                requestBody = @RequestBody(description = "batch parameters", required = true,
                        content = @Content( mediaType = "application/json",
                                            examples = {@ExampleObject(value = "{\"name\": \"my new batch\", \"description\":\"desc\"}")})
                )
    )
    @ApiResponse(responseCode = "404", description = "if the source batch search is not found in database")
    @ApiResponse(responseCode = "200", description = "returns the id of the created batch search", useReturnTypeSchema = true)
    @Post("/search/copy/:sourcebatchid")
    public String copySearch(String sourceBatchId, Context context) throws Exception {
        BatchSearch sourceBatchSearch = batchSearchRepository.get((User) context.currentUser(), sourceBatchId);
        if (sourceBatchSearch == null) {
            throw new NotFoundException();
        }
        BatchSearch copy = new BatchSearch(sourceBatchSearch, context.extract(HashMap.class));
        boolean isSaved = batchSearchRepository.save(copy);
        if (isSaved) taskManager.startTask(copy.uuid, BatchSearchRunner.class, (User) context.currentUser());
        return copy.uuid;
    }

    @Operation( description = "Retrieves the results of a batch search as JSON with a list of items and a pagination metadata.<br/>" +
            "If from/size are not given their default values are 0, meaning that all the results are returned.",
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

    private LinkedHashSet<String> getQueries(String csv) {
        return asSet(stream(csv.split("\r?\n")).filter(q -> q.length() >= 2).toArray(String[]::new));
    }

    private WebResponse<SearchResult> getResultsOrThrowUnauthorized(String batchId, User user, BatchSearchRepository.WebQuery webQuery) {
        try {
            return new WebResponse<>(batchSearchRepository.getResults(user, batchId, webQuery), webQuery.from,webQuery.size,
                    batchSearchRepository.getResultsTotal(user,batchId,webQuery));
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
