package org.icij.datashare.text.artifact;

import java.util.List;

/** Half-open [start, end) byte offsets into a single content file, one per page. Only
 *  datashare-python writes this scheme, so nothing here builds one: it exists to be read back. */
public record ByteRangePagination(String type, List<long[]> ranges) implements Pagination {
    public static final String TYPE = "byteRanges";
}
