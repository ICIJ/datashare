package org.icij.datashare.db;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.user.User;
import org.jooq.*;
import org.jooq.impl.DSL;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;
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
                Record record = inner.insertInto(table(BATCH_SEARCH), field("name"), field("description"), field("user_id"), field("prj_id")).
                        values(batchSearch.name, batchSearch.description, user.id, batchSearch.project.getId()).
                        returning(field("id")).
                        fetchOne();
                InsertValuesStep2<Record, Object, Object> insertQuery = inner.insertInto(table(BATCH_SEARCH_QUERY), field("search_id"), field("query"));
                batchSearch.queries.forEach(q -> insertQuery.values(record.getValue("id", Integer.class), q));
                return insertQuery.execute() > 0;
            });
        }
    }

    @Override
    public List<BatchSearch> get(final User user) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            return mergeBatchSearches(
                    create.select().from(table(BATCH_SEARCH).
                            join(BATCH_SEARCH_QUERY).
                            on(field(BATCH_SEARCH + ".id").
                                    equal(field(BATCH_SEARCH_QUERY + ".search_id")))).
                    where(field(BATCH_SEARCH + ".user_id").eq(user.id)).
                            orderBy(field(BATCH_SEARCH + ".id"), field(BATCH_SEARCH_QUERY + ".id")).
                    fetch().stream().map(this::createBatchSearchFrom).collect(toList()));
        }
    }

    private List<BatchSearch> mergeBatchSearches(final List<BatchSearch> flatBatchSearches) {
        Map<Long, List<BatchSearch>> collect = flatBatchSearches.stream().collect(groupingBy(bs -> bs.id));
        return collect.values().stream().map(batchSearches ->
                new BatchSearch(batchSearches.get(0).id, batchSearches.get(0).project, batchSearches.get(0).name, batchSearches.get(0).description,
                        batchSearches.stream().map(bs -> bs.queries).flatMap(List::stream).collect(toList()))).collect(toList());
    }

    private BatchSearch createBatchSearchFrom(final Record record) {
        return new BatchSearch(record.get(field(name(BATCH_SEARCH, "id")), Long.class),
                project(record.getValue("prj_id", String.class)),
                record.getValue("name", String.class),
                record.getValue("description", String.class),
                singletonList(record.getValue("query", String.class))
        );
    }
}
