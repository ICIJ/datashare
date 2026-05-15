package org.icij.datashare.project.admin;

public record ProjectStats(
        String name,
        long indexedDocuments,
        int memberCount
) {
    /** Sentinel meaning "index check skipped" (used when --keep-index is set). */
    public static final long INDEX_CHECK_SKIPPED = -1L;
}
