package org.icij.datashare.db;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearch.State;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.text.Document;
import org.icij.datashare.user.User;
import org.jooq.*;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.text.Project.project;
import static org.jooq.impl.DSL.*;

public class JooqBatchSearchRepository implements BatchSearchRepository {
    private static final String BATCH_SEARCH = "batch_search";
    private static final String BATCH_SEARCH_QUERY = "batch_search_query";
    private static final String BATCH_SEARCH_RESULT = "batch_search_result";
    private final DataSource dataSource;
    private final SQLDialect dialect;

    JooqBatchSearchRepository(final DataSource dataSource, final SQLDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public boolean save(final User user, final BatchSearch batchSearch) {
        return DSL.using(dataSource, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            inner.insertInto(table(BATCH_SEARCH), field("uuid"), field("name"), field("description"), field("user_id"), field("prj_id"), field("batch_date"), field("state")).
                    values(batchSearch.uuid, batchSearch.name, batchSearch.description, user.id, batchSearch.project.getId(), new Timestamp(batchSearch.getDate().getTime()), batchSearch.state.name()).execute();
            InsertValuesStep3<Record, Object, Object, Object> insertQuery =
                    inner.insertInto(table(BATCH_SEARCH_QUERY), field("search_uuid"), field("query"), field("query_number"));
            IntStream.range(0, batchSearch.queries.size()).forEach(i -> insertQuery.values(batchSearch.uuid, batchSearch.queries.get(i), i));
            return insertQuery.execute() > 0;
        });
    }

    @Override
    public boolean saveResults(String batchSearchId, String query, List<Document> documents) {
        DSLContext create = DSL.using(dataSource, dialect);
        InsertValuesStep7<Record, Object, Object, Object, Object, Object, Object, Object> insertQuery =
                create.insertInto(table(BATCH_SEARCH_RESULT), field("search_uuid"), field("query"), field("doc_nb"),
                        field("doc_id"), field("root_id"), field("doc_path"), field("creation_date"));
        IntStream.range(0, documents.size()).forEach(i -> insertQuery.values(batchSearchId, query, i,
                documents.get(i).getId(), documents.get(i).getRootDocument(), documents.get(i).getPath().toString(),
                documents.get(i).getCreationDate() == null ? val((Timestamp)null):
                        new Timestamp(documents.get(i).getCreationDate().getTime())));
        return insertQuery.execute() > 0;
    }

    @Override
    public boolean setState(String batchSearchId, State state) {
        DSLContext create = DSL.using(dataSource, dialect);
        return create.update(table(BATCH_SEARCH)).set(field("state"), state.name()).where(field("uuid").eq(batchSearchId)).execute() > 0;
    }

    @Override
    public boolean deleteBatchSearches(User user) {
        return DSL.using(dataSource, dialect).transactionResult(configuration -> {
            DSLContext inner = using(configuration);
            inner.deleteFrom(table(BATCH_SEARCH_QUERY)).where(field("search_uuid").
                    in(select(field("uuid")).from(table(BATCH_SEARCH)).where(field("user_id").eq(user.id)))).
                    execute();
            inner.deleteFrom(table(BATCH_SEARCH_RESULT)).where(field("search_uuid").
                    in(select(field("uuid")).from(table(BATCH_SEARCH)).where(field("user_id").eq(user.id)))).
                    execute();
            return inner.deleteFrom(table(BATCH_SEARCH)).where(field("user_id").eq(user.id)).execute() > 0;
        });
    }

    @Override
    public List<BatchSearch> get(final User user) {
        DSLContext create = DSL.using(dataSource, dialect);
        return mergeBatchSearches(
                create.select().from(table(BATCH_SEARCH).
                        join(BATCH_SEARCH_QUERY).
                        on(field(BATCH_SEARCH + ".uuid").
                                equal(field(BATCH_SEARCH_QUERY + ".search_uuid")))).
                where(field(BATCH_SEARCH + ".user_id").eq(user.id)).
                        orderBy(field(BATCH_SEARCH + ".batch_date").desc(), field(BATCH_SEARCH_QUERY + ".query_number")).
                fetch().stream().map(this::createBatchSearchFrom).collect(toList()));
    }

    @Override
    public List<BatchSearch> getQueued() {
        DSLContext create = DSL.using(dataSource, dialect);
        return mergeBatchSearches(
                create.select().from(table(BATCH_SEARCH).
                        join(BATCH_SEARCH_QUERY).
                        on(field(BATCH_SEARCH + ".uuid").
                                equal(field(BATCH_SEARCH_QUERY + ".search_uuid")))).
                where(field(BATCH_SEARCH + ".state").eq(State.QUEUED.name())).
                        orderBy(field(BATCH_SEARCH + ".batch_date").desc(), field(BATCH_SEARCH_QUERY + ".query_number")).
                fetch().stream().map(this::createBatchSearchFrom).collect(toList()));
    }

    @Override
    public List<SearchResult> getResults(final User user, String batchSearchId) {
        return getResults(user, batchSearchId, 0, 0);
    }

    @Override
    public List<SearchResult> getResults(User user, String batchSearchId, int size, int from) {
        DSLContext create = DSL.using(dataSource, dialect);
        SelectSeekStep2<Record, Object, Object> query = create.select().from(table(BATCH_SEARCH_RESULT)).
                join(BATCH_SEARCH).on(field(BATCH_SEARCH + ".uuid").equal(field(BATCH_SEARCH_RESULT + ".search_uuid"))).
                where(field("search_uuid").eq(batchSearchId)).orderBy(field("query"), field("doc_nb"));
        if (size > 0) query.limit(size);
        if (from > 0) query.offset(from);
        return query.fetch().stream().map(r -> createSearchResult(user, r)).collect(toList());
    }

    @Override
    public BatchSearch get(User user, String batchId) {
        DSLContext create = DSL.using(dataSource, dialect);
        return mergeBatchSearches(
            create.select().from(table(BATCH_SEARCH).
                    join(BATCH_SEARCH_QUERY).
                    on(field(BATCH_SEARCH + ".uuid").
                            equal(field(BATCH_SEARCH_QUERY + ".search_uuid")))).
            where(field(BATCH_SEARCH + ".uuid").eq(batchId)).
            fetch().stream().map(this::createBatchSearchFrom).collect(toList())).get(0);
    }

    private List<BatchSearch> mergeBatchSearches(final List<BatchSearch> flatBatchSearches) {
        Map<String, List<BatchSearch>> collect = flatBatchSearches.stream().collect(groupingBy(bs -> bs.uuid));
        return collect.values().stream().map(batchSearches ->
                new BatchSearch(batchSearches.get(0).uuid, batchSearches.get(0).project, batchSearches.get(0).name, batchSearches.get(0).description,
                        batchSearches.stream().map(bs -> bs.queries).flatMap(List::stream).collect(toList()), batchSearches.get(0).getDate(), batchSearches.get(0).state)).
                sorted(comparing(BatchSearch::getDate).reversed()).collect(toList());
    }

    private BatchSearch createBatchSearchFrom(final Record record) {
        return new BatchSearch(record.get(field(name(BATCH_SEARCH, "uuid")), String.class).trim(),
                project(record.getValue("prj_id", String.class)),
                record.getValue("name", String.class),
                record.getValue("description", String.class),
                singletonList(record.getValue("query", String.class)),
                new Date(record.get("batch_date", Timestamp.class).getTime()),
                State.valueOf(record.get("state", String.class)));
    }

    private SearchResult createSearchResult(final User actualUser, final Record record) {
        String owner = record.get("user_id", String.class);
        if (!actualUser.id.equals(owner))
            throw new UnauthorizedUserException(record.get("uuid", String.class), owner, actualUser.id);
        Timestamp creationDate = record.get("creation_date", Timestamp.class);
        return new SearchResult(record.get(field("query"), String.class),
                record.get(field("doc_id"), String.class),
                record.getValue("root_id", String.class),
                Paths.get(record.getValue("doc_path", String.class)),
                creationDate == null ? null: new Date(creationDate.getTime()),
                record.get("doc_nb", Integer.class));
    }

    public static class UnauthorizedUserException extends RuntimeException {
        public UnauthorizedUserException(String searchId, String owner, String actualUser) {
            super("user " + actualUser + " requested results for search " + searchId + " that belongs to user " + owner);
        }
    }
}
