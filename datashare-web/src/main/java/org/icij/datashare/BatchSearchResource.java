package org.icij.datashare;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.Part;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.user.User;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.icij.datashare.text.Project.project;

@Prefix("/api/batch")
public class BatchSearchResource {
    private final BatchSearchRepository batchSearchRepository;

    @Inject
    public BatchSearchResource(final BatchSearchRepository batchSearchRepository) {
        this.batchSearchRepository = batchSearchRepository;
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
        BatchSearch batchSearch = new BatchSearch(project(projectId), namePart.content(), descPart.content(), Arrays.asList(csv.content().split("\\r?\\n")));
        return batchSearchRepository.save((User) context.currentUser(), batchSearch) ?
                new Payload("application/json", batchSearch.uuid, 200) : Payload.badRequest();
    }

    @Get("search/result/:batchid")
    public List<SearchResult> getResult(String batchId, Context context) throws SQLException {
        return getResultsOrThrowUnauthorized(batchId, (User) context.currentUser());
    }

    @Get("search/result/csv/:batchid")
    public Payload getResultAsCsv(String batchId, Context context) throws SQLException {
        StringBuilder builder = new StringBuilder("\"documentId\",\"rootId\",\"documentPath\",\"creationDate\",\"documentNumber\"\n");

        getResultsOrThrowUnauthorized(batchId, (User) context.currentUser()).forEach(result -> builder.
                append("\"").append(result.documentId).append("\"").append(",").
                append("\"").append(result.rootId).append("\"").append(",").
                append("\"").append(result.documentPath).append("\"").append(",").
                append("\"").append(result.creationDate).append("\"").append(",").
                append("\"").append(result.documentNumber).append("\"").append("\n")
        );

        return new Payload("text/csv", builder.toString()).
                withHeader("Content-Disposition", "attachment;filename=\"" + batchId + ".csv\"");
    }

    private List<SearchResult> getResultsOrThrowUnauthorized(String batchId, User user) throws SQLException {
        try {
            return batchSearchRepository.getResults(user, batchId);
        } catch (JooqBatchSearchRepository.UnauthorizedUserException unauthorized) {
            throw new UnauthorizedException();
        }
    }
}
