package org.icij.datashare.asynctasks;

import java.io.IOException;
import java.io.Serializable;

/**
 * Task manager back by a queueing system and a persistent storay
 */
interface StoreAndQueueTaskManager extends TaskManager {
    /**
     * This is a "inner method" that is used in the template method for start(task).
     * It saves the method in the inner persistent state of TaskManagers implementations.
     *
     * @param task to be saved in persistent state
     * @throws IOException if a network error occurs
     */
    <V extends Serializable> void insert(Task<V> task, Group group) throws IOException, TaskAlreadyExists;

    <V extends Serializable> void update(Task<V> task) throws IOException, UnknownTask;

    /**
     * This is a "inner method" that is used in the template method for start(task).
     * It put the task in the task queue for workers.
     *
     * @param task task to be queued
     * @throws IOException if a network error occurs
     */
    <V extends Serializable> void enqueue(Task<V> task) throws IOException;
}
