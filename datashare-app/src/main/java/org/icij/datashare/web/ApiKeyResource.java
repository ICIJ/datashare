package org.icij.datashare.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import net.codestory.http.errors.ForbiddenException;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.session.ApiKeyExpiration;
import org.icij.datashare.session.ApiKeyFilter;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.user.ApiKey;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.ProjectScope;
import org.icij.datashare.user.User;
import org.icij.datashare.utils.PayloadFormatter;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api/key")
public class ApiKeyResource {
    private static final ObjectMapper MAPPER = JsonObjectMapper.getMapper();
    private final DatashareTaskFactory taskFactory;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyExpiration apiKeyExpiration;

    @Inject
    public ApiKeyResource(DatashareTaskFactory taskFactory, ApiKeyRepository apiKeyRepository,
                          ApiKeyExpiration apiKeyExpiration) {
        this.taskFactory = taskFactory;
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyExpiration = apiKeyExpiration;
    }

    @Operation(description = "Preflight for key management")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS, GET, PUT and DELETE")
    @Options("/:userId")
    public Payload createKey(
            @Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId) {
        return ok().withAllowMethods("OPTIONS", "GET", "PUT", "DELETE");
    }

    @Operation(description = "Preflight for key list management")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS, GET and POST")
    @Options("/:userId/keys")
    public Payload keysOptions(
            @Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId) {
        return ok().withAllowMethods("OPTIONS", "GET", "POST");
    }

    @Operation(description = "Preflight for single key management")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS and DELETE")
    @Options("/:userId/keys/:hashedKey")
    public Payload keyOptions(
            @Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId,
            @Parameter(name = "hashedKey", description = "hashed key", in = ParameterIn.PATH) String hashedKey) {
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    @Operation(
            description = "Rotates the keys: creates a new unscoped private key, replaces all the existing keys of the current user and saves its SHA384 hash into database. Accepts an optional JSON body with an ISO-8601 expiration date, e.g. {\"expirationDate\":\"2027-02-01T00:00:00Z\"}. Without it, the key expires in 30 days. Only available in SERVER mode.")
    @ApiResponse(responseCode = "201", description = "returns the api key JSON", content = {@Content(
            examples = {@ExampleObject(value = "{\"apiKey\":\"SrcasvUmaAD6NsZ3+VmUkFFWVfRggIRNmWR5aHx7Kfc=\"}")})})
    @ApiResponse(responseCode = "400",
            description = "if the expiration date is invalid, in the past or beyond the maximum lifetime")
    @ApiResponse(responseCode = "403",
            description = "if the request is authenticated with an API key: a key cannot create another key")
    @Put("/:userId")
    public Payload createKey(
            @Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId,
            Context context) throws Exception {
        verifyOwnership(userId, context);
        refuseApiKeyAuthentication(context);
        String apiKey;
        try {
            String body = context.request().content();
            Date expirationDate = requestedExpirationDate(body);
            User user = new User(userId);
            apiKey = taskFactory.createGenApiKey(user, expirationDate).call();
        } catch (ApiKeyExpiration.InvalidExpirationException e) {
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
        return new Payload("application/json", Map.of("apiKey", apiKey), 201);
    }

    @Operation(description = "Get the newest private key of an existing user. Only available in SERVER mode.")
    @ApiResponse(responseCode = "200", description = "returns the hashed key JSON", content = {@Content(examples = {
            @ExampleObject(
                    value = "{\"hashedKey\":\"c3e7766f7605659f2b97f2a6f5bcf34611997fc31173931eefcea91df1b465ffe35c2b9b4b91e8bbe2eec3730ce2a74a\",\"expirationDate\":\"2027-02-01T00:00:00Z\"}")})})
    @Get("/:userId")
    public Payload getKey(
            @Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId,
            Context context) throws Exception {
        verifyOwnership(userId, context);
        User user = new User(userId);
        ApiKey apiKey = apiKeyRepository.get(user);
        Map<String, Object> body = new HashMap<>();
        body.put("hashedKey", apiKey == null ? null : apiKey.getId());
        body.put("expirationDate", effectiveExpiration(apiKey));
        return new Payload("application/json", body, 200);
    }

    @Operation(description = "Deletes all the apikeys of the current user. Only available in SERVER mode.")
    @ApiResponse(responseCode = "204", description = "when key has been deleted")
    @Delete("/:userId")
    public Payload deleteKey(
            @Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId,
            Context context) throws Exception {
        verifyOwnership(userId, context);
        refuseApiKeyAuthentication(context);
        taskFactory.createDelApiKey(new User(userId)).call();
        return new Payload(204);
    }

    @Operation(
            description = "Lists the keys of the current user, newest first, with their project scope. An empty projects list means unscoped. Only available in SERVER mode.")
    @ApiResponse(responseCode = "200", description = "returns the list of keys", content = {@Content(examples = {
            @ExampleObject(
                    value = "[{\"hashedKey\":\"c3e7766f\",\"projects\":[\"p1\"],\"creationDate\":\"2026-01-01T00:00:00Z\",\"expirationDate\":\"2027-02-01T00:00:00Z\"}]")})})
    @ApiResponse(responseCode = "403",
            description = "if the request is authenticated with a scoped API key: it must not see the projects of the other keys")
    @Get("/:userId/keys")
    public Payload listKeys(
            @Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId,
            Context context) {
        verifyOwnership(userId, context);
        User currentUser = (User) context.currentUser();
        if (currentUser.scope() instanceof ProjectScope.Only) {
            throw new ForbiddenException();
        }
        List<Map<String, Object>> keys = new ArrayList<>();
        List<ApiKey> userKeys = apiKeyRepository.getAll(new User(userId));
        for (ApiKey apiKey : userKeys) {
            Map<String, Object> key = new HashMap<>();
            key.put("hashedKey", apiKey.getId());
            key.put("projects", apiKey.getProjects());
            key.put("creationDate", apiKey.getCreationDate().toInstant().toString());
            key.put("expirationDate", effectiveExpiration(apiKey));
            keys.add(key);
        }
        return new Payload("application/json", keys, 200);
    }

    @Operation(
            description = "Adds a private key for the current user without touching the existing ones. Accepts an optional JSON body with an ISO-8601 expiration date and a list of project ids the key is limited to, e.g. {\"expirationDate\":\"2027-02-01T00:00:00Z\",\"projects\":[\"p1\"]}. Without projects the key is unscoped. Only available in SERVER mode.")
    @ApiResponse(responseCode = "201", description = "returns the api key JSON", content = {@Content(
            examples = {@ExampleObject(value = "{\"apiKey\":\"SrcasvUmaAD6NsZ3+VmUkFFWVfRggIRNmWR5aHx7Kfc=\"}")})})
    @ApiResponse(responseCode = "400",
            description = "if the expiration date is invalid, projects is not an array of strings or a project is not granted to the user")
    @ApiResponse(responseCode = "403",
            description = "if the request is authenticated with an API key: a key cannot create another key")
    @Post("/:userId/keys")
    public Payload addKey(
            @Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId,
            Context context) throws Exception {
        verifyOwnership(userId, context);
        refuseApiKeyAuthentication(context);
        String apiKey;
        try {
            String body = context.request().content();
            Date expirationDate = requestedExpirationDate(body);
            List<String> projects = requestedProjects(body);
            User currentUser = (User) context.currentUser();
            List<String> refused = projects.stream().filter(id -> !currentUser.isGranted(id)).toList();
            if (!refused.isEmpty()) {
                String refusedNames = String.join(", ", refused);
                return PayloadFormatter.error("projects not granted: " + refusedNames, HttpStatus.BAD_REQUEST);
            }
            apiKey = taskFactory.createGenApiKey(new User(userId), expirationDate, projects).call();
        } catch (IllegalArgumentException e) {
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
        return new Payload("application/json", Map.of("apiKey", apiKey), 201);
    }

    @Operation(
            description = "Deletes one key of the current user, identified by its hash. Only available in SERVER mode.")
    @ApiResponse(responseCode = "204", description = "when the key has been deleted")
    @ApiResponse(responseCode = "404", description = "if the user has no such key")
    @Delete("/:userId/keys/:hashedKey")
    public Payload deleteOneKey(
            @Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId,
            @Parameter(name = "hashedKey", description = "hashed key", in = ParameterIn.PATH) String hashedKey,
            Context context) {
        verifyOwnership(userId, context);
        refuseApiKeyAuthentication(context);
        boolean deleted = apiKeyRepository.delete(new User(userId), hashedKey);
        return new Payload(deleted ? 204 : 404);
    }

    private String effectiveExpiration(ApiKey apiKey) {
        Date expiration = apiKey == null ? null : apiKeyExpiration.effectiveExpiration(apiKey);
        return expiration == null ? null : expiration.toInstant().toString();
    }

    private static Date requestedExpirationDate(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode json = MAPPER.readTree(body);
            if (!json.isObject()) {
                throw new ApiKeyExpiration.InvalidExpirationException("invalid body: expected a JSON object");
            }
            JsonNode expirationDate = json.path("expirationDate");
            if (expirationDate.isMissingNode() || expirationDate.isNull()) {
                return null;
            }
            Instant instant = Instant.parse(expirationDate.asText());
            return ApiKeyExpiration.toExpirationDate(instant);
        } catch (JsonProcessingException | DateTimeParseException e) {
            throw new ApiKeyExpiration.InvalidExpirationException(
                    "invalid expirationDate: expected an ISO-8601 instant", e);
        }
    }

    private static List<String> requestedProjects(String body) throws JsonProcessingException {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode projects = MAPPER.readTree(body).path("projects");
        if (projects.isMissingNode() || projects.isNull()) {
            return List.of();
        }
        if (!projects.isArray()) {
            throw new IllegalArgumentException("invalid projects: expected an array of strings");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode project : projects) {
            if (!project.isTextual()) {
                throw new IllegalArgumentException("invalid projects: expected an array of strings");
            }
            ids.add(project.asText());
        }
        return ids;
    }

    // a key must not mint its successor, otherwise a leaked key outlives its own expiration
    private static void refuseApiKeyAuthentication(Context context) {
        if (ApiKeyFilter.isAuthenticated(context)) {
            throw new ForbiddenException();
        }
    }

    private void verifyOwnership(String userId, Context context) {
        String currentUserId = ((User) context.currentUser()).id;
        if (!userId.equals(currentUserId)) {
            throw new ForbiddenException();
        }
    }
}
