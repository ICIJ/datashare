package org.icij.datashare.tasks.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.icij.datashare.extract.ScanOptions;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class IndexationWorkflowImpl implements IndexationWorkflow, PathBatchReceiver {
    private final ScanActivity scanActivity = Workflow.newActivityStub(ScanActivity.class, ActivityOptions.newBuilder()
                                                                                                          .setStartToCloseTimeout(
                                                                                                                  Duration.ofMinutes(
                                                                                                                          1))
                                                                                                          .build());
    private final IndexActivity indexActivity = Workflow.newActivityStub(IndexActivity.class,
                                                                         ActivityOptions.newBuilder()
                                                                                        .setStartToCloseTimeout(
                                                                                                Duration.ofMinutes(1))
                                                                                        .build());
    private final List<Promise<Void>> pendingIndexations = new ArrayList<>();
    private String index;
    private IndexOptions indexOptions;

    @Override
    public Long run(ScanOptions scanOptions, Path path, String index, IndexOptions indexOptions) throws IOException {
        this.index = index;
        this.indexOptions = indexOptions;

        // launch scan activity, it will trigger indexation activities via the onPaths signal
        Long totalScanned = scanActivity.run(scanOptions, path);

        // wait for every indexation activity triggered by onPaths to complete before finishing
        Promise.allOf(pendingIndexations).get();

        return totalScanned;
    }

    @Override
    public void onPaths(List<Path> paths) {
        // We received a batch of paths that can be indexed: fire the indexation asynchronously so
        // several batches can be indexed concurrently, and track it so run() can wait for it.
        pendingIndexations.add(Async.procedure(indexActivity::index, indexOptions, paths, index));
    }
}
