package org.icij.datashare.asyncsearch;

import java.time.Duration;
import java.util.Optional;

/**
 * Strict-ownership record store for async searches, keyed by the ES async id.
 * Records expire after keepAlive so the store stays in lockstep with ES.
 */
public interface AsyncSearchStore {
    void put(String asyncId, AsyncSearchOwner owner, Duration keepAlive);
    Optional<AsyncSearchOwner> get(String asyncId);
    void remove(String asyncId);
    void refresh(String asyncId, Duration keepAlive);
}
