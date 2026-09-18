package org.icij.datashare.tasks;

import org.icij.datashare.Stage;

/**
 * A stage's worker pool lost at least one worker to an unexpected failure. The run fails instead of
 * returning a partial count: whatever that worker was holding stayed unprocessed, and a green run
 * carrying a smaller number hides it from the operator.
 */
public class WorkersFailed extends RuntimeException {
    final Stage stage;
    final int nbFailures;
    final int nbWorkers;

    public WorkersFailed(Stage stage, int nbFailures, int nbWorkers, Throwable cause) {
        super("%d of %d %s worker(s) terminated abnormally".formatted(nbFailures, nbWorkers, stage), cause);
        this.stage = stage;
        this.nbFailures = nbFailures;
        this.nbWorkers = nbWorkers;
    }
}
