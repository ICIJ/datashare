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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
        DSLContext create = DSL.using(dataSource, dialect);
        List<Query> queries = statements.stream().map(statement -> (Query) upsert(create, projectId, runId, statement, now)).toList();
        int written = 0;
        for (int from = 0; from < queries.size(); from += CHUNK) {
            written += IntStream.of(create.batch(queries.subList(from, Math.min(from + CHUNK, queries.size()))).execute()).sum();
        }
        return written;
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
                .onConflict(STATEMENT.ID).doUpdate()
                .set(STATEMENT.LAST_SEEN, now)
                .set(STATEMENT.RUN_ID, runId);
    }

    @Override
    public Stream<ModelEntity> entities(String projectId) {
        throw new UnsupportedOperationException("task 5");
    }

    @Override
    public Optional<ModelEntity> entity(String projectId, String entityId) {
        throw new UnsupportedOperationException("task 5");
    }
}
