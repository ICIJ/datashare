package org.icij.datashare.db;

import org.icij.datashare.Repository;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;

import static java.nio.charset.Charset.forName;
import static org.icij.datashare.text.Document.Status.fromCode;
import static org.icij.datashare.text.Language.parse;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

public class JooqRepository implements Repository {
    private static final String DOCUMENT = "document";
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
            Record result = create.select().from(table(DOCUMENT)).where(field("id").eq(id)).fetch().get(0);
            return createDocumentFrom(result);
        }
    }

    @Override
    public void create(List<NamedEntity> neList) {

    }

    @Override
    public void create(Document doc) throws SQLException {
        try(Connection conn = connectionProvider.acquire()) {
            DSLContext ctx = DSL.using(conn, dialect);
            ctx.insertInto(table(DOCUMENT),
                    field("id"), field("path"), field("content"), field("status"),
                    field("charset"), field("language"), field("content_type")).
                    values(doc.getId(), doc.getPath().toString(), doc.getContent(), doc.getStatus().code,
                            doc.getContentEncoding(), doc.getLanguage().iso6391Code(), doc.getContentType()).execute();
        }
    }

    @Override
    public void update(NamedEntity ne) {

    }

    @Override
    public NamedEntity delete(String id) {
        return null;
    }

    private Document createDocumentFrom(Record result) {
        return new Document(result.get("id", String.class), Paths.get(result.get("path", String.class)),
                result.get("content", String.class), parse(result.get("language", String.class)), forName(result.get("charset", String.class)),
                result.get("content_type", String.class), new HashMap<>(), fromCode(result.get("status", Integer.class)));
    }
}
