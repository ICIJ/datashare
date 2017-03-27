package org.icij.datashare.concurrent.task;

import java.util.concurrent.TimeUnit;


/**
 * DataShare {@link Task} executor
 *
 * Created by julien on 9/30/16.
 */
public interface TaskExecutor {

    /**
     * Start {@link Task}s
     */
    void start();

    /**
     * Try shutdown; stop accepting submitted {@link Task}
     */
    void shutdown();

    /**
     * Is there any {@link Task} running?
     * @return true if at least one task is not done yet; false otherwise
     */
    boolean runningTask();

    /**
     * Block until no single {@link Task} is running
     */
    void awaitTermination();

    /**
     * Block until no more running {@link Task} or duration passed
     * @param duration maximum waiting time
     * @param timeUnit duration's time unit
     */
    void awaitTermination(int duration, TimeUnit timeUnit);

    /**
     * Force shutdown; eventually cancel non-terminated {@link Task}s
     */
    void stop();

}
