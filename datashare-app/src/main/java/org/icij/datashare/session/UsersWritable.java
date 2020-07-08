package org.icij.datashare.session;

import net.codestory.http.security.User;
import net.codestory.http.security.Users;

public interface UsersWritable extends Users {
    boolean saveOrUpdate(User user);
}
