package org.icij.datashare.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.icij.datashare.Repository;
import org.icij.datashare.db.tables.records.*;
import org.icij.datashare.text.*;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.jooq.*;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.*;

import static java.nio.charset.Charset.forName;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.db.tables.Document.DOCUMENT;
import static org.icij.datashare.db.tables.DocumentTag.DOCUMENT_TAG;
import static org.icij.datashare.db.tables.DocumentUserStar.DOCUMENT_USER_STAR;
import static org.icij.datashare.db.tables.NamedEntity.NAMED_ENTITY;
import static org.icij.datashare.db.tables.Project.PROJECT;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.text.Document.Status.fromCode;
import static org.icij.datashare.text.Language.parse;
import static org.icij.datashare.text.Project.project;
import static org.jooq.impl.DSL.*;

public class JooqRepository implements Repository {
    private final DataSource connectionProvider;
    private SQLDialect dialect;

    JooqRepository(final DataSource connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    @Override
    public NamedEntity getNamedEntity(String id) {
        return createFrom(DSL.using(connectionProvider, dialect).
                selectFrom(NAMED_ENTITY).where(NAMED_ENTITY.ID.eq(id)).fetchOne());
    }

    @Override
    public void create(List<NamedEntity> neList) {
        DSLContext create = DSL.using(connectionProvider, dialect);
        InsertValuesStep9<NamedEntityRecord, String, String, Long, Short, String, String, String, String, Boolean>
                insertQuery = create.insertInto(NAMED_ENTITY,
                NAMED_ENTITY.ID, NAMED_ENTITY.MENTION, NAMED_ENTITY.NE_OFFSET, NAMED_ENTITY.EXTRACTOR,
                NAMED_ENTITY.CATEGORY, NAMED_ENTITY.DOC_ID, NAMED_ENTITY.ROOT_ID,
                NAMED_ENTITY.EXTRACTOR_LANGUAGE, NAMED_ENTITY.HIDDEN);
        neList.forEach(ne -> insertQuery.values(
                ne.getId(), ne.getMention(), ne.getOffset(), ne.getExtractor().code,
                ne.getCategory().getAbbreviation(), ne.getDocumentId(), ne.getRootDocument(),
                ne.getExtractorLanguage().iso6391Code(), ne.isHidden()));
        insertQuery.execute();
    }

    @Override
    public Document getDocument(final String id) {
        return createDocumentFrom(DSL.using(connectionProvider, dialect).
                selectFrom(DOCUMENT).where(DOCUMENT.ID.eq(id)).fetchOne());
    }

    @Override
    public void create(Document doc) {
        DSLContext ctx = DSL.using(connectionProvider, dialect);
        try {
            ctx.insertInto(DOCUMENT, DOCUMENT.PROJECT_ID,
                    DOCUMENT.ID, DOCUMENT.PATH, DOCUMENT.CONTENT, DOCUMENT.STATUS,
                    DOCUMENT.CHARSET, DOCUMENT.LANGUAGE, DOCUMENT.CONTENT_TYPE,
                    DOCUMENT.EXTRACTION_DATE, DOCUMENT.PARENT_ID, DOCUMENT.ROOT_ID,
                    DOCUMENT.EXTRACTION_LEVEL, DOCUMENT.CONTENT_LENGTH, DOCUMENT.METADATA, DOCUMENT.NER_MASK).
                    values(doc.getProject().getId(), doc.getId(), doc.getPath().toString(), doc.getContent(), doc.getStatus().code,
                            doc.getContentEncoding().toString(), doc.getLanguage().iso6391Code(), doc.getContentType(),
                            new Timestamp(doc.getExtractionDate().getTime()), doc.getParentDocument(), doc.getRootDocument(),
                            doc.getExtractionLevel(), doc.getContentLength(),
                            MAPPER.writeValueAsString(doc.getMetadata()), doc.getNerMask()).execute();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Document> getDocumentsNotTaggedWithPipeline(Project project, Pipeline.Type type) {
        DSLContext create = DSL.using(connectionProvider, dialect);
        return create.selectFrom(DOCUMENT).where(
                condition("(ner_mask & ?) = 0", type.mask)).
                fetch().stream().map(this::createDocumentFrom).collect(toList());
    }

    @Override
    public boolean star(User user, String documentId) {
        DSLContext create = DSL.using(connectionProvider, dialect);
        Result<Record1<Integer>> existResult = create.selectCount().from(DOCUMENT_USER_STAR).
                where(DOCUMENT_USER_STAR.USER_ID.eq(user.id), DOCUMENT_USER_STAR.DOC_ID.eq(documentId)).fetch();
        if (existResult.get(0).value1() == 0) {
            return create.insertInto(DOCUMENT_USER_STAR, DOCUMENT_USER_STAR.DOC_ID, DOCUMENT_USER_STAR.USER_ID).
                    values(documentId, user.id).execute() > 0;
        } else {
            return false;
        }
    }

    @Override
    public boolean unstar(User user, String documentId) {
        return DSL.using(connectionProvider, dialect).deleteFrom(DOCUMENT_USER_STAR).
                where(DOCUMENT_USER_STAR.DOC_ID.eq(documentId), DOCUMENT_USER_STAR.USER_ID.equal(user.id)).execute() > 0;
    }

    @Override
    public List<Document> getStarredDocuments(User user) {
        DSLContext create = DSL.using(connectionProvider, dialect);
        return create.selectFrom(DOCUMENT.join(DOCUMENT_USER_STAR).on(DOCUMENT.ID.eq(DOCUMENT_USER_STAR.DOC_ID))).
                where(DOCUMENT_USER_STAR.USER_ID.eq(user.id)).fetch().stream().map(this::createDocumentFrom).collect(toList());
    }

    // ------------- functions that don't need document migration/indexing
    // they can use just the DOCUMENT_USER_STAR table thus denormalizing project information
    // this could be removed later
    @Override
    public int star(Project project, User user, List<String> documentIds) {
        InsertValuesStep3<DocumentUserStarRecord, String, String, String> query = using(connectionProvider, dialect).
                insertInto(DOCUMENT_USER_STAR, DOCUMENT_USER_STAR.DOC_ID, DOCUMENT_USER_STAR.USER_ID, DOCUMENT_USER_STAR.PRJ_ID);
        documentIds.forEach(t -> query.values(t, user.id, project.getId()));
        return query.execute();
    }

    @Override
    public int unstar(Project project, User user, List<String> documentIds) {
        return DSL.using(connectionProvider, dialect).deleteFrom(DOCUMENT_USER_STAR).
                where(DOCUMENT_USER_STAR.DOC_ID.in(documentIds),
                        DOCUMENT_USER_STAR.USER_ID.equal(user.id),
                        DOCUMENT_USER_STAR.PRJ_ID.equal(project.getId())).execute();
    }

    @Override
    public List<String> getStarredDocuments(Project project, User user) {
        DSLContext create = DSL.using(connectionProvider, dialect);
        return create.select(DOCUMENT_USER_STAR.DOC_ID).from(DOCUMENT_USER_STAR).
                where(DOCUMENT_USER_STAR.USER_ID.eq(user.id)).
                and(DOCUMENT_USER_STAR.PRJ_ID.eq(project.getId())).
                fetch().getValues(DOCUMENT_USER_STAR.DOC_ID);
    }

    @Override
    public boolean tag(Project prj, String documentId, Tag... tags) {
        InsertValuesStep5<DocumentTagRecord, String, String, String, Timestamp, String> query = using(connectionProvider, dialect).insertInto(
                DOCUMENT_TAG, DOCUMENT_TAG.DOC_ID, DOCUMENT_TAG.LABEL, DOCUMENT_TAG.PRJ_ID,
                DOCUMENT_TAG.CREATION_DATE, DOCUMENT_TAG.USER_ID);
        List<Tag> tagList = asList(tags);
        tagList.forEach(t -> query.values(documentId, t.label, prj.getId(), new Timestamp(t.creationDate.getTime()), t.user.id));
        return query.onConflictDoNothing().execute() > 0;
    }

    @Override
    public boolean untag(Project prj, String documentId, Tag... tags) {
        return DSL.using(connectionProvider, dialect).deleteFrom(DOCUMENT_TAG).
                where(DOCUMENT_TAG.DOC_ID.eq(documentId),
                        DOCUMENT_TAG.LABEL.in(stream(tags).map(t -> t.label).collect(toSet())),
                        DOCUMENT_TAG.PRJ_ID.equal(prj.getId())).execute() > 0;
    }

    @Override
    public boolean tag(Project prj, List<String> documentIds, Tag... tags) {
        InsertValuesStep5<DocumentTagRecord, String, String, String, Timestamp, String> query = using(connectionProvider, dialect).insertInto(
                        DOCUMENT_TAG, DOCUMENT_TAG.DOC_ID, DOCUMENT_TAG.LABEL, DOCUMENT_TAG.PRJ_ID,
                        DOCUMENT_TAG.CREATION_DATE, DOCUMENT_TAG.USER_ID);
        List<Tag> tagList = asList(tags);
        documentIds.forEach(d -> tagList.forEach(t -> query.values(d, t.label, prj.getId(), new Timestamp(t.creationDate.getTime()), t.user.id)));
        return query.onConflictDoNothing().execute() > 0;
    }

    @Override
    public boolean untag(Project prj, List<String> documentIds, Tag... tags) {
        return DSL.using(connectionProvider, dialect).deleteFrom(DOCUMENT_TAG).
                        where(DOCUMENT_TAG.DOC_ID.in(documentIds),
                                DOCUMENT_TAG.LABEL.in(stream(tags).map(t -> t.label).collect(toSet())),
                                DOCUMENT_TAG.PRJ_ID.equal(prj.getId())).execute() > 0;
    }

    @Override
    public List<String> getDocuments(Project project, Tag... tags) {
        DSLContext create = DSL.using(connectionProvider, dialect);
        return create.selectDistinct(DOCUMENT_TAG.DOC_ID).from(DOCUMENT_TAG).
                where(DOCUMENT_TAG.LABEL.in(stream(tags).map(t -> t.label).collect(toSet()))).
                and(DOCUMENT_TAG.PRJ_ID.eq(project.getId())).
                fetch().getValues(DOCUMENT_TAG.DOC_ID);
    }

    @Override
    public List<Tag> getTags(Project project, String documentId) {
        return DSL.using(connectionProvider, dialect).selectFrom(DOCUMENT_TAG).
                where(DOCUMENT_TAG.DOC_ID.eq(documentId)).and(DOCUMENT_TAG.PRJ_ID.eq(project.getId())).
                stream().map(this::createTagFrom).collect(toList());
    }

    @Override
    public boolean deleteAll(String projectId) {
       return DSL.using(connectionProvider, dialect).transactionResult(configuration -> {
           DSLContext inner = using(configuration);
           int deleteTagResult = inner.deleteFrom(DOCUMENT_TAG).where(DOCUMENT_TAG.PRJ_ID.eq(projectId)).execute();
           int deleteStarResult = inner.deleteFrom(DOCUMENT_USER_STAR).where(DOCUMENT_USER_STAR.PRJ_ID.eq(projectId)).execute();
           return deleteStarResult + deleteTagResult > 0;
       });
    }

    @Override
    public Project getProject(String projectId) {
        return createProjectFrom(DSL.using(connectionProvider, dialect).selectFrom(PROJECT).
                where(PROJECT.ID.eq(projectId)).fetchOne());
    }

    boolean save(Project project) {
        return DSL.using(connectionProvider, dialect).insertInto(PROJECT, PROJECT.ID, PROJECT.PATH, PROJECT.ALLOW_FROM_MASK).
                values(project.name, project.sourcePath.toString(), project.allowFromMask).execute() > 0;
    }

    // ---------------------------
    private Project createProjectFrom(ProjectRecord record) {
        return record == null ? null: new Project(record.getId(), Paths.get(record.getPath()), record.getAllowFromMask());
    }

    private NamedEntity createFrom(NamedEntityRecord record) {
        return NamedEntity.create(NamedEntity.Category.parse(record.getCategory()),
                record.getMention(), record.getNeOffset(),
                record.getDocId(), Pipeline.Type.fromCode(record.getExtractor()),
                Language.parse(record.getExtractorLanguage()));
    }

    private Document createDocumentFrom(Record result) {
        DocumentRecord documentRecord = result.into(DOCUMENT);
        Map<String, Object> metadata;
        try {
            metadata = MAPPER.readValue(documentRecord.getMetadata(), HashMap.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Set<Pipeline.Type> nerTags = Document.fromNerMask(documentRecord.getNerMask());
        return new Document(project(documentRecord.getProjectId()), documentRecord.getId(),
                Paths.get(documentRecord.getPath()), documentRecord.getContent(), parse(documentRecord.getLanguage()),
                forName(documentRecord.getCharset()), documentRecord.getContentType(), metadata, fromCode(documentRecord.getStatus()),
                nerTags, new Date(documentRecord.getExtractionDate().getTime()), documentRecord.getParentId(),
                documentRecord.getRootId(), documentRecord.getExtractionLevel(),
                documentRecord.getContentLength());
    }

    private Tag createTagFrom(DocumentTagRecord record) {
        return new Tag(record.getLabel(), new User(record.getUserId()), new Date(record.getCreationDate().getTime()));
    }
}
