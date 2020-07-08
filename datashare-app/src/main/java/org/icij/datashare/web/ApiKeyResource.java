package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.user.User;

import java.util.HashMap;

@Singleton
@Prefix("/api/key")
public class ApiKeyResource {
    private final TaskFactory taskFactory;

    @Inject
    public ApiKeyResource(TaskFactory taskFactory) {
        this.taskFactory = taskFactory;
    }

    /**
     * Creates a new private key and saves its 384 hash into database for current user.
     *
     * @param context
     * @return 201 (created) or error
     * @throws Exception
     * Example:
     * $(curl -i -XPUT -H 'Content-Type: application/json' http://localhost:8080/api/key)
     */
    @Put()
    public Payload createKey(Context context) throws Exception {
        return new Payload("application/json", new HashMap<String, String>() {{
            put("apiKey", taskFactory.createGenApiKey((User) context.currentUser()).call());
        }},201);
    }
}
