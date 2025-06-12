package org.icij.datashare.batch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.User;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

public class BatchSearchRecord {
    public enum State {QUEUED, RUNNING, SUCCESS, FAILURE}
    public final String uuid;
    public final boolean published;
    @JsonIgnore
    public final List<ProjectProxy> projects;
    public final String name;
    public final String description;
    public final User user;
    public final State state;
    public final Date date;
    public final String uri;
    public final int nbQueries;
    public final int nbQueriesWithoutResults;
    public final int nbResults;
    public final String errorMessage;
    public final String errorQuery;

    // This constructor interface is mostly used in tests
    public BatchSearchRecord(final List<ProjectProxy> projects, final String name, final String description, final int nbQueries, Date date, String uri) {
        this(
                UUID.randomUUID().toString(),
                projects.stream().map(ProjectProxy::getId).toList(),
                name,
                description,
                nbQueries,
                nbQueries,
                date,
                State.QUEUED,
                uri,
                User.local(),
                0,
                false,
                null,
                null
        );
    }

    @JsonCreator
    public BatchSearchRecord(
            @JsonProperty("uuid") String uuid,
            @JsonProperty("projects") List<String> projects,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("nbQueries") int nbQueries,
            @JsonProperty("nbQueriesWithoutResults") int nbQueriesWithoutResults,
            @JsonProperty("date") Date date,
            @JsonProperty("state") State state,
            @JsonProperty("uri") String uri,
            @JsonProperty("user") User user,
            @JsonProperty("nbResults") int nbResults,
            @JsonProperty("published") boolean published,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("errorQuery") String errorQuery) {
        assert date != null && uuid != null;
        this.uuid = uuid;
        this.published = published;
        this.projects = ofNullable(projects).orElse(new ArrayList<>()).stream().map(ProjectProxy::new).toList();
        this.name = name;
        this.description = description;
        this.user = user;
        this.date = date;
        this.uri = uri;
        this.nbResults = nbResults;
        this.nbQueries = nbQueries;
        this.nbQueriesWithoutResults = nbQueriesWithoutResults;
        this.state = state;
        this.errorMessage = errorMessage;
        this.errorQuery = errorQuery;
    }

    /***
     * copy constructor
     * @param record to copy
     */
    public BatchSearchRecord(BatchSearchRecord record){
        this(record.uuid, record.getProjects(), record.name, record.description, record.nbQueries, record.nbQueriesWithoutResults, record.date,
                record.state, record.uri, record.user, record.nbResults, record.published, record.errorMessage, record.errorQuery);
    }
    @JsonProperty("projects")
    List<String> getProjects() {
        return projects.stream().map(ProjectProxy::getId).collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return "BatchSearchSummary{" + uuid + " name='" + name + '\'' + " (" + state + ")}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchSearchRecord that = (BatchSearchRecord) o;
        return uuid.equals(that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }
}
