package org.icij.datashare.tasks;

import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.extract.ScanCallback;
import org.icij.datashare.extract.ScanOptions;
import org.icij.datashare.extract.Scanner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import static org.icij.datashare.PropertiesProvider.DATA_DIR_OPT;

/**
 * The source stage: it is handed an empty batch and emits the paths it finds under {@code dataDir}, in groups. The
 * scan options are the defaults: the options of the run are not mapped to them yet.
 */
public class ScanStage implements StageProcessor<Void, Path> {
    // Each batch costs the workflow ~4 history events (signal + activity scheduled/started/completed).
    // Kept low enough to stay well under Temporal's ~50k-event history cap even at ~10M scanned paths,
    // while still splitting the work into enough batches for horizontal scaling across index workers.
    static final int BATCH_SIZE = 1000;

    @Override
    public Stage stage() {
        return Stage.SCAN;
    }

    @Override
    public Class<Void> itemType() {
        return Void.class;
    }

    @Override
    public StageResult process(List<Void> batch, StageContext<Path> ctx) {
        Path dataDir = Paths.get((String) ctx.options().get(DATA_DIR_OPT));
        PathGrouper grouper = new PathGrouper(ctx);
        try {
            long scanned = new Scanner(ScanOptions.defaultValues(), grouper).scan(dataDir);
            grouper.purge(); // the trailing batch, smaller than BATCH_SIZE, that scan() never triggered
            return new StageResult(scanned, 0, 0, 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Groups paths before emitting them: one emit per path would cost the workflow a signal each. */
    private static class PathGrouper implements ScanCallback {
        private final StageContext<Path> ctx;
        private List<Path> paths = new ArrayList<>();

        PathGrouper(StageContext<Path> ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onFile(Path path) {
            if (paths.size() == BATCH_SIZE) {
                purge();
            }
            paths.add(path);
        }

        void purge() {
            if (paths.isEmpty()) {
                return;
            }
            ctx.emit(paths);
            paths = new ArrayList<>();
        }
    }
}
