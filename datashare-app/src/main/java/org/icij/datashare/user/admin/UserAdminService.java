package org.icij.datashare.user.admin;

import org.icij.datashare.user.User;
import org.icij.datashare.web.WebResponse;

public interface UserAdminService {
    UserCreated create(UserCreateRequest req)
            throws UserExistsException, ValidationException;

    boolean delete(String login)
            throws UserNotFoundException;

    UserCreated createIfNotExists(UserCreateRequest req)
            throws ValidationException;

    boolean deleteIfExists(String login);

    User get(String login) throws UserNotFoundException;

    WebResponse<User> list(UserFilter filter, int from, int size);

    UserCreated update(String login, UserUpdateRequest req)
            throws UserNotFoundException, ValidationException;
}
