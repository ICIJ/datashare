package org.icij.datashare.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.icij.datashare.Repository;
import org.icij.datashare.text.*;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.jooq.*;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

import static java.nio.charset.Charset.forName;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.text.Document.Status.fromCode;
import static org.icij.datashare.text.Language.parse;
import static org.icij.datashare.text.Project.project;
import static org.jooq.impl.DSL.*;

public class JooqRepository implements Repository {
    private static final String DOCUMENT = "document";
    private static final String NAMED_ENTITY = "named_entity";
    private static final String DOCUMENT_USER_STAR = "document_user_star";
    private static final String DOCUMENT_TAG = "document_tag";

    private final ConnectionProvider connectionProvider;
    private SQLDialect dialect;

    public JooqRepository(final ConnectionProvider connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    @Override
    public NamedEntity getNamedEntity(String id) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            return createFrom(create.select().from(table(NAMED_ENTITY)).where(field("id").eq(id)).fetch().get(0));
        }
    }

    @Override
    public void create(List<NamedEntity> neList) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            InsertValuesStep9<Record, Object, Object, Object, Object, Object, Object, Object, Object, Object>
                    insertQuery = create.insertInto(table(NAMED_ENTITY),
                    field("id"), field("mention"), field("ne_offset"), field("extractor"),
                    field("category"), field("doc_id"), field("root_id"),
                    field("extractor_language"), field("hidden"));
            neList.forEach(ne -> insertQuery.values(
                    ne.getId(), ne.getMention(), ne.getOffset(), ne.getExtractor().code,
                    ne.getCategory().getAbbreviation(), ne.getDocumentId(), ne.getRootDocument(),
                    ne.getExtractorLanguage().iso6391Code(), ne.isHidden()));
            insertQuery.execute();
        }
    }

    @Override
    public Document getDocument(final String id) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            Record docResult = create.select().from(table(DOCUMENT)).where(field("id").eq(id)).fetch().get(0);
            return createDocumentFrom(docResult);
        }
    }

    @Override
    public void create(Document doc) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext ctx = DSL.using(conn, dialect);
            try {
                ctx.insertInto(table(DOCUMENT), field("project_id"),
                        field("id"), field("path"), field("content"), field("status"),
                        field("charset"), field("language"), field("content_type"),
                        field("extraction_date"), field("parent_id"), field("root_id"),
                        field("extraction_level"), field("content_length"), field("metadata"), field("ner_mask")).
                        values(doc.getProject().getId(), doc.getId(), doc.getPath().toString(), doc.getContent(), doc.getStatus().code,
                                doc.getContentEncoding().toString(), doc.getLanguage().iso6391Code(), doc.getContentType(),
                                new Timestamp(doc.getExtractionDate().getTime()), doc.getParentDocument(), doc.getRootDocument(),
                                doc.getExtractionLevel(), doc.getContentLength(),
                                MAPPER.writeValueAsString(doc.getMetadata()), doc.getNerMask()).execute();
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public List<Document> getDocumentsNotTaggedWithPipeline(Project project, Pipeline.Type type) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            Result<Record> fetch = create.select().from(table(DOCUMENT)).where(
                    condition("(ner_mask & ?) = 0", type.mask)).fetch();
            return fetch.stream().map(this::createDocumentFrom).collect(toList());
        }
    }

    @Override
    public boolean star(User user, String documentId) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            Result<Record1<Integer>> existResult = create.selectCount().from(table(DOCUMENT_USER_STAR)).
                    where(field("user_id").equal(user.id), field("doc_id").equal(documentId)).fetch();
            if (existResult.get(0).value1() == 0) {
                return create.insertInto(table(DOCUMENT_USER_STAR), field("doc_id"), field("user_id")).
                        values(documentId, user.id).execute() > 0;
            } else {
                return false;
            }
        }
    }

    @Override
    public boolean unstar(User user, String documentId) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            return DSL.using(conn, dialect).deleteFrom(table(DOCUMENT_USER_STAR)).
                    where(field("doc_id").equal(documentId), field("user_id").equal(user.id)).execute() > 0;
        }
    }

    @Override
    public List<Document> getStarredDocuments(User user) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            return create.select().from(table(DOCUMENT_USER_STAR).join(DOCUMENT).on(field(DOCUMENT + ".id").equal(field(DOCUMENT_USER_STAR + ".doc_id")))).
                    where(field("user_id").eq(user.id)).fetch().stream().map(this::createDocumentFrom).collect(toList());
        }
    }

    // ------------- functions that don't need document migration/indexing
    // they can use just the DOCUMENT_USER_STAR table thus denormalizing project information
    // this could be removed later
    @Override
    public boolean star(Project project, User user, String documentId) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            Result<Record1<Integer>> existResult = create.selectCount().from(table(DOCUMENT_USER_STAR)).
                    where(field("user_id").equal(user.id), field("doc_id").equal(documentId)).fetch();
            if (existResult.get(0).value1() == 0) {
                return create.insertInto(table(DOCUMENT_USER_STAR), field("doc_id"), field("user_id"), field("prj_id")).
                        values(documentId, user.id, project.getId()).execute() > 0;
            } else {
                return false;
            }
        }
    }

    @Override
    public boolean unstar(Project project, User user, String documentId) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            return DSL.using(conn, dialect).deleteFrom(table(DOCUMENT_USER_STAR)).
                    where(field("doc_id").equal(documentId),
                            field("user_id").equal(user.id),
                            field("prj_id").equal(project.getId())).execute() > 0;
        }
    }

    @Override
    public List<String> getStarredDocuments(Project project, User user) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            return create.select(field("doc_id")).from(table(DOCUMENT_USER_STAR)).
                    where(field("user_id").eq(user.id)).
                    and(field("prj_id").eq(project.getId())).
                    fetch().getValues("doc_id", String.class);
        }
    }

    @Override
    public boolean tag(Project prj, String documentId, Tag... tags) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            Set<Tag> existResult = create.select(field("label")).from(table(DOCUMENT_TAG)).
                    where(field("label").in(stream(tags).map(t -> t.label).collect(toSet())), field("doc_id").equal(documentId)).
                    fetch().getValues("label", String.class).stream().map(Tag::tag).collect(toSet());
            if (existResult.size() != tags.length) {
                List<Tag> tagList = asList(tags);
                tagList.removeAll(existResult);
                InsertValuesStep3<Record, Object, Object, Object> insertQuery = create.insertInto(table(DOCUMENT_TAG)).columns(field("doc_id"), field("label"), field("prj_id"));
                tagList.forEach(t -> insertQuery.values(documentId, t.label, prj.getId()));
                return insertQuery.execute() > 0;
            } else {
                return false;
            }
        }
    }

    @Override
    public boolean untag(Project prj, String documentId, Tag... tags) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            return DSL.using(conn, dialect).deleteFrom(table(DOCUMENT_TAG)).
                    where(field("doc_id").equal(documentId),
                            field("label").in(stream(tags).map(t -> t.label).collect(toSet())),
                            field("prj_id").equal(prj.getId())).execute() > 0;
        }
    }

    @Override
    public List<String> getDocuments(Project project, Tag... tags) throws SQLException {
        try (Connection conn = connectionProvider.acquire()) {
            DSLContext create = DSL.using(conn, dialect);
            return create.selectDistinct(field("doc_id")).from(table(DOCUMENT_TAG)).
                    where(field("label").in(stream(tags).map(t -> t.label).collect(toSet()))).
                    and(field("prj_id").eq(project.getId())).
                    fetch().getValues("doc_id", String.class);
        }
    }
    // ---------------------------

    private NamedEntity createFrom(Record record) {
        return NamedEntity.create(NamedEntity.Category.parse(record.get("category", String.class)),
                record.get("mention", String.class), record.get("ne_offset", Integer.class),
                record.get("doc_id", String.class), Pipeline.Type.fromCode(record.get("extractor", Integer.class)),
                Language.parse(record.get("extractor_language", String.class)));
    }

    private Document createDocumentFrom(Record result) {
        Map<String, Object> metadata;
        try {
            metadata = MAPPER.readValue(result.get("metadata", String.class), HashMap.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Set<Pipeline.Type> nerTags = Document.fromNerMask(result.get("ner_mask", Integer.class));
        return new Document(project(result.get("project_id", String.class)), result.get("id", String.class),
                Paths.get(result.get("path", String.class)), result.get("content", String.class), parse(result.get("language", String.class)),
                forName(result.get("charset", String.class)), result.get("content_type", String.class), metadata, fromCode(result.get("status", Integer.class)),
                nerTags, new Date(result.get("extraction_date", Timestamp.class).getTime()), result.get("parent_id", String.class),
                result.get("root_id", String.class), result.get("extraction_level", Integer.class),
                result.get("content_length", Long.class));
    }
}
