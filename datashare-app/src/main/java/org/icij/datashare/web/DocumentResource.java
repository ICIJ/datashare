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
import org.icij.datashare.text.Project;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
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

    @Get("/src/:project/:id?routing=:routing")
    public Payload getSourceFile(final String index, final String id,
                                 final String routing, final Context context) throws IOException {
        boolean inline = context.request().query().getBoolean("inline");
        if (isGranted((HashMapUser)context.currentUser(), index, context.request().clientAddress())) {
            return routing == null ? getPayload(indexer.get(index, id), index, inline) : getPayload(indexer.get(index, id, routing),index, inline);
        }
        throw new ForbiddenException();
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

    @Get("/project/:project/tag/:docId")
    public List<Tag> getDocumentTags(final String projectId, final String docId) {
        return repository.getTags(project(projectId), docId);
    }

    @Post("/project/:project/group/tag")
    public Payload groupTagDocument(final String projectId, BatchTagQuery query, Context context) throws IOException {
        repository.tag(project(projectId), query.docIds, query.tagsAsArray((User)context.currentUser()));
        indexer.tag(project(projectId), query.docIds, query.tagsAsArray((User)context.currentUser()));
        return Payload.ok();
    }

    @Post("/project/:project/group/untag")
    public Payload groupUntagDocument(final String projectId, BatchTagQuery query,  Context context) throws IOException {
        repository.untag(project(projectId), query.docIds, query.tagsAsArray((User)context.currentUser()));
        indexer.untag(project(projectId), query.docIds, query.tagsAsArray((User)context.currentUser()));
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

    private boolean isGranted(HashMapUser user, String projectId, InetSocketAddress inetSocketAddress) {
        Project project = repository.getProject(projectId);
        return (project == null || project.matches(inetSocketAddress.getAddress().getHostAddress())) &&
                (user.getIndices().contains(projectId) || user.projectName().equals(projectId));
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
