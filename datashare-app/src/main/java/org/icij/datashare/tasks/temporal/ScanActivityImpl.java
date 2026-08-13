package org.icij.datashare.tasks.temporal;

import io.temporal.activity.Activity;
import io.temporal.client.WorkflowClient;
import org.icij.datashare.extract.ScanCallback;
import org.icij.datashare.extract.ScanOptions;
import org.icij.datashare.extract.Scanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ScanActivityImpl implements ScanActivity {
    // Each batch costs the workflow ~4 history events (signal + activity scheduled/started/completed).
    // Kept low enough to stay well under Temporal's ~50k-event history cap even at ~10M scanned paths,
    // while still splitting the work into enough batches for horizontal scaling across index workers.
    private static final int BATCH_SIZE = 1000;
    final WorkflowClient client;

    public ScanActivityImpl(final WorkflowClient client) {
        this.client = client;
    }

    @Override
    public Long run(final ScanOptions options, final Path path) throws IOException {
        String workflowId = Activity.getExecutionContext().getInfo().getWorkflowId();
        PathBatchReceiver self = client.newWorkflowStub(PathBatchReceiver.class, workflowId);

        PathGrouper grouper = new PathGrouper(BATCH_SIZE, self);
        Scanner scanner = new Scanner(ScanOptions.defaultValues(), grouper);
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
