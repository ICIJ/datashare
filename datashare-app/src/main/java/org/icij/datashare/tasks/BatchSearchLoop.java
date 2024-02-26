package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;

public class BatchSearchLoop implements Callable<Integer> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    final TaskSupplier taskSupplier;
    private final TaskFactory factory;
    final AtomicReference<BatchSearchRunner> currentBatchSearchRunner = new AtomicReference<>();
    private final CountDownLatch waitForMainLoopCalled; // for tests only
    private volatile boolean exitAsked = false;
    private volatile Thread loopThread;

    @Inject
    public BatchSearchLoop(TaskSupplier taskSupplier, TaskFactory factory) {
        this(taskSupplier, factory, new CountDownLatch(1));
    }

    BatchSearchLoop(TaskSupplier taskSupplier, TaskFactory factory, CountDownLatch countDownLatch) {
        this.taskSupplier = taskSupplier;
        this.factory = factory;
        this.waitForMainLoopCalled = countDownLatch;
        Signal.handle(new Signal("TERM"), signal -> {
            exitAsked = true;
            ofNullable(currentBatchSearchRunner.get()).ifPresent(BatchSearchRunner::cancel);
            ofNullable(loopThread).ifPresent(Thread::interrupt); // for interrupting poll
        });
    }

    public Integer call() {
        logger.info("Waiting batch searches from supplier ({}) ds:batchsearch:queue", taskSupplier.getClass());
        waitForMainLoopCalled.countDown();
        loopThread = Thread.currentThread();
        TaskView<Serializable> currentTask = null;
        int nbBatchSearch = 0;
        do {
            try {
                currentTask = taskSupplier.get(60, TimeUnit.SECONDS);
                if (currentTask != null && !TaskView.nullObject().equals(currentTask)) {
                    currentBatchSearchRunner.set(factory.createBatchSearchRunner(currentTask, taskSupplier::progress));
                    currentBatchSearchRunner.get().call();
                    currentBatchSearchRunner.set(null);
                    nbBatchSearch++;
                }
            } catch (BatchSearchRunner.CancelException cex) {
                taskSupplier.cancel(currentTask);
            } catch (Throwable ex) {
                logger.error(format("error in loop for task %s", currentTask), ex);
                if (currentTask != null && !currentTask.isNull()) {
                    taskSupplier.error(currentTask.id, ex);
                }
            }
        } while (!TaskView.nullObject().equals(currentTask) && !exitAsked);
        logger.info("exiting main loop");
        return nbBatchSearch;
    }

    public void enqueuePoison() {
        // TODO: who is adding POISON? taskSupplier.add(POISON);
    }

    public void close() throws IOException {
        logger.info("closing {}", taskSupplier.getClass());
        taskSupplier.close();
    }
}
