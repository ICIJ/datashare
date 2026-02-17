package org.icij.datashare.web;

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
import net.codestory.http.payload.Payload;
import net.codestory.http.errors.ForbiddenException;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.user.User;

import java.util.HashMap;

import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api/key")
public class ApiKeyResource {
    private final DatashareTaskFactory taskFactory;

    @Inject
    public ApiKeyResource(DatashareTaskFactory taskFactory) {
        this.taskFactory = taskFactory;
    }

    @Operation(description = "Preflight for key management")
    @ApiResponse(responseCode = "200", description="returns OPTIONS, GET, PUT and DELETE")
    @Options("/:userId")
    public Payload createKey(@Parameter(name="userId", description="user identifier", in = ParameterIn.PATH) String userId) {
        return ok().withAllowMethods("OPTIONS", "GET", "PUT", "DELETE");
    }

    @Operation(description = "Creates a new private key and saves its SHA384 hash into database for current user. Only available in SERVER mode.")
    @ApiResponse(responseCode = "201", description = "returns the api key JSON",
            content = { @Content(examples = { @ExampleObject(value="{\"apiKey\":\"SrcasvUmaAD6NsZ3+VmUkFFWVfRggIRNmWR5aHx7Kfc=\"}")})})
    @Put("/:userId")
    public Payload createKey(@Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId, Context context) throws Exception {
        verifyOwnership(userId, context);
        return new Payload("application/json", new HashMap<String, String>() {{
            put("apiKey", taskFactory.createGenApiKey(new User(userId)).call());
        }},201);
    }

    @Operation(description = "Get the private key for an existing user. Only available in SERVER mode.")
    @ApiResponse(responseCode = "200", description = "returns the hashed key JSON",
            content = { @Content(examples = { @ExampleObject(value="{\"hashedKey\":\"c3e7766f7605659f2b97f2a6f5bcf34611997fc31173931eefcea91df1b465ffe35c2b9b4b91e8bbe2eec3730ce2a74a\"}")})})
    @Get("/:userId")
    public Payload getKey(@Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId, Context context) throws Exception{
        verifyOwnership(userId, context);
        return new Payload("application/json", new HashMap<String, String>() {{
            put("hashedKey", taskFactory.createGetApiKey(new User(userId)).call());
        }},200);
    }

    @Operation(description = "Deletes an apikey for current user. Only available in SERVER mode.")
    @ApiResponse(responseCode = "204", description = "when key has been deleted")
    @Delete("/:userId")
    public Payload deleteKey(@Parameter(name = "userId", description = "user identifier", in = ParameterIn.PATH) String userId,Context context) throws Exception {
        verifyOwnership(userId, context);
        taskFactory.createDelApiKey(new User(userId)).call();
        return new Payload(204);
    }

    private void verifyOwnership(String userId, Context context) {
        String currentUserId = ((User) context.currentUser()).id;
        if (!userId.equals(currentUserId)) {
            throw new ForbiddenException();
        }
    }
}
