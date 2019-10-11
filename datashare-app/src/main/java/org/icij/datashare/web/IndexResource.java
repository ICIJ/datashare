package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.Query;
import net.codestory.http.annotations.*;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import okhttp3.*;
import okio.BufferedSink;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.HashMapUser;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static java.lang.String.join;
import static java.util.stream.Collectors.toList;
import static net.codestory.http.payload.Payload.created;
import static net.codestory.http.payload.Payload.ok;

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

    /**
     * Create the index for the current user if it doesn't exist.
     *
     * @return 201 (Created) or 200 if it already exists
     *
     * Example : $(curl -i -XPUT localhost:8080/api/index/create)
     */
    @Put("/create")
    public Payload createIndex(Context context) throws IOException {
        return indexer.createIndex(((User)context.currentUser()).projectName()) ? created() : ok();
    }

    /**
      * The search endpoint is just a proxy in front of Elasticsearch, everything sent is forwarded to Elasticsearch. DELETE method is not allowed.
      *
      * Warn: Normally with elasticsearch, the search url is of the form : http://elasticsearch:9200/index/_search with search request in the body.
      * Datashare is appending the user to the index. So if you request http://dsenv:8080/api/search/datashare/_search then the url
      * requested in elasticsearch will be http://dsenv:8080/api/index/search/local-datashare/_search
      * because in local mode, the default user is called `local`
      *
      * The type requested is "doc" that is the default type of the datashare mapping.
      * @param index
      * @param path
      * @return 200 or http error from Elasticsearch
      *
      * Example : $(curl -XPOST -H 'Content-Type: application/json' http://dsenv:8080/api/index/search/datashare/_search -d '{}')
      */
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

    /**
     * Search GET request to Elasticsearch
     *
     * @param index
     * @param path
     * @return 200 or http error from Elasticsearch
     *
     * Example :  $(curl -H 'Content-Type: application/json' http://dsenv:8080/api/index/search/datashare/_search?q=type:NamedEntity)
     */
    @Get("/search/:index/:path:")
    public Payload esGet(final String index, final String path, Context context) throws IOException {
        return createPayload(http.newCall(new Request.Builder().url(getUrl(index, path, context)).get().build()).execute());
    }

    /**
     * Head request useful for JS api (for example to test if an index exists)
     *
     * @param index
     * @param path
     * @return 200
     */
    @Head("/search/:index/:path:")
    public Payload esHead(final String index, final String path, Context context) throws IOException {
        return createPayload(http.newCall(new Request.Builder().url(getUrl(index, path, context)).head().build()).execute());
    }

    /**
     * Prefligth option request
     *
     * @param index
     * @param path
     * @return 200
     */
    @Options("/search/:index/:path:")
    public Payload esOptions(final String index, final String path, Context context) throws IOException {
        return createPayload(http.newCall(new Request.Builder().url(getUrl(index, path, context)).method("OPTIONS", null).build()).execute());
    }

    @NotNull
    private String getUrl(String index, String path, Context context) {
        if (((HashMapUser)context.currentUser()).isGranted(index) || ("scroll".equals(path) && "_search".equals(index))) {
            String s = es_url + "/" + index + "/" + path;
            if (context.query().keyValues().size() > 0) {
                s += "?" + getQueryAsString(context.query());
            }
            return s;
        }
        throw new UnauthorizedException();
    }

    static String getQueryAsString(final Query query) {
        return join("&", query.keyValues().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(toList()));
    }

    @NotNull
    private Payload createPayload(Response esResponse) throws IOException {
        return new Payload(esResponse.header("Content-Type"), esResponse.body().string(), esResponse.code());
    }
}
