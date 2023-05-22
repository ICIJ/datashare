package org.icij.datashare.tasks;

import static org.icij.datashare.com.TaskStatusHandler.STATUS_QUEUE;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.cli.DatashareCliOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BatchDownloadLoop {
    private final Path DOWNLOAD_DIR = Paths.get(System.getProperty("user.dir")).resolve("app/tmp");
    private final static String DEFAULT_BATCH_DOWNLOAD_ZIP_TTL = "24";
    private final int ttlHour;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final TaskFactory factory;
    private static final String BATCH_DL_QUEUE = "batch-download";

    protected final Connection connection;
    protected final Session session;

    protected final MessageConsumer taskConsumer;
    protected final MessageProducer statusUpdater;

    @Inject
    public BatchDownloadLoop(PropertiesProvider propertiesProvider, Connection connection,
                             TaskFactory factory) throws JMSException {
        this.connection = connection;
        this.session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        // TODO: set dead letter, retry letter, neg ack + timeout etc etc
        this.taskConsumer = session.createConsumer(session.createQueue(BATCH_DL_QUEUE));
        // TODO: set dead letter, retry letter, neg ack + timeout etc etc
        this.statusUpdater = session.createProducer(session.createQueue(STATUS_QUEUE));
        ttlHour = Integer.parseInt(
            propertiesProvider.get(DatashareCliOptions.BATCH_DOWNLOAD_ZIP_TTL)
                .orElse(DEFAULT_BATCH_DOWNLOAD_ZIP_TTL));
        this.factory = factory;
    }

    public <V> void run() {
        String workerName;
        try {
            workerName = this.connection.getClientID();
        } catch (JMSException e) {
            throw new RuntimeException(e);
        }

        while (true) {
            if (Thread.interrupted()) {
                logger.info("Batck download loop was interrupted, exiting...");
                return;
            }
            BytesMessage msg;
            try {
                msg = (BytesMessage) this.taskConsumer.receive();
                byte[] msgBytes = {};
                msg.readBytes(msgBytes);
                LanguageAgnosticTaskView<V> task =
                    MAPPER.readValue(msgBytes, LanguageAgnosticTaskView.class);
                try {
                    logger.info("received task {}", task.getName());
                    LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<File> update =
                        new LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<File>(
                            task.getName()).withState(TaskViewInterface.State.RUNNING);
                    BytesMessage updatedMsg = this.session.createBytesMessage();
                    updatedMsg.writeBytes(MAPPER.writeValueAsBytes(update));
                    this.statusUpdater.send(updatedMsg);
                    // TODO: improve this mess
                    BatchDownload batchDownload =
                        MAPPER.readValue(MAPPER.writeValueAsBytes(task.inputs),
                            BatchDownload.class);
                    createDownloadCleaner(DOWNLOAD_DIR, ttlHour).run();
                    MonitorableFutureTask<File> fileMonitorableFutureTask =
                        new MonitorableFutureTask<>(
                            factory.createDownloadRunner(batchDownload,
                                t -> this.broadcastProgressUpdate(workerName, update, t)));
                    fileMonitorableFutureTask.run();
                    logger.info("{} completed task {}", workerName, task.getName());
                    update.withResult(fileMonitorableFutureTask.get())
                        .withProgress(100)
                        .withState(TaskViewInterface.State.DONE);
                    this.updateTaskStatus(workerName, update);
                    msg.acknowledge();
                } catch (Exception ex) {
                    // TODO: recover from recoverable error + neg ack
                    LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<File> update =
                        new LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<File>(
                            task.getName()).withState(TaskViewInterface.State.RUNNING);
                    this.updateTaskStatus(workerName, update);
                    logger.error("{} - error: {}", workerName, ex);
                    msg.acknowledge();
                }
            } catch (Exception ex) {
                logger.info("{} error while receiving or deserializing task {}", workerName, ex);
            }
        }
    }

    protected <V, W> Void broadcastProgressUpdate(
        String workerName,
        LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<V> initialUpdate,
        TaskViewInterface<W> task) {
        initialUpdate.withProgress(task.getProgress());
        updateTaskStatus(workerName, initialUpdate);
        return null;
    }

    private <V> void updateTaskStatus(
        String workerName,
        LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<V> initialUpdate
    ) {
        try {
            BytesMessage statusMsg = this.session.createBytesMessage();
            statusMsg.writeBytes(MAPPER.writeValueAsBytes(initialUpdate));
            this.statusUpdater.send(statusMsg);
        } catch (JMSException e) {
            logger.error("{} - failed to broadcast update for {}", workerName, initialUpdate.name);
            throw new RuntimeException(e);
        } catch (JsonProcessingException e) {
            logger.error("{} - failed to serialize status update for {}", workerName,
                initialUpdate.name);
            throw new RuntimeException(e);
        }
    }

    public void close() throws IOException, JMSException {
        this.taskConsumer.close();
    }

    public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int delaySeconds) {
        return new BatchDownloadCleaner(downloadDir, delaySeconds);
    }
}
