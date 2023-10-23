package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;

public class DocumentUserRecommendation {

    public final Project project;
    public final Document document;
    public final User user;

    @JsonCreator
    public DocumentUserRecommendation(@JsonProperty("document") Document document, @JsonProperty("project") Project project, @JsonProperty("user") User user) {
        this.document = document;
        this.project = project;
        this.user = user;
    }
}
