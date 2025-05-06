package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.asynctasks.bus.amqp.CancelEvent;
import org.icij.datashare.asynctasks.bus.amqp.CancelledEvent;
import org.icij.datashare.asynctasks.bus.amqp.ShutdownEvent;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;


public class TaskWorkerLoop implements Callable<Integer>, Closeable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final TaskFactory factory;
    private final TaskSupplier taskSupplier;
    protected final ObjectMapper mapper;
    final AtomicReference<Callable<?>> currentTaskReference = new AtomicReference<>();
    final AtomicReference<Task> currentTask = new AtomicReference<>();
    private final CountDownLatch waitForMainLoopCalled; // for tests only
    private final int pollTimeMillis;
    private final ConcurrentHashMap<String, Boolean> cancelledTasks;
    private volatile boolean exitAsked = false;
    private volatile Thread loopThread;
    private int nbTasks = 0;

    public TaskWorkerLoop(TaskFactory factory, TaskSupplier taskSupplier) {
        this(factory, taskSupplier, MAPPER, new CountDownLatch(1));
    }

    public TaskWorkerLoop(TaskFactory factory, TaskSupplier taskSupplier, ObjectMapper mapper) {
        this(factory, taskSupplier, mapper, new CountDownLatch(1));
    }

    public TaskWorkerLoop(TaskFactory factory, TaskSupplier taskSupplier, CountDownLatch countDownLatch) {
        this(factory, taskSupplier, MAPPER, countDownLatch,60_000);
    }

    public TaskWorkerLoop(TaskFactory factory, TaskSupplier taskSupplier, ObjectMapper mapper, CountDownLatch countDownLatch) {
        this(factory, taskSupplier, mapper, countDownLatch,60_000);
    }

    public TaskWorkerLoop(TaskFactory factory, TaskSupplier taskSupplier, CountDownLatch countDownLatch, int pollTimeMillis) {
        this(factory, taskSupplier, MAPPER, countDownLatch, pollTimeMillis);
    }

    public TaskWorkerLoop(TaskFactory factory, TaskSupplier taskSupplier, ObjectMapper mapper, CountDownLatch countDownLatch, int pollTimeMillis) {
        this.factory = factory;
        this.taskSupplier = taskSupplier;
        this.mapper = mapper;
        this.waitForMainLoopCalled = countDownLatch;
        this.pollTimeMillis = pollTimeMillis;
        this.cancelledTasks = new ConcurrentHashMap<>();
        Signal.handle(new Signal("TERM"), signal -> {
            exit();
            cancel(null, true);
            ofNullable(loopThread).ifPresent(Thread::interrupt); // for interrupting poll
        });
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
                cancelledTasks.put(cancelEvent.taskId, cancelEvent.requeue);
            }
        }));
    }

    public Integer call()  {
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
        Task task;
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

    void handle(Task task) {
        loopThread = Thread.currentThread();
        currentTask.set(task);
        if (cancelledTasks.get(currentTask.get().id) != null) {
            logger.info("cancelled task {} not executed", currentTask.get().id);
            taskSupplier.canceled(currentTask.get(), cancelledTasks.remove(currentTask.get().id));
        } else {
            try {
                Callable<?> taskFn = TaskFactoryHelper.createTaskCallable(factory, currentTask.get().name, currentTask.get(),
                        currentTask.get().progress(taskSupplier::progress));
                currentTaskReference.set(taskFn);
                logger.info("running task {}", currentTask.get());
                taskSupplier.progress(currentTask.get().id, 0);
                byte[] result = mapper.writeValueAsBytes(taskFn.call());
                taskSupplier.result(currentTask.get().id, result);
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
                currentTaskReference.set(null);
                currentTask.set(null);
            }
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
        taskSupplier.close();
        ofNullable(loopThread).ifPresent(Thread::interrupt);
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
