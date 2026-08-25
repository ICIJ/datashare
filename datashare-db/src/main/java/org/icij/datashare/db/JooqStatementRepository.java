package org.icij.datashare.db;

import org.icij.datashare.db.tables.records.StatementRecord;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Statement;
import org.icij.datashare.model.StatementRepository;
import org.icij.datashare.model.TargetModelRegistry;
import org.icij.datashare.time.DatashareTime;
import org.jooq.DSLContext;
import org.jooq.InsertOnDuplicateSetMoreStep;
import org.jooq.Query;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.icij.datashare.db.Tables.STATEMENT;

public class JooqStatementRepository implements StatementRepository {
    private static final int CHUNK = 1_000;
    private final DataSource dataSource;
    private final SQLDialect dialect;

    public JooqStatementRepository(DataSource dataSource, SQLDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public int save(String projectId, String runId, Collection<Statement> statements) {
        LocalDateTime now = new Timestamp(DatashareTime.getInstance().currentTimeMillis()).toLocalDateTime();
        return DSL.using(dataSource, dialect).transactionResult(configuration -> {
            DSLContext create = DSL.using(configuration);
            List<Query> queries = statements.stream().map(statement -> (Query) upsert(create, projectId, runId, statement, now)).toList();
            int written = 0;
            for (int from = 0; from < queries.size(); from += CHUNK) {
                written += IntStream.of(create.batch(queries.subList(from, Math.min(from + CHUNK, queries.size()))).execute()).sum();
            }
            return written;
        });
    }

    private InsertOnDuplicateSetMoreStep<StatementRecord> upsert(DSLContext create, String projectId, String runId, Statement statement, LocalDateTime now) {
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
                .onConflict(STATEMENT.PRJ_ID, STATEMENT.ID).doUpdate()
                .set(STATEMENT.LAST_SEEN, now)
                .set(STATEMENT.RUN_ID, runId)
                .set(STATEMENT.MODEL_VERSION, TargetModelRegistry.get(statement.model()).version());
    }

    @Override
    public Optional<ModelEntity> entity(String projectId, String entityId) {
        List<Statement> statements = DSL.using(dataSource, dialect)
                .selectFrom(STATEMENT)
                .where(STATEMENT.PRJ_ID.eq(projectId)).and(STATEMENT.ENTITY_ID.eq(entityId))
                .fetch().map(JooqStatementRepository::toStatement);
        return statements.isEmpty() ? Optional.empty() : Optional.of(ModelEntity.from(statements));
    }

    @Override
    public Stream<ModelEntity> entities(String projectId) {
        Stream<Statement> rows = DSL.using(dataSource, dialect)
                .selectFrom(STATEMENT)
                .where(STATEMENT.PRJ_ID.eq(projectId))
                .orderBy(STATEMENT.ENTITY_ID)
                .fetchLazy().stream()
                .map(JooqStatementRepository::toStatement);
        return group(rows);
    }

    private static Statement toStatement(StatementRecord row) {
        return new Statement(row.getId(), row.getModel(), row.getEntityId(), row.getEntityType(),
                row.getProperty().substring(row.getModel().length() + 1), row.getValue(),
                new Statement.Provenance(row.getDocId(), row.getSheet(), row.getRowNumber(), row.getColumnName()));
    }

    // The query is ordered by entity_id, so an entity's statements are consecutive and each group can
    // be emitted without holding the whole project in memory: one CSV can produce 100k+ entities.
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
                pending = null;
                while (rows.hasNext()) {
                    Statement row = rows.next();
                    if (!row.entityId().equals(entityId)) {
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
