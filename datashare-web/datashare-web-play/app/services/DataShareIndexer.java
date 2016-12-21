package services;

import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import javax.inject.*;

import org.icij.datashare.DataShare;
import play.Logger;
import play.inject.ApplicationLifecycle;
import play.Configuration;

import org.icij.datashare.text.indexing.Indexer;
import static org.icij.datashare.util.function.ThrowingFunctions.*;


/**
 * Created by julien on 12/13/16.
 */
@Singleton
public class DataShareIndexer {

    private final Configuration        configuration;
    private final ApplicationLifecycle appLifecycle;
    private final Indexer indexer;

    @Inject
    DataShareIndexer(Configuration configuration, ApplicationLifecycle appLifecycle) {
        this.appLifecycle = appLifecycle;
        this.configuration = configuration;

        String type      = configuration.getString("datashare.indexer.type");
        String nodeType  = configuration.getString("datashare.indexer.node.type");
        String hosts     = configuration.getString("datashare.indexer.hosts");
        String ports     = configuration.getString("datashare.indexer.ports");

        Indexer.Type indexerType = Indexer.Type.parse(type).orElse(DataShare.DEFAULT_INDEXER_TYPE);
        Properties indexerProperties = Indexer.Property.build
                .apply(Indexer.NodeType.parse(nodeType).orElse(DataShare.DEFAULT_INDEXER_NODE_TYPE))
                .apply(removeSpaces.andThen(splitComma).apply(hosts))
                .apply(removeSpaces.andThen(splitComma).andThen(parseInts).apply(ports));

        Logger.info("Opening DataShare " + indexerType + "Indexer\n" + indexerProperties);
        Optional<Indexer> indexerOpt = Indexer.create(
                indexerType,
                indexerProperties);
        if (! indexerOpt.isPresent()) {
            throw new RuntimeException("Failed to create " + Indexer.Type.parse(type) + " indexer");
        }

        indexer = indexerOpt.get();

        this.appLifecycle.addStopHook( () -> {
            Logger.info("Closing DataShare Indexer");
            indexer.close();
            return CompletableFuture.completedFuture(null);
        });
    }

    public Indexer get() {
        return indexer;
    }

}
