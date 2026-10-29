package org.icij.datashare.asynctasks.pipeline;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageResult;

/** A stage activity whose behavior is scripted per stage: sleeps, or throws, and counts its invocations. */
public class ScriptedStageActivity implements StageActivity {
    private final Map<Stage, Duration> sleeps = new EnumMap<>(Stage.class);
    private final Map<Stage, RuntimeException> failures = new EnumMap<>(Stage.class);
    private final Map<Stage, AtomicInteger> calls = new ConcurrentHashMap<>();

    public ScriptedStageActivity sleeps(Stage stage, Duration duration) {
        sleeps.put(stage, duration);
        return this;
    }

    public ScriptedStageActivity fails(Stage stage, RuntimeException failure) {
        failures.put(stage, failure);
        return this;
    }

    public int calls(Stage stage) {
        return calls.getOrDefault(stage, new AtomicInteger()).get();
    }

    @Override
    public StageResult run(Stage stage, Map<String, Object> options, List<?> batch) {
        calls.computeIfAbsent(stage, s -> new AtomicInteger()).incrementAndGet();
        if (failures.containsKey(stage)) {
            throw failures.get(stage);
        }
        try {
            Thread.sleep(sleeps.getOrDefault(stage, Duration.ZERO).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return StageResult.EMPTY;
    }
}
