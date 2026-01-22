package org.icij.datashare.asynctasks;

import java.io.IOException;
import java.io.Serializable;
import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ErrorEvent;
import org.icij.datashare.asynctasks.bus.amqp.ProgressEvent;
import org.icij.datashare.asynctasks.bus.amqp.ResultEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskEvent;

/**
 * Task manager back by a queueing system and a persistent storay
 */
abstract class StoreAndQueueTaskManagerImpl implements StoreAndQueueTaskManager {

     // For test
    abstract protected Group getTaskGroup(String taskId) throws IOException;

    /**
     * Main start task function. it saves the task in the persistent storage. If the task is new it will enqueue
     * it in the memory/redis/AMQP queue and return the id. Else it will not enqueue the task and return null.
     *
     * @param taskView: the task description.
     * @param group:    task group
     * @return task id if it was new and has been saved else null
     * @throws IOException       in case of communication failure with Redis or AMQP broker
     * @throws TaskAlreadyExists when the task has already been started
     */
    @Override
    public <V extends Serializable> String startTask(Task<V> taskView, Group group)
        throws IOException, TaskAlreadyExists {
        insert(taskView, group);
        taskView.queue();
        enqueue(taskView);
        return taskView.id;
    }

    private <V extends Serializable> Task<V> setResult(ResultEvent<V> e) throws IOException, UnknownTask {
        Task<V> taskView = getTask(e.taskId);
        if (taskView != null) {
            logger.info("result event for {}", e.taskId);
            taskView.setResult(e.result);
            update(taskView);
        } else {
            logger.warn("no task found for result event {}", e.taskId);
        }
        return taskView;
    }

    private <V extends Serializable> Task<V> setError(ErrorEvent e) throws IOException, UnknownTask {
        Task<V> taskView = getTask(e.taskId);
        if (taskView != null) {
            logger.info("error event for {}", e.taskId);
            taskView.setError(e.error);
            update(taskView);
        } else {
            logger.warn("no task found for error event {}", e.taskId);
        }
        return taskView;
    }

    private <V extends Serializable> Task<V> setCanceled(CancelledEvent e) throws IOException, UnknownTask {
        Task<V> taskView = getTask(e.taskId);
        if (taskView != null) {
            logger.info("canceled event for {}", e.taskId);
            taskView.cancel();
            update(taskView);
            if (e.requeue) {
                try {
                    enqueue(taskView);
                } catch (IOException ex) {
                    logger.error("error while reposting canceled event", ex);
                }
            }
        } else {
            logger.warn("no task found for canceled event {}", e.taskId);
        }
        return taskView;
    }

    private <V extends Serializable> Task<V> setProgress(ProgressEvent e) throws IOException, UnknownTask {
        logger.debug("progress event for {}", e.taskId);
        try {
            Task<V> taskView = getTask(e.taskId);
            if (taskView != null) {
                taskView.setProgress(e.progress);
                update(taskView);
            }
            return taskView;
        } catch (UnknownTask ex) {
            throw new NackException(ex, false);
        }
    }

    protected final <V extends Serializable> Task<V> handleAck(TaskEvent e) {
        try {
            if (e instanceof CancelledEvent ce) {
                return setCanceled(ce);
            }
            if (e instanceof ResultEvent) {
                return setResult((ResultEvent<V>) e);
            }
            if (e instanceof ErrorEvent ee) {
                return setError(ee);
            }
            if (e instanceof ProgressEvent pe) {
                return setProgress(pe);
            }
            logger.warn("received event not handled {}", e);
            return null;
        } catch (IOException | UnknownTask ioe) {
            throw new TaskEventHandlingException(ioe);
        }
    }

    protected static class TaskEventHandlingException extends RuntimeException {
        public TaskEventHandlingException(Exception cause) {
            super(cause);
        }
    }
}
