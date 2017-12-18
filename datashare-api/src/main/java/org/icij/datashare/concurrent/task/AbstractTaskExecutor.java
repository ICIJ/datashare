package org.icij.datashare.concurrent.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * Common structure and behavior for {@link TaskExecutor}s
 * An executor service handles a list of {@link Task}s
 *
 * Created by julien on 9/30/16.
 */
abstract class AbstractTaskExecutor implements TaskExecutor {
    final Logger LOGGER = LoggerFactory.getLogger(getClass());

    private final ExecutorService executor;

    protected final List<? extends Task> tasks;


    AbstractTaskExecutor(ExecutorService executor, List<? extends Task> tasks) {
        this.executor = executor;
        this.tasks    = tasks;
    }


    private void submit(Task task) {
        task.setFuture( executor.submit(task) );
    }

    @Override
    public void start() {
        if ( ! runningTask()) {
            tasks.forEach( this::submit );
        }
    }

    @Override
    public void shutdown() {
        LOGGER.info(getClass().getSimpleName() + ": " + "Shutdown executor");
        executor.shutdown();
    }

    @Override
    public void awaitTermination(int duration, TimeUnit timeUnit) {
        LOGGER.info("Awaiting termination of processings up to " + duration + " " + timeUnit.name());
        try {
            executor.awaitTermination(duration, timeUnit);
        } catch (InterruptedException e) {
            LOGGER.error("Await processing termination interrupted");
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void awaitTermination() {
        LOGGER.info("Awaiting termination of processings...");
        while ( runningTask() && ! Thread.currentThread().isInterrupted() ) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                LOGGER.info("Await termination interrupted");
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void stop() {
        try {
            LOGGER.info("Attempting to stop executor");
            executor.shutdown();
            LOGGER.info("Awaiting completion of processings...");
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("Executor Stop interrupted");
            Thread.currentThread().interrupt();
        } catch (SecurityException e) {
            LOGGER.error("Executor Stop denied", e);
            Thread.currentThread().interrupt();
        } finally {
            if ( ! executor.isTerminated()) {
                executor.shutdownNow().forEach( runnable ->
                        LOGGER.info("Cancelling awaiting tasks " + runnable.getClass().getEnclosingClass().getName())
                );
            }
            LOGGER.info("Executor stopped");
        }
    }

    @Override
    public boolean runningTask() {
        return ! tasks.stream()
                .filter(task -> ! task.isDone())
                .collect(Collectors.toList())
                .isEmpty();
    }

}
