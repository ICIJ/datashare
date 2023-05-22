package org.icij.datashare.com;


import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

import com.google.inject.Inject;
import java.io.IOException;
import java.util.concurrent.Callable;
import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.jms.ActiveMQJMSClient;
import org.apache.activemq.artemis.api.jms.JMSFactoryType;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnectorFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQDestination;
import org.icij.datashare.tasks.LanguageAgnosticTaskView;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class TaskStatusHandler implements AutoCloseable, Runnable {
    private final static Logger LOGGER = LoggerFactory.getLogger(TaskStatusHandler.class);

    public static final String TASK_QUEUE = "tasks";
    public static final String STATUS_QUEUE = TASK_QUEUE + ".status";
    public static final String CREATE_QUEUE = TASK_QUEUE + ".creation";


    private final RocksDB rocksDB;

    private final MessageConsumer consumer
        ;

    @Inject
    public TaskStatusHandler(RocksDB rocksDB) throws JMSException {
        // Listen on the full task queue, we choose a queue here rather than a topic which implies
        // a single consumer
        // As the handler starts in its own thread it has to create a new session
        TransportConfiguration transportConfiguration =
            new TransportConfiguration(NettyConnectorFactory.class.getName());

        // TODO: handle the autoclosable side of things...
        ConnectionFactory connectionFactory = ActiveMQJMSClient.createConnectionFactoryWithoutHA(
            JMSFactoryType.CF, transportConfiguration);
        Connection conn = connectionFactory.createConnection();
        conn.start();
        Session session = conn.createSession();
        Queue queue = session.createQueue(TASK_QUEUE + ".#");
        this.consumer = session.createConsumer(queue);
        this.rocksDB = rocksDB;
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
                LOGGER.info("task status handler has been interrupted, exiting...");
                return;
            }
            // TODO: improve error handling check BatchSearchLoop.run or BatchDownloadLoop.run
            // TODO: run this asynchronously ?
            BytesMessage msg;
            ActiveMQDestination destination;
            String action = "";
            try {
                Message receivedMsg = consumer.receive();
                if (!(receivedMsg instanceof BytesMessage)) {
                    String e =
                        "Unknown message of unknown type" + receivedMsg.getClass() + receivedMsg;
                    LOGGER.error(e);
                    throw new RuntimeException(e);
                } else {
                    msg = (BytesMessage) receivedMsg;
                }
                destination = (ActiveMQDestination) msg.getJMSDestination();
            } catch (JMSException e) {
                LOGGER.error("Error while receiving message from status bus: {}", e.toString());
                throw new RuntimeException(e);
            }
            String queueName = destination.getName();
            try {
                if (queueName.equals(CREATE_QUEUE)) {
                    action = "creation";
                    createDBTask(msg.getBody(byte[].class));
                    msg.acknowledge();
                } else if (queueName.equals(STATUS_QUEUE)) {
                    action = "update";
                    updateDBTask(msg.getBody(byte[].class));
                    msg.acknowledge();
                } else {
                    throw new IllegalStateException("Invalid status topic \"" + queueName + "\"");
                }
            } catch (IOException e) {
                // TODO: we ignore the serialization exceptions otherwise malformed messages make
                // everything crash, not sure it's a good idea ^^
                LOGGER.error("Serialization error at task {}: {}", action, e.toString());
            } catch (RocksDBException e) {
                LOGGER.error("RockDB error at task {}: {}", action, e.toString());
                throw new RuntimeException(e);
            } catch (JMSException e) {
                LOGGER.error("Failed to acknowledge message");
                throw new RuntimeException(e);
            }
        }
    }

    private void createDBTask(byte[] msg) throws IOException, RocksDBException {
        LanguageAgnosticTaskView task = MAPPER.readValue(msg, LanguageAgnosticTaskView.class);
        LOGGER.info("Received new task: {}", lazy(() -> MAPPER.writeValueAsString(task)));
        byte[] key = task.name.getBytes();
        this.rocksDB.put(key, MAPPER.writeValueAsBytes(task));
    }

    private void updateDBTask(byte[] msg) throws IOException, RocksDBException {
        LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate statusUpdate = MAPPER.readValue(
            msg, LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate.class);
        // TODO: this should be debug
        LOGGER.info("Received status update: {}",
            lazy(() -> MAPPER.writeValueAsString(statusUpdate)));
        this.updateRockDBStatus(statusUpdate);
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
