package org.icij.datashare.asynctasks;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;

public final class Interruptions {
    private Interruptions() {}

    /**
     * True when this throwable is, or wraps, an InterruptedException. Redisson and the
     * Elasticsearch rest-client both re-interrupt the thread and rethrow a RuntimeException
     * wrapping the InterruptedException, so a plain instanceof test misses a real cancellation.
     */
    public static boolean causedByInterrupt(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof InterruptedIOException ||
                cause instanceof ClosedByInterruptException) {
                return true;
            }
            // a custom or deserialised exception can return itself from getCause()
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }
}
