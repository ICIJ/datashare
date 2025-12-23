package org.icij.datashare.tasks.temporal;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.icij.datashare.function.Pair;

public abstract class TemporalWorkflowImpl {
    ConcurrentHashMap<Pair<String, String>, Double> progress = new ConcurrentHashMap<>();
    ConcurrentHashMap<Pair<String, String>, Double> maxProgress = new ConcurrentHashMap<>();

    @SignalMethod
    public void progress(ProgressSignal progressSignal) {
        Pair<String, String> key = new Pair<>(progressSignal.runId(), progressSignal.activityId());
        progress.putIfAbsent(key, 0.0);
        maxProgress.putIfAbsent(key, 0.0);
        progress.compute(key, (ignored, p) -> p + progressSignal.progress());
        maxProgress.compute(key, (ignored, maxP) -> maxP + progressSignal.progress());
    }

    @QueryMethod(name = "progress")
    public double getProgress(String runId) {
        double maxP = maxProgress.entrySet()
            .stream()
            .filter(e -> e.getKey()._1().equals(runId))
            .map(Map.Entry::getValue)
            .reduce(0.0, Double::sum);
        if (maxP == 0.0) {
            return maxP;
        }
        double p = progress.entrySet()
            .stream()
            .filter(e -> e.getKey()._1().equals(runId))
            .map(Map.Entry::getValue)
            .reduce(0.0, Double::sum);
        return p / maxP;
    }
}
