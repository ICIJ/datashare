package org.icij.datashare.asyncsearch;

import java.util.List;

/**
 * Records who submitted an async search and on which projects, so poll/cancel
 * requests carrying only the opaque ES id can be authorized.
 *
 * Public no-arg constructor and public fields are required by the Redisson
 * JSON codec used in {@link RedisAsyncSearchStore}.
 */
public class AsyncSearchOwner {
    public String userId;
    public List<String> projects;

    public AsyncSearchOwner() {}

    public AsyncSearchOwner(String userId, List<String> projects) {
        this.userId = userId;
        this.projects = projects;
    }
}
