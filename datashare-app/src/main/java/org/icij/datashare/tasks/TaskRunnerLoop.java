package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static java.lang.String.format;


public class TaskRunnerLoop implements Callable<Integer> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final TaskFactory factory;
    private final TaskSupplier taskSupplier;
    public static final TaskView<Serializable> POISON = TaskView.nullObject();

    @Inject
    public TaskRunnerLoop(TaskFactory factory, TaskSupplier taskSupplier) {
        this.factory = factory;
        this.taskSupplier = taskSupplier;
    }

    public Integer call() {
        return mainLoop();
    }

    @SuppressWarnings("unchecked")
    private <R extends Serializable> Integer mainLoop() {
        TaskView<R> currentTask = null;
        int nbTasks = 0;
        logger.info("Waiting tasks from supplier ({})", taskSupplier.getClass());
        while (!POISON.equals(currentTask)) {
            try {
                currentTask = taskSupplier.get(60, TimeUnit.SECONDS);

                if (currentTask != null && !POISON.equals(currentTask)) {
                    Class<? extends Callable<R>> taskClass = (Class<? extends Callable<R>>) Class.forName(currentTask.name);
                    Method method = factory.getClass().getMethod(format("create%s", taskClass.getSimpleName()), currentTask.getClass(), BiFunction.class);
                    Callable<R> task = (Callable<R>) method.invoke(factory, currentTask, (BiFunction<String, Double, Void>) taskSupplier::progress);
                    taskSupplier.result(currentTask.id, task.call());
                    nbTasks++;
                }
            } catch (Throwable ex) {
                logger.error(format("error in loop for task %s", currentTask), ex);
                if (currentTask != null && !currentTask.isNull()) {
                    taskSupplier.error(currentTask.id, ex);
                }
            }
        }
        logger.info("Exiting loop after {} tasks", nbTasks);
        return nbTasks;
    }
}
