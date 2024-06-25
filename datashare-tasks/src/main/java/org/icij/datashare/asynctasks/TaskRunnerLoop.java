package org.icij.datashare.asynctasks;

import static java.util.Optional.ofNullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.icij.datashare.asynctasks.bus.amqp.CancelEvent;
import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;


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
            // TODO: python alignment possible, in Python if the
            //  worker.negative_acknowledge(task_id, requeue) succeeds the worker doesn't wait
            //  for confirmation by the task manager to consider the task nacked (this works for
            //  AMQP where the nack is transactional, does it work for Redis ?)
            if (event instanceof CancelledEvent) {
                cancelledTasks.remove(event.taskId);
            } else if (event instanceof CancelEvent) {
                CancelEvent cancelEvent = (CancelEvent) event;
                cancel(cancelEvent.taskId, cancelEvent.requeue);
                cancelledTasks.put(cancelEvent.taskId, cancelEvent.requeue);
            }
        }));
    }

    public Integer call() {
        return mainLoop();
    }

    @SuppressWarnings("unchecked")
    private Integer mainLoop() {
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
                    // TODO: this should probably evolve towards
                    //  TaskFactoryHelper.createTaskCallable(factory, currentTask.name, currentTask.properties, currentTask.progress(taskSupplier::progress))
                    //  since the task view itself is not needed, only the properties are needed.
                    //  Passing the view allows the task function to perform actions it shouldn't
                    //  be allow to perform (setResult, setError, cancel, queue and so on...) with
                    //  potential side effects
                    Callable<?> taskFn;
                    taskFn = TaskFactoryHelper.createTaskCallable(factory, currentTask.name, currentTask, currentTask.progress(taskSupplier::progress));
                    currentTaskReference.set(taskFn);
                    logger.info("running task {}", currentTask);
                    taskSupplier.progress(currentTask.id, 0);
                    Serializable result = (Serializable) taskFn.call();
                    taskSupplier.result(currentTask.id, result);
                    nbTasks++;
                    currentTask = null;
                }
            } catch (CancelException cex) {
                // TODO: this has to be improved/simplified. The cancellation mechanism relies on
                //  the fact that the CancellableTask code will properly handle the cancellation.
                //  However while some task correctly throw a CancelException and correctly forward
                //  the requeue arg. However some tasks like the PipelineTask simple throw an
                //  InterruptedException and without rethrowing a new CancelException(requeue) with
                //  the requeue attribute correctly set. This will lead to unexpected behavior,
                //  such asking for cancel with requeue argument which is actually ignored.
                logger.error("task {} cancelled with requeue = {}", currentTask, cex.requeue);
                taskSupplier.canceled(currentTask, cex.requeue);
            } catch (InterruptedException iex) {
                logger.error("task {} interrupted, cancelling it", currentTask, iex);
                // TODO: align with Python (requeue)
                ofNullable(currentTask).ifPresent(t -> taskSupplier.canceled(t, false));
            } catch (Throwable ex) {
                logger.error("error in loop for task {}", currentTask, ex);
                if (currentTask != null && !currentTask.isNull()) {
                    taskSupplier.error(currentTask.id, new TaskError(ex));
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
        // TODO: different from Python where all task are cancellable by default. Behavior will
        //  have to be aligned to have a consistent cancellation behavior across languages
        //  Users must be able to cancel all task whether they are cancellable or not. Otherwise
        //  some useful behavior will be painful to implement:
        //  - global graceful shutdown (task which are not cancellable will run until complete...)
        //  - graceful restart of a task
        ofNullable(currentTaskReference.get()).ifPresent(t -> {
            if (CancellableTask.class.isAssignableFrom(t.getClass()) &&
                    (taskId == null || (currentTask != null && taskId.equals(currentTask.id)))) {
                logger.info("cancelling callable for task {} requeue={}", taskId, requeue);
                ((CancellableTask) t).cancel(requeue);
            }
        });
    }
}
