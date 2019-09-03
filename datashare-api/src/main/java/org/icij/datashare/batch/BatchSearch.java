package org.icij.datashare.batch;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.icij.datashare.text.Project;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

public class BatchSearch {
    public enum State {QUEUED, RUNNING, SUCCESS, FAILURE}
    public final String uuid;
    public final Project project;
    public final String name;
    public final String description;
    public final LinkedHashMap<String, Integer> queries; // LinkedHashMap keeps insert order
    public final State state;
    private final Date date;
    public final int nbResults;

    // batch search creation
    public BatchSearch(final Project project, final String name, final String description, final List<String> queries) {
        this(UUID.randomUUID().toString(), project, name, description, toLinkedHashMap(queries), new Date(), State.QUEUED, 0);
    }

    // for tests
    public BatchSearch(final Project project, final String name, final String description, final List<String> queries, Date date) {
        this(UUID.randomUUID().toString(), project, name, description, toLinkedHashMap(queries), date, State.QUEUED, 0);
    }
    public BatchSearch(String uuid, Project project, String name, String description, List<String> queries, Date date, State state) {
        this(uuid, project, name, description, toLinkedHashMap(queries), date, state, 0);
    }

    // retrieved from persistence
    public BatchSearch(String uuid, Project project, String name, String description, LinkedHashMap<String, Integer> queries, Date date, State state, int nbResults) {
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
    @JsonIgnore
    public List<String> getQueryList() {return new ArrayList<>(queries.keySet());}

    @NotNull
    private static LinkedHashMap<String, Integer> toLinkedHashMap(List<String> queries) {
        return queries.stream().collect(toMap(identity(), i -> 0,
                (u,v) -> { throw new IllegalStateException(String.format("Duplicate key %s", u)); },
                LinkedHashMap::new));
    }

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
