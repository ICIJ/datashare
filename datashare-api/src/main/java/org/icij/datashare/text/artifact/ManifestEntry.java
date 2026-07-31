package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** One artifact type's entry in a document's manifest.json. Single-file types use
 *  contentType/filename; paginated types use a Pages. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManifestEntry(
        ManifestEntryStatus status,
        Map<String, Object> taskInput,
        Pages pages,
        String contentType,
        String filename,
        Double confidence,
        String label) {

    public static ManifestEntry singleFile(Map<String, Object> taskInput, String contentType, String filename) {
        return new ManifestEntry(null, taskInput, null, contentType, filename, null, null);
    }

    /** A payload split into one file per page (see {@link FilesystemPagination}). */
    public static ManifestEntry paginated(Map<String, Object> taskInput, int total) {
        return new ManifestEntry(null, taskInput, new Pages(total, new FilesystemPagination()), null, null, null, null);
    }

    /** A single content file split by half-open byte ranges, one per page. `total` is derived from the
     *  offsets themselves, so a producer cannot record a count that disagrees with the ranges it wrote. */
    public static ManifestEntry paginated(Map<String, Object> taskInput, List<long[]> ranges) {
        return new ManifestEntry(null, taskInput, new Pages(ranges.size(), new ByteRangePagination(ranges)),
                null, null, null, null);
    }

    /** A node that was processed but has no payload to serve from its own dir (e.g. a root
     *  document whose source is the on-disk original). Recorded so it is not reprocessed. */
    public static ManifestEntry empty(Map<String, Object> taskInput) {
        return new ManifestEntry(ManifestEntryStatus.EMPTY, taskInput, null, null, null, null, null);
    }

    public ManifestEntry withStatus(ManifestEntryStatus status) {
        return new ManifestEntry(status, taskInput, pages, contentType, filename, confidence, label);
    }

    /** Producers return EMPTY as-is; any other (status-less) entry becomes COMPLETE. Callers stamp
     *  only once the payload is written, so a crash mid-produce never leaves a "ready" lie. */
    public ManifestEntry withTerminalStatus() {
        return isTerminal() ? this : withStatus(ManifestEntryStatus.COMPLETE);
    }

    /** Whether this entry makes regeneration unnecessary: terminal, and produced with the exact same
     *  task input (config + version). Compares from the argument side so an absent taskInput does not NPE. */
    public boolean isCurrentFor(Map<String, Object> taskInput) {
        return isTerminal() && taskInput.equals(this.taskInput);
    }

    public boolean isComplete() {
        return status != null && status.isServable();
    }

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }
}
