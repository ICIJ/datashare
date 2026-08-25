package org.icij.datashare.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import org.icij.datashare.tabular.InvalidExtractionMapping;
import org.icij.datashare.tabular.UnreadableExtractionMapping;
import org.icij.datashare.time.DatashareTime;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.icij.datashare.db.Tables.EXTRACTION_MAPPING;

public class JooqExtractionMappingRepository implements ExtractionMappingRepository {
    private static final Logger logger = LoggerFactory.getLogger(JooqExtractionMappingRepository.class);
    private final DataSource dataSource;
    private final SQLDialect dialect;

    public JooqExtractionMappingRepository(DataSource dataSource, SQLDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public boolean save(ExtractionMapping mapping) {
        List<TargetModel.Violation> violations = mapping.validate();
        if (!violations.isEmpty()) {
            throw new InvalidExtractionMapping(mapping.id(), violations);
        }
        return create().insertInto(EXTRACTION_MAPPING)
                .set(EXTRACTION_MAPPING.ID, mapping.id())
                .set(EXTRACTION_MAPPING.PRJ_ID, mapping.projectId())
                .set(EXTRACTION_MAPPING.USER_ID, mapping.userId())
                .set(EXTRACTION_MAPPING.NAME, mapping.name())
                .set(EXTRACTION_MAPPING.DEFINITION, write(mapping))
                .set(EXTRACTION_MAPPING.CREATED_AT,
                        new Timestamp(DatashareTime.getInstance().currentTimeMillis()).toLocalDateTime())
                .onConflict(EXTRACTION_MAPPING.ID, EXTRACTION_MAPPING.PRJ_ID).doNothing().execute() > 0;
    }

    @Override
    public Optional<ExtractionMapping> get(String projectId, String id) {
        return create().select(EXTRACTION_MAPPING.DEFINITION).from(EXTRACTION_MAPPING)
                .where(EXTRACTION_MAPPING.ID.eq(id)).and(EXTRACTION_MAPPING.PRJ_ID.eq(projectId))
                .fetchOptional(EXTRACTION_MAPPING.DEFINITION)
                .map(definition -> read(id, definition));
    }

    @Override
    public List<ExtractionMapping> list(String projectId) {
        return create().select(EXTRACTION_MAPPING.ID, EXTRACTION_MAPPING.DEFINITION).from(EXTRACTION_MAPPING)
                .where(EXTRACTION_MAPPING.PRJ_ID.eq(projectId))
                .orderBy(EXTRACTION_MAPPING.CREATED_AT)
                .fetch().stream()
                .flatMap(row -> {
                    try {
                        return Stream.of(read(row.value1(), row.value2()));
                    } catch (UnreadableExtractionMapping e) {
                        logger.warn("skipping unreadable extraction mapping '{}'", row.value1(), e);
                        return Stream.empty();
                    }
                }).toList();
    }

    @Override
    public boolean delete(String projectId, String id) {
        return create().deleteFrom(EXTRACTION_MAPPING)
                .where(EXTRACTION_MAPPING.ID.eq(id)).and(EXTRACTION_MAPPING.PRJ_ID.eq(projectId)).execute() > 0;
    }

    private DSLContext create() {
        return DSL.using(dataSource, dialect);
    }

    // The definition column holds one concrete record type, not a Map<String, Object>, so there is
    // no @type marker to force: the plain mapper round-trips it, and it also spares Charset (e.g.
    // sun.nio.cs.UTF_8) from the typed mapper's polymorphic deserialization, which has no
    // string-argument constructor to satisfy.
    private static String write(ExtractionMapping mapping) {
        try {
            return JsonObjectMapper.writeValueAsString(mapping);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ExtractionMapping read(String id, String definition) {
        try {
            return JsonObjectMapper.readValue(definition, ExtractionMapping.class);
        } catch (JsonProcessingException e) {
            throw new UnreadableExtractionMapping(id, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
