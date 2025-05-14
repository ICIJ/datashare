package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.io.Serializable;

@JsonTypeName("BatchSearchRunnerResult")
public record BatchSearchRunnerResult(int nbResults, int nbQueriesWithoutResults) implements Serializable {
    public BatchSearchRunnerResult(@JsonProperty("nbResults") int nbResults, @JsonProperty("nbQueriesWithoutResults") int nbQueriesWithoutResults) {
        this.nbResults = nbResults;
        this.nbQueriesWithoutResults = nbQueriesWithoutResults;
    }
}
