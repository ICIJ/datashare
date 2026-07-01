package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** How a paginated artifact's payload is split. `total` is the page count; `byteRanges` is present
 *  only for the byte-ranges scheme. NOTE (cross-repo): reconcile field name and `type` serialization
 *  with datashare-python's StructureManifestEntry.pages before shipping a Java paginated producer. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pagination(String type, int total, List<long[]> byteRanges) {
    public static Pagination filesystem(int total) {
        return new Pagination("filesystem", total, null);
    }

    public static Pagination byteRanges(int total, List<long[]> byteRanges) {
        return new Pagination("byteRanges", total, byteRanges);
    }
}
