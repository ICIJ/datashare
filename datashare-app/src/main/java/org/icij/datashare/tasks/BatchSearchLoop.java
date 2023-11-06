package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Optional.ofNullable;

public class BatchSearchLoop {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    final BlockingQueue<String> batchSearchQueue;
    private final TaskFactory factory;
    final AtomicReference<BatchSearchRunner> currentBatchSearchRunner = new AtomicReference<>();
    private static final String POISON = "poison";
    private final BatchSearchRepository repository;
    private final CountDownLatch waitForMainLoopCalled; // for tests only
    private volatile boolean exitAsked = false;
    private volatile Thread loopThread;

    @Inject
    public BatchSearchLoop(BatchSearchRepository batchSearchRepository, BlockingQueue<String> batchSearchQueue, TaskFactory factory) {
        this(batchSearchRepository, batchSearchQueue, factory, new CountDownLatch(1));
    }

    BatchSearchLoop(BatchSearchRepository repository, BlockingQueue<String> batchSearchQueue, TaskFactory factory, CountDownLatch countDownLatch) {
        this.repository = repository;
        this.batchSearchQueue = batchSearchQueue;
        this.factory = factory;
        this.waitForMainLoopCalled = countDownLatch;
        Signal.handle(new Signal("TERM"), signal -> {
            exitAsked = true;
            ofNullable(currentBatchSearchRunner.get()).ifPresent(BatchSearchRunner::cancel);
            ofNullable(loopThread).ifPresent(Thread::interrupt); // for interrupting poll
        });
    }

    public void run() {
        logger.info("Datashare running in batch mode. Waiting batch from ds:batchsearch:queue ({})", batchSearchQueue.getClass());
        String currentBatchId = null;
        waitForMainLoopCalled.countDown();
        loopThread = Thread.currentThread();
        while (!POISON.equals(currentBatchId) && !exitAsked) {
            try {
                currentBatchId = batchSearchQueue.poll(60, TimeUnit.SECONDS);
                if (currentBatchId != null && !POISON.equals(currentBatchId)) {
                    BatchSearch batchSearch = repository.get(currentBatchId);
                    if (batchSearch.state == BatchSearchRecord.State.QUEUED) {
                        repository.setState(batchSearch.uuid, BatchSearchRecord.State.RUNNING);
                        currentBatchSearchRunner.set(factory.createBatchSearchRunner(batchSearch, repository::saveResults));
                        currentBatchSearchRunner.get().call();
                        currentBatchSearchRunner.set(null);
                        repository.setState(batchSearch.uuid, BatchSearchRecord.State.SUCCESS);
                    } else {
                        logger.warn("batch search {} not ran because in state {}", batchSearch.uuid, batchSearch.state);
                    }
                }
            } catch (JooqBatchSearchRepository.BatchNotFoundException notFound) {
                logger.warn("batch was not executed : {}", notFound.toString());
            } catch (BatchSearchRunner.CancelException cancelEx) {
                Objects.requireNonNull(currentBatchId,"BatchSearch Id cannot be null");
                logger.info("cancelling batch search {}", currentBatchId);
                batchSearchQueue.offer(currentBatchId);
                repository.reset(currentBatchId);
            } catch (SearchException sex) {
                logger.error("exception while running batch " + currentBatchId, sex);
                repository.setState(currentBatchId, sex);
            } catch (InterruptedException e) {
                logger.warn("main loop interrupted");
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

    public void enqueuePoison() {
        batchSearchQueue.add(POISON);
    }

    public void close() throws IOException {
        logger.info("{}", batchSearchQueue.getClass());
        if (batchSearchQueue instanceof Closeable) {
            logger.info("closing batch search queue");
            ((Closeable) batchSearchQueue).close();
        }
        repository.close();
    }
}
