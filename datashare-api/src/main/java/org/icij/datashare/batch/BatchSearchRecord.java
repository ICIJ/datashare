package org.icij.datashare.batch;

import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.User;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static java.util.Collections.unmodifiableList;
import static java.util.Optional.ofNullable;

public class BatchSearchRecord {

    public enum State {QUEUED, RUNNING, SUCCESS, FAILURE}
    public final String uuid;
    public final boolean published;
    public final List<ProjectProxy> projects;
    public final String name;
    public final String description;
    public final User user;
    public final State state;
    public final Date date;
    public final int nbQueries;
    public final int nbResults;
    public final String errorMessage;
    public final String errorQuery;

    // for tests
    public BatchSearchRecord(final List<ProjectProxy> projects, final String name, final String description, final int nbQueries, Date date) {
        this(UUID.randomUUID().toString(), projects, name, description, nbQueries, date, State.QUEUED, User.local(),
                0, false,null, null);
    }

    public BatchSearchRecord(String uuid, List<ProjectProxy> projects, String name, String description, int nbQueries, Date date, State state, User user,
                             int nbResults, boolean published, String errorMessage, String errorQuery){
        assert date != null && uuid != null;
        this.uuid = uuid;
        this.published = published;
        this.projects = unmodifiableList(ofNullable(projects).orElse(new ArrayList<>()));
        this.name = name;
        this.description = description;
        this.user = user;
        this.date = date;
        this.nbResults = nbResults;
        this.nbQueries = nbQueries;
        this.state = state;
        this.errorMessage = errorMessage;
        this.errorQuery = errorQuery;
    }

    /***
     * copy constructor
     * @param record to copy
     */
    public BatchSearchRecord(BatchSearchRecord record){
        this(record.uuid, record.projects, record.name, record.description, record.nbQueries, record.date,
                record.state, record.user, record.nbResults, record.published, record.errorMessage, record.errorQuery);
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
