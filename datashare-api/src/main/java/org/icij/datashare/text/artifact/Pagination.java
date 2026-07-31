package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** How a paginated artifact's payload is split, as the storage convention's `pages.pagination`.
 *  `ranges` is present only for the byteRanges scheme: half-open [start, end) byte offsets into a
 *  single content file, one per page. Only datashare-python writes that scheme, so only `filesystem`
 *  has a factory here. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pagination(String type, List<long[]> ranges) {
    public static Pagination filesystem() {
        return new Pagination("filesystem", null);
    }
}
