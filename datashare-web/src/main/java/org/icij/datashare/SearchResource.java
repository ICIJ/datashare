package org.icij.datashare;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.payload.Payload;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
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
    public String esGet(final String path, Context context) throws IOException {
        return http.newCall(new Request.Builder().url(getUrl(path, context)).get().build()).execute().body().string();
    }

    @Post("/:path")
    public String esPost(final String path, Context context, final net.codestory.http.Request request) throws IOException {
        return http.newCall(new Request.Builder().url(getUrl(path, context)).post(new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse(request.contentType());
            }
            @Override
            public void writeTo(BufferedSink bufferedSink) throws IOException {
                bufferedSink.write(request.contentAsBytes());
            }
        }).build()).execute().body().string();
    }

    @Put("/createIndex")
    public Payload createIndex(Context context) {
        if (context.currentUser() == null) {
            return forbidden();
        }
        return indexer.createIndex(((User)context.currentUser()).indexName()) ? created() : ok();
    }

    @Head("/:path")
    public String esHead(final String path, Context context) throws IOException {
        return http.newCall(new Request.Builder().url(getUrl(path, context)).head().build()).execute().body().string();
    }

    @Options("/:path")
    public String esOptions(final String path, Context context) throws IOException {
        return http.newCall(new Request.Builder().url(getUrl(path, context)).method("OPTIONS", null).build()).execute().body().string();
    }

    @NotNull
    private String getUrl(String path, Context context) {
        return context.currentUser() == null ? es_url + "/" + path : es_url + "/" + context.currentUser().login() + "-" + path;
    }
}
