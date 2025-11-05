package org.icij.datashare.tasks;

import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TaskGroup(TaskGroupType.Java)
public class SleepTask implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(SleepTask.class);

    private final Function<Double, Void> progressCallback;
    private final int durationS;

    @Inject
    public SleepTask(@Assisted Task<Long> taskView, @Assisted final Function<Double, Void> progressCallback) {
        this.durationS = (int) taskView.args.get("durationS");
        this.progressCallback = progressCallback;
    }

    @Override
    public Integer call() throws Exception {
        logger.info("starting sleeping...");
        IntStream.range(0, durationS).boxed().forEach(
            rethrowConsumer(i -> {
                Thread.sleep(1000);
                logger.info("slept for {} secs", i);
                progressCallback.apply((double) i / (double) durationS);
            })
        );
        return durationS;
    }
}
