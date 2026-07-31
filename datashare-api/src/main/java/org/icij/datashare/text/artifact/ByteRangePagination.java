package org.icij.datashare.text.artifact;

import java.util.List;

/** Half-open [start, end) byte offsets into a single content file, one per page. Written here by the
 *  page artifact, and by datashare-python's own producers. */
public record ByteRangePagination(String type, List<long[]> ranges) implements Pagination {
    public static final String TYPE = "byteRanges";

    public ByteRangePagination(List<long[]> ranges) {
        this(TYPE, ranges);
    }
}
