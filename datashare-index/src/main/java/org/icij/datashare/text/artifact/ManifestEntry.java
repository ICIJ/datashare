package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** One artifact type's entry in a document's manifest.json. Single-file types use
 *  contentType/filename; paginated types use total/pagination. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManifestEntry(
        String status,
        Map<String, Object> taskInput,
        Integer total,
        Map<String, Object> pagination,
        String contentType,
        String filename,
        Double confidence,
        String label) {

    public static ManifestEntry singleFile(Map<String, Object> taskInput, String contentType, String filename) {
        return new ManifestEntry(null, taskInput, null, null, contentType, filename, null, null);
    }

    public static ManifestEntry paginated(Map<String, Object> taskInput, int total, Map<String, Object> pagination) {
        return new ManifestEntry(null, taskInput, total, pagination, null, null, null, null);
    }

    public ManifestEntry withStatus(String status) {
        return new ManifestEntry(status, taskInput, total, pagination, contentType, filename, confidence, label);
    }

    public boolean isComplete() {
        return "complete".equals(status);
    }
}
