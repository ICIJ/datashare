package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.Query;
import net.codestory.http.annotations.*;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.utils.IndexAccessVerifier;
import org.icij.datashare.utils.PayloadFormatter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        try{
            return indexer.createIndex(IndexAccessVerifier.checkIndices(index)) ? created() : ok();
        }catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Preflight for index creation.
     *
     * @param index
     * @return 200 with PUT
     */
    @Options("/:index")
    public Payload createIndexPreflight(final String index) {
        try{
            IndexAccessVerifier.checkIndices(index);
            return PayloadFormatter.allowMethods("OPTIONS", "PUT");
        }catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Head request useful for JS api (for example to test if an index exists)
     *
     * @param path
     * @return 200
     */
    @Head("/search/:path:")
    public Payload esHead(final String path) throws IOException {
        try {
            return new Payload(indexer.executeRaw("HEAD", path, null));
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
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
     *  @return 200 or http error from Elasticsearch
      *
      * Example :
     * $(curl -XPOST -H 'Content-Type: application/json' http://dsenv:8080/api/index/search/apigen-datashare/_search -d '{}')
      */
    @Post("/search/:path:")
    public Payload esPost(final String path, Context context, final net.codestory.http.Request request) throws IOException {
        try {
            return PayloadFormatter.json(indexer.executeRaw("POST", IndexAccessVerifier.checkPath(path, context), new String(request.contentAsBytes())));
        } catch ( IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
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
        try {
            return PayloadFormatter.json(indexer.executeRaw("GET", IndexAccessVerifier.checkPath(path, context), ""));
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Prefligth option request
     *
     * @param path
     * @return 200
     */
    @Options("/search/:path:")
    public Payload esOptions(final String index, final String path, Context context) throws IOException {
        try {
            IndexAccessVerifier.checkIndices(index);
            return PayloadFormatter.allowMethods(indexer.executeRaw("OPTIONS", path, null));
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }
}
