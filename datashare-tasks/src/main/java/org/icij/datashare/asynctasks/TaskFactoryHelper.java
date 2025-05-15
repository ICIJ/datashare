package org.icij.datashare.asynctasks;

import static java.lang.String.format;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.function.Function;

public class TaskFactoryHelper {
    public static Callable<?> createTaskCallable(TaskFactory factory, String name, Task taskView, Function<Double, Void> progress)
    throws ReflectiveOperationException {
        Callable<?> taskFn;
            Class<? extends Callable<?>> taskClass = (Class<? extends Callable<?>>) Class.forName(name);
            Method method = factory.getClass().getMethod(format("create%s", taskClass.getSimpleName()), Task.class, Function.class);
            taskFn = (Callable<?>) method.invoke(factory, taskView, progress);

        if (taskFn == null) {
            throw new NullPointerException("Task named " + name + " return a null callable");
        }
        return taskFn;
    }

}
