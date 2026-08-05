package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The page attributes of a paginated artifact entry, as the storage convention's `pages` object:
 *  `total` is the page count, `pagination` how those pages are located on disk. NON_NULL matters for
 *  `pagination`: per the convention an omitted one means "single document, content.<ext>", while a
 *  `"pagination": null` is a third state nothing defines. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pages(int total, Pagination pagination) {
}
