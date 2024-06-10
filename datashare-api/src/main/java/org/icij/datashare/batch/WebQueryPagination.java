package org.icij.datashare.batch;


public class WebQueryPagination {
    public final String sort;
    public final String order;
    public final int from;
    public final int size;

    public WebQueryPagination(String sort, String order, int from, int size) {
        this.sort = sort;
        this.order = order;
        this.from = from;
        this.size = size;
    }
}
