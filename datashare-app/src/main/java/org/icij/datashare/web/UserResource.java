package org.icij.datashare.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Repository;
import org.icij.datashare.UserEvent;
import org.icij.datashare.UserEvent.Type;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static net.codestory.http.payload.Payload.ok;
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
     * Preflight for history.
     *
     * @return 200 with OPTIONS, GET, PUT and DELETE
     */
    @Options("/me/history")
    public Payload getUserHistory(String userId) {
        return ok().withAllowMethods("OPTIONS", "GET", "PUT", "DELETE");
    }

    /**
     * Gets the user's history by type
     *
     * @param type
     * @param from
     * @param size
     * @return 200, the user's list of events and the total number of events
     *
     * Example :
     * $(curl -i localhost:8080/api/users/me/history?type=document&from=0&size=10)
     */
    @Get("/me/history?type=:type&from=:from&size=:size")
    public WebResponse<UserEvent> getUserHistory(String type, int from, int size, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        Type eventType = Type.valueOf(type.toUpperCase());
        return new WebResponse<>(repository.getUserEvents(user, eventType, from, size),
                repository.getTotalUserEvents(user, eventType));
    }

    /**
     * Add event to history. The event's type, the project id and the uri are passed in the request body.
     *
     * It answers 200 when event is added or updated.
     *
     * @param query
     * @return 200
     *
     * Example :
     * $(curl -i -XPUT  -H "Content-Type: application/json"  localhost:8080/api/users/me/history -d '{"type": "SEARCH", "project": "apigen-datashare", "name": "foo AND bar", "uri": "?q=foo%20AND%20bar&from=0&size=100&sort=relevance&index=luxleaks&field=all&stamp=cotgpe"}')
     */
    @Put("/me/history")
    public Payload addToHistory(UserHistoryQuery query, Context context) {
        repository.addToHistory(query.project, new UserEvent((DatashareUser) context.currentUser(), query.type, query.name, query.uri));
        return Payload.ok();
    }

    /**
     * Delete user history by type.
     *
     * Returns 200 if rows have been removed and 404 if nothing has been done (i.e. not found).
     *
     * @param type
     * @return 200 or 404
     *
     * Example :
     * $(curl -i -XDELETE localhost:8080/api/users/me/history?type=search)
     *
     */
    @Delete("/me/history?type=:type")
    public Payload deleteUserHistory(String type, Context context) {
        boolean isDeleted = repository.deleteUserHistory((DatashareUser) context.currentUser(), Type.valueOf(type.toUpperCase()));
        return isDeleted ? Payload.ok() : Payload.notFound();
    }

    /**
     * Preflight for history.
     *
     * @return 200 with OPTIONS, DELETE
     */
    @Options("/me/history/event")
    public Payload deleteUserEvent(String userId) {
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    /**
     * Delete user event by id.
     *
     * Returns 200 if removed and 404 if nothing has been done (i.e. not found).
     *
     * @param eventId
     * @return 200 or 404
     *
     * Example :
     * $(curl -i -XDELETE localhost:8080/api/users/me/history/event?id=1)
     *
     */
    @Delete("/me/history/event?id=:eventId")
    public Payload deleteUserEvent(String eventId, Context context) {
        boolean isDeleted = repository.deleteUserEvent((DatashareUser) context.currentUser(), Integer.parseInt(eventId));
        return isDeleted ? Payload.ok() : Payload.notFound();
    }

    private static class UserHistoryQuery {
        final Type type;
        final Project project;
        final String name;
        final URI uri;

        @JsonCreator
        private UserHistoryQuery(@JsonProperty("type") String type, @JsonProperty("name") String name, @JsonProperty("project") String projectId, @JsonProperty("uri") String uri) {
            this.type = Type.valueOf(type);
            this.project = project(projectId);
            this.name = name;
            this.uri = URI.create(uri);
        }
    }
}
