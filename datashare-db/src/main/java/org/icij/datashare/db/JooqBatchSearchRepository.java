package org.icij.datashare.db;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearch.State;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.text.Document;
import org.icij.datashare.user.User;
import org.jooq.*;
import org.jooq.impl.DSL;

import java.sql.Connection;
import java.sql.SQLException;
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
    private final ConnectionProvider connectionProvider;
    private final SQLDialect dialect;

    JooqBatchSearchRepository(final ConnectionProvider connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    @Override
    public boolean save(final User user, final BatchSearch batchSearch) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            return using(conn, dialect).transactionResult(configuration -> {
                DSLContext inner = using(configuration);
                inner.insertInto(table(BATCH_SEARCH), field("uuid"), field("name"), field("description"), field("user_id"), field("prj_id"), field("batch_date"), field("state")).
                        values(batchSearch.uuid, batchSearch.name, batchSearch.description, user.id, batchSearch.project.getId(), new Timestamp(batchSearch.getDate().getTime()), batchSearch.state.name()).execute();
                InsertValuesStep3<Record, Object, Object, Object> insertQuery =
                        inner.insertInto(table(BATCH_SEARCH_QUERY), field("search_uuid"), field("query"), field("query_number"));
                IntStream.range(0, batchSearch.queries.size()).forEach(i -> insertQuery.values(batchSearch.uuid, batchSearch.queries.get(i), i));
                return insertQuery.execute() > 0;
            });
        }
    }

    @Override
    public boolean saveResults(String batchSearchId, List<Document> documents) throws SQLException {
        return false;
    }

    @Override
    public boolean setState(String batchSearchId, State state) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            return create.update(table(BATCH_SEARCH)).set(field("state"), state.name()).where(field("uuid").eq(batchSearchId)).execute() > 0;
        }
    }

    @Override
    public List<BatchSearch> get(final User user) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            return mergeBatchSearches(
                    create.select().from(table(BATCH_SEARCH).
                            join(BATCH_SEARCH_QUERY).
                            on(field(BATCH_SEARCH + ".uuid").
                                    equal(field(BATCH_SEARCH_QUERY + ".search_uuid")))).
                    where(field(BATCH_SEARCH + ".user_id").eq(user.id)).
                            orderBy(field(BATCH_SEARCH + ".batch_date").desc(), field(BATCH_SEARCH_QUERY + ".query_number")).
                    fetch().stream().map(this::createBatchSearchFrom).collect(toList()));
        }
    }

    @Override
    public List<BatchSearch> getQueued() throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            return mergeBatchSearches(
                    create.select().from(table(BATCH_SEARCH).
                            join(BATCH_SEARCH_QUERY).
                            on(field(BATCH_SEARCH + ".uuid").
                                    equal(field(BATCH_SEARCH_QUERY + ".search_uuid")))).
                    where(field(BATCH_SEARCH + ".state").eq(State.QUEUED.name())).
                            orderBy(field(BATCH_SEARCH + ".batch_date").desc(), field(BATCH_SEARCH_QUERY + ".query_number")).
                    fetch().stream().map(this::createBatchSearchFrom).collect(toList()));
        }
    }

    private List<BatchSearch> mergeBatchSearches(final List<BatchSearch> flatBatchSearches) {
        Map<String, List<BatchSearch>> collect = flatBatchSearches.stream().collect(groupingBy(bs -> bs.uuid));
        return collect.values().stream().map(batchSearches ->
                new BatchSearch(batchSearches.get(0).uuid, batchSearches.get(0).project, batchSearches.get(0).name, batchSearches.get(0).description,
                        batchSearches.stream().map(bs -> bs.queries).flatMap(List::stream).collect(toList()), batchSearches.get(0).getDate(), batchSearches.get(0).state)).
                sorted(comparing(BatchSearch::getDate).reversed()).collect(toList());
    }

    private BatchSearch createBatchSearchFrom(final Record record) {
        return new BatchSearch(record.get(field(name(BATCH_SEARCH, "uuid")), String.class),
                project(record.getValue("prj_id", String.class)),
                record.getValue("name", String.class),
                record.getValue("description", String.class),
                singletonList(record.getValue("query", String.class)),
                new Date(record.get("batch_date", Timestamp.class).getTime()),
                State.valueOf(record.get("state", String.class)));
    }
}
