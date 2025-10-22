package org.icij.datashare.text.indexing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.icij.datashare.json.JsonObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static java.util.Optional.ofNullable;

public class SearchQuery {
    final String query;

    @JsonCreator
    public SearchQuery(@JsonProperty("query") String query) {
        this.query = query;
    }

    @JsonIgnore
    public JsonNode asJson() {
        try {
            return JsonObjectMapper.readTree(
                    ofNullable(query).orElseThrow(() -> new IllegalStateException("null query")).
                            getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { // should be a JsonParseException
            throw new IllegalStateException(e);
        }
    }

    @JsonIgnore
    public boolean isJsonQuery() {
        return !isNull() && query.trim().startsWith("{") && query.trim().endsWith("}");
    }

    @JsonIgnore
    public boolean isNull() {
        return query == null;
    }

    public String toString() {return query;}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchQuery that = (SearchQuery) o;
        return Objects.equals(query, that.query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(query);
    }
}
