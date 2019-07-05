package org.icij.datashare.batch;

import org.icij.datashare.text.Project;

import java.util.List;
import java.util.Objects;

public class BatchSearch {
    public final Long id;
    public final Project project;
    public final String name;
    public final String description;
    public final List<String> queries;

    public BatchSearch(final Project project, final String name, final String description, final List<String> queries) {
        this(0L, project, name, description, queries);
    }

    public BatchSearch(long id, Project project, String name, String description, List<String> queries) {
        this.id = id;
        this.project = project;
        this.name = name;
        this.description = description;
        this.queries = queries;
    }

    @Override
    public String toString() {
        return "BatchSearch{" + id + " name='" + name + '\'' + ", queries=" + queries + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchSearch that = (BatchSearch) o;
        return id.equals(that.id) && name.equals(that.name) &&
                queries.equals(that.queries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, queries);
    }
}
