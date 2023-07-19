package org.icij.datashare.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Repository;
import org.icij.datashare.UserEvent;
import org.icij.datashare.UserEvent.Type;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.lang.Boolean.parseBoolean;
import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.db.tables.UserHistory.USER_HISTORY;

@Singleton
@Prefix("/api/users")
public class UserResource {
    private final Repository repository;

    private List<Project> getDatashareUserProjects (DatashareUser datashareUser) {
        List<String> projectNames =  datashareUser.getProjectNames();
        List<Project> projects = datashareUser.getProjects();
        List<Project> repositoryProjects = repository.getProjects(projectNames.toArray(new String[0]));
        return projects.stream().map(project -> {
                    return repositoryProjects.stream()
                            .filter(p -> p.name.equals(project.name))
                            .findFirst()
                            .orElse(project);
                }).collect(Collectors.toList());
    }

    @Inject
    public UserResource(Repository repository) {
        this.repository = repository;
    }

    @Operation(description = "Gets the user's session information.")
    @ApiResponse(responseCode = "200", description = "returns the user map", useReturnTypeSchema = true)
    @Get("/me")
    public Map<String, Object> getUser(Context context) {
        DatashareUser datashareUser = (DatashareUser) context.currentUser();
        Map<String, Object> details = datashareUser.getDetails();
        details.put("projects", getDatashareUserProjects(datashareUser));
        return details;
    }

    @Operation(description = "Preflight request for history")
    @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS, GET, PUT and DELETE")
    @Options("/me/history")
    public Payload getUserHistory(String userId) {
        return ok().withAllowMethods("OPTIONS", "GET", "PUT", "DELETE");
    }

    @Operation(description = "Gets the user's history by type",
            parameters = {@Parameter(name = "from", description = "the offset of the list, starting from 0", in = ParameterIn.QUERY),
                    @Parameter(name = "size", description = "the number of element retrieved", in = ParameterIn.QUERY),
                    @Parameter(name = "type", description = "string included in 'document' or 'search'", in = ParameterIn.QUERY),
                    @Parameter(name = "sort", description = "the name of the parameter to sort on (default: modificationDate)", in = ParameterIn.QUERY),
                    @Parameter(name = "desc", description = "the list is sorted in descending order (default: true)", in = ParameterIn.QUERY),
                    @Parameter(name = "projects", description = "projectIds separated by comma to filter by projects (default: none)", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns the user's list of events and the total number of events")
    @Get("/me/history?type=:type&from=:from&size=:size&sort=:sort&desc=:desc&projects=:projects")
    public Payload getUserHistory(String type, int from, int size, String sort, String desc, String projects, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        Type eventType = Type.valueOf(type.toUpperCase());
        String sortBy = getStringValue(sort).orElse( USER_HISTORY.MODIFICATION_DATE.getName());
        try {
            WebResponse<UserEvent> userEventWebResponse = new WebResponse<>(
                    repository.getUserHistory(user, eventType, from, size, sortBy, parseBooleanQueryArg(desc), parseProjectIdsQueryArg(projects)),
                    from,size,
                    repository.getUserHistorySize(user, eventType, parseProjectIdsQueryArg(projects)));
            return new Payload(userEventWebResponse);
        } catch (IllegalArgumentException e){
            return Payload.badRequest();
        }
    }

    private static Optional<String> getStringValue(String value){
        return Optional.ofNullable(value).filter(Predicate.not(String::isBlank));
    }
    @NotNull
    private static String[] parseProjectIdsQueryArg(String projects) {
        return getStringValue(projects).isEmpty() ? new String[]{} : projects.trim().split(",");
    }

    private static boolean parseBooleanQueryArg(String desc) {
        return parseBoolean(desc) || getStringValue(desc).isEmpty() || !desc.equalsIgnoreCase("false");
    }

    @Operation(description = "Add event to history. The event's type, the project ids and the uri are passed in the request body.<br>" +
            "The project list related to the event is stored in database but is never queried (no filters on project)")
    @ApiResponse(responseCode = "200", description = "returns 200 when event is added or updated.")
    @Put("/me/history")
    public Payload addToUserHistory(@Parameter(name = "query", description = "query to perform", in = ParameterIn.QUERY) UserHistoryQuery query, Context context) {
        repository.addToUserHistory(query.projects, new UserEvent((DatashareUser) context.currentUser(), query.type, query.name, query.uri));
        return ok();
    }

    @Operation(description = "Delete user history by type.")
    @ApiResponse(responseCode = "204", description = "Returns 204 (No Content) : idempotent", useReturnTypeSchema = true)
    @Delete("/me/history?type=:type")
    public Payload deleteUserHistory(@Parameter(name = "type", description = "type of user element", in = ParameterIn.QUERY) String type, Context context) {
        repository.deleteUserHistory((DatashareUser) context.currentUser(), Type.valueOf(type.toUpperCase()));
        return new Payload(204);
    }

    @Operation(description = "Preflight request for history")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS and DELETE")
    @Options("/me/history/event")
    public Payload deleteUserEvent(String userId) {
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    @Operation(description = "Delete user event by id.")
    @ApiResponse(responseCode = "204", description = "Returns 204 (No Content) : idempotent")
    @Delete("/me/history/event?id=:eventId")
    public Payload deleteUserEvent(@Parameter(name = "query", description = "query to perform", in = ParameterIn.QUERY) String eventId, Context context) {
        repository.deleteUserHistoryEvent((DatashareUser) context.currentUser(), Integer.parseInt(eventId));
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
