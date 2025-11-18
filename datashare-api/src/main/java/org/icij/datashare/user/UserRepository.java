package org.icij.datashare.user;

public interface UserRepository {
    boolean save(User user);
    User getUser(String userId);

}
