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
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

import static java.lang.Boolean.parseBoolean;
import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static net.codestory.http.payload.Payload.notFound;
import static net.codestory.http.payload.Payload.ok;
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

    @Get("search")
    public List<BatchSearch> getSearches(Context context) {
        return batchSearchRepository.get((User) context.currentUser());
    }

    @Options("search")
    public Payload optionsSearches(Context context) {
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    @Delete("search")
    public Payload deleteSearches(Context context) {
        return batchSearchRepository.deleteAll((User) context.currentUser()) ? new Payload(204): notFound();
    }

    @Get("search/:batchid")
    public BatchSearch getBatch(String batchId, Context context) {
        return batchSearchRepository.get((User) context.currentUser(), batchId);
    }

    @Options("search/:batchid")
    public Payload optionsDelete(String batchId, Context context) {
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    @Delete("search/:batchid")
    public Payload deleteBatch(String batchId, Context context) {
        return batchSearchRepository.delete((User) context.currentUser(), batchId) ? new Payload(204): notFound();
    }

    @Post("search/:project")
    public Payload search(String projectId, Context context) throws Exception {
        List<Part> parts = context.parts();
        if (parts.size() < 3 || !"name".equals(parts.get(0).name()) ||
                !"description".equals(parts.get(1).name()) || !"csvFile".equals(parts.get(2).name())) {
            return Payload.badRequest();
        }
        Part namePart = parts.get(0);
        Part descPart = parts.get(1);
        Part csv = parts.get(2);
        boolean published = false;
        if (parts.size() == 4) {
            published = parseBoolean(parts.get(3).content());
        }
        BatchSearch batchSearch = new BatchSearch(project(projectId), namePart.content(), descPart.content(), getQueries(csv), published);
        return batchSearchRepository.save((User) context.currentUser(), batchSearch) ?
                new Payload("application/json", batchSearch.uuid, 200) : Payload.badRequest();
    }

    @Post("search/result/:batchid")
    public List<SearchResult> getResult(String batchId, BatchSearchRepository.WebQuery webQuery, Context context) {
        return getResultsOrThrowUnauthorized(batchId, (User) context.currentUser(), webQuery);
    }

    @Get("search/result/csv/:batchid")
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

    private String docUrl(String uri, Project project, String documentId, String rootId) {
        return format("%s/#/d/%s/%s/%s", uri, project.getId(), documentId, rootId);
    }

    @NotNull
    private List<String> getQueries(Part csv) throws IOException {
        return stream(csv.content().split("\r?\n")).filter(q -> q.length() >= 2).collect(toList());
    }

    private List<SearchResult> getResultsOrThrowUnauthorized(String batchId, User user, BatchSearchRepository.WebQuery webQuery) {
        try {
            return batchSearchRepository.getResults(user, batchId, webQuery);
        } catch (JooqBatchSearchRepository.UnauthorizedUserException unauthorized) {
            throw new UnauthorizedException();
        }
    }
}
