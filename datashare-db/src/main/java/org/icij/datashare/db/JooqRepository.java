package org.icij.datashare.db;

import org.icij.datashare.Repository;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.jooq.*;
import org.jooq.impl.DSL;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static java.nio.charset.Charset.forName;
import static org.icij.datashare.text.Document.Status.fromCode;
import static org.icij.datashare.text.Language.parse;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

public class JooqRepository implements Repository {
    private static final String DOCUMENT = "document";
    private static final String DOCUMENT_META = "document_meta";
    private final ConnectionProvider connectionProvider;
    private SQLDialect dialect;

    public JooqRepository(final ConnectionProvider connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    @Override
    public NamedEntity getNamedEntity(String id) {
        return null;
    }

    @Override
    public Document getDocument(final String id) throws SQLException {
        try(Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            Record docResult = create.select().from(table(DOCUMENT)).where(field("id").eq(id)).fetch().get(0);
            Result<Record> metaResults = create.select().from(table(DOCUMENT_META)).where(field("doc_id").eq(id)).fetch();
            return createDocumentFrom(docResult, metaResults);
        }
    }

    @Override
    public void create(List<NamedEntity> neList) {

    }

    @Override
    public void create(Document doc) throws SQLException {
        try(Connection conn = connectionProvider.acquire()) {
            DSLContext ctx = DSL.using(conn, dialect);
            ctx.transaction(configuration -> {
                DSL.using(configuration).insertInto(table(DOCUMENT),
                                    field("id"), field("path"), field("content"), field("status"),
                                    field("charset"), field("language"), field("content_type")).
                                    values(doc.getId(), doc.getPath().toString(), doc.getContent(), doc.getStatus().code,
                                            doc.getContentEncoding(), doc.getLanguage().iso6391Code(), doc.getContentType()).execute();
                InsertValuesStep3<Record, Object, Object, Object> insertMeta = DSL.using(configuration).insertInto(table(DOCUMENT_META), field("doc_id"), field("key"), field("value"));
                doc.getMetadata().forEach((key, value) -> insertMeta.values(doc.getId(), key, value));
                insertMeta.execute();
            });
        }
    }

    @Override
    public void update(NamedEntity ne) {

    }

    @Override
    public NamedEntity delete(String id) {
        return null;
    }

    private Document createDocumentFrom(Record result, Result<Record> metaResults) {
        Map<String, String> map = (Map<String, String>) metaResults.intoMap("key", "value");
        return new Document(result.get("id", String.class), Paths.get(result.get("path", String.class)),
                result.get("content", String.class), parse(result.get("language", String.class)), forName(result.get("charset", String.class)),
                result.get("content_type", String.class), map, fromCode(result.get("status", Integer.class)));
    }
}
