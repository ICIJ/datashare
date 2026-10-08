package org.icij.datashare.user.admin;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.Repository;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.PostLoginEnroller;
import org.icij.datashare.session.UserStore;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.user.User;
import org.icij.datashare.web.WebResponse;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class UserAdminServiceImpl implements UserAdminService {
    private final UserStore userStore;
    @Nullable
    private final PostLoginEnroller postLoginEnroller;
    private final Authorizer authorizer;
    private final Repository repository;

    @Inject
    public UserAdminServiceImpl(UserStore userStore, @Nullable PostLoginEnroller postLoginEnroller,
                                Authorizer authorizer, Repository repository) {
        this.userStore = userStore;
        this.postLoginEnroller = postLoginEnroller;
        this.authorizer = authorizer;
        this.repository = repository;
    }

    @Override
    public UserCreated create(UserCreateRequest request) throws UserExistsException, ValidationException {
        List<String> groups = validate(request);
        if (userStore.find(request.login()) != null) {
            throw new UserExistsException(request.login());
        }
        return persist(request, groups);
    }

    @Override
    public UserCreated createIfNotExists(UserCreateRequest request) throws ValidationException {
        List<String> groups = validate(request);
        if (userStore.find(request.login()) != null) {
            String name = request.name() == null ? request.login() : request.name();
            return new UserCreated(request.login(), request.email(), name, request.provider(), groups, true);
        }
        return persist(request, groups);
    }

    @Override
    public boolean delete(String login) throws UserNotFoundException {
        if (!userStore.delete(login)) {
            throw new UserNotFoundException(login);
        }
        // Rows outlive the user row otherwise, and a user re-created with the same
        // login inherits them (see #2441). Store first: a Casbin wipe for a user we
        // failed to delete would strip a live user of every role.
        authorizer.removeAllPoliciesForUser(login);
        return true;
    }

    @Override
    public boolean deleteIfExists(String login) {
        boolean removed = userStore.delete(login);
        // Unconditional and idempotent: rows left behind by an earlier delete outlive the user row,
        // so gating this on `removed` would make them unreachable through any API or CLI path.
        authorizer.removeAllPoliciesForUser(login);
        return removed;
    }

    @Override
    public User get(String login) throws UserNotFoundException {
        net.codestory.http.security.User found = userStore.find(login);
        if (found == null) {
            throw new UserNotFoundException(login);
        }
        return (User) found;
    }

    @Override
    public WebResponse<User> list(UserFilter filter, Comparator<User> sort, int from, int size) {
        return userStore.listUsers(filter, sort, from, size);
    }

    @Override
    public List<User> getByIds(Set<String> ids) {
        return userStore.getUsersByIds(ids);
    }

    @Override
    public UserCreated update(String login, UserUpdateRequest req) throws UserNotFoundException, ValidationException {
        net.codestory.http.security.User found = userStore.find(login);
        if (found == null) {
            throw new UserNotFoundException(login);
        }
        User existing = (User) found;

        if (req.email() != null) {
            try {
                Validators.email(req.email());
            } catch (Validators.InvalidValueException e) {
                throw new ValidationException(e.field(), e.getMessage());
            }
        }
        List<String> requestedGroups = validateGroups(req.groups());

        String newEmail = req.email() != null ? req.email() : existing.email;
        String newName = req.name() != null ? req.name() : existing.name;
        List<String> currentGroups = existing.getApplicationProjectNames();
        List<String> newGroups = requestedGroups != null ? requestedGroups : currentGroups;

        // A password is write-only: a resubmitted password is indistinguishable from a new one, so
        // any password at all counts as a change rather than reporting a noop that silently rehashed.
        boolean changed = !java.util.Objects.equals(newEmail, existing.email) ||
                          !java.util.Objects.equals(newName, existing.name) || !newGroups.equals(currentGroups) ||
                          req.password() != null;

        Map<String, Object> details = new HashMap<>(existing.details);
        details.put("uid", login);
        details.put("name", newName);
        details.put("email", newEmail);

        if (req.password() != null) {
            if (req.password().isEmpty()) {
                throw new ValidationException("password", "password cannot be empty");
            }
            details.put("password", Hasher.SHA_256.hash(req.password()));
        }

        Map<String, Object> appsByGroup = new LinkedHashMap<>();
        appsByGroup.put("datashare", List.copyOf(newGroups));
        details.put("groups_by_applications", appsByGroup);

        User updated = new User(login, newName, newEmail, existing.provider, details);
        userStore.save(updated);
        if (postLoginEnroller != null) {
            postLoginEnroller.enroll(new DatashareUser(updated));
        }
        return new UserCreated(login, newEmail, newName, existing.provider, newGroups, !changed);
    }

    private static boolean isLocal(UserCreateRequest request) {
        return User.LOCAL.equals(request.provider());
    }

    private boolean isExternal(UserCreateRequest request) {
        return User.EXTERNAL.equals(request.provider());
    }

    private List<String> validate(UserCreateRequest request) throws ValidationException {
        try {
            Validators.login(request.login());
            Validators.email(request.email());
            Validators.provider(request.provider());
            if (isLocal(request)) {
                Validators.password(request.password());
            }
        } catch (Validators.InvalidValueException e) {
            throw new ValidationException(e.field(), e.getMessage());
        }
        return validateGroups(request.groups());
    }

    /**
     * Canonicalizes a groups list through the same validator the CLI uses, then rejects names that
     * are not existing projects. PostLoginEnroller writes a PROJECT_MEMBER row per name at every
     * login, so an unknown name is not inert: it accumulates rows for a project nobody can reach.
     * Returns null for a null input, which callers read as "the request did not touch groups".
     */
    private List<String> validateGroups(List<String> groups) throws ValidationException {
        if (groups == null) {
            return null;
        }
        List<String> canonical;
        try {
            canonical = Validators.groups(String.join(",", groups));
        } catch (Validators.InvalidValueException e) {
            throw new ValidationException(e.field(), e.getMessage());
        }
        List<String> deduplicated = canonical.stream().distinct().collect(Collectors.toList());
        for (String projectName : deduplicated) {
            if (repository.getProject(projectName) == null) {
                // Projects must exist before the users that reference them. Note the default project
                // (local-datashare) has no row until something creates it: YesCookieAuthFilter
                // synthesizes it in memory for the session, it is not persisted.
                throw new ValidationException("groups",
                                              "project '" + projectName + "' does not exist, create it first");
            }
        }
        return deduplicated;
    }

    private UserCreated persist(UserCreateRequest request, List<String> groups) {
        String name = request.name() == null ? request.login() : request.name();
        Map<String, Object> details = new HashMap<>();
        details.put("uid", request.login());
        details.put("name", name);
        details.put("email", request.email());

        if ((isLocal(request) || isExternal(request)) && request.password() != null) {
            details.put("password", Hasher.SHA_256.hash(request.password()));
        }

        Map<String, Object> appsByGroup = new LinkedHashMap<>();
        appsByGroup.put("datashare", List.copyOf(groups));
        details.put("groups_by_applications", appsByGroup);

        User user = new User(request.login(), name, request.email(), request.provider(), details);
        userStore.save(user);
        return new UserCreated(request.login(), request.email(), name, request.provider(), groups, false);
    }
}
