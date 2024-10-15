package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.text.Project;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class YesCookieAuthFilter extends CookieAuthFilter {
    private final Integer ttl;
    private final String defaultProject;
    private final JooqRepository jooqRepository;

    @Inject
    public YesCookieAuthFilter(final PropertiesProvider propertiesProvider, final JooqRepository jooqRepository) {
        super(propertiesProvider.get("protectedUrlPrefix").orElse("/"), new UsersInRedis(propertiesProvider), new RedisSessionIdStore(propertiesProvider));
        this.ttl = Integer.valueOf(propertiesProvider.get("sessionTtlSeconds").orElse("1"));
        this.jooqRepository = jooqRepository;
        this.defaultProject = propertiesProvider.get("defaultProject").orElse("local-datashare");
    }

    @Override
    protected Payload otherUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        Payload payload = super.otherUri(uri, context, nextFilter);
        if (payload.code() == 401) {
            User user = createUser(NameGenerator.generate());
            context.setCurrentUser(user);
            return nextFilter.get().withCookie(this.authCookie(this.buildCookie(user, "/")));
        }
        return payload;
    }

    private User createUser(String userName) {
        List<Project> projects = getProjects();
        List<String> projectNames = getProjectNames();
        // Build datashare user
        DatashareUser user = new DatashareUser(org.icij.datashare.user.User.localUser(userName, projectNames));
        user.setProjects(projects);
        // Finally, store the user in redis so the session can be retrieved
        ((UsersInRedis) users).saveOrUpdate(user);
        return user;
    }

    private List<Project> getProjects() {
        // Get the project and create a new list
        List<Project> projects = jooqRepository.getProjects();
        // Check if the default project exists in db
        if (projects.stream().noneMatch(project -> project.getName().equals(defaultProject))) {
            // Then add the default project as a Project instance
            projects.add(new Project(defaultProject));
        }
        return projects;
    }

    private List<String> getProjectNames() {
        return this.getProjects().stream().map(Project::getName).collect(Collectors.toList());
    }

    @Override protected String cookieName() { return "_ds_session_id";}
    @Override protected int expiry() { return ttl;}
    @Override protected boolean redirectToLogin(String uri) { return false;}
}
