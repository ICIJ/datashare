package org.icij.datashare.user.admin;

public interface UserAdminService {
    UserCreated create(UserCreateRequest req)
            throws UserExistsException, ValidationException;

    boolean delete(String login)
            throws UserNotFoundException;

    UserCreated createIfNotExists(UserCreateRequest req)
            throws ValidationException;

    boolean deleteIfExists(String login);
}
