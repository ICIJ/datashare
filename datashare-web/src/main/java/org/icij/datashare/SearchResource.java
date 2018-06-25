package org.icij.datashare;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.payload.Payload;
import okhttp3.*;
import okio.BufferedSink;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import static net.codestory.http.payload.Payload.created;
import static net.codestory.http.payload.Payload.forbidden;
import static net.codestory.http.payload.Payload.ok;

@Prefix("/search")
public class SearchResource {
    private final String es_url;
    private final Indexer indexer;
    private OkHttpClient http = new OkHttpClient();

    @Inject
    public SearchResource(PropertiesProvider propertiesProvider, Indexer indexer) {
        this.es_url = propertiesProvider.get("elasticsearchUrl").orElse("http://elasticsearch:9200");
        this.indexer = indexer;
    }

    @Get("/:path")
    public Payload esGet(final String path, Context context) throws IOException {
        return createPayload(http.newCall(new Request.Builder().url(getUrl(path, context)).get().build()).execute());
    }

    @Post("/:path")
    public Payload esPost(final String path, Context context, final net.codestory.http.Request request) throws IOException {
        return createPayload(http.newCall(new Request.Builder().url(getUrl(path, context)).post(new RequestBody() {
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

    @Put("/createIndex")
    public Payload createIndex(Context context) {
        if (context.currentUser() == null) {
            return forbidden();
        }
        return indexer.createIndex(((User)context.currentUser()).indexName()) ? created() : ok();
    }

    @Head("/:path")
    public Payload esHead(final String path, Context context) throws IOException {
        return createPayload(http.newCall(new Request.Builder().url(getUrl(path, context)).head().build()).execute());
    }

    @Options("/:path")
    public Payload esOptions(final String path, Context context) throws IOException {
        return createPayload(http.newCall(new Request.Builder().url(getUrl(path, context)).method("OPTIONS", null).build()).execute());
    }

    @NotNull
    private String getUrl(String path, Context context) {
        return context.currentUser() == null ? es_url + "/" + path : es_url + "/" + context.currentUser().login() + "-" + path;
    }

    @NotNull
    private Payload createPayload(Response esResponse) throws IOException {
        return new Payload(esResponse.header("Content-Type"), esResponse.body().string(), esResponse.code());
    }
}
