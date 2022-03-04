package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.Query;
import net.codestory.http.annotations.*;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.indexing.Indexer;

import java.io.IOException;
import java.net.URLEncoder;

import static java.lang.String.join;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static net.codestory.http.payload.Payload.created;
import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api/index")
public class IndexResource {
    private final Indexer indexer;

    @Inject
    public IndexResource(Indexer indexer) {
        this.indexer = indexer;
    }

    /**
     * Create the index for the current user if it doesn't exist.
     *
     * @return 201 (Created) or 200 if it already exists
     *
     * Example :
     * $(curl -i -XPUT localhost:8080/api/index/apigen-datashare)
     */
    @Put("/:index")
    public Payload createIndex(final String index) throws IOException {
        return indexer.createIndex(index) ? created() : ok();
    }

    /**
     * Preflight for index creation.
     *
     * @param index
     * @return 200 with PUT
     */
    @Options("/:index")
    public Payload createIndexPreflight(final String index) {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    /**
     * Head request useful for JS api (for example to test if an index exists)
     *
     * @param path
     * @return 200
     */
    @Head("/search/:path:")
    public Payload esHead(final String path) throws IOException {
        return new Payload(indexer.executeRaw("HEAD", path, null));
    }

    /**
      * The search endpoint is just a proxy in front of Elasticsearch, everything sent is forwarded to Elasticsearch. DELETE method is not allowed.
      *
      * Path can be of the form :
      * * _search/scroll
      * * index_name/_search
      * * index_name1,index_name2/_search
      * * index_name/_count
      * * index_name1,index_name2/_count
      * * index_name/doc/_search
      * * index_name1,index_name2/doc/_search
      *
      * @param path
      * @return 200 or http error from Elasticsearch
      *
      * Example :
     * $(curl -XPOST -H 'Content-Type: application/json' http://dsenv:8080/api/index/search/apigen-datashare/_search -d '{}')
      */
    @Post("/search/:path:")
    public Payload esPost(final String path, Context context, final net.codestory.http.Request request) throws IOException {
        return createPayload(indexer.executeRaw("POST", checkPath(path, context), new String(request.contentAsBytes())));
    }

    /**
     * Search GET request to Elasticsearch
     *
     * As it is a GET method, all paths are accepted.
     *
     * if a body is provided, the body will be sent to ES as source=urlencoded(body)&source_content_type=application%2Fjson
     * in that case, request parameters are not taken into account.
     *
     * @param path
     * @return 200 or http error from Elasticsearch
     *
     * Example :
     *  $(curl -H 'Content-Type: application/json' 'http://dsenv:8080/api/index/search/apigen-datashare/_search?q=type:NamedEntity&size=1')
     */
    @Get("/search/:path:")
    public Payload esGet(final String path, Context context) throws IOException {
        String url;
        byte[] getBody = context.request().contentAsBytes();
        if (getBody != null && getBody.length > 0) {
            // hack to remove when we will upgrade elasticsearch-py/ES to v7
            url = path + "?source_content_type=application%2Fjson&source=" + URLEncoder.encode(new String(getBody), "utf-8");
        } else {
            url = checkPath(path, context);
        }
        return createPayload(indexer.executeRaw("GET", url, ""));
    }

    /**
     * Prefligth option request
     *
     * @param path
     * @return 200
     */
    @Options("/search/:path:")
    public Payload esOptions(final String index, final String path, Context context) throws IOException {
        return ok().withAllowMethods(indexer.executeRaw("OPTIONS", path, null).split(","));
    }

    private String checkPath(String path, Context context) {
        String[] pathParts = path.split("/");
        if ("_search".equals(pathParts[0]) && "scroll".equals(pathParts[1])) {
            return getUrlString(context, path);
        }
        String[] indexes = pathParts[0].split(",");
        if (stream(indexes).allMatch(index -> ((DatashareUser)context.currentUser()).isGranted(index)) &&
                ("GET".equalsIgnoreCase(context.method()) ||
                        "_search".equals(pathParts[1]) ||
                        "_count".equals(pathParts[1]) ||
                        (pathParts.length >=3 && "_search".equals(pathParts[2])))) {
            return getUrlString(context, path);
        }
        throw new UnauthorizedException();
    }

    private String getUrlString(Context context, String s) {
        if (context.query().keyValues().size() > 0) {
            s += "?" + getQueryAsString(context.query());
        }
        return s;
    }

    static String getQueryAsString(final Query query) {
        return join("&", query.keyValues().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(toList()));
    }

    private Payload createPayload(String responseBody) {
        return new Payload("application/json", responseBody);
    }
}
