package org.icij.datashare.tasks.temporal;

import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;
import org.icij.datashare.extract.ScanCallback;
import org.icij.datashare.extract.ScanOptions;
import org.icij.datashare.extract.Scanner;
import org.icij.datashare.utils.DataDirVerifier;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ScanActivityImpl implements ScanActivity {
    // Each batch costs the workflow ~4 history events (signal + activity scheduled/started/completed).
    // Kept low enough to stay well under Temporal's ~50k-event history cap even at ~10M scanned paths,
    // while still splitting the work into enough batches for horizontal scaling across index workers.
    private static final int BATCH_SIZE = 1000;
    final TemporalInterlocutor temporal;
    private final DataDirVerifier dataDirVerifier;

    public ScanActivityImpl(final TemporalInterlocutor temporal, final PropertiesProvider serverProperties) {
        this.temporal = temporal;
        this.dataDirVerifier = new DataDirVerifier(serverProperties);
    }

    @Override
    public Long run(final ScanOptions options, final Path path) throws IOException {
        try {
            dataDirVerifier.check(path);
        } catch (IllegalArgumentException refusal) {
            // non retryable: Temporal would otherwise retry a refusal that can never succeed
            throw ApplicationFailure.newNonRetryableFailureWithCause(refusal.getMessage(), refusal.getClass().getName(),
                                                                     refusal);
        }
        String workflowId = Activity.getExecutionContext().getInfo().getWorkflowId();
        PathBatchReceiver self = temporal.newWorkflowStub(PathBatchReceiver.class, workflowId);

        PathGrouper grouper = new PathGrouper(BATCH_SIZE, self);
        ScanOptions confined =
                new ScanOptions(options.includeOSFiles(), options.includeHiddenFiles(), options.includePatterns(),
                                options.excludePatterns(), false, options.maxDepth());
        Scanner scanner = new Scanner(confined, grouper);
        Long total = scanner.scan(path);
        grouper.purge(); // flush the trailing batch, smaller than groupSize, that scan() never triggered
        return total;
    }

    /**
     * Class that groups paths by groupSize before sending the notification that they can be processed
     */
    private static class PathGrouper implements ScanCallback {
        private final int groupSize;
        private final PathBatchReceiver receiver;

        public PathGrouper(final int groupSize, PathBatchReceiver receiver) {
            if (groupSize <= 0) {
                this.groupSize = 10;
            } else {
                this.groupSize = groupSize;
            }
            this.receiver = receiver;
        }

        List<Path> paths = new ArrayList<>();

        @Override
        public void onFile(Path path) throws Exception {
            if (paths.size() == groupSize) {
                purge();
            }
            this.paths.add(path);
        }

        public void purge() {
            if (paths.isEmpty()) {
                return;
            }
            receiver.onPaths(paths);
            this.paths = new ArrayList<>();
        }
    }
}
