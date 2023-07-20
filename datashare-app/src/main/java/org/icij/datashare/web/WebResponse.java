package org.icij.datashare.web;

import java.util.Collections;
import java.util.List;

class WebResponse<T> {
    public final List<T> items;
    public final Pagination pagination;

    public WebResponse(List<T> items, final int from, final int size, final int total) {
        this.items = Collections.unmodifiableList(items);
        this.pagination = new Pagination(items.size(), from, size, total);
    }

    static class Pagination {
        final int count;
        final int from;
        final int size;
        final int total;

        public Pagination(int count, int from, int size, int total) {
            this.count = count;
            this.from = from;
            this.size = size;
            this.total = total;
        }
    }
}
