package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;


public class TaskRunnerLoop implements Callable<Integer>, Closeable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final TaskFactory factory;
    private final TaskSupplier taskSupplier;
    final AtomicReference<Callable<?>> currentTaskReference = new AtomicReference<>();
    public static final TaskView<Serializable> POISON = TaskView.nullObject();
    private final CountDownLatch waitForMainLoopCalled; // for tests only
    private volatile boolean exitAsked = false;
    private volatile Thread loopThread;

    @Inject
    public TaskRunnerLoop(TaskFactory factory, TaskSupplier taskSupplier) {
        this(factory, taskSupplier, new CountDownLatch(1));
    }

    TaskRunnerLoop(TaskFactory factory, TaskSupplier taskSupplier, CountDownLatch countDownLatch) {
        this.factory = factory;
        this.taskSupplier = taskSupplier;
        this.waitForMainLoopCalled = countDownLatch;
        Signal.handle(new Signal("TERM"), signal -> {
            exitAsked = true;
            ofNullable(currentTaskReference.get()).ifPresent(t -> {
                    if (CancellableCallable.class.isAssignableFrom(t.getClass())) {
                        ((CancellableCallable<?>) t).cancel();
                    }
            });
            ofNullable(loopThread).ifPresent(Thread::interrupt); // for interrupting poll
        });
    }

    public Integer call() {
        return mainLoop();
    }

    @SuppressWarnings("unchecked")
    private <R extends Serializable> Integer mainLoop() {
        waitForMainLoopCalled.countDown();
        loopThread = Thread.currentThread();
        TaskView<R> currentTask = null;
        int nbTasks = 0;
        logger.info("Waiting tasks from supplier ({})", taskSupplier.getClass());
        while (!POISON.equals(currentTask) && !exitAsked) {
            try {
                currentTask = taskSupplier.get(60, TimeUnit.SECONDS);

                if (currentTask != null && !POISON.equals(currentTask)) {
                    Class<? extends Callable<R>> taskClass = (Class<? extends Callable<R>>) Class.forName(currentTask.name);
                    Method method = factory.getClass().getMethod(format("create%s", taskClass.getSimpleName()), currentTask.getClass(), BiFunction.class);
                    currentTaskReference.set((Callable<R>) method.invoke(factory, currentTask, (BiFunction<String, Double, Void>) taskSupplier::progress));
                    taskSupplier.result(currentTask.id, ((Callable<R>)currentTaskReference.get()).call());
                    currentTaskReference.set(null);
                    nbTasks++;
                }
            } catch (BatchSearchRunner.CancelException cex) {
                taskSupplier.cancel(currentTask);
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

    @Override
    public void close() throws IOException {
        logger.info("closing {}", taskSupplier.getClass());
        taskSupplier.close();
    }
}
