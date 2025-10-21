package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;

import java.util.Date;
import java.util.Objects;

public class DocumentUserRecommendation {

    public final ProjectProxy project;
    public final Document document;
    public final User user;
    public final Date creationDate;

    @JsonCreator
    public DocumentUserRecommendation(@JsonProperty("document") Document document, @JsonProperty("project") ProjectProxy project, @JsonProperty("user") User user, @JsonProperty("creationDate") Date creationDate) {
        this.document = document;
        this.project = project;
        this.user = user;
        this.creationDate = creationDate;
    }
    public DocumentUserRecommendation(@JsonProperty("document") Document document, @JsonProperty("project") ProjectProxy project, @JsonProperty("user") User user) {
        this(document, project, user, DatashareTime.getInstance().now());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentUserRecommendation that = (DocumentUserRecommendation) o;
        return Objects.equals(project, that.project) && Objects.equals(document, that.document) && Objects.equals(user, that.user);
    }

    @Override
    public int hashCode() {
        return Objects.hash(project, document, user);
    }

    @Override
    public String toString() {
        return "DocumentUserRecommendation{project=" + project + ", document=" + document + ", user=" + user + '}';
    }
}
