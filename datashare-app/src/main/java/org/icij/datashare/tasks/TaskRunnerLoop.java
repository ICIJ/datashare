package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.com.bus.amqp.CancelEvent;
import org.icij.datashare.com.bus.amqp.CanceledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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
    private final int pollTimeMillis;
    private final ConcurrentHashMap<String, Boolean> cancelledTasks;
    private volatile boolean exitAsked = false;
    private volatile Thread loopThread;
    private volatile TaskView<?> currentTask = null;

    @Inject
    public TaskRunnerLoop(TaskFactory factory, TaskSupplier taskSupplier) {
        this(factory, taskSupplier, new CountDownLatch(1));
    }

    TaskRunnerLoop(TaskFactory factory, TaskSupplier taskSupplier, CountDownLatch countDownLatch) {
        this(factory, taskSupplier, countDownLatch, 60_000);
    }

    TaskRunnerLoop(TaskFactory factory, TaskSupplier taskSupplier, CountDownLatch countDownLatch, int pollTimeMillis) {
        this.factory = factory;
        this.taskSupplier = taskSupplier;
        this.waitForMainLoopCalled = countDownLatch;
        this.pollTimeMillis = pollTimeMillis;
        this.cancelledTasks = new ConcurrentHashMap<>();
        Signal.handle(new Signal("TERM"), signal -> {
            exitAsked = true;
            cancel(null, true);
            ofNullable(loopThread).ifPresent(Thread::interrupt); // for interrupting poll
        });
        taskSupplier.addEventListener((event -> {
            if (event instanceof CanceledEvent) {
                cancelledTasks.remove(event.taskId);
            } else if (event instanceof CancelEvent) {
                CancelEvent cancelEvent = (CancelEvent) event;
                cancel(cancelEvent.taskId, cancelEvent.requeue );
                cancelledTasks.put(cancelEvent.taskId, cancelEvent.requeue);
            }
        }));
    }

    public Integer call() {
        return mainLoop();
    }

    @SuppressWarnings("unchecked")
    private <R extends Serializable> Integer mainLoop() {
        waitForMainLoopCalled.countDown();
        loopThread = Thread.currentThread();
        int nbTasks = 0;
        logger.info("Waiting tasks from supplier ({})", taskSupplier.getClass());
        while (!POISON.equals(currentTask) && !exitAsked) {
            try {
                currentTask = taskSupplier.get(pollTimeMillis, TimeUnit.MILLISECONDS);

                if (currentTask != null && !POISON.equals(currentTask)) {
                    if (cancelledTasks.get(currentTask.id) != null) {
                        logger.info("cancelled task {} not executed", currentTask.id);
                        taskSupplier.canceled(currentTask, cancelledTasks.remove(currentTask.id));
                        continue;
                    }
                    taskSupplier.progress(currentTask.id, 0);
                    Class<? extends Callable<R>> taskClass = (Class<? extends Callable<R>>) Class.forName(currentTask.name);
                    Method method = factory.getClass().getMethod(format("create%s", taskClass.getSimpleName()), currentTask.getClass(), BiFunction.class);
                    Callable<R> callable = (Callable<R>) method.invoke(factory, currentTask, (BiFunction<String, Double, Void>) taskSupplier::progress);
                    if (callable != null) {
                        logger.info("running task {}", currentTask);
                        currentTaskReference.set(callable);
                        taskSupplier.result(currentTask.id, ((Callable<R>) currentTaskReference.get()).call());
                        nbTasks++;
                    } else {
                        logger.error("cannot run null callable for task {}", currentTask);
                    }
                    currentTask = null;
                }
            } catch (CancelException cex) {
                taskSupplier.canceled(currentTask, cex.requeue);
            } catch (InterruptedException iex) {
                logger.error(format("task %s interrupted, cancelling it", currentTask), iex);
                ofNullable(currentTask).ifPresent(t -> taskSupplier.canceled(t, false));
            } catch (Throwable ex) {
                logger.error(format("error in loop for task %s", currentTask), ex);
                if (currentTask != null && !currentTask.isNull()) {
                    taskSupplier.error(currentTask.id, ex);
                }
            } finally {
                currentTaskReference.set(null);
            }
        }
        logger.info("Exiting loop after {} tasks", nbTasks);
        return nbTasks;
    }

    @Override
    public void close() throws IOException {
        exitAsked = true;
        taskSupplier.close();
        loopThread.interrupt();
    }

    public void cancel(String taskId, boolean requeue) {
        ofNullable(currentTaskReference.get()).ifPresent(t -> {
            if (CancellableCallable.class.isAssignableFrom(t.getClass()) &&
                    (taskId == null || (currentTask != null && taskId.equals(currentTask.id)))) {
                logger.info("cancelling callable for task {} requeue={}", taskId, requeue);
                ((CancellableCallable<?>) t).cancel(taskId, requeue);
            }
        });
    }
}
