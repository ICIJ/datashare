package org.icij.datashare.tasks;

import static org.icij.datashare.com.PulsarStatusHandler.STATUS_TOPIC;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;
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
    private static final String BATCH_DL_TOPIC = "batch-download";

    protected final PulsarClient pulsarClient;
    protected final Consumer<byte[]> taskConsumer;
    protected final Producer<byte[]> statusUpdater;

    @Inject
    public BatchDownloadLoop(PropertiesProvider propertiesProvider, PulsarClient pulsarClient,
                             TaskFactory factory)
        throws PulsarClientException {
        this.pulsarClient = pulsarClient;
        this.taskConsumer = pulsarClient.newConsumer()
            // TODO: set dead letter, retry letter, neg ack + timeout etc etc
            .topic(BATCH_DL_TOPIC)
            .subscriptionName(BATCH_DL_TOPIC + "-datashare")
            .consumerName("worker-" + BATCH_DL_TOPIC + "-" + UUID.randomUUID())
            .subscriptionType(SubscriptionType.Shared)
            .negativeAckRedeliveryDelay(10, TimeUnit.SECONDS)
            .subscribe();
        this.statusUpdater = this.pulsarClient
            .newProducer()
            .topic(STATUS_TOPIC)
            .create();
        ttlHour = Integer.parseInt(
            propertiesProvider.get(DatashareCliOptions.BATCH_DOWNLOAD_ZIP_TTL)
                .orElse(DEFAULT_BATCH_DOWNLOAD_ZIP_TTL));
        this.factory = factory;
    }

    public <V> void run() {
        String workerName = this.taskConsumer.getConsumerName();

        while (true) {
            if (Thread.interrupted()) {
                logger.info("Batck download loop was interrupted, exiting...");
                return;
            }
            logger.info("{} waiting for work...", workerName);
            Message<byte[]> msg;
            try {
                msg = this.taskConsumer.receive();
                LanguageAgnosticTaskView<V> task =
                    MAPPER.readValue(msg.getData(), LanguageAgnosticTaskView.class);
                try {
                    logger.info("{} received task {}", workerName, task.getName());
                    LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<File> update =
                        new LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<File>(
                            task.getName()).withState(TaskViewInterface.State.RUNNING);
                    this.statusUpdater.send(MAPPER.writeValueAsBytes(update));
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
                    this.taskConsumer.acknowledge(msg);
                } catch (Exception ex) {
                    // TODO: recover from recoverable error + neg ack
                    LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<File> update =
                        new LanguageAgnosticTaskView.LanguageAgnosticTaskViewUpdate<File>(
                            task.getName()).withState(TaskViewInterface.State.RUNNING);
                    this.updateTaskStatus(workerName, update);
                    logger.error("{} - error: {}", workerName, ex);
                    try {
                        this.taskConsumer.acknowledge(msg);
                    } catch (PulsarClientException e) {
                        logger.error("{} - failed to acknowledge: {}", workerName, e);
                        throw new RuntimeException(e);
                    }
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
            this.statusUpdater.send(MAPPER.writeValueAsBytes(initialUpdate));
        } catch (PulsarClientException e) {
            logger.error("{} - failed to broadcast update for {}", workerName, initialUpdate.name);
            throw new RuntimeException(e);
        } catch (JsonProcessingException e) {
            logger.error("{} - failed to serialize status update for {}", workerName,
                initialUpdate.name);
            throw new RuntimeException(e);
        }
    }

    public void close() throws IOException {
        this.taskConsumer.close();
        this.statusUpdater.close();
    }

    public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int delaySeconds) {
        return new BatchDownloadCleaner(downloadDir, delaySeconds);
    }
}
