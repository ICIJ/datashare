package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.Repository;
import org.icij.datashare.UserEvent;
import org.icij.datashare.session.DatashareUser;

import java.util.List;
import java.util.Map;

@Singleton
@Prefix("/api/users")
public class UserResource {
    private final Repository repository;

    @Inject
    public UserResource(Repository repository) {
        this.repository = repository;
    }

    /**
     * gets the user's session information
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
     * gets the user's history
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
}
