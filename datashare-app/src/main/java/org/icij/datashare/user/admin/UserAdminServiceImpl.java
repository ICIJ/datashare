package org.icij.datashare.user.admin;

import com.google.inject.Inject;
import com.google.inject.Singleton;
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
import java.util.Objects;
import java.util.Set;

@Singleton
public class UserAdminServiceImpl implements UserAdminService {
    private final UserStore userStore;
    @Nullable
    private final PostLoginEnroller postLoginEnroller;
    private final Authorizer authorizer;

    @Inject
    public UserAdminServiceImpl(UserStore userStore, @Nullable PostLoginEnroller postLoginEnroller,
                                Authorizer authorizer) {
        this.userStore = userStore;
        this.postLoginEnroller = postLoginEnroller;
        this.authorizer = authorizer;
    }

    @Override
    public UserCreated create(UserCreateRequest request) throws UserExistsException, ValidationException {
        if (userStore.find(request.login()) != null) {
            // before validate(): an existing user must answer 409 rather than a 400 about a body
            // field the caller cannot fix by changing it
            throw new UserExistsException(request.login());
        }
        return persist(request, validate(request));
    }

    @Override
    public UserCreated createIfNotExists(UserCreateRequest request) throws ValidationException {
        if (userStore.find(request.login()) instanceof User existing) {
            // the groups the stored user has, not the ones asked for: nothing was written, so
            // echoing the request would claim a membership that does not exist
            String name = request.name() == null ? request.login() : request.name();
            return new UserCreated(request.login(), request.email(), name, request.provider(),
                                   existing.getApplicationProjectNames(), true);
        }
        return persist(request, validate(request));
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
        boolean changed = !Objects.equals(newEmail, existing.email) || !Objects.equals(newName, existing.name) ||
                          !newGroups.equals(currentGroups) || req.password() != null;

        if (!changed) {
            // returning before the save, so the flag matches the behaviour: a "noop" that still
            // writes and re-enrolls through PostLoginEnroller is not one
            return new UserCreated(login, newEmail, newName, existing.provider, newGroups, true);
        }

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
        return new UserCreated(login, newEmail, newName, existing.provider, newGroups, false);
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
     * Canonicalizes a groups list through the same validator the CLI uses. Deliberately does not
     * check that each name has a project row: users are legitimately provisioned before their
     * projects exist ({@code --user-create --user-create-groups}), and the default local-datashare
     * project is synthesized in memory by YesCookieAuthFilter rather than persisted.
     * Returns null for a null input, which callers read as "the request did not touch groups".
     */
    private List<String> validateGroups(List<String> groups) throws ValidationException {
        if (groups == null) {
            return null;
        }
        if (groups.stream().anyMatch(g -> g != null && g.contains(","))) {
            // Validators.groups parses a CSV, so one entry holding a comma would come back as two
            throw new ValidationException("groups", "a group name cannot contain a comma");
        }
        List<String> canonical;
        try {
            canonical = Validators.groups(String.join(",", groups));
        } catch (Validators.InvalidValueException e) {
            throw new ValidationException(e.field(), e.getMessage());
        }
        return canonical.stream().distinct().toList();
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
