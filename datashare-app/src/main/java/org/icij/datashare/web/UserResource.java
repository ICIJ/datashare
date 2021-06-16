package org.icij.datashare.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Repository;
import org.icij.datashare.UserEvent;
import org.icij.datashare.UserEvent.Type;
import org.icij.datashare.db.tables.UserHistory;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.Tag;
import org.icij.datashare.user.User;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.icij.datashare.text.Project.project;

@Singleton
@Prefix("/api/users")
public class UserResource {
    private final Repository repository;

    @Inject
    public UserResource(Repository repository) {
        this.repository = repository;
    }

    /**
     * Gets the user's session information
     *
     * @return 200 and the user map
     *
     * Example :
     * $(curl -i localhost:8080/api/users/me)
     */
    @Get("/me")
    public Map<String, Object> getUser(Context context) {
        return ((DatashareUser) context.currentUser()).getDetails();
    }

    /**
     * Gets the user's history
     *
     * @return 200 and the user's list of events
     *
     * Example :
     * $(curl -i localhost:8080/api/users/me/history)
     */
    @Get("/me/history")
    public List<UserEvent> getUserHistory(Context context) {
        return repository.getUserEvents((DatashareUser) context.currentUser());
    }

    /**
     * Add event to history. The event's type, the project id and the uri are passed in the request body.
     *
     * It answers 200 when event is added or updated.
     *
     * @param query
     * @@return 200
     *
     * Example :
     * $(curl -i -XPOST  -H "Content-Type: application/json"  localhost:8080/api/apigen-datashare/documents/batchUpdate/tag -d '{"docIds": ["bd2ef02d39043cc5cd8c5050e81f6e73c608cafde339c9b7ed68b2919482e8dc7da92e33aea9cafec2419c97375f684f", "7473df320bee9919abe3dc179d7d2861e1ba83ee7fe42c9acee588d886fe9aef0627df6ae26b72f075120c2c9d1c9b61"], "tags": ["foo", "bar"]}')
     */
    @Put("/me/history")
    public Payload addToHistory(UserHistoryQuery query, Context context) throws IOException {
        repository.addToHistory(query.project, new UserEvent((DatashareUser) context.currentUser(), query.type, "something", query.uri));
        return Payload.ok();
    }

    private static class UserHistoryQuery {
        final Type type;
        final Project project;
        final URI uri;

        @JsonCreator
        private UserHistoryQuery(@JsonProperty("type") String type, @JsonProperty("project") String projectId, @JsonProperty("uri") String uri) {
            this.type = Type.valueOf(type);
            this.project = project(projectId);
            this.uri = URI.create(uri);
        }
    }
}
