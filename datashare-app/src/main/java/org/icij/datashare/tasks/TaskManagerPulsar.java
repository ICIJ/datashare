package org.icij.datashare.tasks;

import static org.icij.datashare.com.PulsarStatusHandler.STATUS_TOPIC;
import static org.icij.datashare.com.PulsarStatusHandler.TASK_TOPIC;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.icij.datashare.user.User;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskManagerPulsar implements TaskManager, AutoCloseable {
    protected final PulsarClient client;
    protected final Hashtable<String, Producer<byte[]>> producers;
    protected final Producer<byte[]> taskCreator;
    protected final Producer<byte[]> statusUpdater;
    protected final RocksDB rocksDB;

    private final static Logger LOGGER = LoggerFactory.getLogger(TaskManagerPulsar.class);

    @Inject
    public TaskManagerPulsar(RocksDB rocksDB, PulsarClient pulsarClient)
        throws PulsarClientException {
        this.client = pulsarClient;
        this.producers = new Hashtable<>();
        this.rocksDB = rocksDB;
        this.taskCreator = this.client
            .newProducer()
            .topic(TASK_TOPIC)
            .create();
        this.statusUpdater = this.client
            .newProducer()
            .topic(STATUS_TOPIC)
            .create();
    }

    @Override
    public <V> Void save(TaskViewInterface<V> task) {
        byte[] key = task.getName().getBytes();
        byte[] value;
        try {
            value = MAPPER.writeValueAsBytes(task);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        try {
            this.rocksDB.put(key, value);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public TaskViewInterface<?> get(String id) {
        byte[] taskKey = id.getBytes();
        byte[] taskBytes;
        try {
            taskBytes = this.rocksDB.get(taskKey);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
        if (taskBytes != null) {
            try {
                return MAPPER.readValue(taskBytes, LanguageAgnosticTaskView.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    @Override
    public List<TaskViewInterface<?>> get() {
        // TODO: it would be nice to provide a stream/iterator
        List<TaskViewInterface<?>> tasks = new ArrayList<>();
        try (final RocksIterator iterator = this.rocksDB.newIterator()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                try {
                    iterator.status();
                } catch (RocksDBException e) {
                    throw new RuntimeException(e);
                }
                try {
                    tasks.add(MAPPER.readValue(iterator.value(), LanguageAgnosticTaskView.class));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return tasks;
    }

    @Override
    public List<TaskViewInterface<?>> clearDoneTasks() {
        // TODO: nice error handling in case of partial clearing
        List<TaskViewInterface<?>> cleared = new ArrayList<>();
        byte[] taskAsBytes = null;
        try (final RocksIterator iterator = this.rocksDB.newIterator()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                taskAsBytes = iterator.value();
                TaskView<?> task = MAPPER.readValue(taskAsBytes, TaskView.class);
                if (task.getState() == TaskView.State.RUNNING) {
                    continue;
                }
                this.rocksDB.delete(iterator.key());
                cleared.add(task);
            }
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            LOGGER.error("Failed to convert {} into a {}", taskAsBytes.toString(), LanguageAgnosticTaskView.class.getName());
        }
        return cleared;
    }

    @Override
    public TaskView<?> clearTask(String taskName) {
        byte[] taskKey = taskName.getBytes();
        // TODO: decide if we can clear task whatever the status
        try {
            this.rocksDB.delete(taskKey);
        } catch (RocksDBException e) {
            if (e.getStatus().getCode() == Status.Code.NotFound) {
                // TODO: do smart things here if needed
                LOGGER.debug("Task %s does not exist, it was probably not running");
            }
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public TaskView<Void> startTask(Runnable task) {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public <V> LanguageAgnosticTaskView<V> startTask(User user, String taskType,
                                                     Map<String, Object> inputs) {
        // TODO, do something more robust here.... the task type should be in the TaskView...
        // TODO: configure the producer properly here...
        Producer<byte[]> prod;
        String taskId = taskType + "-" + UUID.randomUUID();
        LanguageAgnosticTaskView<V> task = new LanguageAgnosticTaskView<>(taskType, taskId, user, inputs);
        this.createTask(task);
        synchronized (this.producers) {
            try {
                prod = Optional.ofNullable(this.producers.get(taskType))
                    .orElse(client.newProducer().topic(taskType).create());
            } catch (PulsarClientException e) {
                throw new RuntimeException(e);
            }
            this.producers.put(taskType, prod);
        }
        task.setState(TaskViewInterface.State.QUEUING);
        this.updateTask(task);
        try {
            LOGGER.info("Broadcasting task {} to pulsar bus", task.name);
            // TODO: could be done in an async fashion
            prod.send(MAPPER.writeValueAsBytes(task));
        } catch (PulsarClientException | JsonProcessingException e) {
            LOGGER.error("Failed to broadcast task {}, reverting queue status", task.name);
            // TODO put more details in the error
            task.setError(e.getMessage());
            task.setState(TaskViewInterface.State.ERROR);
            this.updateTask(task);
            throw new RuntimeException(e);
        }
        return task;
    }

    protected <V> void updateTask(LanguageAgnosticTaskView<V> task) {
        try {
            LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<V> update = task.asUpdate();
            LOGGER.info("Trying to update task to {}", update);
            this.statusUpdater.send(MAPPER.writeValueAsBytes(update));
        } catch (PulsarClientException e) {
            LOGGER.error("Failed to post status update for task {}: {}", task.name, e);
            throw new RuntimeException(e);
        } catch (JsonProcessingException e) {
            LOGGER.error("Couldn't convert task update {} to JSON: {}", task, e);
            throw new RuntimeException(e);
        }
    }

    protected <V> void createTask(LanguageAgnosticTaskView<V> task) {
        try {
            LOGGER.info("Trying to update task to {}", task);
            this.taskCreator.send(MAPPER.writeValueAsBytes(task));
        } catch (PulsarClientException e) {
            LOGGER.error("Failed to create task {}: {}", task.name, e);
            throw new RuntimeException(e);
        } catch (JsonProcessingException e) {
            LOGGER.error("Couldn't convert task {} to JSON: {}", task, e);
            throw new RuntimeException(e);
        }
    }


    @Override
    public <V> TaskView<V> startTask(Callable<V> task, Runnable callback) {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public <V> TaskView<V> startTask(Callable<V> task, Map<String, Object> properties) {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public <V> TaskView<V> startTask(Callable<V> task) {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public boolean stopTask(String taskName) {
        // TODO: broadcast cancellation signal to workers
        this.clearTask(taskName);
        // TODO: not sure in which case this should return false
        return true;
    }

    @Override
    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) {
        throw new IllegalStateException("not implemented");
    }

    @Override
    public void close() {
        this.producers.values()
            .forEach(producer -> {
                try {
                    producer.close();
                } catch (PulsarClientException e) {
                    throw new RuntimeException(e);
                }
            });
        try {
            this.taskCreator.close();
        } catch (PulsarClientException e) {
            throw new RuntimeException(e);
        }
        try {
            this.statusUpdater.close();
        } catch (PulsarClientException e) {
            throw new RuntimeException(e);
        }
    }
}
