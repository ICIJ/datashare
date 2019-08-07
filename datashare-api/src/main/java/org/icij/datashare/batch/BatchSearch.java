package org.icij.datashare.batch;

import org.icij.datashare.text.Project;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class BatchSearch {
    public enum State {QUEUED, RUNNING, SUCCESS, FAILURE}
    public final String uuid;
    public final Project project;
    public final String name;
    public final String description;
    public final List<String> queries;
    public final State state;
    private final Date date;
    public final int nbResults;

    // batch search creation
    public BatchSearch(final Project project, final String name, final String description, final List<String> queries) {
        this(UUID.randomUUID().toString(), project, name, description, queries, new Date(), State.QUEUED, 0);
    }

    // for tests
    public BatchSearch(final Project project, final String name, final String description, final List<String> queries, Date date) {
        this(UUID.randomUUID().toString(), project, name, description, queries, date, State.QUEUED, 0);
    }

    // retrieved from persistence
    public BatchSearch(String uuid, Project project, String name, String description, List<String> queries, Date date, State state, int nbResults) {
        assert date != null && uuid != null;
        this.uuid = uuid;
        this.project = project;
        this.name = name;
        this.description = description;
        this.queries = queries;
        this.date = date;
        this.state = state;
        this.nbResults = nbResults;
    }

    public Date getDate() { return date;}

    @Override
    public String toString() {
        return "BatchSearch{" + uuid + " name='" + name + '\'' + " (" + state + ")}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchSearch that = (BatchSearch) o;
        return uuid.equals(that.uuid) && name.equals(that.name) &&
                queries.equals(that.queries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, queries);
    }
}
