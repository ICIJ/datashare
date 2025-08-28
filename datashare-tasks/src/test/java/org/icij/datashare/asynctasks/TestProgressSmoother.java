package org.icij.datashare.asynctasks;

import static org.fest.assertions.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.time.DatashareTime;
import org.junit.Rule;
import org.junit.Test;

public class TestProgressSmoother {
    @Rule
    public DatashareTimeRule time = new DatashareTimeRule();

    @Test
    public void test_should_smooth_progress() {
        float minIntervalS = 10;
        ProgressRecorder recorder = new ProgressRecorder();
        ProgressSmoother smoothedProgress = new ProgressSmoother(recorder, minIntervalS);
        String t0 = "task0";
        String t1 = "task1";

        smoothedProgress.apply(t0, 0.10);
        smoothedProgress.apply(t1, 0.15);
        DatashareTime.getInstance().addMilliseconds(5 * 1000);
        smoothedProgress.apply(t0, 0.2);
        smoothedProgress.apply(t1, 0.25);
        DatashareTime.getInstance().addMilliseconds(6 * 1000);
        smoothedProgress.apply(t0, 0.70);
        smoothedProgress.apply(t1, 0.75);
        smoothedProgress.apply(t0, 0.95);
        smoothedProgress.apply(t1, 1.0);
        DatashareTime.getInstance().addMilliseconds(1);
        smoothedProgress.apply(t0, 1.0);

        List<Double> expected0 = List.of(0.1, 0.7, 1.);
        List<Double> expected1 = List.of(0.15, 0.75, 1.);
        assertThat(recorder.getPublished(t0)).isEqualTo(expected0);
        assertThat(recorder.getPublished(t1)).isEqualTo(expected1);
    }

    private static class ProgressRecorder implements BiConsumer<String, Double> {
        private final Map<String, List<Double>> published = new HashMap<>();

        @Override
        public void accept(String taskId, Double progress) {
            published.putIfAbsent(taskId, new ArrayList<>());
            published.get(taskId).add(progress);
        }

        public List<Double> getPublished(String taskId) {
            return published.get(taskId);
        }
    }
}
