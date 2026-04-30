package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.icij.datashare.asynctasks.DownloadableResult;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.net.URI;

/**
 * @param uri
 * @param size
 * @param truncationReason can be null if no truncation was made, or have a value depending on the reason of the truncation
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@type")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchDownloadRunnerResult(URI uri, long size, @Nullable TruncationReason truncationReason) implements Serializable, DownloadableResult {

    @Override
    public URI getUri() {
        return uri;
    }

    public enum TruncationReason {
        SIZE_LIMIT,
        FILE_COUNT_LIMIT,
        UNKNOWN
    }
}

