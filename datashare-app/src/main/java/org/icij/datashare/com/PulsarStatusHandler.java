package org.icij.datashare.com;

import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

import com.google.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;
import org.icij.datashare.tasks.LanguageAgnosticTaskView;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class PulsarStatusHandler implements AutoCloseable, Runnable {
    private final static Logger LOGGER = LoggerFactory.getLogger(PulsarStatusHandler.class);
    public static final String STATUS_TOPIC = "task-status";
    public static final String TASK_TOPIC = "task";
    private static final String SUBSCRIPTION_NAME = "task-status-handler";

    private final PulsarClient pulsarClient;
    private final RocksDB rocksDB;

    private final Consumer<byte[]> consumer;

    @Inject
    public PulsarStatusHandler(PulsarClient pulsarClient, RocksDB rocksDB)
        throws PulsarClientException {
        this.pulsarClient = pulsarClient;
        this.rocksDB = rocksDB;
        // TODO: set this up properly: see https://pulsar.apache.org/docs/2.11.x/client-libraries-java/#configure-consumer
        this.consumer = this.pulsarClient.newConsumer()
            // TODO: handle the namesapce...
            .topics(List.of(STATUS_TOPIC, TASK_TOPIC))
            .subscriptionName(SUBSCRIPTION_NAME)
            .subscriptionType(SubscriptionType.Exclusive)
            .subscribe();
    }

    static Object lazy(Callable<?> callable) {
        return new Object() {
            @Override
            public String toString() {
                try {
                    Object result = callable.call();
                    if (result == null) {
                        return "null";
                    }

                    return result.toString();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    @Override
    public void run() {
        while (true) {
            if (Thread.interrupted()) {
                LOGGER.info("Pulsar status handler has been interrupted, exiting...");
                return;
            }
            // TODO: improve error handling check BatchSearchLoop.run or BatchDownloadLoop.run
            // TODO: run this asynchronously ?
            Message<byte[]> msg;
            String action = "";
            try {
                msg = consumer.receive();
            } catch (PulsarClientException e) {
                LOGGER.error("Error while receiving message from status bus: {}", e.toString());
                throw new RuntimeException(e);
            }
            String topicName = msg.getTopicName();
            try {
                if (topicName.endsWith(TASK_TOPIC)) {
                    action = "creation";
                    createDBTask(msg);
                } else if (topicName.endsWith(STATUS_TOPIC)) {
                    action = "update";
                    updateDBTask(msg);
                } else {
                    throw new IllegalStateException("Invalid status topic \"" + topicName + "\"");
                }
            } catch (IOException e) {
                // TODO: we ignore the serialization exceptions otherwise malformed messages make
                // everything crash, not sure it's a good idea ^^
                LOGGER.error("Serialization error at task {}: {}", action, e.toString());
                consumer.negativeAcknowledge(msg);
            } catch (RocksDBException e) {
                LOGGER.error("RockDB error at task {}: {}", action, e.toString());
                throw new RuntimeException(e);
            }
        }
    }

    private void createDBTask(Message<byte[]> msg) throws IOException, RocksDBException {
        LanguageAgnosticTaskView task =
            MAPPER.readValue(msg.getValue(), LanguageAgnosticTaskView.class);
        LOGGER.info("Received new task: {}", lazy(() -> MAPPER.writeValueAsString(task)));
        byte[] key = task.name.getBytes();
        this.rocksDB.put(key, MAPPER.writeValueAsBytes(task));
        consumer.acknowledge(msg);
    }

    private void updateDBTask(Message<byte[]> msg) throws IOException, RocksDBException {
        LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate statusUpdate = MAPPER.readValue(
            msg.getValue(), LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate.class);
        // TODO: this should be debug
        LOGGER.info("Received status update: {}",
            lazy(() -> MAPPER.writeValueAsString(statusUpdate)));
        this.updateRockDBStatus(statusUpdate);
        consumer.acknowledge(msg);
    }

    private <V> void updateRockDBStatus(
        LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<V> statusUpdate
    )
        throws RocksDBException, IOException {
        byte[] key = statusUpdate.name.getBytes();
        byte[] taskBytes = this.rocksDB.get(key);
        if (taskBytes != null) {
            LanguageAgnosticTaskView<V> view = MAPPER.readValue(
                taskBytes, LanguageAgnosticTaskView.class);
            // TODO: improve the status handling here:
            //  - we can't set some attribute to null...
            if (statusUpdate.state != null) {
                view.setState(statusUpdate.state);
            }
            if (statusUpdate.result != null) {
                view.setResult(statusUpdate.result);
            }
            if (statusUpdate.error != null) {
                view.setError(statusUpdate.error);
            }
            if (statusUpdate.progress != null) {
                view.setProgress(statusUpdate.progress);
            }
            if (statusUpdate.retries != null) {
                view.setRetries(statusUpdate.retries);
            }
            if (view.maxRetries < 0 && statusUpdate.maxRetries > 0) {
                view.setMaxRetries(statusUpdate.maxRetries);
            }
            this.rocksDB.put(key, MAPPER.writeValueAsBytes(view));
        } else {
            // TODO: do something smarter here
            LOGGER.error("Couldn't find {} in the DB, creating task !", statusUpdate.name);
        }
    }

    @Override
    public void close() throws Exception {
        this.consumer.close();
    }

}
