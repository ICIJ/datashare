package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.Part;
import net.codestory.http.annotations.*;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static net.codestory.http.payload.Payload.notFound;
import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.text.Project.project;

@Prefix("/api/batch")
public class BatchSearchResource {
    private final BatchSearchRepository batchSearchRepository;

    @Inject
    public BatchSearchResource(final BatchSearchRepository batchSearchRepository) {
        this.batchSearchRepository = batchSearchRepository;
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
        return batchSearchRepository.deleteBatchSearches((User) context.currentUser()) ? new Payload(204): notFound();
    }

    @Get("search/:batchid")
    public BatchSearch getBatch(String batchId, Context context) {
        return batchSearchRepository.get((User) context.currentUser(), batchId);
    }

    @Post("search/:project")
    public Payload search(String projectId, Context context) throws Exception {
        List<Part> parts = context.parts();
        if (parts.size() != 3 || !"name".equals(parts.get(0).name()) ||
                !"description".equals(parts.get(1).name()) || !"csvFile".equals(parts.get(2).name())) {
            return Payload.badRequest();
        }
        Part namePart = parts.get(0);
        Part descPart = parts.get(1);
        Part csv = parts.get(2);
        BatchSearch batchSearch = new BatchSearch(project(projectId), namePart.content(), descPart.content(), getQueries(csv));
        return batchSearchRepository.save((User) context.currentUser(), batchSearch) ?
                new Payload("application/json", batchSearch.uuid, 200) : Payload.badRequest();
    }

    @Get("search/result/:batchid")
    public List<SearchResult> getResult(String batchId, Context context) {
        int size = context.request().query().getInteger("size");
        int from = context.request().query().getInteger("from");
        return getResultsOrThrowUnauthorized(batchId, (User) context.currentUser(), size, from);
    }

    @Get("search/result/csv/:batchid")
    public Payload getResultAsCsv(String batchId, Context context) {
        StringBuilder builder = new StringBuilder("\"query\", \"documentId\",\"rootId\",\"documentPath\",\"creationDate\",\"documentNumber\"\n");

        getResultsOrThrowUnauthorized(batchId, (User) context.currentUser(), 0, 0).forEach(result -> builder.
                append("\"").append(result.query).append("\"").append(",").
                append("\"").append(result.documentId).append("\"").append(",").
                append("\"").append(result.rootId).append("\"").append(",").
                append("\"").append(result.documentPath).append("\"").append(",").
                append("\"").append(result.creationDate).append("\"").append(",").
                append("\"").append(result.documentNumber).append("\"").append("\n")
        );

        return new Payload("text/csv", builder.toString()).
                withHeader("Content-Disposition", "attachment;filename=\"" + batchId + ".csv\"");
    }

    @NotNull
    private List<String> getQueries(Part csv) throws IOException {
        return stream(csv.content().split("\r?\n")).filter(q -> q.length() >= 2).collect(toList());
    }

    private List<SearchResult> getResultsOrThrowUnauthorized(String batchId, User user, int size, int from) {
        try {
            return batchSearchRepository.getResults(user, batchId, size, from);
        } catch (JooqBatchSearchRepository.UnauthorizedUserException unauthorized) {
            throw new UnauthorizedException();
        }
    }
}
