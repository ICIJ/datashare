package controllers.org.icij.datashare.web;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.*;

import javax.inject.*;

import org.icij.datashare.text.extraction.FileParser;
import org.icij.datashare.text.indexing.Indexer;
import play.mvc.*;
import scala.concurrent.ExecutionContextExecutor;

import org.icij.datashare.DataShare;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.NlpPipeline;
import org.icij.datashare.text.nlp.NlpStage;
import services.DataShareIndexer;

import static org.icij.datashare.util.function.ThrowingFunctions.splitComma;


/**
 * DataShare Processing Controller
 *
 * {@link DataShare#processDirectory(Path, FileParser.Type, boolean, List, List, List, int, boolean, Indexer, String)}
 * see datashare-web-play/conf/routes
 *
 */
@Singleton
public class ProcessController extends Controller {

    private final ExecutionContextExecutor exec;
    private final Indexer indexer;


    @Inject
    public ProcessController(DataShareIndexer dataShareIndexer, ExecutionContextExecutor exec) {
        this.exec = exec;
        this.indexer = dataShareIndexer.get();
    }


    public CompletionStage<Result> processDirectory(String inputDir,
                                                    String index,
                                                    String pipelines,
                                                    int parallelism,
                                                    String stages,
                                                    String entities) {
        return CompletableFuture.supplyAsync( () ->
                DataShare.processDirectory(
                        Paths.get(inputDir),
                        splitComma.andThen(NlpStage.parseAll).apply(stages),
                        splitComma.andThen(NamedEntity.Category.parseAll).apply(entities),
                        splitComma.andThen(NlpPipeline.Type.parseAll).apply(pipelines),
                        parallelism,
                        indexer,
                        index
                )
        ).thenApplyAsync(proc -> proc ? Results.ok() : Results.internalServerError(), exec);
    }

}
