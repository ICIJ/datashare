package org.icij.datashare.tasks;

import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.List;
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
    private final List<Integer> sleepDurations;

    @Inject
    public SleepTask(@Assisted Task<Long> taskView, @Assisted final Function<Double, Void> progressCallback) {
        this.sleepDurations = (List<Integer>) taskView.args.get("sleepDurations");
        this.progressCallback = progressCallback;
    }

    @Override
    public Integer call() throws Exception {
        logger.info("starting sleeping...");
        int totalDurationS = sleepDurations.stream().reduce(Integer::sum).orElse(0);

        int currentSleepDurationS = 0;
        for (Integer sleepDurationS : sleepDurations) {
            currentSleepDurationS = accumulator(totalDurationS, currentSleepDurationS, sleepDurationS);
        }
        return totalDurationS;
    }

    private Integer accumulator(Integer totalSleepDurationS, Integer currentSleepDurationS, Integer sleepDurationS)
        throws InterruptedException {
        IntStream.range(0, sleepDurationS).boxed().forEach(
            rethrowConsumer(i -> {
                Thread.sleep(1000);
                logger.info("slept for {} secs", i);
                progressCallback.apply((double) currentSleepDurationS + i + 1 / (double) totalSleepDurationS);
            })
        );
        return currentSleepDurationS + sleepDurationS;
    }
}
