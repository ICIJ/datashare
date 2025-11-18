package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.security.User;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.user.UserRepository;

@Singleton
public class UsersInDb implements UsersWritable {
    private final UserRepository userRepository;

    @Inject
    public UsersInDb(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public User find(String login) {
        return new DatashareUser(userRepository.getUserWithPolicies(login));
    }

    @Override
    public User find(String login, String password) {
        org.icij.datashare.user.User user = userRepository.getUserWithPolicies(login);
        return user != null && Hasher.SHA_256.hash(password).equals(user.details.get("password")) ? new DatashareUser(user): null;
    }

    @Override
    public boolean saveOrUpdate(User user) {
        return userRepository.save((DatashareUser)user);
    }
}
