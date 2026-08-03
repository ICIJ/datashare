package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The page attributes of a paginated artifact entry, as the storage convention's `pages` object:
 *  `total` is the page count, `pagination` how those pages are located on disk. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pages(Integer total, Pagination pagination) {
}
