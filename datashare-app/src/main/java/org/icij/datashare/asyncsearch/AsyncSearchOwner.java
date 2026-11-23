package org.icij.datashare.asyncsearch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;

/**
 * Records who submitted an async search and on which projects, so poll/cancel
 * requests carrying only the opaque ES id can be authorized.
 *
 * Record are not working likely because the public visibility of the properties is removed.
 *
 * Serialized by {@code RedisCodec} with the untyped JsonObjectMapper in
 * {@link RedisAsyncSearchStore}, through its {@code @JsonCreator} constructor.
 */
public class AsyncSearchOwner {
    public final String userId;
    public final List<String> projects;

    @JsonCreator
    public AsyncSearchOwner(@JsonProperty("userId") String userId, @JsonProperty("projects") List<String> projects) {
        this.userId = userId;
        this.projects = Collections.unmodifiableList(projects);
    }
}
