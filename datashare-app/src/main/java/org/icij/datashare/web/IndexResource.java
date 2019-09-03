package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.Query;
import net.codestory.http.annotations.*;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.io.InputStreams;
import net.codestory.http.payload.Payload;
import net.codestory.http.types.ContentTypes;
import okhttp3.*;
import okio.BufferedSink;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.HashMapUser;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.FileExtension;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import static java.lang.String.join;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static net.codestory.http.payload.Payload.created;
import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.text.Project.project;

@Prefix("/api/index")
public class IndexResource {
    private final String es_url;
    private final Indexer indexer;
    private OkHttpClient http = new OkHttpClient.Builder().
            readTimeout(60, TimeUnit.SECONDS).
            writeTimeout(60, TimeUnit.SECONDS).build();

    @Inject
    public IndexResource(PropertiesProvider propertiesProvider, Indexer indexer) {
        this.es_url = propertiesProvider.get("elasticsearchAddress").orElse("http://elasticsearch:9200");
        this.indexer = indexer;
    }

    @Put("/create")
    public Payload createIndex(Context context) throws IOException {
        return indexer.createIndex(((User)context.currentUser()).projectName()) ? created() : ok();
    }

    @Get("/search/:index/:path:")
    public Payload esGet(final String index, final String path, Context context) throws IOException {
        return createPayload(http.newCall(new Request.Builder().url(getUrl(index, path, context)).get().build()).execute());
    }

    @Post("/search/:index/:path:")
    public Payload esPost(final String index, final String path, Context context, final net.codestory.http.Request request) throws IOException {
        return createPayload(http.newCall(new Request.Builder().url(getUrl(index, path, context)).post(new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse(request.contentType());
            }
            @Override
            public void writeTo(BufferedSink bufferedSink) throws IOException {
                bufferedSink.write(request.contentAsBytes());
            }
        }).build()).execute());
    }

    @Head("/search/:index/:path:")
    public Payload esHead(final String index, final String path, Context context) throws IOException {
        return createPayload(http.newCall(new Request.Builder().url(getUrl(index, path, context)).head().build()).execute());
    }

    @Options("/search/:index/:path:")
    public Payload esOptions(final String index, final String path, Context context) throws IOException {
        return createPayload(http.newCall(new Request.Builder().url(getUrl(index, path, context)).method("OPTIONS", null).build()).execute());
    }

    @Get("/src/:index/:id?routing=:routing")
    public Payload getSourceFile(final String index, final String id,
                                 final String routing, final Context context) throws IOException {
        boolean inline = context.request().query().getBoolean("inline");
        if (isGranted((HashMapUser)context.currentUser(), index)) {
            return routing == null ? getPayload(indexer.get(index, id), index, inline) : getPayload(indexer.get(index, id, routing),index, inline);
        }
        throw new ForbiddenException();
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

    @NotNull
    private String getUrl(String index, String path, Context context) {
        if (isGranted((HashMapUser)context.currentUser(), index) || ("scroll".equals(path) && "_search".equals(index))) {
            String s = es_url + "/" + index + "/" + path;
            if (context.query().keyValues().size() > 0) {
                s += "?" + getQueryAsString(context.query());
            }
            return s;
        }
        throw new UnauthorizedException();
    }

    private boolean isGranted(HashMapUser user, String index) {
        return user.getIndices().contains(index) || user.projectName().equals(index);
    }

    static String getQueryAsString(final Query query) {
        return join("&", query.keyValues().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(toList()));
    }

    @NotNull
    private Payload createPayload(Response esResponse) throws IOException {
        return new Payload(esResponse.header("Content-Type"), esResponse.body().string(), esResponse.code());
    }
}
