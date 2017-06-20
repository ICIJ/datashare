package org.icij.datashare.text.indexing;

import java.util.List;
import static java.util.Collections.singletonList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.queue.OutputQueue;
import org.icij.datashare.concurrent.queue.QueueForwarding;
import org.icij.datashare.concurrent.task.DatashareTask;
import org.icij.datashare.Entity;
import org.icij.datashare.concurrent.task.QueuesInTask;


/**
 * {@link DatashareTask} indexing {@link Entity}ies
 *
 * Created by julien on 11/30/16.
 */
public class Indexing<I extends Entity> extends QueuesInTask<I> {

    public static <E  extends Entity> Indexing<E> create(Indexer indexer, String index, QueueForwarding<E> source) {
        Indexing<E> indexing = new Indexing<>(indexer, index, source.noMoreOutput());
        source.addOutput(indexing.inputs());
        return indexing;
    }

    public static <E  extends Entity, S extends OutputQueue<E>> Indexing<E> create(Indexer indexer, String index, List<S> sources) {
        List<BlockingQueue<E>> sourcesOutputs = sources.stream().map(OutputQueue::output).collect(Collectors.toList());
        List<Latch> sourcesNoMoreOutput = sources.stream().map(OutputQueue::noMoreOutput).collect(Collectors.toList());
        return new Indexing<>(indexer, index, sourcesOutputs, sourcesNoMoreOutput);
    }


    private final Indexer indexer;

    private final String index;


    public Indexing(Indexer indexer, String index, List<BlockingQueue<I>> inputs, List<Latch> noMoreInput) {
        super(inputs, noMoreInput);
        if (indexer == null)
            throw new IllegalArgumentException("Indexer is undefined");
        this.indexer = indexer;
        this.index   = index;
    }

    public Indexing(Indexer indexer, String index, BlockingQueue<I> input, List<Latch> noMoreInputs) {
        this(indexer, index, singletonList(input), noMoreInputs);
    }

    public Indexing(Indexer indexer, String index, BlockingQueue<I> input, Latch noMoreInput) {
        this(indexer, index, singletonList(input), singletonList(noMoreInput));
    }

    public Indexing(Indexer indexer, String index, List<Latch> noMoreInputs) {
        this(indexer, index, new LinkedBlockingQueue<>(), noMoreInputs);
    }

    public Indexing(Indexer indexer, String index, Latch noMoreInput) {
        this(indexer, index, new LinkedBlockingQueue<>(), singletonList(noMoreInput));
    }

    public Indexing(Indexer indexer, String index, OutputQueue<I> source) {
        this(indexer, index, source.output(), source.noMoreOutput());
    }


    @Override
    protected Result process(I element) {
        LOGGER.info(getClass().getName() + " - INDEXING " + element.getClass().getName());
        try{
//            boolean indexed = indexer.add(index, element);
//            if ( ! indexed ) {
//                LOGGER.error(getClass().getName() + " - " + indexer + " FAILED INDEXING " + element);
//                return Result.FAILURE;
//            }
            indexer.addBatch(index, element);
            return Result.SUCCESS;
        } catch (Exception e ) {
            LOGGER.error( getClass().getName() + " - " + indexer.getType() + " FAILED INDEXING " + element, e);
            return Result.FAILURE;
        }
    }

    @Override
    protected boolean initialize() { return true; }

    @Override
    protected void terminate() { LOGGER.info(getClass().getName() + " - TERMINATING"); }

}
