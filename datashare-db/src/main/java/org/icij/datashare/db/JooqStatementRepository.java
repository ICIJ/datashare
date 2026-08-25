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
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.icij.datashare.db.Tables.STATEMENT;

public class JooqStatementRepository implements StatementRepository {
    private static final int FETCH_SIZE = 1_000;
    private static final Field<?>[] READ_FIELDS = {
            STATEMENT.ID, STATEMENT.MODEL, STATEMENT.ENTITY_ID, STATEMENT.ENTITY_TYPE, STATEMENT.PROPERTY,
            STATEMENT.VALUE, STATEMENT.DOC_ID, STATEMENT.SHEET, STATEMENT.ROW_NUMBER, STATEMENT.COLUMN_NAME};
    private static final Comparator<Statement> BY_PROPERTY_THEN_VALUE =
            Comparator.comparing(Statement::property).thenComparing(Statement::value);
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

    @Override
    public int save(String projectId, String runId, Collection<Statement> statements) {
        Write write = new Write(projectId, runId,
                new Timestamp(DatashareTime.getInstance().currentTimeMillis()).toLocalDateTime());
        Iterator<Statement> source = statements.iterator();
        int written = 0;
        while (source.hasNext()) {
            List<Statement> chunk = new ArrayList<>(Math.min(chunkSize, statements.size()));
            while (chunk.size() < chunkSize && source.hasNext()) {
                chunk.add(source.next());
            }
            written += create().transactionResult(configuration ->
                    saveChunk(DSL.using(configuration), write, chunk));
        }
        return written;
    }

    // A batch built from Query...values(...) inlines every row's SQL and ships it as literal text
    // (jOOQ's "batch multiple"); a batch built from one bound template query and repeated .bind()
    // calls ("batch single") reuses the same PreparedStatement and lets Postgres plan it once. Both
    // the template's column list and each row's positional binds come from the same record, so they
    // cannot drift.
    private static int saveChunk(DSLContext create, Write write, List<Statement> chunk) {
        BatchBindStep batch = create.batch(
                create.insertInto(STATEMENT).set(row(write, chunk.get(0))).onConflictDoNothing());
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
        row.setDocId(statement.provenance().documentId());
        row.setSheet(statement.provenance().sheet());
        row.setRowNumber(statement.provenance().rowNumber());
        row.setColumnName(statement.provenance().column());
        row.setFirstSeen(write.now());
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
        List<Statement> statements = create()
                .select(READ_FIELDS).from(STATEMENT)
                .where(STATEMENT.PRJ_ID.eq(projectId)).and(STATEMENT.ENTITY_ID.eq(entityId))
                .orderBy(STATEMENT.MODEL)
                .fetch().map(JooqStatementRepository::toStatement);
        try (Stream<ModelEntity> entities = group(statements.stream())) {
            return entities.findFirst();
        }
    }

    @Override
    public <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer) {
        try (Stream<ModelEntity> entities = entities(projectId)) {
            return consumer.apply(entities);
        }
    }

    // A DataSource-bound DSLContext never sets autoCommit(false), and pgjdbc only opens a server-side
    // cursor when fetchSize > 0 AND autoCommit is false: without both, the driver buffers the whole
    // result before the first row is emitted. So this holds one connection, outside the pool's
    // default autocommit, for the stream's lifetime.
    private Stream<ModelEntity> entities(String projectId) {
        Connection connection = openStreamingConnection();
        try {
            Cursor<Record> cursor = DSL.using(connection, dialect)
                    .select(READ_FIELDS).from(STATEMENT)
                    .where(STATEMENT.PRJ_ID.eq(projectId))
                    .orderBy(STATEMENT.ENTITY_ID, STATEMENT.MODEL)
                    .fetchSize(FETCH_SIZE)
                    .fetchLazy();
            return group(cursor.stream()
                    .map(JooqStatementRepository::toStatement)
                    .onClose(() -> release(connection, cursor)));
        } catch (RuntimeException e) {
            JDBCUtils.safeClose(connection);
            throw e;
        }
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

    private static Statement toStatement(Record row) {
        String model = row.get(STATEMENT.MODEL);
        String prefix = model + ":";
        String property = row.get(STATEMENT.PROPERTY);
        if (!property.startsWith(prefix)) {
            throw new DataAccessException("statement '" + row.get(STATEMENT.ID) + "' holds property '" + property
                    + "', which is not namespaced under its model '" + model + "'");
        }
        return new Statement(row.get(STATEMENT.ID), model, row.get(STATEMENT.ENTITY_ID),
                row.get(STATEMENT.ENTITY_TYPE), property.substring(prefix.length()), row.get(STATEMENT.VALUE),
                new Statement.Provenance(row.get(STATEMENT.DOC_ID), row.get(STATEMENT.SHEET),
                        row.get(STATEMENT.ROW_NUMBER), row.get(STATEMENT.COLUMN_NAME)));
    }

    // The query is ordered by (entity_id, model), so an entity's statements are consecutive and each
    // group can be emitted without holding the whole project in memory: one CSV can produce 100k+
    // entities. Grouping on entity_id alone would let a same-id entity spanning two models reach
    // ModelEntity.from, which throws on a multi-model group. Property and value are then sorted here
    // rather than in the ORDER BY, because a database collation would otherwise decide the order of
    // an entity's values, and SQLite and Postgres do not sort text alike.
    private static Stream<ModelEntity> group(Stream<Statement> statements) {
        Iterator<Statement> rows = statements.iterator();
        Iterator<ModelEntity> entities = new Iterator<>() {
            private Statement pending;
            private boolean primed;

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
                List<Statement> group = new ArrayList<>();
                group.add(pending);
                String entityId = pending.entityId();
                String model = pending.model();
                pending = null;
                while (rows.hasNext()) {
                    Statement row = rows.next();
                    if (!row.entityId().equals(entityId) || !row.model().equals(model)) {
                        pending = row;
                        break;
                    }
                    group.add(row);
                }
                group.sort(BY_PROPERTY_THEN_VALUE);
                return ModelEntity.from(group);
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(entities, Spliterator.ORDERED), false)
                .onClose(statements::close);
    }
}
