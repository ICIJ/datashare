package org.icij.datashare;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.postgresql.ds.PGPoolingDataSource;

import java.sql.Connection;
import java.sql.SQLException;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

public class JooqNamedEntityRepository implements NamedEntityRepository {
    private static final String NAMED_ENTITY = "named_entity";
    private final PGPoolingDataSource source;

    public JooqNamedEntityRepository() {
        source = new PGPoolingDataSource();
        source.setDataSourceName("datashare");
        source.setServerName("datashare");
        source.setDatabaseName("datashare");
        source.setUser("datashare");
        source.setPassword("datashare");
        source.setMaxConnections(10);
    }

    @Override
    public NamedEntity get(String id) throws SQLException {
        try (Connection conn = source.getConnection()) {
            DSLContext create = DSL.using(conn, SQLDialect.POSTGRES_10);
            Record result = create.select().from(table(NAMED_ENTITY)).where(field("id").eq("id")).fetch().get(0);
            return createFrom(result);
        }
    }

    @Override
    public NamedEntity delete(String id) throws SQLException {
        try (Connection conn = source.getConnection()) {
            DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES_10);
            Record result = ctx.delete(table(NAMED_ENTITY)).where(field("id").eq(id)).returning().fetchOne();
            return createFrom(result);
        }
    }

    @Override
    public void create(NamedEntity ne) throws SQLException {
        try (Connection conn = source.getConnection()) {
            DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES_10);
            ctx.insertInto(table(NAMED_ENTITY),
                    field("category"), field("mention"), field("offset"), field("document_id"),
                    field("document_root"), field("extractor"), field("language")).
                    values(ne.getCategory(), ne.getMention(), ne.getOffset(), ne.getDocumentId(),
                            ne.getRootDocument(), ne.getExtractor(), ne.getExtractorLanguage());
        }
    }

    @Override
    public void update(NamedEntity ne) {
    }

    private NamedEntity createFrom(Record result) {
        return NamedEntity.create(
                NamedEntity.Category.parse(result.get("category", String.class)),
                result.get("mention", String.class),
                result.get("offset", Integer.class),
                result.get("document_id", String.class),
                result.get("document_root", String.class),
                Pipeline.Type.parse(result.get("extractor", String.class)),
                Language.parse(result.get("language", String.class))
        );
    }
}
