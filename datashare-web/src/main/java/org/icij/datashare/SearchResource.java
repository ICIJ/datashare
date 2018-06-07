package org.icij.datashare;

import com.google.inject.Inject;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

import java.io.IOException;

@Prefix("/search")
public class SearchResource {
    private final String es_url;
    private OkHttpClient http = new OkHttpClient();

    @Inject
    public SearchResource(PropertiesProvider propertiesProvider) {
        this.es_url = propertiesProvider.get("elasticsearchUrl").orElse("http://elasticsearch:9200");
    }

    @Get("/:path")
    public String esGet(final String path) throws IOException {
        return http.newCall(new Request.Builder().url(es_url + "/" + path).get().build()).execute().body().string();
    }

    @Post("/:path")
    public String esPost(final String path, final net.codestory.http.Request request) throws IOException {
        return http.newCall(new Request.Builder().url(es_url + "/" + path).post(new RequestBody() {
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
}
