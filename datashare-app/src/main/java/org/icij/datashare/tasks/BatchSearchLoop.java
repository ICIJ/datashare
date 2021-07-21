package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Optional.ofNullable;

public class BatchSearchLoop {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    final BlockingQueue<String> batchSearchQueue;
    private final TaskFactory factory;
    final AtomicReference<BatchSearchRunner> currentBatchSearchRunner = new AtomicReference<>();
    public static final String POISON = "poison";
    private final BatchSearchRepository repository;

    @Inject
    public BatchSearchLoop(BatchSearchRepository batchSearchRepository, BlockingQueue<String> batchSearchQueue, TaskFactory factory) {
        this.repository = batchSearchRepository;
        this.batchSearchQueue = batchSearchQueue;
        this.factory = factory;
        Signal.handle(new Signal("TERM"), signal -> {
            batchSearchQueue.add(POISON);
            ofNullable(currentBatchSearchRunner.get()).ifPresent(BatchSearchRunner::cancel);
        });
    }

    public void run() {
        logger.info("Datashare running in batch mode. Waiting batch from ds:batchsearch.queue ({})", batchSearchQueue.getClass());
        String currentBatchId = null;
        while (! POISON.equals(currentBatchId)) {
            try {
                currentBatchId = batchSearchQueue.poll(60, TimeUnit.SECONDS);
                if (currentBatchId != null && !POISON.equals(currentBatchId)) {
                    BatchSearch batchSearch = repository.get(currentBatchId);
                    if (batchSearch.state == BatchSearchRecord.State.QUEUED) {
                        currentBatchSearchRunner.set(factory.createBatchSearchRunner(batchSearch));
                        currentBatchSearchRunner.get().call();
                        currentBatchSearchRunner.set(null);
                    } else {
                        logger.warn("batch search {} not ran because in state {}", batchSearch.uuid, batchSearch.state);
                    }
                }
            } catch(JooqBatchSearchRepository.BatchNotFoundException notFound){
                logger.warn("batch was not executed : {}", notFound.toString());
            } catch(InterruptedException e) {
                throw new RuntimeException(e);
            } catch(Exception ex){
                logger.error("error during main loop", ex);
            }
        }
        logger.info("exiting main loop");
    }

    public Integer requeueDatabaseBatches() {
        List<String> batchSearchIds = repository.getQueued();
        logger.info("found {} queued batch searches in database", batchSearchIds.size());
        batchSearchQueue.addAll(batchSearchIds);
        return batchSearchIds.size();
    }

    public void enqueuePoison() {batchSearchQueue.add(POISON);}

    public void close() throws IOException {
        ofNullable(currentBatchSearchRunner.get()).ifPresent(batchSearchRunner -> {
            try {
                batchSearchRunner.close();
            } catch (IOException e) {
                logger.error("error while closing batchSearchRunner", e);
            }
        });
        if (batchSearchQueue instanceof Closeable) {
            ((Closeable) batchSearchQueue).close();
        }
    }
}
