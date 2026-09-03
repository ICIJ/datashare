package org.icij.datashare.db;

import org.icij.datashare.db.tables.records.StatementRecord;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Statement;
import org.icij.datashare.model.StatementRepository;
import org.icij.datashare.model.TargetModelRegistry;
import org.icij.datashare.time.DatashareTime;
import org.jooq.BatchBindStep;
import org.jooq.Cursor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.JDBCUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toCollection;
import static org.icij.datashare.db.Tables.STATEMENT;

public class JooqStatementRepository implements StatementRepository {
    private static final int FETCH_SIZE = 1_000;
    private static final Field<?>[] READ_FIELDS = {
            STATEMENT.ID, STATEMENT.MODEL, STATEMENT.MODEL_VERSION, STATEMENT.ENTITY_ID, STATEMENT.ENTITY_TYPE,
            STATEMENT.PROPERTY, STATEMENT.VALUE, STATEMENT.DOC_ID, STATEMENT.SHEET,
            STATEMENT.ROW_NUMBER, STATEMENT.COLUMN_NAME};
    private final DataSource dataSource;
    private final SQLDialect dialect;
    private final int chunkSize;

    public JooqStatementRepository(DataSource dataSource, SQLDialect dialect) {
        this(dataSource, dialect, JooqCasbinRuleAdapter.determineBatchSize(dialect));
    }

    JooqStatementRepository(DataSource dataSource, SQLDialect dialect, int chunkSize) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.chunkSize = chunkSize;
    }

    private record Write(String projectId, String runId, LocalDateTime now) { }

    private record Row(Statement statement, String modelVersion) { }

    @Override
    public int save(String projectId, String runId, Stream<Statement> statements) {
        Write write = new Write(projectId, runId,
                new Timestamp(DatashareTime.getInstance().currentTimeMillis()).toLocalDateTime());
        DSLContext create = create();
        int written = 0;
        try (statements) {
            Iterator<Statement> source = statements.iterator();
            while (source.hasNext()) {
                List<Statement> chunk = new ArrayList<>(chunkSize);
                while (chunk.size() < chunkSize && source.hasNext()) {
                    chunk.add(source.next());
                }
                written += create.transactionResult(configuration ->
                        saveChunk(DSL.using(configuration), write, chunk));
            }
        }
        return written;
    }

    // A batch built from Query...values(...) inlines every row's SQL and ships it as literal text
    // (jOOQ's "batch multiple"); a batch built from one bound template query and repeated .bind()
    // calls ("batch single") reuses the same PreparedStatement and lets Postgres plan it once. Both
    // the template's column list and each row's positional binds come from the same record, so they
    // cannot drift, and the conflict branch reads the row being inserted through EXCLUDED rather than
    // binding its own values, which would not line up with the record's positional binds.
    private static int saveChunk(DSLContext create, Write write, List<Statement> chunk) {
        // The update is conditional so a no-op re-run rewrites nothing: unconditional, every row and
        // both index entries are rewritten on every re-run (measured 35->70->94 MB over three
        // identical saves), for LAST_SEEN and RUN_ID values nothing reads. Only a row whose content
        // actually moved (an ontology bump, an original_value recorded under another format) is
        // rewritten, and takes the fresh run and timestamps with it. DO UPDATE ... WHERE needs
        // SQLite 3.24+; the bundled driver carries 3.40.
        BatchBindStep batch = create.batch(create.insertInto(STATEMENT).set(row(write, chunk.get(0)))
                .onConflict(STATEMENT.ID, STATEMENT.PRJ_ID).doUpdate()
                .set(STATEMENT.RUN_ID, DSL.excluded(STATEMENT.RUN_ID))
                .set(STATEMENT.MODEL_VERSION, DSL.excluded(STATEMENT.MODEL_VERSION))
                // The one content column outside the id hash, so a conflict does not imply it matches.
                .set(STATEMENT.ORIGINAL_VALUE, DSL.excluded(STATEMENT.ORIGINAL_VALUE))
                .set(STATEMENT.LAST_SEEN, DSL.excluded(STATEMENT.LAST_SEEN))
                .where(STATEMENT.MODEL_VERSION.ne(DSL.excluded(STATEMENT.MODEL_VERSION))
                        .or(STATEMENT.ORIGINAL_VALUE.isDistinctFrom(DSL.excluded(STATEMENT.ORIGINAL_VALUE)))));
        for (Statement statement : chunk) {
            batch.bind(row(write, statement).intoArray());
        }
        return inserted(batch.execute());
    }

    private static StatementRecord row(Write write, Statement statement) {
        StatementRecord row = new StatementRecord();
        row.setId(statement.id());
        row.setPrjId(write.projectId());
        row.setRunId(write.runId());
        row.setModel(statement.model());
        row.setModelVersion(TargetModelRegistry.get(statement.model()).version());
        row.setEntityId(statement.entityId());
        row.setEntityType(statement.entityType());
        row.setProperty(statement.qualifiedProperty());
        row.setValue(statement.value());
        row.setOriginalValue(statement.originalValue());
        row.setDocId(statement.provenance().documentId());
        row.setSheet(statement.provenance().sheet());
        row.setRowNumber(statement.provenance().rowNumber());
        row.setColumnName(statement.provenance().column());
        row.setFirstSeen(write.now());
        row.setLastSeen(write.now());
        return row;
    }

    // A driver that rewrites a batch into one multi-row insert (pgjdbc's reWriteBatchedInserts)
    // reports SUCCESS_NO_INFO rather than a per-row count, so a negative entry has to contribute
    // nothing instead of subtracting from the total.
    private static int inserted(int[] results) {
        return IntStream.of(results).filter(result -> result > 0).sum();
    }

    @Override
    public Optional<ModelEntity> entity(String projectId, String entityId) {
        List<Row> rows = create()
                .select(READ_FIELDS).from(STATEMENT)
                .where(STATEMENT.PRJ_ID.eq(projectId)).and(STATEMENT.ENTITY_ID.eq(entityId))
                .fetch(JooqStatementRepository::toRow);
        // The model an id shared by two models resolves to is picked here rather than with an ORDER
        // BY, because a database collation would otherwise decide it, and SQLite and Postgres do not
        // sort text alike.
        return rows.stream().map(row -> row.statement().model()).min(String::compareTo).map(model -> {
            List<Row> group = rows.stream().filter(row -> model.equals(row.statement().model())).toList();
            return ModelEntity.from(group.stream().map(Row::statement).toList(), versions(group));
        });
    }

    @Override
    public <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer) {
        try (Stream<ModelEntity> entities = entities(projectId)) {
            return consumer.apply(entities);
        }
    }

    // Only pgjdbc streams, and only when fetchSize > 0 AND autoCommit is false: without both, the
    // driver buffers the whole result before the first row is emitted. So Postgres holds one
    // connection, outside the pool's default autocommit, for the stream's lifetime. SQLite ignores
    // fetchSize and has no server-side cursor, and a read it keeps open holds a shared lock on the
    // whole file until the consumer returns, which makes every concurrent writer fail "database is
    // locked": the embedded database buffers instead.
    private Stream<ModelEntity> entities(String projectId) {
        if (dialect != SQLDialect.POSTGRES) {
            return group(read(create(), projectId).fetch(JooqStatementRepository::toRow).stream());
        }
        Connection connection = openStreamingConnection();
        try {
            Cursor<Record> cursor = read(DSL.using(connection, dialect), projectId)
                    .fetchSize(FETCH_SIZE)
                    .fetchLazy();
            return group(cursor.stream()
                    .map(JooqStatementRepository::toRow)
                    .onClose(() -> release(connection, cursor)));
        } catch (RuntimeException e) {
            JDBCUtils.safeClose(connection);
            throw e;
        }
    }

    private static ResultQuery<Record> read(DSLContext create, String projectId) {
        return create.select(READ_FIELDS).from(STATEMENT)
                .where(STATEMENT.PRJ_ID.eq(projectId))
                .orderBy(STATEMENT.ENTITY_ID, STATEMENT.MODEL);
    }

    private Connection openStreamingConnection() {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new DataAccessException("could not open a streaming connection", e);
        }
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            JDBCUtils.safeClose(connection);
            throw new DataAccessException("could not open a streaming connection", e);
        }
        return connection;
    }

    // Cursor.close() throws unchecked, so closing it through safeClose keeps the rollback on the
    // path where it fails: the connection goes back to the pool without an open read transaction.
    private static void release(Connection connection, Cursor<?> cursor) {
        try (connection) {
            JDBCUtils.safeClose(cursor);
            connection.rollback();
        } catch (SQLException e) {
            throw new DataAccessException("could not release the streaming connection", e);
        }
    }

    private DSLContext create() {
        return DSL.using(dataSource, dialect);
    }

    private static Row toRow(Record row) {
        String model = row.get(STATEMENT.MODEL);
        String prefix = model + ":";
        String property = row.get(STATEMENT.PROPERTY);
        if (!property.startsWith(prefix)) {
            throw new DataAccessException("statement '" + row.get(STATEMENT.ID) + "' holds property '" + property
                    + "', which is not namespaced under its model '" + model + "'");
        }
        return new Row(new Statement(row.get(STATEMENT.ID), model, row.get(STATEMENT.ENTITY_ID),
                row.get(STATEMENT.ENTITY_TYPE), property.substring(prefix.length()), row.get(STATEMENT.VALUE),
                null,
                new Statement.Provenance(row.get(STATEMENT.DOC_ID), row.get(STATEMENT.SHEET),
                        row.get(STATEMENT.ROW_NUMBER), row.get(STATEMENT.COLUMN_NAME))),
                row.get(STATEMENT.MODEL_VERSION));
    }

    private static Set<String> versions(List<Row> rows) {
        return rows.stream().map(Row::modelVersion).collect(toCollection(TreeSet::new));
    }

    // The query is ordered by (entity_id, model), so an entity's statements are consecutive and each
    // one is folded into its entity as it is read: one CSV can produce 100k+ entities, and a mapping
    // keyed on a low-cardinality column puts every one of its rows under a single entity, so neither
    // the project nor one group is ever collected first. Grouping on entity_id alone would let a
    // same-id entity spanning two models reach ModelEntity.from, which throws on a multi-model group.
    private static Stream<ModelEntity> group(Stream<Row> rows) {
        Iterator<ModelEntity> entities = new Groups(rows.iterator());
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(entities, Spliterator.ORDERED), false)
                .onClose(rows::close);
    }

    private static class Groups implements Iterator<ModelEntity> {
        private final Iterator<Row> rows;
        private Row pending;
        private boolean primed;

        Groups(Iterator<Row> rows) {
            this.rows = rows;
        }

        @Override
        public boolean hasNext() {
            if (!primed) {
                pending = rows.hasNext() ? rows.next() : null;
                primed = true;
            }
            return pending != null;
        }

        @Override
        public ModelEntity next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            // The versions the group was written under are collected as it is folded, and read once
            // the fold is over, so a group is still never held whole to be counted.
            Set<String> modelVersions = new TreeSet<>();
            Iterator<Statement> statements = group(modelVersions);
            try {
                return ModelEntity.from(() -> statements, modelVersions);
            } catch (RuntimeException unfoldable) {
                // A fold that gives up part way leaves the group half read and pending null, which
                // reads as "no more entities": the rows it never reached would end the stream
                // instead of the group. Finishing the group puts pending back on the next one, so a
                // caller that skips this entity still sees every entity after it.
                try {
                    statements.forEachRemaining(ignored -> { });
                } catch (RuntimeException unreadable) {
                    unfoldable.addSuppressed(unreadable);
                }
                throw unfoldable;
            }
        }

        // The row that ends a group is the first row of the next one, so it is held back as pending
        // rather than read twice.
        private Iterator<Statement> group(Set<String> modelVersions) {
            Row first = pending;
            pending = null;
            return new Iterator<>() {
                private Row next = first;

                @Override
                public boolean hasNext() {
                    return next != null;
                }

                @Override
                public Statement next() {
                    Row current = next;
                    modelVersions.add(current.modelVersion());
                    next = rows.hasNext() ? sameGroupAs(current.statement()) : null;
                    return current.statement();
                }

                private Row sameGroupAs(Statement current) {
                    Row row = rows.next();
                    if (row.statement().entityId().equals(current.entityId())
                            && row.statement().model().equals(current.model())) {
                        return row;
                    }
                    pending = row;
                    return null;
                }
            };
        }
    }
}
