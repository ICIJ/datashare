package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.icij.datashare.tabular.StatementBuilder;

import java.io.Serializable;
import java.util.Map;

/** What one run of a mapping did. The skip counts travel with the rest because a run reporting rows
 *  read and statements written, and nothing else, presents a total loss as a clean import: a blank
 *  key column alone accounts for every row of a file. */
@JsonTypeName("StructuredEntityExtractionResult")
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@type")
public record StructuredEntityExtractionResult(long rows, int retracted, int written, int indexed,
                                               Map<StatementBuilder.Skip, Long> skipped) implements Serializable {
    public StructuredEntityExtractionResult(@JsonProperty("rows") long rows, @JsonProperty("retracted") int retracted,
                                            @JsonProperty("written") int written, @JsonProperty("indexed") int indexed,
                                            @JsonProperty("skipped") Map<StatementBuilder.Skip, Long> skipped) {
        this.rows = rows;
        this.retracted = retracted;
        this.written = written;
        this.indexed = indexed;
        this.skipped = Map.copyOf(skipped);
    }
}
