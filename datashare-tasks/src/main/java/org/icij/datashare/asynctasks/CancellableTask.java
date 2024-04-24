package org.icij.datashare.asynctasks;

public interface CancellableTask {
     void cancel(boolean requeue);
}
