package org.icij.datashare.session;

import net.codestory.http.security.Users;
import org.icij.datashare.user.User;

import java.util.List;

public interface UserStore extends Users {
    boolean save(User user);
    boolean delete(String login);
    List<User> listUsers();
}
