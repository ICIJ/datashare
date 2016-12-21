package org.icij.datashare.concurrent.queue;

import java.util.List;
import java.util.concurrent.BlockingQueue;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.task.QueueInQueueOutTask;
import org.icij.datashare.concurrent.task.Task;


/**
 * {@link QueueInQueueOutTask} forwarding input queue elements to N other blocking queues
 *   - take each element from input and put on each destination queue
 *
 * Created by julien on 10/6/16.
 */
public class QueueForwardingTask<I> extends QueueInQueueOutTask<I,I> {

    private final List<BlockingQueue<I>> destinations;


    public QueueForwardingTask(BlockingQueue<I> source, List<BlockingQueue<I>> destinations, Latch noMoreInput) {
        super(source, source, noMoreInput);
        this.destinations = destinations;
    }


    @Override
    public Result execute(I element) {

        for (BlockingQueue<I> outputQueue : destinations) {
            try {
                LOGGER.info("Forwarding input element, [" + input().size() + "] remaining");
                outputQueue.put(element);

            } catch (InterruptedException e) {
                LOGGER.info("Queue forwarding interrupted");
                Thread.currentThread().interrupt();

            } catch (Exception e) {
                LOGGER.info("Failed forwarding queue element");
            }
        }
        return Result.SUCCESS;

    }

}
