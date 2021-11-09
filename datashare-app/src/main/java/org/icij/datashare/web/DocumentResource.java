package org.icij.datashare.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.io.InputStreams;
import net.codestory.http.payload.Payload;
import net.codestory.http.types.ContentTypes;
import org.icij.datashare.Repository;
import org.icij.datashare.Repository.AggregateList;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.FileExtension;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.Boolean.parseBoolean;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.text.Project.isAllowed;
import static org.icij.datashare.text.Project.project;

@Singleton
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
    @Get("/:project/documents/src/:id?routing=:routing&filter_metadata=:filter_metadata")
    public Payload getSourceFile(final String project, final String id,
                                 final String routing, final String filterMetadata, final Context context) throws IOException {
        boolean inline = context.request().query().getBoolean("inline");
        if (((DatashareUser)context.currentUser()).isGranted(project) &&
                isAllowed(repository.getProject(project), context.request().clientAddress())) {
            return routing == null ? getPayload(indexer.get(project, id), project, inline, parseBoolean(filterMetadata)) : getPayload(indexer.get(project, id, routing),project, inline, parseBoolean(filterMetadata));
        }
        throw new ForbiddenException();
    }

    /**
     * Group star the documents. The id list is passed in the request body as a json list.
     *
     * It answers 200 if the change has been done and the number of documents updated in the response body.
     * @param projectId
     * @param docIds as json
     * @return 200 and the number of documents updated
     *
     * Example :
     * $(curl -i -XPOST -H "Content-Type: application/json" localhost:8080/api/apigen-datashare/documents/batchUpdate/star -d '["bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f"]')
     */
    @Post("/:project/documents/batchUpdate/star")
    public Result<Integer> groupStarProject(final String projectId, final List<String> docIds, Context context) {
        Result<Integer> res = new Result(repository.star(project(projectId), (DatashareUser)context.currentUser(), docIds));
        return new Result<>(repository.star(project(projectId), (DatashareUser)context.currentUser(), docIds));
    }

    /**
     * Group unstar the documents. The id list is passed in the request body as a json list.
     *
     * It answers 200 if the change has been done and the number of documents updated in the response body.
     *
     * @param projectId
     * @param docIds as json in body
     * @return 200 and the number of documents unstarred
     *
     * Example :
     * $(curl -i -XPOST -H "Content-Type: application/json" localhost:8080/api/apigen-datashare/documents/batchUpdate/unstar -d '["bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f", "unknownId"]')
     */
    @Post("/:project/documents/batchUpdate/unstar")
    public Result<Integer> groupUnstarProject(final String projectId, final List<String> docIds, Context context) {
        return new Result<>(repository.unstar(project(projectId), (DatashareUser)context.currentUser(), docIds));
    }

    /**
     * Retrieves the list of starred document for a given project.
     *
     * @param projectId
     * @return 200
     *
     * Example :
     * $(curl -i localhost:8080/api/apigen-datashare/documents/starred)
     */
    @Get("/:project/documents/starred")
    public List<String> getProjectStarredDocuments(final String projectId, Context context) {
        return repository.getStarredDocuments(project(projectId), (DatashareUser)context.currentUser());
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
    @Options("/:project/documents/tags/:docId")
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
     * $(curl -XPUT -H "Content-Type: application/json" localhost:8080/api/apigen-datashare/documents/tags/bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f -d '["tag1","tag2"]')
     */
    @Put("/:project/documents/tags/:docId?routing=:routing")
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
     * $(curl  http://localhost:8080/api/apigen-datashare/documents/tags/bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f)
     */
    @Get("/:project/documents/tags/:docId")
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
     * $(curl -i -XPOST  -H "Content-Type: application/json"  localhost:8080/api/apigen-datashare/documents/batchUpdate/tag -d '{"docIds": ["bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f", "7473df320bee9919abe3dc179d7d2861e1ba83ee7fe42c9acee588d886fe9aef0627df6ae26b72f075120c2c9d1c9b61"], "tags": ["foo", "bar"]}')
     */
    @Post("/:project/documents/batchUpdate/tag")
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
     * $(curl -i -XPOST  -H "Content-Type: application/json"  localhost:8080/api/documents/apigen-datashare/batchUpdate/untag -d '{"docIds": ["bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f", "7473df320bee9919abe3dc179d7d2861e1ba83ee7fe42c9acee588d886fe9aef0627df6ae26b72f075120c2c9d1c9b61"], "tags": ["foo", "bar"]}')
     */
    @Post("/:project/documents/batchUpdate/untag")
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
    @Options("/:project/documents/untag/:docId")
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
     * $(curl -i -XPUT -H "Content-Type: application/json" localhost:8080/api/apigen-datashare/documents/untag/bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f -d '["tag1"]')
     */
    @Put("/:project/documents/untag/:docId?routing=:routing")
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
     * $(curl localhost:8080/api/documents/starred)
     */
    @Get("/documents/starred")
    public List<Document> getStarredDocuments(Context context) {
        return repository.getStarredDocuments((DatashareUser)context.currentUser());
    }

    /**
     * Retrieves the list of users who recommended a document with the total count of recommended documents
     * for the given project id
     *
     *
     * @param projectId
     * @return 200
     *
     * Example :
     * $(curl -i localhost:8080/api/users/recommendations?project=apigen-datashare)
     */
    @Get("/users/recommendations?project=:project")
    public AggregateList<User> getProjectRecommendations(final String projectId) {
        return repository.getRecommendations(project(projectId));
    }

    /**
     * Get all users who recommended a document with the count of all recommended documents
     * for project and documents ids.
     *
     * @param projectId
     * @param comaSeparatedDocIds
     * @return 200 and the list of tags
     *
     * Example :
     * $(curl  http://localhost:8080/api/users/recommendations?project=apigen-datashare&docIds=bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f)
     */
    @Get("/users/recommendationsby?project=:project&docIds=:coma_separated_docIds")
    public AggregateList<User> getProjectRecommendations(final String projectId, final String comaSeparatedDocIds) {
        return repository.getRecommendations(project(projectId),stream(comaSeparatedDocIds.split(",")).map(String::new).collect(Collectors.toList()));
    }

    /**
     * Retrieves the set of marked read documents for the given project id and a list of users
     * provided in the url.
     *
     * This service doesn't need to have the document stored in the database (no join is made)
     *
     * @param projectId
     * @param comaSeparatedUsers
     * @return 200
     *
     * Example :
     * $(curl -i localhost:8080/api/apigen-datashare/documents/recommendations?userids=apigen)
     */
    @Get("/:project/documents/recommendations?userids=:coma_separated_users")
    public Set<String> getProjectRecommentationsBy(final String projectId, final String comaSeparatedUsers) {
        return repository.getRecommentationsBy(project(projectId), stream(comaSeparatedUsers.split(",")).map(User::new).collect(Collectors.toList()));
    }


    /**
     * Group mark the documents "read". The id list is passed in the request body as a json list.
     *
     * It answers 200 if the change has been done and the number of documents updated in the response body.
     * @param projectId
     * @param docIds as json
     * @return 200 and the number of documents marked
     *
     * Example :
     * $(curl -i -XPOST -H "Content-Type: application/json" localhost:8080/api/apigen-datashare/documents/batchUpdate/recommend -d '["7473df320bee9919abe3dc179d7d2861e1ba83ee7fe42c9acee588d886fe9aef0627df6ae26b72f075120c2c9d1c9b61"]')
     */
    @Post("/:project/documents/batchUpdate/recommend")
    public Result<Integer> groupRecommend(final String projectId, final List<String> docIds, Context context) {
        return new Result<>(repository.recommend(project(projectId), (DatashareUser)context.currentUser(), docIds));
    }

    /**
     * Group unmark the documents. The id list is passed in the request body as a json list.
     *
     * It answers 200 if the change has been done and the number of documents updated in the response body.
     *
     * @param projectId
     * @param docIds as json
     * @return 200 and the number of documents unmarked
     *
     * Example :
     * $(curl -i -XPOST -H "Content-Type: application/json" localhost:8080/api/apigen-datashare/documents/batchUpdate/unrecommend -d '["bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f"]')
     */
    @Post("/:project/documents/batchUpdate/unrecommend")
    public Result<Integer> groupUnrecommend(final String projectId, final List<String> docIds, Context context) {
        return new Result<>(repository.unrecommend(project(projectId), (DatashareUser)context.currentUser(), docIds));
    }

    private Payload getPayload(Document doc, String index, boolean inline, boolean filterMetadata) throws IOException {
        try (InputStream from = new SourceExtractor(filterMetadata).getSource(project(index), doc)) {
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
