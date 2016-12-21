package org.icij.datashare.text.indexing;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.task.DatashareTask;
import org.icij.datashare.concurrent.task.QueueInQueueOutTask;
import org.icij.datashare.concurrent.task.Task;
import org.icij.datashare.Entity;

import static java.util.Collections.singletonList;


/**
 * {@link DatashareTask} indexing {@link Entity}s, putting {@link }s on output queue
 *
 * Created by julien on 11/30/16.
 */
public class IndexingTask<I extends Entity> extends QueueInQueueOutTask<I, Task.Result> {

    private final Indexer indexer;

    private final String index;


    public IndexingTask(Indexer indexer,
                        String index,
                        BlockingQueue<I> input,
                        BlockingQueue<Result> output,
                        List<Latch> noMoreInput) {
        super(input, output, noMoreInput);
        if (indexer == null) {
            throw new IllegalArgumentException("indexer is undefined");
        }
        this.indexer = indexer;
        this.index   = index;
    }

    public IndexingTask(Indexer indexer,
                        String index,
                        BlockingQueue<I> input,
                        BlockingQueue<Result> output,
                        Latch noMoreInput) {
        this(indexer, index, input, output, singletonList(noMoreInput));
    }

    @Override
    protected Result execute(I element) {
        LOGGER.debug("Indexing " + element);
        try{
            boolean indexed = indexer.add(index, element);
            if ( ! indexed ) {
                LOGGER.error(indexer + " failed to index " + element);
                put( Result.FAILURE );
                return Result.FAILURE;
            }
            put( Result.SUCCESS );
            return Result.SUCCESS;

        } catch (Exception e ) {
            LOGGER.error( indexer.getType() + " failed to index " + element, e);
            put( Result.FAILURE );
            return Result.FAILURE;
        }
    }

}
