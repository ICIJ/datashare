package org.icij.datashare.batch;


import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.batch.WebQueryPagination.OrderDirection.ASC;

public class WebQueryPagination {
    public enum OrderDirection { ASC, DESC }
    public final String sort;
    public final OrderDirection order;
    public final int from;
    public final int size;

    public WebQueryPagination() {
        this(null, null, 0, Integer.MAX_VALUE);
    }

    public WebQueryPagination(String sort, String order, int from, int size) {
        this.sort = ofNullable(sort).orElse("name");
        this.order = OrderDirection.valueOf(ofNullable(order).orElse(ASC.name()).toUpperCase());
        this.from = from;
        this.size = size;
    }

    public static Set<String> fields() {
        return stream(WebQueryPagination.class.getFields()).map(Field::getName).collect(Collectors.toSet());
    }

    public static WebQueryPagination fromMap(Map<String, Object> paginationMap) {
        return new WebQueryPagination(
                (String) paginationMap.get("sort"),
                (String) paginationMap.get("order"),
                Integer.parseInt(ofNullable(paginationMap.get("from")).orElse("0").toString()),
                Integer.parseInt(ofNullable(paginationMap.get("size")).orElse(Integer.MAX_VALUE).toString()));
    }

    public <T> Stream<T> paginate(Stream<T> stream, Function<WebQueryPagination, Comparator<T>> comparatorFactory) {
        return stream.sorted(comparatorFactory.apply(this)).skip(from).limit(size);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        WebQueryPagination that = (WebQueryPagination) o;
        return from == that.from && size == that.size && Objects.equals(sort, that.sort) && Objects.equals(order, that.order);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sort, order, from, size);
    }
}
