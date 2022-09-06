package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.Part;
import net.codestory.http.annotations.*;
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import static java.lang.Boolean.*;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static net.codestory.http.payload.Payload.*;
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

    /**
     * Retrieve the batch search list for the user issuing the request.
     *
     * @return 200 and the list of batch searches
     *
     * Example :
     * $(curl localhost:8080/api/batch/search )
     */
    @Get("/search")
    public List<BatchSearchRecord> getSearches(Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        return batchSearchRepository.getRecords(user, user.getProjects());
    }

    /**
     * Retrieve the batch search list for the user issuing the request filter with the given criteria, and the total
     * of batch searches matching the criteria.
     *
     * It needs a Query json body with the parameters :
     *
     * - from : index offset of the first document to return (mandatory)
     * - size : window size of the results (mandatory)
     * - sort : field to sort (prj_id name user_id description state batch_date batch_results published) (default "batch_date")
     * - order : "asc" or "desc" (default "asc")
     * - project : projects to include in the filter (default null / empty list)
     * - batchDate : batch search with a creation date included in this range (default null / empty list)
     * - state : states to include in the filter (default null / empty list)
     * - publishState : publish state to filter (default null)
     *
     * If from/size are not given their default values are 0, meaning that all the results are returned.
     * BatchDate must be a list of 2 items (the first one for the starting date and the second one for the ending date)
     * If defined publishState is a string equals to "0" or "1"
     *
     * @return 200 and the list of batch searches with the total batch searches for the query. See example for the JSON format.
     *
     * Example :
     * $(curl -H 'Content-Type: application/json' localhost:8080/api/batch/search -d '{"from":0, "size": 2}')
     */
    @Post("/search")
    public WebResponse<BatchSearchRecord> getSearchesFiltered(BatchSearchRepository.WebQuery webQuery, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        return new WebResponse<>(batchSearchRepository.getRecords(user, user.getProjects(), webQuery),
                batchSearchRepository.getTotal(user, user.getProjects(), webQuery));
    }

    /**
     * Retrieve the batch search with the given id
     * The query param "withQueries" accepts a boolean value
     * When "withQueries" is set to false, the list of queries is empty and nbQueries contains the number of queries.
     *
     * @param batchId
     * @return 200 and the batch search
     *
     * Example :
     * $(curl localhost:8080/api/batch/search/b7bee2d8-5ede-4c56-8b69-987629742146?withQueries=true)
     */
    @Get("/search/:batchid")
    public BatchSearch getBatch(String batchId, Context context) {
        boolean withQueries = Boolean.parseBoolean(context.get("withQueries"));
        return batchSearchRepository.get((User) context.currentUser(), batchId, withQueries);
    }

    /**
     * Retrieve the batch search queries with the given batch id and returns a list of strings UTF-8 encoded
     *
     * if the request parameter format is set with csv, then it will answer with
     * content-disposition attachment (file downloading)
     *
     * if the requests parameter from and size are provided: a window of queries
     * is returned ordered by their query number.
     * If "from" is not provided then it starts from 0.
     * If "size" is not provided (or 0) then all the queries starting from the "from" parameter are returned.
     *
     * @param batchId
     * @return 200 and the batch search
     *
     * Example :
     * $(curl localhost:8080/api/batch/search/b7bee2d8-5ede-4c56-8b69-987629742146/queries?format=csv&from=0&size=2 )
     */
    @Get("/search/:batchid/queries")
    public Payload getBatchQueries(String batchId, Context context) {
        int from = Integer.parseInt(ofNullable(context.get("from")).orElse("0"));
        int size = Integer.parseInt(ofNullable(context.get("size")).orElse("0"));

        Map<String,Integer> queries = batchSearchRepository.getQueries((User) context.currentUser(), batchId, from, size, null, null);

        if ("csv".equals(context.get("format"))) {
            return new Payload("text/csv;charset=UTF-8", String.join("\n", queries.keySet())).
                            withHeader("Content-Disposition", "attachment;filename=\"" + batchId + "-queries.csv\"");
        }
        return new Payload(queries);
    }

    /**
     * preflight request
     *
     * @return 200 DELETE
     */
    @Options("/search")
    public Payload optionsSearches(Context context) {
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    /**
     * preflight resquest for removal of one batchsearch
     *
     * @param batchId
     * @return 200 DELETE
     */
    @Options("/search/:batchid")
    public Payload optionsDelete(String batchId, Context context) {
        return ok().withAllowMethods("OPTIONS", "DELETE", "PATCH");
    }

    /**
     * Delete batch search with the given id and its results.
     * It won't delete running batch searches, because results are added and would be orphans.
     *
     * Returns 204 (No Content) if rows have been removed and 404 if nothing has been done (i.e. not found).
     *
     * @return 204 or 404
     *
     * Example :
     * $(curl -i -XDELETE localhost:8080/api/batch/search/unknown_id)
     *
     */
    @Delete("/search/:batchid")
    public Payload deleteBatch(String batchId, Context context) {
        return batchSearchRepository.delete((User) context.currentUser(), batchId) ? new Payload(204): notFound();
    }

    /**
     * Update batch search with the given id.
     *
     * Returns 200 and 404 if there is no batch id
     * If the user issuing the request is not the same as the batch owner in database, it will do nothing (thus returning 404)
     *
     * @return 200 or 404
     *
     * Example :
     * $(curl -i -XPATCH localhost:8080/api/batch/search/f74432db-9ae8-401d-977c-5c44a124f2c8 -H 'Content-Type: application/json' -d '{"data": {"published": true}}')
     *
     */
    @Patch("/search/:batchid")
    public Payload updateBatch(String batchId, Context context, JsonData data) {
        return batchSearchRepository.publish((User) context.currentUser(), batchId, data.asBoolean("published")) ? ok(): notFound();
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
                (User) context.currentUser(), published, fileTypes, paths, fuzziness,phraseMatches);
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

        getResultsOrThrowUnauthorized(batchId, (User) context.currentUser(), new BatchSearchRepository.WebQuery()).forEach(result -> builder.
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
     * Returns 204 (No Content) if rows have been removed and 404 if nothing has been done (i.e. not found).
     *
     * @return 204 or 404
     *
     * Example :
     * $(curl -XDELETE localhost:8080/api/batch/search)
     */
    @Delete("/search")
    public Payload deleteSearches(Context context) {
        return batchSearchRepository.deleteAll((User) context.currentUser()) ? new Payload(204): notFound();
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
