package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.security.User;
import org.icij.datashare.Repository;

@Singleton
public class UsersInDb implements UsersWritable {
    private final Repository userRepository;

    @Inject
    public UsersInDb(Repository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public User find(String login) {
        return new DatashareUser(userRepository.getUser(login).details);
    }

    @Override
    public User find(String login, String password) {
        return null;
    }

    @Override
    public boolean saveOrUpdate(User user) {
        return userRepository.save((DatashareUser)user);
    }
}
