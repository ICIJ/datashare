package org.icij.datashare.project.admin;

import java.util.OptionalLong;

/**
 * Index-document count + casbin member count for a project.
 *
 * <p>{@code indexedDocuments} is empty when the caller skipped the ES round-trip
 * (e.g. {@code project delete --keep-index}); present otherwise. Use
 * {@link OptionalLong#isPresent()} to distinguish "skipped" from "zero".
 */
public record ProjectStats(
        String name,
        OptionalLong indexedDocuments,
        int memberCount
) {
    public static ProjectStats withSkippedIndex(String name, int memberCount) {
        return new ProjectStats(name, OptionalLong.empty(), memberCount);
    }

    public static ProjectStats of(String name, long indexedDocuments, int memberCount) {
        return new ProjectStats(name, OptionalLong.of(indexedDocuments), memberCount);
    }
}
