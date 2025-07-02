package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.io.Serializable;

@JsonTypeName("BatchSearchRunnerResult")
public record BatchSearchRunnerResult(int nbResults, int nbQueriesWithoutResults) implements Serializable {
    public BatchSearchRunnerResult(@JsonProperty("nbResults") int nbResults, @JsonProperty("nbQueriesWithoutResults") int nbQueriesWithoutResults) {
        this.nbResults = nbResults;
        this.nbQueriesWithoutResults = nbQueriesWithoutResults;
    }
}
