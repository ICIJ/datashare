package org.icij.datashare.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

class WebResponse<T> {
    public final List<T> items;
    public final Pagination pagination;

    public WebResponse(List<T> items, final int from, final int size, final int total) {
        this.items = Collections.unmodifiableList(items);
        this.pagination = new Pagination(items.size(), from, size, total);
    }

    record Pagination(int count, int from, int size, int total) { }

    public static <S> WebResponse<S> fromStream(Stream<S> items, final int from, final int size) {
        Iterator<S> iterator = items.iterator();

        int skipped = 0;
        while (skipped < from && iterator.hasNext()) {
            iterator.next();
            skipped++;
        }

        List<S> page = new ArrayList<>();
        int returned = 0;
        while (returned < size && iterator.hasNext()) {
            page.add(iterator.next());
            returned++;
        }

        while (iterator.hasNext()) {
            iterator.next();
            skipped++;
        }

        return new WebResponse<>(page, from, size, skipped + returned);
    }
}
