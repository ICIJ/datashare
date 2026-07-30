package org.icij.datashare.tasks;


import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * filters the document queue with extracted docs
 */
@TemporalSingleActivityWorkflow(name = "deduplicate-documents", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class DeduplicateTask extends PipelineTask<Path> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentCollectionFactory<Path> factory;

    @Inject
    public DeduplicateTask(final DocumentCollectionFactory<Path> factory, final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.DEDUPLICATE, taskView.getUser(), factory, new PropertiesProvider(taskView.args), Path.class, gateFactory.forTask(taskView));
        this.factory = factory;
    }

    @Override
    public Long call() throws Exception {
        super.call();
        Set<Path> seen = new HashSet<>();
        // dedup per entry, not in one upfront pass: SCAN keeps enqueueing while this drain runs
        long duplicates = transferToOutputQueue(seen::add);
        logger.info("removed {} duplicate paths in inputQueue {}", duplicates, inputQueue.getName());
        return duplicates;
    }

    /** Drains the input queue into the output queue, returning how many entries the filter rejected. */
    long transferToOutputQueue(Predicate<Path> filter) throws Exception {
        long rejected = 0;
        try (DocumentQueue<Path> outputQueue = factory.createQueue(getOutputQueueName(), Path.class)) {
            while (!Thread.currentThread().isInterrupted()) {
                Path path;
                try {
                    path = inputQueue.poll();
                } catch (RuntimeException e) {
                    if (causedByInterrupt(e)) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    throw e;
                }
                if (path == null) {
                    if (drained()) {
                        break;
                    }
                    Thread.sleep(UPSTREAM_POLL_INTERVAL_MS);
                    continue;
                }
                if (filter.test(path)) {
                    outputQueue.add(path);
                } else {
                    rejected++;
                }
            }
            // Thread.interrupted() tests AND clears: TaskWorkerLoop never clears the flag itself, so
            // leaving it set would leak the interrupt onto the runner thread and make the next task
            // start already cancelled.
            if (Thread.interrupted()) {
                throw new InterruptedException("cancelled while draining " + inputQueue.getName());
            }
            return rejected;
        }
    }
}
