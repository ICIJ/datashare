package controllers.org.icij.datashare.web;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.icij.datashare.json.JsonObjectMapper;
import scala.concurrent.ExecutionContextExecutor;

import play.libs.Json;
import play.mvc.*;

import services.DataShareIndexer;
import org.icij.datashare.text.indexing.Indexer;


/**
 * DataShare Indexing Controller
 *
 * @see Indexer
 * see datashare-web-play/conf/routes
 */
@Singleton
public class IndexController extends Controller {

    private final ExecutionContextExecutor exec;

    private final Indexer indexer;


    @Inject
    public IndexController(DataShareIndexer dataShareIndexer, ExecutionContextExecutor exec) {
        this.exec = exec;
        this.indexer = dataShareIndexer.get();
    }


    public CompletionStage<Result> listIndices() {
        return CompletableFuture
                .supplyAsync( indexer::getIndices )
                .thenApplyAsync( Json::toJson )
                .thenApplyAsync( Results::ok, exec);
    }

    public CompletionStage<Result> createIndex(String index) {
        return CompletableFuture
                .supplyAsync( () -> indexer.createIndex( index ) )
                .thenApplyAsync( ack -> ack ? Results.ok() : Results.internalServerError(), exec);
    }

    public CompletionStage<Result> deleteIndex(String index) {
        return CompletableFuture
                .supplyAsync( () -> indexer.deleteIndex( index ) )
                .thenApplyAsync( del -> del ? Results.ok() : Results.internalServerError(), exec);
    }

    public CompletionStage<Result> commitIndex(String index) {
        return CompletableFuture
                .supplyAsync( () -> indexer.commitIndices( index  ) )
                .thenApplyAsync( cmt -> cmt ? Results.ok() : Results.internalServerError(), exec);
    }


    @BodyParser.Of(BodyParser.Json.class)
    public CompletionStage<Result> addDocument(String index, String type, String id) {
        JsonNode source = request().body().asJson();
        Map<String, Object> sourceAsMap = JsonObjectMapper.MAPPER.convertValue(source, JsonObjectMapper.MAP_TYPEREF);
        return CompletableFuture
                .supplyAsync( () -> indexer.add(index, type, id, sourceAsMap))
                .thenApplyAsync( add -> add ? Results.ok() : Results.internalServerError(), exec);
    }

    public CompletionStage<Result> updateDocument(String index, String type, String id) {
        JsonNode source = request().body().asJson();
        Map<String, Object> sourceAsMap = JsonObjectMapper.MAPPER.convertValue(source, JsonObjectMapper.MAP_TYPEREF);
        return CompletableFuture
                .supplyAsync( () ->  indexer.update(index, type, id, sourceAsMap) )
                .thenApplyAsync( Json::toJson )
                .thenApplyAsync( Results::ok, exec);
    }

    public CompletionStage<Result> getDocument(String index, String type, String id) {
        return CompletableFuture
                .supplyAsync( () ->  indexer.read(index, type, id) )
                .thenApplyAsync( Json::toJson )
                .thenApplyAsync( Results::ok, exec);
    }

    public CompletionStage<Result> deleteDocument(String index, String type, String id) {
        return CompletableFuture
                .supplyAsync( () -> indexer.delete(index, type, id) )
                .thenApplyAsync( del -> del ? Results.ok() : Results.internalServerError(), exec);
    }


    public CompletionStage<Result> search(String query, Integer from, Integer size) {
        Supplier<Stream<Map<String, Object>>> search = (from == null || size == null) ?
                () -> indexer.search(query) : () -> indexer.search(query, from, size);
        return CompletableFuture
                .supplyAsync( search )
                .thenApplyAsync( stream -> stream.collect(Collectors.toList()) )
                .thenApplyAsync( Json::toJson )
                .thenApplyAsync( Results::ok, exec) ;
    }

    public CompletionStage<Result> searchIndexAndType(String index, String type, String query, Integer from, Integer size) {
        Supplier<Stream<Map<String, Object>>> search = (from == null || size == null) ?
                () -> indexer.search(query, type, index) : () -> indexer.search(query, from, size, type, index);
        return CompletableFuture
                .supplyAsync( search )
                .thenApplyAsync( stream -> stream.collect(Collectors.toList()) )
                .thenApplyAsync( Json::toJson )
                .thenApplyAsync( Results::ok, exec);
    }

}
