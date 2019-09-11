package org.icij.datashare.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Repository;
import org.icij.datashare.session.HashMapUser;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.indexing.Indexer;

import java.io.IOException;
import java.util.List;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.text.Project.project;

@Prefix("/api/document")
public class DocumentResource {
    private final Repository repository;
    private final Indexer indexer;

    @Inject
    public DocumentResource(Repository repository, Indexer indexer) {
        this.repository = repository;
        this.indexer = indexer;
    }

    @Post("/project/:project/group/star")
    public int groupStarProject(final String projectId, final List<String> docIds, Context context) {
        return repository.star(project(projectId), (HashMapUser)context.currentUser(), docIds);
    }

    @Post("/project/:project/group/unstar")
    public int groupUnstarProject(final String projectId, final List<String> docIds, Context context) {
        return repository.unstar(project(projectId), (HashMapUser)context.currentUser(), docIds);
    }

    @Get("/project/starred/:project")
    public List<String> getProjectStarredDocuments(final String projectId, Context context) {
        return repository.getStarredDocuments(project(projectId), (HashMapUser)context.currentUser());
    }

    @Get("/project/tagged/:project/:coma_separated_tags")
    public List<String> getProjectTaggedDocuments(final String projectId, final String comaSeparatedTags) {
        return repository.getDocuments(project(projectId),
                stream(comaSeparatedTags.split(",")).map(Tag::tag).toArray(Tag[]::new));
    }

    @Options("/project/tag/:project/:docId")
    public Payload tagDocument(final String projectId, final String docId) {return ok().withAllowMethods("OPTIONS", "PUT");}

    @Put("/project/tag/:project/:docId?routing=:routing")
    public Payload tagDocument(final String projectId, final String docId, String routing, Tag[] tags) throws IOException {
        boolean tagSaved = repository.tag(project(projectId), docId, tags);
        indexer.tag(project(projectId), docId, ofNullable(routing).orElse(docId), tags);
        return tagSaved ? Payload.created(): Payload.ok();
    }

    @Post("/project/:project/group/tag")
    public Payload groupTagDocument(final String projectId, BatchTagQuery query) throws IOException {
        repository.tag(project(projectId), query.docIds, query.tagsAsArray());
        indexer.tag(project(projectId), query.docIds, query.tagsAsArray());
        return Payload.ok();
    }

    @Post("/project/:project/group/untag")
    public Payload groupUntagDocument(final String projectId, BatchTagQuery query) throws IOException {
        repository.untag(project(projectId), query.docIds, query.tagsAsArray());
        indexer.untag(project(projectId), query.docIds, query.tagsAsArray());
        return Payload.ok();
    }

    @Options("/project/untag/:project/:docId")
    public Payload untagDocument(final String projectId, final String docId) {return ok().withAllowMethods("OPTIONS", "PUT");}

    @Put("/project/untag/:project/:docId?routing=:routing")
    public Payload untagDocument(final String projectId, final String docId, String routing, Tag[] tags) throws IOException {
        boolean untagSaved = repository.untag(project(projectId), docId, tags);
        indexer.untag(project(projectId), docId, ofNullable(routing).orElse(docId), tags);
        return untagSaved ? Payload.created(): Payload.ok();
    }

    @Get("/starred")
    public List<Document> getStarredDocuments(Context context) {
        return repository.getStarredDocuments((HashMapUser)context.currentUser());
    }

    @Options("/star/:docId")
    public Payload starDocument(final String docId) {return ok().withAllowMethods("OPTIONS", "PUT");}

    @Put("/star/:docId")
    public Payload starDocument(final String docId, Context context) {
        return repository.star((HashMapUser)context.currentUser(), docId) ? Payload.created(): Payload.ok();
    }

    @Options("/unstar/:docId")
    public Payload unstarDocument(final String docId) {return ok().withAllowMethods("OPTIONS", "PUT");}

    @Put("/unstar/:docId")
    public Payload unstarDocument(final String docId, Context context) {
        return repository.unstar((HashMapUser)context.currentUser(), docId) ? Payload.created(): Payload.ok();
    }

    private static class BatchTagQuery {
        final List<String> tags;
        final List<String> docIds;

        @JsonCreator
        private BatchTagQuery(@JsonProperty("tags") List<String> tags, @JsonProperty("docIds") List<String> docIds) {
            this.tags = tags;
            this.docIds = docIds;
        }

        Tag[] tagsAsArray() {
            return tags.stream().map(Tag::new).toArray(Tag[]::new);
        }
    }
}
