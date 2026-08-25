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
import org.jooq.Query;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
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
    private final DataSource dataSource;
    private final SQLDialect dialect;
    private final int chunkSize;

    public JooqStatementRepository(DataSource dataSource, SQLDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.chunkSize = JooqCasbinRuleAdapter.determineBatchSize(dialect);
    }

    @Override
    public int save(String projectId, String runId, Collection<Statement> statements) {
        LocalDateTime now = new Timestamp(DatashareTime.getInstance().currentTimeMillis()).toLocalDateTime();
        Iterator<Statement> source = statements.iterator();
        int written = 0;
        while (source.hasNext()) {
            List<Statement> chunk = new ArrayList<>(chunkSize);
            while (chunk.size() < chunkSize && source.hasNext()) {
                chunk.add(source.next());
            }
            written += create().transactionResult(configuration ->
                    saveChunk(DSL.using(configuration), projectId, runId, chunk, now));
        }
        return written;
    }

    // A batch built from Query...values(...) inlines every row's SQL and ships it as literal text
    // (jOOQ's "batch multiple"); a batch built from one bound template query and repeated .bind()
    // calls ("batch single") reuses the same PreparedStatement and lets Postgres plan it once.
    private static int saveChunk(DSLContext create, String projectId, String runId, List<Statement> chunk, LocalDateTime now) {
        BatchBindStep batch = create.batch(upsert(create, projectId, runId, chunk.get(0), now));
        for (Statement statement : chunk) {
            batch.bind(bindValues(projectId, runId, statement, now));
        }
        return IntStream.of(batch.execute()).sum();
    }

    private static Query upsert(DSLContext create, String projectId, String runId, Statement statement, LocalDateTime now) {
        return create.insertInto(STATEMENT)
                .set(STATEMENT.ID, statement.id())
                .set(STATEMENT.PRJ_ID, projectId)
                .set(STATEMENT.RUN_ID, runId)
                .set(STATEMENT.MODEL, statement.model())
                .set(STATEMENT.MODEL_VERSION, TargetModelRegistry.get(statement.model()).version())
                .set(STATEMENT.ENTITY_ID, statement.entityId())
                .set(STATEMENT.ENTITY_TYPE, statement.entityType())
                .set(STATEMENT.PROPERTY, statement.qualifiedProperty())
                .set(STATEMENT.VALUE, statement.value())
                .set(STATEMENT.DOC_ID, statement.provenance().documentId())
                .set(STATEMENT.SHEET, statement.provenance().sheet())
                .set(STATEMENT.ROW_NUMBER, statement.provenance().rowNumber())
                .set(STATEMENT.COLUMN_NAME, statement.provenance().column())
                .set(STATEMENT.FIRST_SEEN, now)
                .set(STATEMENT.LAST_SEEN, now)
                .onConflictDoNothing();
    }

    // Bind order must match upsert()'s set(...) chain exactly: jOOQ's batch-single binds are
    // positional, not named.
    private static Object[] bindValues(String projectId, String runId, Statement statement, LocalDateTime now) {
        return new Object[]{
                statement.id(), projectId, runId, statement.model(),
                TargetModelRegistry.get(statement.model()).version(),
                statement.entityId(), statement.entityType(), statement.qualifiedProperty(), statement.value(),
                statement.provenance().documentId(), statement.provenance().sheet(),
                statement.provenance().rowNumber(), statement.provenance().column(),
                now, now
        };
    }

    @Override
    public Optional<ModelEntity> entity(String projectId, String entityId) {
        List<Statement> statements = create()
                .selectFrom(STATEMENT)
                .where(STATEMENT.PRJ_ID.eq(projectId)).and(STATEMENT.ENTITY_ID.eq(entityId))
                .orderBy(STATEMENT.MODEL, STATEMENT.PROPERTY, STATEMENT.VALUE)
                .fetch().map(JooqStatementRepository::toStatement);
        return statements.isEmpty() ? Optional.empty() : Optional.of(ModelEntity.from(statements));
    }

    // A DataSource-bound DSLContext never sets autoCommit(false), and pgjdbc only opens a
    // server-side cursor when fetchSize > 0 AND autoCommit is false: without both, the driver
    // buffers the whole result before the first row is emitted. So this holds one connection,
    // outside the pool's default autocommit, for the stream's lifetime. xerial's SQLite driver
    // ignores fetchSize entirely, so the same treatment there is pure cost: a held read
    // transaction on the shared-cache SQLite database, which makes a concurrent writer fail with
    // SQLITE_LOCKED instead of retrying.
    @Override
    public Stream<ModelEntity> entities(String projectId) {
        if (dialect != SQLDialect.POSTGRES) {
            Stream<Statement> rows = create()
                    .selectFrom(STATEMENT)
                    .where(STATEMENT.PRJ_ID.eq(projectId))
                    .orderBy(STATEMENT.ENTITY_ID, STATEMENT.MODEL, STATEMENT.PROPERTY, STATEMENT.VALUE)
                    .fetchStream()
                    .map(JooqStatementRepository::toStatement);
            return group(rows);
        }
        Connection connection = openStreamingConnection();
        try {
            Cursor<StatementRecord> cursor = DSL.using(connection, dialect)
                    .selectFrom(STATEMENT)
                    .where(STATEMENT.PRJ_ID.eq(projectId))
                    .orderBy(STATEMENT.ENTITY_ID, STATEMENT.MODEL, STATEMENT.PROPERTY, STATEMENT.VALUE)
                    .fetchSize(FETCH_SIZE)
                    .fetchLazy();
            Stream<Statement> rows = cursor.stream()
                    .map(JooqStatementRepository::toStatement)
                    .onClose(() -> release(connection, cursor));
            return group(rows);
        } catch (RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    @Override
    public <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer) {
        try (Stream<ModelEntity> entities = entities(projectId)) {
            return consumer.apply(entities);
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
            closeQuietly(connection);
            throw new DataAccessException("could not open a streaming connection", e);
        }
        return connection;
    }

    private static void release(Connection connection, Cursor<?> cursor) {
        try (connection) {
            cursor.close();
            connection.rollback();
        } catch (SQLException e) {
            throw new DataAccessException("could not release the streaming connection", e);
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    private DSLContext create() {
        return DSL.using(dataSource, dialect);
    }

    private static Statement toStatement(StatementRecord row) {
        return new Statement(row.getId(), row.getModel(), row.getEntityId(), row.getEntityType(),
                row.getProperty().substring(row.getModel().length() + 1), row.getValue(),
                new Statement.Provenance(row.getDocId(), row.getSheet(), row.getRowNumber(), row.getColumnName()));
    }

    // The query is ordered by (entity_id, model), so an entity's statements are consecutive and each
    // group can be emitted without holding the whole project in memory: one CSV can produce 100k+
    // entities. Grouping on entity_id alone would let a same-id entity spanning two models reach
    // ModelEntity.from, which throws on a multi-model group.
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
                List<Statement> group = new ArrayList<>(List.of(pending));
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
                return ModelEntity.from(group);
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(entities, Spliterator.ORDERED), false)
                .onClose(statements::close);
    }
}
