package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** How a paginated artifact's payload is split, as the storage convention's `pages.pagination`. The
 *  discriminator is declared here rather than on the field so Jackson reads the `type` the convention
 *  already puts in the JSON instead of adding one of its own. EXISTING_PROPERTY leaves writing it to
 *  the records, and visible=true is what hands it back to their `type` component on the way in. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = FilesystemPagination.class, name = FilesystemPagination.TYPE),
        @JsonSubTypes.Type(value = ByteRangePagination.class, name = ByteRangePagination.TYPE)
})
public sealed interface Pagination permits FilesystemPagination, ByteRangePagination {
    String type();
}
