package org.icij.datashare.batch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties("message")
public class SearchException extends RuntimeException {
    public final String query;

    @JsonCreator
    public SearchException(@JsonProperty("query") String query, @JsonProperty("cause") Throwable root) {
        super(root);
        this.query = query;
    }

    @Override
    public String toString() {
        return "SearchException: query='" + query + "' message='" + super.toString() + "'";
    }
}
