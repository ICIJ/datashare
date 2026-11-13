package org.icij.datashare.asynctasks;

import org.icij.datashare.asynctasks.bus.amqp.CancelEvent;
import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ShutdownEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static java.util.Optional.ofNullable;

public class TaskWorkerLoop implements Callable<Integer>, Closeable {
    private static final Duration CANCEL_GRACE_PERIOD = Duration.ofSeconds(5);
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final TaskFactory factory;
    private final TaskSupplier taskSupplier;
    final AtomicReference<Callable<?>> currentTaskReference = new AtomicReference<>();
    final AtomicReference<Task<?>> currentTask = new AtomicReference<>();
    private final CountDownLatch waitForMainLoopCalled; // for tests only
    private final int pollTimeMillis;
    private final double progressMinIntervalS;
    private final ConcurrentHashMap<String, Boolean> cancelledTasks;
    private volatile boolean exitAsked = false;
    private volatile boolean draining = false;
    private final Object stateLock = new Object(); // guards currentTask claim/release against stopAcceptingTasks
    private volatile Thread loopThread;
    private int nbTasks = 0;

    public TaskWorkerLoop(TaskFactory factory, TaskSupplier taskSupplier) {
        this(factory, taskSupplier, new CountDownLatch(1), 10F);
    }

    public TaskWorkerLoop(TaskFactory factory, TaskSupplier taskSupplier, double progressMinIntervalS) {
        this(factory, taskSupplier, new CountDownLatch(1), progressMinIntervalS);
    }

    public TaskWorkerLoop(TaskFactory factory, TaskSupplier taskSupplier, CountDownLatch countDownLatch,
                          double progressMinIntervalS) {
        this(factory, taskSupplier, countDownLatch, 60_000, progressMinIntervalS);
    }

    public TaskWorkerLoop(TaskFactory factory, TaskSupplier taskSupplier, CountDownLatch countDownLatch,
                          int pollTimeMillis, double progressMinIntervalS) {
        this.factory = factory;
        this.taskSupplier = taskSupplier;
        this.waitForMainLoopCalled = countDownLatch;
        this.pollTimeMillis = pollTimeMillis;
        this.progressMinIntervalS = progressMinIntervalS;
        this.cancelledTasks = new ConcurrentHashMap<>();

        taskSupplier.addEventListener((event -> {
            if (event instanceof ShutdownEvent) {
                closeAsync(); // for sending ack
                // TODO: python alignment possible, in Python if the
                //  worker.negative_acknowledge(task_id, requeue) succeeds the worker doesn't wait
                //  for confirmation by the task manager to consider the task nacked (this works for
                //  AMQP where the nack is transactional, does it work for Redis ?)
            } else if (event instanceof CancelledEvent cancelledEvent) {
                cancelledTasks.remove(cancelledEvent.taskId);
            } else if (event instanceof CancelEvent cancelEvent) {
                cancel(cancelEvent.taskId, cancelEvent.requeue);
            }
        }));
    }

    public Integer call() {
        waitForMainLoopCalled.countDown();
        if (taskSupplier instanceof TaskSupplierAmqp) {
            taskSupplier.consumeTasks(this::handle);
            taskSupplier.waitForConsumer();
            return nbTasks;
        } else {
            return mainLoop();
        }
    }

    private Integer mainLoop() {
        loopThread = Thread.currentThread();
        Task<Serializable> task;
        logger.info("Waiting tasks from supplier ({})", taskSupplier.getClass());
        while (!exitAsked) {
            try {
                task = taskSupplier.get(pollTimeMillis, TimeUnit.MILLISECONDS);
                if (task != null) {
                    handle(task);
                }
            } catch (InterruptedException e) {
                logger.info("get from task supplier has been interrupted");
            } catch (NackException nex) {
                logger.error("fatal error in handle(task)", nex);
            }
        }
        logger.info("Exiting loop after {} tasks", nbTasks);
        loopThread.interrupt();
        return nbTasks;
    }

    void handle(Task<?> task) {
        loopThread = Thread.currentThread();
        if (!claim(task)) {
            // the task was already taken from the queue when the shutdown started: give it back
            Thread.interrupted(); // clears the flag possibly set by stopAcceptingTasks while we were polling
            logger.info("worker is shutting down, requeueing task {}", task.id);
            taskSupplier.canceled(task, true);
            return;
        }
        try {
            if (cancelledTasks.get(currentTask.get().id) != null) {
                logger.info("cancelled task {} not executed", currentTask.get().id);
                taskSupplier.canceled(currentTask.get(), cancelledTasks.remove(currentTask.get().id));
            } else {
                try {
                    ProgressSmoother smoothedProgress = new ProgressSmoother(taskSupplier::progress, progressMinIntervalS);
                    Callable<?> taskFn =
                            TaskFactoryHelper.createTaskCallable(factory, currentTask.get().name, currentTask.get(),
                                                                 currentTask.get().progress(smoothedProgress));
                    currentTaskReference.set(taskFn);
                    Boolean requeue = cancelledTasks.remove(currentTask.get().id);
                    if (requeue != null) {
                        throw new CancelException(requeue);
                    }
                    logger.info("running task {}", currentTask.get());
                    taskSupplier.progress(currentTask.get().id, 0);
                    Serializable result = (Serializable) taskFn.call();
                    taskSupplier.result(currentTask.get().id, new TaskResult<>(result));
                    nbTasks++;
                } catch (CancelException cex) {
                    // TODO: this has to be improved/simplified. The cancellation mechanism relies on
                    //  the fact that the CancellableTask code will properly handle the cancellation.
                    //  However while some task correctly throw a CancelException and correctly forward
                    //  the requeue arg. However some tasks like the PipelineTask simple throw an
                    //  InterruptedException and without rethrowing a new CancelException(requeue) with
                    //  the requeue attribute correctly set. This will lead to unexpected behavior,
                    //  such asking for cancel with requeue argument which is actually ignored.
                    logger.error("task {} cancelled with requeue = {}", currentTask, cex.requeue);
                    taskSupplier.canceled(currentTask.get(), cex.requeue);
                } catch (InterruptedException iex) {
                    logger.error("task {} interrupted, cancelling it", currentTask.get(), iex);
                    ofNullable(currentTask.get()).ifPresent(t -> taskSupplier.canceled(t, false));
                } catch (RuntimeException ex) {
                    logger.error("error running task {}", currentTask.get(), ex);
                    if (currentTask.get() != null && !currentTask.get().isNull()) {
                        taskSupplier.error(currentTask.get().id, new TaskError(ex));
                    }
                } catch (ReflectiveOperationException unknownTask) {
                    throw new NackException(unknownTask, true);
                } catch (Error | Exception ex) {
                    throw new NackException(ex, false);
                } finally {
                    cancelledTasks.remove(task.id);
                    currentTaskReference.set(null);
                }
            }
        } finally {
            release();
        }
    }

    private boolean claim(Task<?> task) {
        synchronized (stateLock) {
            if (draining) {
                return false;
            }
            currentTask.set(task);
            return true;
        }
    }

    private void release() {
        synchronized (stateLock) {
            currentTask.set(null);
            stateLock.notifyAll();
        }
    }

    /**
     * First step of a graceful shutdown (SIGTERM): the worker does not start any new task, the one
     * that is running (if any) is left untouched. A task that was already polled but not started is
     * requeued.
     */
    public void stopAcceptingTasks() {
        synchronized (stateLock) {
            draining = true;
            exit();
            if (currentTask.get() == null) {
                ofNullable(loopThread).ifPresent(Thread::interrupt); // wakes up the poll
            }
        }
        // outside the lock: this talks to the broker. A delivery already in flight is requeued by handle()
        taskSupplier.stopConsumingTasks();
    }

    /**
     * Second step of a graceful shutdown: waits for the running task to finish. If it is still
     * running after the timeout, it is cancelled with requeue=true so that another worker can take
     * it over, and as a last resort (the task ignores the cancellation) the worker thread is
     * interrupted.
     */
    public void awaitCurrentTask(Duration timeout) {
        if (waitForRelease(timeout)) {
            return;
        }
        Task<?> running = currentTask.get();
        if (running != null) {
            logger.warn("task {} still running after {}, cancelling it with requeue", running.id, timeout);
            cancel(running.id, true);
            if (!waitForRelease(CANCEL_GRACE_PERIOD)) {
                logger.warn("task {} did not stop after cancellation, interrupting it", running.id);
                ofNullable(loopThread).ifPresent(Thread::interrupt);
            }
        }
    }

    private boolean waitForRelease(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (stateLock) {
            while (currentTask.get() != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(stateLock, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private void closeAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                close();
            } catch (IOException e) {
                logger.error("error closing worker loop", e);
            }
        });
    }

    @Override
    public void close() throws IOException {
        exit();
        ofNullable(loopThread).ifPresent(Thread::interrupt);
        taskSupplier.close();
    }

    public void cancel(String taskId, boolean requeue) {
        // TODO: different from Python where all task are cancellable by default. Behavior will
        //  have to be aligned to have a consistent cancellation behavior across languages
        //  Users must be able to cancel all task whether they are cancellable or not. Otherwise
        //  some useful behavior will be painful to implement:
        //  - global graceful shutdown (task which are not cancellable will run until complete...)
        //  - graceful restart of a task
        ofNullable(taskId).ifPresent(id -> cancelledTasks.put(id, requeue));
        ofNullable(currentTaskReference.get()).ifPresent(t -> {
            if (CancellableTask.class.isAssignableFrom(t.getClass()) &&
                (taskId == null || (currentTask.get() != null && taskId.equals(currentTask.get().id)))) {
                logger.info("cancelling callable for task {} requeue={}", taskId, requeue);
                ((CancellableTask) t).cancel(requeue);
            }
        });
    }

    void exit() {
        exitAsked = true;
    }
}
