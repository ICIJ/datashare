package org.icij.datashare.text.artifact;

/** One file per page next to the manifest, named {@code page-N.<ext>}. The only scheme Java writes. */
public record FilesystemPagination(String type) implements Pagination {
    public static final String TYPE = "filesystem";

    public FilesystemPagination() {
        this(TYPE);
    }
}
