package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.security.User;
import net.codestory.http.security.Users;
import org.icij.datashare.Repository;
import org.icij.datashare.text.Hasher;

@Singleton
public class UsersInDb implements Users {
    private final Repository userRepository;

    @Inject
    public UsersInDb(Repository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public User find(String login) {
        org.icij.datashare.user.User userInDb = userRepository.getUser(login);
        if(userInDb == null) {
            return null;
        }
        return new DatashareUser(userInDb);
    }

    @Override
    public User find(String login, String password) {
        org.icij.datashare.user.User user = userRepository.getUser(login);
        return user != null && Hasher.SHA_256.hash(password).equals(user.details.get("password")) ? new DatashareUser(user): null;
    }
}
