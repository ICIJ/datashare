package controllers.org.icij.datashare.web;

import java.util.concurrent.*;
import java.util.stream.Collectors;

import javax.inject.*;
import scala.concurrent.ExecutionContextExecutor;

import com.fasterxml.jackson.databind.JsonNode;
import play.libs.Json;
import play.mvc.*;

import services.DataShareIndexer;
import org.icij.datashare.text.indexing.Indexer;


/**
 * DataShare Indexing Controller
 *
 * {@link Indexer}
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

    public CompletionStage<Result> listIndices() {
        return CompletableFuture
                .supplyAsync( () -> Json.toJson( indexer.getIndices() ) )
                .thenApplyAsync(Results::ok, exec);
    }


    public CompletionStage<Result> searchAnyIndex(String query) {
        return CompletableFuture
                .supplyAsync( () -> indexer.search(query) )
                .thenApplyAsync(stream -> stream.collect(Collectors.toList()))
                .thenApplyAsync(Json::toJson)
                .thenApplyAsync(Results::ok, exec);
    }


    public CompletionStage<Result> search(String index, String type, String query) {
        return CompletableFuture
                .supplyAsync( () -> indexer.search(query, type, index) )
                .thenApplyAsync(stream -> stream.collect(Collectors.toList()))
                .thenApplyAsync(Json::toJson)
                .thenApplyAsync(Results::ok, exec);
    }

}
