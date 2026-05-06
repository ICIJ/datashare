package org.icij.datashare.user.admin;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.Repository;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.user.User;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class UserAdminServiceImpl implements UserAdminService {
    static final String LOCAL = "local";
    static final String OAUTH = "oauth";
    static final String EXTERNAL = "external";

    private final Repository repository;

    @Inject
    public UserAdminServiceImpl(Repository repository) {
        this.repository = repository;
    }

    @Override
    public UserCreated create(UserCreateRequest req)
            throws UserExistsException, ValidationException {
        validate(req);
        if (repository.getUser(req.login()) != null) {
            throw new UserExistsException(req.login());
        }
        return persist(req, false);
    }

    @Override
    public UserCreated createIfNotExists(UserCreateRequest req)
            throws ValidationException {
        validate(req);
        if (repository.getUser(req.login()) != null) {
            String name = req.name() == null ? req.login() : req.name();
            return new UserCreated(req.login(), req.email(), name,
                    req.provider(), req.groups(), true);
        }
        return persist(req, false);
    }

    @Override
    public boolean delete(String login) throws UserNotFoundException {
        boolean removed = repository.deleteUser(login);
        if (!removed) {
            throw new UserNotFoundException(login);
        }
        return true;
    }

    @Override
    public boolean deleteIfExists(String login) {
        return repository.deleteUser(login);
    }

    private void validate(UserCreateRequest req) throws ValidationException {
        if (LOCAL.equals(req.provider()) && (req.password() == null || req.password().isEmpty())) {
            throw new ValidationException("password",
                    "password is required when provider=local");
        }
        if (!LOCAL.equals(req.provider()) && !OAUTH.equals(req.provider()) && !EXTERNAL.equals(req.provider())) {
            throw new ValidationException("provider",
                    "provider must be one of local|oauth|external");
        }
    }

    private UserCreated persist(UserCreateRequest req, boolean noop) {
        String name = req.name() == null ? req.login() : req.name();
        Map<String, Object> details = new HashMap<>();
        details.put("uid", req.login());
        details.put("name", name);
        details.put("email", req.email());

        if (LOCAL.equals(req.provider()) && req.password() != null) {
            details.put("password", Hasher.SHA_256.hash(req.password()));
        }

        Map<String, Object> appsByGroup = new LinkedHashMap<>();
        appsByGroup.put("datashare", List.copyOf(req.groups()));
        details.put("groups_by_applications", appsByGroup);

        User user = new User(req.login(), name, req.email(), req.provider(), details);
        repository.save(user);
        return new UserCreated(req.login(), req.email(), name,
                req.provider(), req.groups(), noop);
    }
}
