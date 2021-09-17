package org.icij.datashare.web;

import java.util.List;

class WebResponse<T> {
    private final List<T> items;
    private final int total;

    public WebResponse(List<T> items, int total) {
        this.items = items;
        this.total = total;
    }
}
