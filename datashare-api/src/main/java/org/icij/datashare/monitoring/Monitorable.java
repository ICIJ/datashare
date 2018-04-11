package org.icij.datashare.monitoring;

public interface Monitorable {
    /**
     * get the progress rate of a long running task
     * @return
     * - a double between 0 and 1.
     * - -1 if the task is not initialized
     * - -2 if the task is not monitorable
     */
    double getProgressRate();
}
