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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static net.codestory.http.payload.Payload.ok;

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
     * Add event to history. The event's type, the project ids and the uri are passed in the request body.
     * The project list related to the event is stored in database but is never queried (no filters on project)
     *
     * It answers 200 when event is added or updated.
     *
     * @param query
     * @return 200
     *
     * Example :
     * $(curl -i -XPUT  -H "Content-Type: application/json"  localhost:8080/api/users/me/history -d '{"type": "SEARCH", "projectIds": ["apigen-datashare","local-datashare"], "name": "foo AND bar", "uri": "?q=foo%20AND%20bar&from=0&size=100&sort=relevance&index=luxleaks&field=all&stamp=cotgpe"}')
     */
    @Put("/me/history")
    public Payload addToHistory(UserHistoryQuery query, Context context) {
        repository.addToHistory(query.projects, new UserEvent((DatashareUser) context.currentUser(), query.type, query.name, query.uri));
        return Payload.ok();
    }

    /**
     * Delete user history by type.
     *
     * Returns 204 (No Content) : idempotent
     *
     * @param type
     * @return 204
     *
     * Example :
     * $(curl -i -XDELETE localhost:8080/api/users/me/history?type=search)
     *
     */
    @Delete("/me/history?type=:type")
    public Payload deleteUserHistory(String type, Context context) {
        repository.deleteUserHistory((DatashareUser) context.currentUser(), Type.valueOf(type.toUpperCase()));
        return new Payload(204);
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
     * Returns 204 (No Content) : idempotent
     *
     * @param eventId
     * @return 204
     *
     * Example :
     * $(curl -i -XDELETE localhost:8080/api/users/me/history/event?id=1)
     *
     */
    @Delete("/me/history/event?id=:eventId")
    public Payload deleteUserEvent(String eventId, Context context) {
        repository.deleteUserEvent((DatashareUser) context.currentUser(), Integer.parseInt(eventId));
        return new Payload(204);
    }

    private static class UserHistoryQuery {
        final Type type;
        final List<Project> projects;
        final String name;
        final URI uri;

        @JsonCreator
        private UserHistoryQuery(@JsonProperty("type") String type, @JsonProperty("name") String name, @JsonProperty("projectIds") List<String> projectIds, @JsonProperty("uri") String uri) {
            this.type = Type.valueOf(type);
            this.projects = projectIds.stream().map(Project::project).collect(Collectors.toList());
            this.name = name;
            this.uri = URI.create(uri);
        }
    }
}
