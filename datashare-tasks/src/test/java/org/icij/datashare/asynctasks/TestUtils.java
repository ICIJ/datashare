package org.icij.datashare.asynctasks;

import java.util.function.Supplier;

public class TestUtils {
    static void awaitPredicate(Integer timeoutMs, Supplier<Boolean> predicate) {
        awaitPredicate(timeoutMs, 10, predicate);
    }

    static void awaitPredicate(Integer timeoutMs, Integer pollIntervalMs, Supplier<Boolean> predicate) {
        long start = System.currentTimeMillis();
        while (true) {
            if (predicate.get()) {
                break;
            }
            if (timeoutMs != null) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed > timeoutMs) {
                    String msg =
                        "Failed to validated predicate in less than " + timeoutMs;
                    throw new RuntimeException(msg);
                }
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
