package org.icij.datashare.tasks;

import io.temporal.worker.WorkerFactory;
import java.io.Closeable;
import java.io.IOException;

public class TemporalHelper {
    public static class CloseableWorkerHandle implements Closeable {
        private final WorkerFactory factory;

        public CloseableWorkerHandle(WorkerFactory factory) {
            this.factory = factory;
        }

        @Override
        public void close() throws IOException {
            synchronized (factory) {
                if (!this.factory.isShutdown()) {
                    this.factory.shutdown();
                }
            }
        }
    }

}
