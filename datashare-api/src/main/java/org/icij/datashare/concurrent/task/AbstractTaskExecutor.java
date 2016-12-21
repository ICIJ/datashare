package org.icij.datashare.concurrent.task;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Common structure and behavior for {@link TaskExecutor}s
 *   - an executor service handles
 *   - a list of {@link Task}s
 *
 * Created by julien on 9/30/16.
 */
abstract class AbstractTaskExecutor implements TaskExecutor {

    protected final Logger LOGGER = LogManager.getLogger(getClass());


    protected final ExecutorService      executor;

    protected final List<? extends Task> tasks;


    AbstractTaskExecutor(ExecutorService executor, List<? extends Task> tasks) {
        this.executor = executor;
        this.tasks    = tasks;

        LOGGER.info(getClass().getName() +
                " - " + "Creating fixed thread pool of " + tasks.size() + " tasks" +
                " - " + Thread.currentThread().getName());
    }


    private void submit(Task task) {
        task.setFuture( executor.submit(task) );
    }

    @Override
    public void start() {
        if ( ! runningTask()) {
            tasks.forEach( this::submit );
        }
    };

    @Override
    public void shutdown() {
        LOGGER.info(getClass().getSimpleName() + ": " + "Shutdown executor");
        executor.shutdown();
    }

    @Override
    public void awaitTermination(int duration, TimeUnit timeUnit) {
        try {
            LOGGER.info(getClass().getSimpleName() + ": " +
                    "Awaiting termination of processings up to " + duration + " " + timeUnit.name());
            executor.awaitTermination(duration, timeUnit);
        } catch (InterruptedException e) {
            LOGGER.error(getClass().getSimpleName() + ": Await termination interrupted");
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void awaitTermination() {
        try {
            LOGGER.info(getClass().getSimpleName() + ": " + "Awaiting termination of processings...");
            while ( runningTask() ) {
                Thread.sleep(5000);
            }
        } catch (InterruptedException e) {
            LOGGER.info(getClass().getSimpleName() + ": " + "Await termination interrupted");
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        try {
            LOGGER.info(getClass().getSimpleName() + ": Attempt to stop executor");
            executor.shutdown();
            LOGGER.info(getClass().getSimpleName() + ": Awaiting completion of processings...");
            executor.awaitTermination(5, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            LOGGER.error(getClass().getSimpleName() + ": Stop interrupted");
            Thread.currentThread().interrupt();
        } finally {
            if ( ! executor.isTerminated()) {
                executor.shutdownNow()
                        .forEach( runnable ->
                                LOGGER.info(getClass().getSimpleName() + ": " +
                                        "Cancelling awaiting tasks " + runnable.getClass().getEnclosingClass().getName())
                        );
            }
            LOGGER.info(getClass().getSimpleName() + ": Executor stopped");
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
