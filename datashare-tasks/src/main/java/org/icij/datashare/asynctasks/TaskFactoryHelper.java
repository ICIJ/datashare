package org.icij.datashare.asynctasks;

import static java.lang.String.format;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.function.Function;

public class TaskFactoryHelper {
    public static Callable<?> createTaskCallable(
        TaskFactory factory, String name, TaskView<?> taskView, Function<Double, Void> progress
    )
        throws UnknownTask {
        Callable<?> taskFn;
        try {
            Class<? extends Callable<?>> taskClass = (Class<? extends Callable<?>>) Class.forName(name);
            Method method = factory.getClass().getMethod(format("create%s", taskClass.getSimpleName()), TaskView.class, Function.class);
            taskFn = (Callable<?>) method.invoke(factory, taskView, progress);
        } catch (ReflectiveOperationException e) {
            throw new UnknownTask("unknown task \"" + name + "\"", e);
        }
        if (taskFn == null) {
            throw new NullPointerException("Task named " + name + " return a null callable");
        }
        return taskFn;
    }

}
