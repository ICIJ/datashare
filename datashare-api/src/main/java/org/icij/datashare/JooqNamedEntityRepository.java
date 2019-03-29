package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.postgresql.ds.PGPoolingDataSource;

import java.sql.Connection;
import java.sql.SQLException;

import static org.jooq.impl.DSL.*;

public class JooqNamedEntityRepository implements NamedEntityRepository {
    private static final String NAMED_ENTITY = "named_entity";
    private static final String DOCUMENT = "document";
    private final PGPoolingDataSource source;

    public JooqNamedEntityRepository() throws SQLException {
        source = new PGPoolingDataSource();
        source.setDataSourceName("datashare");
        source.setServerName("postgresql");
        source.setDatabaseName("datashare");
        source.setUser("datashare");
        source.setPassword("dev");
        source.setMaxConnections(10);
        try (Connection conn = source.getConnection()) {
            DSLContext create = DSL.using(conn, SQLDialect.POSTGRES_10);
            create.createTable(NAMED_ENTITY).
                    column("id", SQLDataType.VARCHAR.length(96).nullable(false)).
                    column("category", SQLDataType.VARCHAR(12).nullable(false)).
                    column("mention", SQLDataType.VARCHAR(4096).nullable(false)).
                    column("neoffset", SQLDataType.BIGINT).
                    column("document_id", SQLDataType.VARCHAR.length(96).nullable(false)).
                    column("document_root", SQLDataType.VARCHAR.length(96)).
                    column("extractor",SQLDataType.VARCHAR.length(12).nullable(false)).
                    column("language", SQLDataType.VARCHAR.length(32)).
                    constraints(
                        constraint("PK_ID_NE").primaryKey("id")
                    ).execute();
            create.createTable(DOCUMENT).
                    column("id", SQLDataType.VARCHAR.length(96).nullable(false)).
                    column("path", SQLDataType.VARCHAR(4096)).
                    constraints(
                            constraint("PK_ID_DOC").primaryKey("id")
                    ).execute();
        }
    }

    @Override
    public NamedEntity get(String id) throws SQLException {
        try (Connection conn = source.getConnection()) {
            DSLContext create = DSL.using(conn, SQLDialect.POSTGRES_10);
            Record result = create.select().from(table(NAMED_ENTITY)).where(field("id").eq(id)).fetch().get(0);
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
                    field("id"), field("category"), field("mention"), field("neoffset"), field("document_id"),
                    field("document_root"), field("extractor"), field("language")).
                    values(ne.getId(), ne.getCategory().toString(), ne.getMention(), ne.getOffset(), ne.getDocumentId(),
                            ne.getRootDocument(), ne.getExtractor().toString(), ne.getExtractorLanguage().toString()).execute();
        }
    }

    @Override
    public void create(Document doc) throws SQLException {
        try (Connection conn = source.getConnection()) {
            DSLContext ctx = DSL.using(conn, SQLDialect.POSTGRES_10);
            ctx.insertInto(table(DOCUMENT),
                    field("id"), field("path")).
                    values(doc.getId(), doc.getPath().toString()).execute();
        }
    }

    @Override
    public void update(NamedEntity ne) {
    }

    private NamedEntity createFrom(Record result) {
        return NamedEntity.create(
                NamedEntity.Category.parse(result.get("category", String.class)),
                result.get("mention", String.class),
                result.get("neoffset", Integer.class),
                result.get("document_id", String.class),
                result.get("document_root", String.class),
                Pipeline.Type.parse(result.get("extractor", String.class)),
                Language.parse(result.get("language", String.class))
        );
    }
}
