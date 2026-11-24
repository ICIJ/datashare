package org.icij.datashare.tasks;

import java.util.List;
import org.icij.datashare.FatalStageException;
import static org.icij.datashare.asynctasks.Interruptions.causedByInterrupt;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.slf4j.Logger;

/**
 * The loop of a stage over the items of its batch, written once so that every stage fails a document the same way.
 * A stage gives the work for one item and returns what it did; it does not catch, count failures or look at the
 * cancellation. For each item:
 * <ul>
 *   <li>once the run is cancelled, the loop ends;</li>
 *   <li>a {@link FatalStageException} stops the run: it is rethrown (an {@link Error} is not caught either);</li>
 *   <li>a cancellation ends the batch: the interrupt flag is set back, which {@code ctx.isCancelled()} reads, and
 *   nothing is counted. It is an {@link InterruptedException}, or one wrapped by a client (Redisson, the
 *   Elasticsearch rest client) that also re-interrupted the thread. A wrapped one on a thread that was not
 *   interrupted is a failure of the item, not a cancellation;</li>
 *   <li>any other exception is one failed item: logged with the task and the item, counted, and the batch goes on.</li>
 * </ul>
 * One instance per call of {@code process}: it is bound to the logger of the stage and to the context of the batch.
 */
final class StageItems {
    private final Logger logger;
    private final StageContext<?> ctx;

    StageItems(Logger logger, StageContext<?> ctx) {
        this.logger = logger;
        this.ctx = ctx;
    }

    @FunctionalInterface
    interface Work<T> {
        StageResult process(T item) throws Exception;
    }

    <T> StageResult forEach(List<T> items, Work<T> work) {
        StageResult result = StageResult.EMPTY;
        for (T item : items) {
            if (ctx.isCancelled()) {
                break;
            }
            try {
                result = result.plus(work.process(item));
            } catch (Exception failure) {
                result = result.plus(onFailure(item, failure));
            }
        }
        return result;
    }

    private StageResult onFailure(Object item, Exception failure) {
        if (failure instanceof FatalStageException fatal) {
            throw fatal;
        }
        if (failure instanceof InterruptedException || (causedByInterrupt(failure) && ctx.isCancelled())) {
            Thread.currentThread().interrupt();
            logger.debug("cancelled while working on {} (task {})", item, ctx.taskId(), failure);
            return StageResult.EMPTY;
        }
        logger.error("failed on {} (task {})", item, ctx.taskId(), failure);
        return new StageResult(0, 0, 0, 1);
    }
}
