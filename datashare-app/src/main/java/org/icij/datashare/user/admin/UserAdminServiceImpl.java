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
import java.util.Set;

@Singleton
public class UserAdminServiceImpl implements UserAdminService {
    private static final Set<String> KNOWN_PROVIDERS = Set.of(User.LOCAL, User.OAUTH, User.EXTERNAL);

    private final Repository repository;

    @Inject
    public UserAdminServiceImpl(Repository repository) {
        this.repository = repository;
    }

    @Override
    public UserCreated create(UserCreateRequest request)
            throws UserExistsException, ValidationException {
        validate(request);
        if (repository.getUser(request.login()) != null) {
            throw new UserExistsException(request.login());
        }
        return persist(request);
    }

    @Override
    public UserCreated createIfNotExists(UserCreateRequest request)
            throws ValidationException {
        validate(request);
        if (repository.getUser(request.login()) != null) {
            String name = request.name() == null ? request.login() : request.name();
            return new UserCreated(request.login(), request.email(), name,
                    request.provider(), request.groups(), true);
        }
        return persist(request);
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

    private static boolean isLocal(UserCreateRequest request) {
        return User.LOCAL.equals(request.provider());
    }

    private void validate(UserCreateRequest request) throws ValidationException {
        if (request.login() == null || request.login().isEmpty()) {
            throw new ValidationException("login", "login is required");
        }
        if (request.email() == null || request.email().isEmpty()) {
            throw new ValidationException("email", "email is required");
        }
        if (request.provider() == null || !KNOWN_PROVIDERS.contains(request.provider())) {
            throw new ValidationException("provider",
                    "provider must be one of " + KNOWN_PROVIDERS);
        }
        if (isLocal(request) && (request.password() == null || request.password().isEmpty())) {
            throw new ValidationException("password",
                    "password is required when provider=local");
        }
    }

    private UserCreated persist(UserCreateRequest request) {
        String name = request.name() == null ? request.login() : request.name();
        Map<String, Object> details = new HashMap<>();
        details.put("uid", request.login());
        details.put("name", name);
        details.put("email", request.email());

        if (isLocal(request) && request.password() != null) {
            details.put("password", Hasher.SHA_256.hash(request.password()));
        }

        Map<String, Object> appsByGroup = new LinkedHashMap<>();
        appsByGroup.put("datashare", List.copyOf(request.groups()));
        details.put("groups_by_applications", appsByGroup);

        User user = new User(request.login(), name, request.email(), request.provider(), details);
        repository.save(user);
        return new UserCreated(request.login(), request.email(), name,
                request.provider(), request.groups(), false);
    }
}
