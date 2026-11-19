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
import org.icij.datashare.user.User;
import org.icij.datashare.utils.PayloadFormatter;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.HashMap;
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

    @Operation(
            description = "Creates a new private key and saves its SHA384 hash into database for current user. Accepts an optional JSON body with an ISO-8601 expiration date, e.g. {\"expirationDate\":\"2027-02-01T00:00:00Z\"}. Only available in SERVER mode.")
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
        // a key must not mint its successor, otherwise a leaked key outlives its own expiration
        if (ApiKeyFilter.isAuthenticated(context)) {
            throw new ForbiddenException();
        }
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

    @Operation(description = "Get the private key for an existing user. Only available in SERVER mode.")
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

    @Operation(description = "Deletes an apikey for current user. Only available in SERVER mode.")
    @ApiResponse(responseCode = "204", description = "when key has been deleted")
    @Delete("/:userId")
    public Payload deleteKey(
            @Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId,
            Context context) throws Exception {
        verifyOwnership(userId, context);
        taskFactory.createDelApiKey(new User(userId)).call();
        return new Payload(204);
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
            JsonNode expirationDate = json.path("expirationDate");
            if (expirationDate.isMissingNode() || expirationDate.isNull()) {
                return null;
            }
            Instant instant = Instant.parse(expirationDate.asText());
            return Date.from(instant);
        } catch (JsonProcessingException | DateTimeParseException e) {
            throw new ApiKeyExpiration.InvalidExpirationException(
                    "invalid expirationDate: expected an ISO-8601 instant", e);
        }
    }

    private void verifyOwnership(String userId, Context context) {
        String currentUserId = ((User) context.currentUser()).id;
        if (!userId.equals(currentUserId)) {
            throw new ForbiddenException();
        }
    }
}
