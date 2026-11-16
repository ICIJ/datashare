package org.icij.datashare.tasks;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageResult;

/**
 * Passes each path on once: the first time the run sees it is emitted, a later one is dropped. The result counts the
 * paths it dropped ({@code updated}), which is what the stage is for; what it passes on is not counted.
 * <p>
 * The paths seen are kept for the whole context, so the stage only removes duplicates across batches where one
 * context spans the run, as on the queues. A context per batch (the Temporal activity) would only deduplicate
 * inside a batch.
 */
public class DeduplicateStage implements StageProcessor<Path, Path> {
    /** The paths seen by the run: a concurrent set, as the workers share the context. */
    private record Seen(Set<Path> paths) {}

    @Override
    public Stage stage() {
        return Stage.DEDUPLICATE;
    }

    @Override
    public Class<Path> itemType() {
        return Path.class;
    }

    @Override
    public StageResult process(List<Path> batch, StageContext<Path> ctx) {
        Set<Path> seen = ctx.resource(Seen.class, () -> new Seen(ConcurrentHashMap.newKeySet())).paths();
        List<Path> notSeen = batch.stream().filter(seen::add).toList();
        ctx.emit(notSeen);
        return new StageResult(batch.size() - notSeen.size(), 0, 0, 0);
    }
}
