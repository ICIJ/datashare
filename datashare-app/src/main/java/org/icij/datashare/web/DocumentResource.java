package org.icij.datashare.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.io.InputStreams;
import net.codestory.http.payload.Payload;
import net.codestory.http.types.ContentTypes;
import org.icij.datashare.Repository;
import org.icij.datashare.session.HashMapUser;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.FileExtension;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.text.Project.isAllowed;
import static org.icij.datashare.text.Project.project;

@Prefix("/api")
public class DocumentResource {
    private final Repository repository;
    private final Indexer indexer;

    @Inject
    public DocumentResource(Repository repository, Indexer indexer) {
        this.repository = repository;
        this.indexer = indexer;
    }

    /**
     * Returns the file from the index with the index id and the root document (if embedded document).
     *
     * The routing can be omitted if it is a top level document, or it can be the same as the id.
     *
     * Returns 404 if it doesn't exist
     *
     * Returns 403 if the user has no access to the requested index.
     *
     * @param project
     * @param id
     * @param routing
     * @return 200 or 404 or 403 (Forbidden)
     *
     * Example :
     *
     * $(curl -i http://localhost:8080/api/apigen-datashare/documents/src/bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f
     */
    @Get("/:project/documents/src/:id?routing=:routing")
    public Payload getSourceFile(final String project, final String id,
                                 final String routing, final Context context) throws IOException {
        boolean inline = context.request().query().getBoolean("inline");
        if (((HashMapUser)context.currentUser()).isGranted(project) &&
                isAllowed(repository.getProject(project), context.request().clientAddress())) {
            return routing == null ? getPayload(indexer.get(project, id), project, inline) : getPayload(indexer.get(project, id, routing),project, inline);
        }
        throw new ForbiddenException();
    }

    /**
     * Group star the documents. The id list is passed in the request body as a json list.
     *
     * It answers 200 if the change as been done and the number of documents updated in the response body.
     * @param projectId
     * @param docIds as json
     * @return 200 and the number of documents updated
     *
     * Example :
     * $(curl -i -XPOST -H "Content-Type: application/json" localhost:8080/api/apigen-datashare/documents/batchUpdate/star -d '["bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f"]')
     */
    @Post("/:project/documents/batchUpdate/star")
    public int groupStarProject(final String projectId, final List<String> docIds, Context context) {
        return repository.star(project(projectId), (HashMapUser)context.currentUser(), docIds);
    }

    /**
     * Group unstar the documents. The id list is passed in the request body as a json list.
     *
     * It answers 200 if the change as been done and the number of documents updated in the response body.
     *
     * @param projectId
     * @param docIds as json in body
     * @return 200 and the number of documents unstarred
     *
     * Example :
     * $(curl -i -XPOST -H "Content-Type: application/json" localhost:8080/api/apigen-datashare/documents/batchUpdate/unstar -d '["curl -i -XPOST -H "Content-Type: application/json" localhost:8080/api/document/project/apigen-datashare/group/star -d '["bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f"", "unknownId"]')
     */
    @Post("/:project/documents/batchUpdate/unstar")
    public int groupUnstarProject(final String projectId, final List<String> docIds, Context context) {
        return repository.unstar(project(projectId), (HashMapUser)context.currentUser(), docIds);
    }

    /**
     * Retrieves the list of starred document for all projects.
     *
     * This service needs to have the document stored in the database.
     *
     * @param projectId
     * @return 200
     *
     * Example :
     * $(curl -i localhost:8080/api/apigen-datashare/documents/starred/)
     */
    @Get("/:project/documents/starred")
    public List<String> getProjectStarredDocuments(final String projectId, Context context) {
        return repository.getStarredDocuments(project(projectId), (HashMapUser)context.currentUser());
    }

    /**
     * Retrieves the list of tagged document with tag "tag" for the given project id.
     *
     * This service doesn't need to have the document stored in the database (no join is made)
     *
     * @param projectId
     * @param comaSeparatedTags
     * @return 200
     *
     * Example :
     * $(curl -i localhost:8080/api/apigen-datashare/documents/tagged/tag_01,tag_02)
     */
    @Get("/:projects/documents/tagged/:coma_separated_tags")
    public List<String> getProjectTaggedDocuments(final String projectId, final String comaSeparatedTags) {
        return repository.getDocuments(project(projectId),
                stream(comaSeparatedTags.split(",")).map(Tag::tag).toArray(Tag[]::new));
    }

    /**
     * preflight request
     *
     * @param projectId
     * @param docId
     * @return 200 PUT
     */
    @Options("/document/project/tag/:project/:docId")
    public Payload tagDocument(final String projectId, final String docId) {return ok().withAllowMethods("OPTIONS", "PUT");}

    /**
     *
     * @param projectId
     * @param docId
     * @param routing
     * @param tags
     * @return 201 if created else 200
     *
     * Example :
     * $(curl localhost:8080/api/document/project/tag/apigen-datashare/bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f -d '[\"tag1\",\"tag2\"]'
     */
    @Put("/document/project/tag/:project/:docId?routing=:routing")
    public Payload tagDocument(final String projectId, final String docId, String routing, Tag[] tags) throws IOException {
        boolean tagSaved = repository.tag(project(projectId), docId, tags);
        indexer.tag(project(projectId), docId, ofNullable(routing).orElse(docId), tags);
        return tagSaved ? Payload.created(): Payload.ok();
    }

    /**
     * Gets all the tags from a document with the user and timestamp.
     * @param projectId
     * @param docId
     * @return 200 and the list of tags
     *
     * Example :
     * $(curl  http://localhost:8080/api/document/project/apigen-datashare/tag/bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f)
     */
    @Get("/document/project/:project/tag/:docId")
    public List<Tag> getDocumentTags(final String projectId, final String docId) {
        return repository.getTags(project(projectId), docId);
    }

    /**
     * Group tag the documents. The document id list and the tag list are passed in the request body.
     *
     * It answers 200 if the change has been done.
     *
     * @param projectId
     * @param query
     * @return 200
     *
     * Example :
     * $(curl -i -XPOST localhost:8080/api/document/project/apigen-datashare/group/tag -d '{"docIds": ["bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f", "7473df320bee9919abe3dc179d7d2861e1ba83ee7fe42c9acee588d886fe9aef0627df6ae26b72f075120c2c9d1c9b61"], "tags": ["foo", "bar"]}')
     */
    @Post("/document/project/:project/group/tag")
    public Payload groupTagDocument(final String projectId, BatchTagQuery query, Context context) throws IOException {
        repository.tag(project(projectId), query.docIds, query.tagsAsArray((User)context.currentUser()));
        indexer.tag(project(projectId), query.docIds, query.tagsAsArray((User)context.currentUser()));
        return Payload.ok();
    }

    /**
     * Group untag the documents. The document id list and the tag list are passed in the request body.
     *
     * It answers 200 if the change has been done.
     *
     * @param projectId
     * @param query
     * @return 200
     *
     * Example :
     * $(curl -i -XPOST localhost:8080/api/document/project/apigen-datashare/group/untag -d '{"docIds": ["bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f", "7473df320bee9919abe3dc179d7d2861e1ba83ee7fe42c9acee588d886fe9aef0627df6ae26b72f075120c2c9d1c9b61"], "tags": ["foo", "bar"]}')
     */
    @Post("/document/project/:project/group/untag")
    public Payload groupUntagDocument(final String projectId, BatchTagQuery query,  Context context) throws IOException {
        repository.untag(project(projectId), query.docIds, query.tagsAsArray((User)context.currentUser()));
        indexer.untag(project(projectId), query.docIds, query.tagsAsArray((User)context.currentUser()));
        return Payload.ok();
    }

    /**
     * preflight request
     *
     * @param projectId
     * @param docId
     * @return 200 PUT
     */
    @Options("/document/project/untag/:project/:docId")
    public Payload untagDocument(final String projectId, final String docId) {return ok().withAllowMethods("OPTIONS", "PUT");}

    /**
     * Untag one document
     *
     * @param projectId
     * @param docId
     * @param routing
     * @param tags
     * @return 201 if untagged else 200
     *
     * $(curl -i -XPUT localhost:8080/api/document/project/untag/apigen-datashare/bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f)
     */
    @Put("/document/project/untag/:project/:docId?routing=:routing")
    public Payload untagDocument(final String projectId, final String docId, String routing, Tag[] tags) throws IOException {
        boolean untagSaved = repository.untag(project(projectId), docId, tags);
        indexer.untag(project(projectId), docId, ofNullable(routing).orElse(docId), tags);
        return untagSaved ? Payload.created(): Payload.ok();
    }

    /**
     * Retrieves the list of starred document for all projects.
     *
     * This service needs to have the document stored in the database.
     *
     * @return 200 and the list of Documents
     *
     * $(curl localhost:8080/api/document/starred)
     */
    @Get("/document/starred")
    public List<Document> getStarredDocuments(Context context) {
        return repository.getStarredDocuments((HashMapUser)context.currentUser());
    }

    /**
     * preflight request
     * @param docId
     * @return 200 PUT
     */
    @Options("/document/star/:docId")
    public Payload starDocument(final String docId) {return ok().withAllowMethods("OPTIONS", "PUT");}

    /**
     * Star the document with id docId
     * @param docId
     * @return 201 if the document has been starred else 200
     *
     * $(curl localhost:8080/api/document/star/bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f)
     */
    @Put("/document/star/:docId")
    public Payload starDocument(final String docId, Context context) {
        return repository.star((HashMapUser)context.currentUser(), docId) ? Payload.created(): Payload.ok();
    }

    /**
     * preflight request
     * @param docId
     * @return 200 PUT
     */
    @Options("/document/unstar/:docId")
    public Payload unstarDocument(final String docId) {return ok().withAllowMethods("OPTIONS", "PUT");}

    /**
     * Star the document with id docId
     * @param docId
     * @return 201 if the document has been starred else 200
     * $(curl localhost:8080/api/document/unstar/bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f)
     */
    @Put("/document/unstar/:docId")
    public Payload unstarDocument(final String docId, Context context) {
        return repository.unstar((HashMapUser)context.currentUser(), docId) ? Payload.created(): Payload.ok();
    }

    @NotNull
    private Payload getPayload(Document doc, String index, boolean inline) throws IOException {
        try (InputStream from = new SourceExtractor().getSource(project(index), doc)) {
            String contentType = ofNullable(doc.getContentType()).orElse(ContentTypes.get(doc.getPath().toFile().getName()));
            Payload payload = new Payload(contentType, InputStreams.readBytes(from));
            String fileName = doc.isRootDocument() ? doc.getName(): doc.getId().substring(0, 10) + "." + FileExtension.get(contentType);
            return inline ? payload: payload.withHeader("Content-Disposition", "attachment;filename=\"" + fileName + "\"");
        } catch (FileNotFoundException fnf) {
            return Payload.notFound();
        }
    }
    private static class BatchTagQuery {
        final List<String> tags;
        final List<String> docIds;

        @JsonCreator
        private BatchTagQuery(@JsonProperty("tags") List<String> tags, @JsonProperty("docIds") List<String> docIds) {
            this.tags = tags;
            this.docIds = docIds;
        }

        Tag[] tagsAsArray(User user) {
            return tags.stream().map(label -> new Tag(label, user)).toArray(Tag[]::new);
        }
    }
}
