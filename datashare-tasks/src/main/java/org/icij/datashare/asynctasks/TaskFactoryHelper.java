package org.icij.datashare.asynctasks;

import static java.lang.String.format;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.function.Function;

public class TaskFactoryHelper {
    public static Callable<?> createTaskCallable(TaskFactory factory, String name, Task<?> taskView,
                                                 Function<Double, Void> progress) throws ReflectiveOperationException {
        Callable<?> taskFn;
        Class<? extends Callable<?>> taskClass = (Class<? extends Callable<?>>) Class.forName(name);
        //TODO This call by reflection is very hard to track, and looks too hacky. Probable refactoring would
        // be to use a registry
        Method method =
                factory.getClass().getMethod(format("create%s", taskClass.getSimpleName()), Task.class, Function.class);
        // the method is found by simple name only: refuse a class of another package that shares that name
        if (!method.getReturnType().isAssignableFrom(taskClass)) {
            throw new NoSuchMethodException(format("%s does not create %s", method.getName(), name));
        }
        taskFn = (Callable<?>) method.invoke(factory, taskView, progress);

        if (taskFn == null) {
            throw new NullPointerException("Task named " + name + " return a null callable");
        }
        return taskFn;
    }

}
