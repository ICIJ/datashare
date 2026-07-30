package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.UnknownTask;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Answers the only question a consumer stage may ask about its surroundings: can my input queue
 * still receive entries? It hides both the task repository and the upstream task id from the
 * tasks, which stay standalone pieces of code.
 */
public interface UpstreamGate {
    /**
     * Task-arg key carrying the id of the task whose output feeds this stage's input queue. Set by
     * the launcher (CliApp.runPipeline, TaskResource), never by an operator: it is not a CLI option.
     */
    String UPSTREAM_TASK_ID = "upstreamTaskId";

    /** True while the producer feeding this stage's input queue may still enqueue. */
    boolean mayGrow();

    /** No producer to wait for: the queue never grows, so a drain stops on its first empty poll. */
    UpstreamGate NONE = () -> false;

    @Singleton
    class Factory {
        private final TaskRepository taskRepository;

        @Inject
        public Factory(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
        }

        /**
         * The gate for this task view: repository-backed when the launcher set an upstream id in
         * the args, {@link #NONE} otherwise. An unknown task means "no upstream to wait for", so
         * the consumer falls back to stopping on an empty queue, the pre-gate behaviour. A
         * repository read failure says nothing about the producer, so it counts as still running:
         * retrying costs a poll interval, whereas calling it finished can end the drain on a
         * transient error and strand whatever is enqueued next. JooqTaskRepository throws jOOQ's
         * unchecked DataAccessException, hence the RuntimeException.
         */
        public UpstreamGate forTask(Task<?> taskView) {
            String upstreamTaskId = (String) taskView.args.get(UPSTREAM_TASK_ID);
            if (upstreamTaskId == null) {
                return NONE;
            }
            return () -> {
                try {
                    return !taskRepository.getTask(upstreamTaskId).getState().isFinal();
                } catch (UnknownTask e) {
                    LoggerFactory.getLogger(Factory.class).warn("upstream task {} is unknown, treating it as finished", upstreamTaskId, e);
                    return false;
                } catch (IOException | RuntimeException e) {
                    LoggerFactory.getLogger(Factory.class).warn("cannot read upstream task {} state, treating it as still running", upstreamTaskId, e);
                    return true;
                }
            };
        }
    }
}
