package org.icij.datashare.concurrent.queue;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.task.QueueInQueueOutTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;


/**
 * {@link QueueInQueueOutTask} forwarding input queue elements to N other blocking queues
 *   - poll each element from input and put on each destination queue
 *
 * Created by julien on 10/6/16.
 */
public class QueueForwarding<I> extends QueueInQueueOutTask<I,I> {

    private final List<BlockingQueue<I>> destinations;


    public static <E> QueueForwarding<E> create(List<BlockingQueue<E>> input, List<Latch> noMoreInput) {
        return new QueueForwarding<>(input, noMoreInput, new ArrayList<>());
    }

    public static <E> QueueForwarding<E> create(List<BlockingQueue<E>> input, Latch noMoreInput) {
        return create(input, singletonList(noMoreInput));
    }

    public static <E> QueueForwarding<E> create(BlockingQueue<E> input, Latch noMoreInput) {
        return create(singletonList(input), noMoreInput);
    }

    public static <E> QueueForwarding<E> create(List<? extends OutputQueue<E>> source) {
        return create(
                source.stream().map(OutputQueue::output).collect(Collectors.toList()),
                source.stream().map(OutputQueue::noMoreOutput).collect(Collectors.toList())
        );
    }

    public static <E> QueueForwarding<E> create(OutputQueue<E> source) {
        return create(singletonList(source));
    }


    private QueueForwarding(List<BlockingQueue<I>> input, List<Latch> noMoreInput, List<BlockingQueue<I>> destinations) {
        super(input, noMoreInput, new LinkedBlockingQueue<>());
        this.destinations = destinations;
    }


    public void addOutput(BlockingQueue<I> queue) {
        destinations.add(queue);
    }

    public void addOutput(List<BlockingQueue<I>> queue) {
        destinations.addAll(queue);
    }


    @Override
    public Result process(I element) {
        for (BlockingQueue<I> outputQueue : destinations) {
            try {
                LOGGER.debug("forwarding input " + element);
                outputQueue.put(element);
            } catch (InterruptedException e) {
                LOGGER.info("queue forwarding interrupted");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.info("failed forwarding queue element " + output());
            }
        }
        return Result.SUCCESS;
    }

    @Override
    protected boolean initialize() { return true; }

}
