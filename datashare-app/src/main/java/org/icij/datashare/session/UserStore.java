package org.icij.datashare.session;

import net.codestory.http.security.Users;
import org.icij.datashare.user.User;
import org.icij.datashare.user.admin.UserFilter;
import org.icij.datashare.web.WebResponse;

public interface UserStore extends Users {
    boolean save(User user);
    boolean delete(String login);
    WebResponse<User> listUsers(UserFilter filter, int from, int size);
}
