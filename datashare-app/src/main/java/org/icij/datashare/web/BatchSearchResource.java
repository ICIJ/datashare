package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.Part;
import net.codestory.http.annotations.*;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.session.HashMapUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.Boolean.*;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static net.codestory.http.payload.Payload.*;
import static org.icij.datashare.text.Project.project;

@Prefix("/api/batch")
public class BatchSearchResource {
    private final BatchSearchRepository batchSearchRepository;
    private final PropertiesProvider propertiesProvider;

    @Inject
    public BatchSearchResource(final BatchSearchRepository batchSearchRepository, PropertiesProvider propertiesProvider) {
        this.batchSearchRepository = batchSearchRepository;
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
    public List<BatchSearch> getSearches(Context context) {
        HashMapUser user = (HashMapUser) context.currentUser();
        List<String> indices = user.getProjects();
        indices.add(user.defaultProject());
        return batchSearchRepository.get(user, indices);
    }

    /**
     * Retrieve the batch search with the given id
     *
     * @param batchId
     * @return 200 and the batch search
     *
     * Example :
     * $(curl localhost:8080/api/batch/search/b7bee2d8-5ede-4c56-8b69-987629742146 )
     */
    @Get("/search/:batchid")
    public BatchSearch getBatch(String batchId, Context context) {
        return batchSearchRepository.get((User) context.currentUser(), batchId);
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
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    /**
     * Delete batch search with the given id and its results.
     *
     * Returns 204 (No Content) if rows have been removed and 404 if nothing has been done (i.e. not found).
     *
     * @return 204 or 404
     *
     * Example :
     * $(curl -i -XDELETE localhost:8080/api/batch/search/f74432db-9ae8-401d-977c-5c44a124f2c8)
     *
     */
    @Delete("/search/:batchid")
    public Payload deleteBatch(String batchId, Context context) {
        return batchSearchRepository.delete((User) context.currentUser(), batchId) ? new Payload(204): notFound();
    }

    /**
     * Creates a new batch search. This is a multipart form with 8 fields :
     * name, description, csvFile, published, fileTypes, paths, fuzziness, phrase_matches
     *
     * No matter the order. The name and csv file are mandatory else it will return 400 (bad request)
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
     * TThen make a curl request with this file :
     * ```
     * curl -i -XPOST localhost:8080/api/batch/search/prj -H 'Content-Type: multipart/form-data; boundary=BOUNDARY' --data-binary @/home/dev/multipart.txt
     * ```
     * @param projectId
     * @param context : the request body
     * @return 200 or 400
     */
    @Post("/search/:project")
    public Payload search(String projectId, Context context) throws Exception {
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

        BatchSearch batchSearch = new BatchSearch(project(projectId), name, description, getQueries(csv),
                (User) context.currentUser(), published, fileTypes, paths, fuzziness,phraseMatches);
        return batchSearchRepository.save(batchSearch) ?
                new Payload("application/json", batchSearch.uuid, 200) : badRequest();
    }

    /**
     * Retrieve the results of a batch search as JSON.
     *
     * It needs a Query json body with the parameters :
     *
     * - from : index offset of the first document to return (mandatory)
     * - size : window size of the results (mandatory)
     * - queries: list of queries to be downloaded (default null)
     * - sort: field to sort ("doc_nb", "doc_id", "root_id", "doc_name", "creation_date", "content_type", "content_length", "creation_date") (default "doc_nb")
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
                append("\"").append(docUrl(url, batchSearch.project, result.documentId, result.rootId)).append("\"").append(",").
                append("\"").append(result.documentId).append("\"").append(",").
                append("\"").append(result.rootId).append("\"").append(",").
                append("\"").append(result.contentType).append("\"").append(",").
                append("\"").append(result.contentLength).append("\"").append(",").
                append("\"").append(result.documentName).append("\"").append(",").
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

    private String docUrl(String uri, Project project, String documentId, String rootId) {
        return format("%s/#/d/%s/%s/%s", uri, project.getId(), documentId, rootId);
    }

    @NotNull
    private List<String> getQueries(String csv) throws IOException {
        return stream(csv.split("\r?\n")).filter(q -> q.length() >= 2).collect(toList());
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
}
