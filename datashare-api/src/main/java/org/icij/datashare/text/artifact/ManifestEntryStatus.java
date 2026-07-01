package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Terminal states an artifact entry can be recorded in. A terminal entry with a matching
 *  config fingerprint means "already processed" (skip regeneration). Only COMPLETE is servable;
 *  EMPTY records "processed, but this node has no payload here" so it is not reprocessed forever. */
public enum ManifestEntryStatus {
    COMPLETE("complete", true, true),
    EMPTY("empty", true, false);

    private final String value;
    private final boolean terminal;
    private final boolean servable;

    ManifestEntryStatus(String value, boolean terminal, boolean servable) {
        this.value = value;
        this.terminal = terminal;
        this.servable = servable;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ManifestEntryStatus from(String value) {
        for (ManifestEntryStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown manifest entry status '" + value + "'");
    }

    /** A recorded, done state — regeneration is skipped when config matches. */
    public boolean isTerminal() {
        return terminal;
    }

    /** Whether the entry points at a payload that can be served. */
    public boolean isServable() {
        return servable;
    }
}
