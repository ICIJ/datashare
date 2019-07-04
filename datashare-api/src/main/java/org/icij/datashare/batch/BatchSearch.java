package org.icij.datashare.batch;

import java.util.List;
import java.util.Objects;

public class BatchSearch {
    private final String name;
    private final String description;
    private final List<String> queries;

    public BatchSearch(final String name, final String description, final List<String> queries) {
        this.name = name;
        this.description = description;
        this.queries = queries;
    }

    @Override
    public String toString() {
        return "BatchSearch{name='" + name + '\'' + ", queries=" + queries + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchSearch that = (BatchSearch) o;
        return name.equals(that.name) &&
                Objects.equals(description, that.description) &&
                queries.equals(that.queries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, queries);
    }
}
