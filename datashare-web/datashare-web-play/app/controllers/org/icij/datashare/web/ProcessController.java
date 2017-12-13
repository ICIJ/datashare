package controllers.org.icij.datashare.web;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.*;

import javax.inject.*;

import play.mvc.*;
import scala.concurrent.ExecutionContextExecutor;

import services.DataShareIndexer;
import org.icij.datashare.DataShare;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.indexing.Indexer;
import static org.icij.datashare.function.ThrowingFunctions.splitComma;


/**
 * DataShare Processing Controller
 *
 * @see DataShare.StandAlone#processDirectory(Path, int, boolean, List, List, List, int, Indexer, String)
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
                                                    int fileParserParallelism,
                                                    String index,
                                                    String nlpPipelines,
                                                    int nlpParallelism,
                                                    String nlpStages,
                                                    String entities) {
        return CompletableFuture.supplyAsync( () ->
                DataShare.StandAlone.processDirectory(
                        Paths.get(inputDir),
                        fileParserParallelism,
                        splitComma.andThen(NlpStage.parseAll).apply(nlpStages),
                        splitComma.andThen(NamedEntity.Category.parseAll).apply(entities),
                        splitComma.andThen(Pipeline.Type.parseAll).apply(nlpPipelines),
                        nlpParallelism,
                        indexer,
                        index
                )
        ).thenApplyAsync( proc -> proc ? Results.ok() : Results.internalServerError(), exec);
    }

}
