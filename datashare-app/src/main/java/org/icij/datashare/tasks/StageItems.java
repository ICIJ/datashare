package org.icij.datashare.tasks;

import java.util.List;
import org.icij.datashare.FatalStageException;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.slf4j.Logger;

final class StageItems {
    private StageItems() {}

    @FunctionalInterface
    interface Work<T> {
        StageResult process(T item) throws Exception;
    }

    static <T> StageResult forEach(Logger logger, StageContext<?> ctx, List<T> items, Work<T> work) {
        StageResult result = StageResult.EMPTY;
        for (T item : items) {
            if (ctx.isCancelled()) {
                break;
            }
            try {
                result = result.plus(work.process(item));
            } catch (Exception failure) {
                result = result.plus(onFailure(logger, ctx, item, failure));
            }
        }
        return result;
    }

    private static StageResult onFailure(Logger logger, StageContext<?> ctx, Object item, Exception failure) {
        if (failure instanceof FatalStageException fatal) {
            throw fatal;
        }
        if (failure instanceof InterruptedException || (StageTask.causedByInterrupt(failure) && ctx.isCancelled())) {
            Thread.currentThread().interrupt();
            logger.debug("cancelled while working on {} (task {})", item, ctx.taskId(), failure);
            return StageResult.EMPTY;
        }
        logger.error("failed on {} (task {})", item, ctx.taskId(), failure);
        return new StageResult(0, 0, 0, 1);
    }
}
