package org.icij.datashare.db;

import org.icij.datashare.Repository;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.jooq.*;
import org.jooq.impl.DSL;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.Charset.forName;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.text.Document.Status.fromCode;
import static org.icij.datashare.text.Language.parse;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

public class JooqRepository implements Repository {
    private static final String DOCUMENT = "document";
    private static final String DOCUMENT_META = "document_meta";
    private static final String DOCUMENT_NER = "document_ner_pipeline_type";

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
    public void create(List<NamedEntity> neList) {

    }

    @Override
    public Document getDocument(final String id) throws SQLException {
        try(Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            Record docResult = create.select().from(table(DOCUMENT)).where(field("id").eq(id)).fetch().get(0);
            Result<Record> metaResults = create.select().from(table(DOCUMENT_META)).where(field("doc_id").eq(id)).fetch();
            Result<Record> nerResults = create.select().from(table(DOCUMENT_NER)).where(field("doc_id").eq(id)).fetch();
            return createFrom(docResult, metaResults, nerResults);
        }
    }

    @Override
    public void create(Document doc) throws SQLException {
        try(Connection conn = connectionProvider.acquire()) {
            DSLContext ctx = DSL.using(conn, dialect);
            ctx.transaction(cfg -> {
                DSL.using(cfg).insertInto(table(DOCUMENT),
                                    field("id"), field("path"), field("content"), field("status"),
                                    field("charset"), field("language"), field("content_type"),
                                    field("extraction_date"), field("parent_id"), field("root_id"),
                                    field("extraction_level"), field("content_length")).
                                    values(doc.getId(), doc.getPath().toString(), doc.getContent(), doc.getStatus().code,
                                            doc.getContentEncoding(), doc.getLanguage().iso6391Code(), doc.getContentType(),
                                            doc.getExtractionDate(), doc.getParentDocument(), doc.getRootDocument(),
                                            doc.getExtractionLevel(), doc.getContentLength()).execute();

                InsertValuesStep3<Record, Object, Object, Object> insertMeta = DSL.using(cfg).insertInto(table(DOCUMENT_META), field("doc_id"), field("key"), field("value"));
                doc.getMetadata().forEach((key, value) -> insertMeta.values(doc.getId(), key, value));
                insertMeta.execute();

                if (!doc.getNerTags().isEmpty()) {
                    InsertValuesStep2<Record, Object, Object> insertNerPipelines = DSL.using(cfg).insertInto(table(DOCUMENT_NER), field("doc_id"), field("type_id"));
                    doc.getNerTags().forEach(type -> insertNerPipelines.values(doc.getId(), type.code));
                    insertNerPipelines.execute();
                }
            });
        }
    }

    @Override
    public NamedEntity deleteNamedEntity(String id) { return null;}

    @Override
    public Document deleteDocument(String id) { return null;}

    private Document createFrom(Record result, Result<Record> metaResults, Result<Record> nerResults) {
        Map<String, String> map = (Map<String, String>) metaResults.intoMap("key", "value");
        Set<Pipeline.Type> nerTags = nerResults.intoSet("type_id").stream().map(i -> Pipeline.Type.fromCode((Byte)i)).collect(toSet());
        return new Document(result.get("id", String.class), Paths.get(result.get("path", String.class)),
                result.get("content", String.class), parse(result.get("language", String.class)), forName(result.get("charset", String.class)),
                result.get("content_type", String.class), map, fromCode(result.get("status", Integer.class)), nerTags,
                new Date(result.get("extraction_date", Long.class)), result.get("parent_id", String.class), result.get("root_id", String.class),
                result.get("extraction_level", Integer.class), result.get("content_length", Long.class)
        );
    }
}
