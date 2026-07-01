package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** One artifact type's entry in a document's manifest.json. Single-file types use
 *  contentType/filename; paginated types use total/pagination. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManifestEntry(
        ManifestEntryStatus status,
        Map<String, Object> taskInput,
        Integer total,
        Map<String, Object> pagination,
        String contentType,
        String filename,
        Double confidence,
        String label) {

    // A single-file artifact (e.g. raw bytes): described by its media type and filename.
    public static ManifestEntry singleFile(Map<String, Object> taskInput, String contentType, String filename) {
        return new ManifestEntry(null, taskInput, null, null, contentType, filename, null, null);
    }

    // A paginated artifact (e.g. structure pages): described by a page count and a pagination scheme.
    public static ManifestEntry paginated(Map<String, Object> taskInput, int total, Map<String, Object> pagination) {
        return new ManifestEntry(null, taskInput, total, pagination, null, null, null, null);
    }

    // Producers return an entry without a status; the registry stamps it complete only
    // once every payload file has been written, so a crash never leaves a "ready" lie.
    public ManifestEntry withStatus(ManifestEntryStatus status) {
        return new ManifestEntry(status, taskInput, total, pagination, contentType, filename, confidence, label);
    }

    public boolean isComplete() {
        return status != null && status.isServable();
    }

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }
}
